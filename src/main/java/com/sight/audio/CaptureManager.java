package com.sight.audio;

import com.sight.audio.wasapi.Com;
import com.sight.audio.wasapi.ProcessLoopback;

import java.util.function.Consumer;

/**
 * 采集总调度：管「现在采谁、怎么采、采到的帧放哪」。
 *
 * <p>两种模式共用一条 20 ms 定长帧的管道，下游（SVC 注入）完全不用关心来源：</p>
 * <pre>
 *   [进程 WASAPI 环回] ┐
 *                      ├─→ 解交织/重采样 → 960 点帧 → 抖动缓冲 → mergeAudio()
 *   [虚拟声卡设备]     ┘
 * </pre>
 *
 * <p>线程模型：所有打开/关闭都发生在采集线程上，命令线程只改标志位，不碰 COM 对象 ——
 * 这样避免了「一边读一边 Release」的崩溃。设备模式例外：{@code read} 会阻塞，
 * 必须由外部 {@code unblock()}（关掉 line）把它踹醒。</p>
 */
public final class CaptureManager {

    /** SVC 一帧固定 960 点（48 kHz 下 20 ms）。 */
    public static final int FRAME_SAMPLES = 960;

    /** {@link #targetPid()} 取这个值表示「整个系统，但不含本游戏」。 */
    public static final int PID_SYSTEM = 0;

    public enum Mode { NONE, PROCESS, DEVICE }

    private static final FrameBuffer BUFFER = new FrameBuffer(5);

    private static volatile Mode mode = Mode.NONE;
    private static volatile boolean running;
    private static volatile Source source;
    private static volatile Thread worker;
    private static volatile String label = "";
    private static volatile int targetPid = -1;
    private static volatile String lastError = "";

    /** 给玩家反馈的出口，由 SightAudioClient 接到聊天栏。 */
    static volatile Consumer<String> feedback = s -> {
    };

    private CaptureManager() {
    }

    // ------------------------------------------------------------------ 状态

    public static boolean isActive() {
        return running && mode != Mode.NONE;
    }

    public static Mode mode() {
        return mode;
    }

    public static int targetPid() {
        return targetPid;
    }

    public static String label() {
        return label;
    }

    public static String lastError() {
        return lastError;
    }

    public static int buffered() {
        return BUFFER.size();
    }

    public static String describe() {
        return switch (mode) {
            case NONE -> "未开启";
            case PROCESS -> targetPid == PID_SYSTEM
                    ? label
                    : "进程：" + label + "（PID " + targetPid + "）";
            case DEVICE -> "设备：" + label;
        };
    }

    /** 按上次记住的选择重新开始（F10 / 自动恢复用）。 */
    public static void startLast() {
        if (Config.lastPid == PID_SYSTEM) {
            startSystem();
        } else if (Config.lastPid > 0) {
            startProcess(Config.lastPid, Config.lastName);
        }
    }

    /** 有没有记住过可用的目标。 */
    public static boolean hasLastTarget() {
        return Config.lastPid == PID_SYSTEM || Config.lastPid > 0;
    }

    // ------------------------------------------------------------------ 启停

    /** 开始分享某个进程的声音。异步打开，结果通过聊天栏反馈。 */
    public static synchronized void startProcess(int pid, String name) {
        stop();
        lastError = "";
        mode = Mode.PROCESS;
        label = name == null || name.isBlank() ? ("PID " + pid) : name;
        targetPid = pid;
        BUFFER.resize(Config.bufferFrames);
        running = true;
        Thread t = new Thread(() -> runLoopback(pid, ProcessLoopback.MODE_INCLUDE, label),
                "sight-audio-process");
        t.setDaemon(true);
        worker = t;          // 🔴 必须先赋值再 start：否则线程可能在赋值前就跑完 finally，
        t.start();           //    那里 `worker == currentThread()` 比不过，状态就永远卡在 PROCESS
    }

    /**
     * 整个系统（**不含本游戏**）的声音。
     *
     * <p>用 EXCLUDE 模式把游戏自己排除掉：这样别人听到的是你在浏览器/播放器里放的东西，
     * 而游戏自己的音效、其他玩家的语音不会被混进去（否则会互相回授成噪音）。</p>
     */
    public static synchronized void startSystem() {
        stop();
        lastError = "";
        mode = Mode.PROCESS;
        label = "整个系统（不含本游戏）";
        targetPid = PID_SYSTEM;
        BUFFER.resize(Config.bufferFrames);
        running = true;
        int self = (int) ProcessHandle.current().pid();
        Thread t = new Thread(() -> runLoopback(self, ProcessLoopback.MODE_EXCLUDE, label),
                "sight-audio-system");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 开始从某个录音设备采集（虚拟声卡后备路线）。 */
    public static synchronized void startDevice(String fragment) {
        stop();
        lastError = "";
        mode = Mode.DEVICE;
        label = fragment;
        BUFFER.resize(Config.bufferFrames);
        running = true;
        Thread t = new Thread(() -> runDevice(fragment), "sight-audio-device");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    public static synchronized void stop() {
        running = false;
        Source s = source;
        if (s != null) {
            s.unblock();       // 设备模式会阻塞在 read 里，必须踹一脚
        }
        Thread t = worker;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        source = null;
        mode = Mode.NONE;
        targetPid = -1;
        BUFFER.clear();
    }

    /** SVC 每 20 ms 调一次；空返回 null 表示这帧不加料。 */
    public static short[] poll() {
        return BUFFER.poll();
    }

    // ------------------------------------------------------------------ 采集线程

    private static void runLoopback(int pid, int loopbackMode, String name) {
        Source src = null;
        try {
            if (!Com.available()) {
                throw new IllegalStateException("当前 Java 无法调用 native（" + Com.loadError() + "）");
            }
            if (!Com.init()) {
                throw new IllegalStateException("COM 初始化失败");
            }
            src = new ProcessSource(ProcessLoopback.open(pid, loopbackMode), name);
            source = src;
            SightAudioClient.LOG.info("[sight-audio] 开始采集 {}（PID {}，模式 {}）", name, pid, loopbackMode);
            feedback.accept("§a[sight-audio] 开始分享「" + name + "」，附近的人能听到了");
            pump(src, false);
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            SightAudioClient.LOG.error("[sight-audio] 采集 {} 失败：{}", name, lastError);
            feedback.accept("§c[sight-audio] 打开失败：" + lastError);
        } finally {
            if (src != null) {
                src.close();
            }
            if (worker == Thread.currentThread()) {
                running = false;
                mode = Mode.NONE;
                source = null;
            }
        }
    }

    private static void runDevice(String fragment) {
        Source src = null;
        try {
            src = DeviceSource.open(fragment);
            source = src;
            SightAudioClient.LOG.info("[sight-audio] 开始采集设备 {}", src.describe());
            feedback.accept("§a[sight-audio] 开始分享设备「" + src.describe() + "」");
            pump(src, true);
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            SightAudioClient.LOG.error("[sight-audio] 打开设备 {} 失败：{}", fragment, lastError);
            StringBuilder sb = new StringBuilder("§c[sight-audio] 打不开设备，可用的录音设备：");
            for (String d : DeviceSource.listRecordable()) {
                sb.append("§7 ").append(d).append("§r");
            }
            feedback.accept(sb.toString());
        } finally {
            if (src != null) {
                src.close();
            }
            if (worker == Thread.currentThread()) {
                running = false;
                mode = Mode.NONE;
                source = null;
            }
        }
    }

    /** 把不定长的包拼成 960 点的帧。 */
    private static void pump(Source src, boolean blocking) throws Throwable {
        short[] acc = new short[FRAME_SAMPLES];
        int accLen = 0;
        int idleMs = 0;
        int loops = 0;
        boolean warnedSilent = false;

        while (running) {
            int r = src.read(acc, accLen, FRAME_SAMPLES - accLen);
            if (r > 0) {
                accLen += r;
                idleMs = 0;
                if (accLen == FRAME_SAMPLES) {
                    pushFrame(acc);
                    accLen = 0;
                }
            } else if (src.dead()) {
                feedback.accept("§7[sight-audio] " + src.describe() + " 已退出，停止分享");
                break;
            } else {
                if (!blocking) {
                    Thread.sleep(6);
                } else {
                    Thread.sleep(2);
                }
                idleMs += blocking ? 2 : 6;
                if (!warnedSilent && idleMs > 4000) {
                    warnedSilent = true;
                    // 进程在但没声音是正常状态（比如只是挂在那儿），只记日志不打扰玩家
                    SightAudioClient.LOG.info("[sight-audio] {} 目前没有音频输出", src.describe());
                }
            }

            // 🔴 不能只在「读不到数据」时才查存活：目标进程关掉之后，
            //    process loopback 仍然会持续送**静音包**，read() 一直返回 960 个 0，
            //    永远进不了上面那个分支 ⇒ 会一直"分享静音"而不自知。
            if (++loops % 120 == 0 && src.dead()) {
                feedback.accept("§7[sight-audio] " + src.describe() + " 已退出，停止分享");
                break;
            }
        }
    }

    private static void pushFrame(short[] frame) {
        // 全静音的帧直接丢掉：混音里加 0 没有任何意义，但会让 SVC 一直发包
        if (isSilent(frame)) {
            return;
        }
        double v = Config.volume;
        if (v == 1.0) {
            BUFFER.push(frame);
            return;
        }
        short[] scaled = new short[frame.length];
        for (int i = 0; i < frame.length; i++) {
            int s = (int) (frame[i] * v);
            scaled[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
        }
        BUFFER.push(scaled);
    }

    /** 阈值 4/32767 ≈ -78 dBFS，正常人耳在游戏里绝对听不见。 */
    private static boolean isSilent(short[] frame) {
        for (short s : frame) {
            if (s > 4 || s < -4) {
                return false;
            }
        }
        return true;
    }
}
