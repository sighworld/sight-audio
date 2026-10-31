package com.sight.audio.wasapi;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 采集**单个进程**的音频输出（Windows 10 2004+ 的 process loopback）。
 *
 * <h2>为什么这条路能去掉虚拟声卡</h2>
 * 系统音量合成器里每个程序一列，说明 Windows 内部本来就有「按进程分流的音频」，
 * 只是一直没开放给普通程序。2004 版起补上了
 * {@code ActivateAudioInterfaceAsync("VAD\Process_Loopback", AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK)}，
 * 按 PID 直接开一条环回流。这条流拿到的就是那个进程（含它的子进程）正在播的声音，
 * 其他程序的声音一点都进不来。
 *
 * <h2>三个硬约束（都是实测出来的，写错就是 E_INVALIDARG）</h2>
 * <ol>
 *   <li>格式<b>必须</b> 48 kHz / 16 bit / <b>双声道</b> PCM，别的采样率或声道数直接失败；</li>
 *   <li>必须带 {@code AUDCLNT_STREAMFLAGS_LOOPBACK}（0x00020000）；</li>
 *   <li>必须先 {@code CoInitializeEx} 再调，否则 {@code CO_E_NOTINITIALIZED}。</li>
 * </ol>
 *
 * <h2>为什么是轮询而不是事件</h2>
 * 事件模式要在 {@code Initialize} 里注册一个 Win32 事件句柄，跨 FFM 传 HANDLE 又要多一层
 * 生命周期管理。实测每 8 ms 轮询一次 {@code GetNextPacketSize} 完全够用 —— 引擎周期约
 * 10 ms，抖动由上层环形队列吸收，玩家听不出区别。
 */
public final class ProcessLoopback implements AutoCloseable {

    // ---------------------------------------------------------------- 常量

    private static final String IID_IAUDIO_CLIENT = "{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}";
    private static final String IID_IAUDIO_CAPTURE_CLIENT = "{C8ADBD64-E71E-48A0-A4DE-185C395CD317}";

    private static final int CLSCTX_ALL = 23;
    private static final int PROCESS_LOOPBACK = 1;

    /** 只采这一个进程（含它的子进程）。 */
    public static final int MODE_INCLUDE = 0;

    /** 采**除**这一个进程之外的所有声音 —— 传本游戏 PID 就是「整个系统，但不要游戏自己那路」。 */
    public static final int MODE_EXCLUDE = 1;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int BUFFER_FLAG_SILENT = 0x2;
    private static final int BUFFER_FLAG_DATA_DISCONTINUITY = 0x1;

    /** 引擎缓冲 200 ms（REFERENCE_TIME 单位 100 ns）。 */
    private static final long BUFFER_DURATION = 2_000_000L;

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BITS = 16;
    private static final int BLOCK_ALIGN = CHANNELS * BITS / 8;

    // ---------------------------------------------------------------- 异步激活的共享槽位
    // 同一时刻只允许一个 open（CaptureManager 保证），所以用静态槽位传递结果足够。

    private static final Object OPEN_LOCK = new Object();
    private static final String TAG = "sight-audio";

    private static volatile CountDownLatch pendingLatch;
    private static volatile MemorySegment pendingClient = MemorySegment.NULL;
    private static volatile int pendingInnerHr = -1;
    private static volatile int pendingOuterHr = -1;

    private static MethodHandle upcallQueryInterface;
    private static MethodHandle upcallAddRef;
    private static MethodHandle upcallRelease;
    private static MethodHandle upcallActivateCompleted;

    // ---------------------------------------------------------------- 实例

    private final int pid;
    private final MemorySegment audioClient;
    private final MemorySegment captureClient;
    private final MemorySegment operation;

    private final MethodHandle getBuffer;
    private final MethodHandle releaseBuffer;
    private final MethodHandle nextPacketSize;
    private final MethodHandle readStop;

    /** 解交织后的单声道残留（上一次没被取走的部分）。 */
    private short[] pending = new short[8192];
    private int pendingLen;

    // 🔴 这些出参必须**预先分配**。pull() 每秒被调 160 多次，
    //    如果在里面 new MemorySegment，共享 Arena 永不释放 ⇒ 一小时泄漏几百 MB。
    //    只有采集线程会碰它们，所以复用是安全的。
    private final MemorySegment framesOut;
    private final MemorySegment pData;
    private final MemorySegment flagsOut;
    private final MemorySegment devPos;
    private final MemorySegment qpcPos;

    private volatile boolean closed;
    private long framesDelivered;
    private long silentPackets;

    private ProcessLoopback(int pid, MemorySegment client, MemorySegment operation) throws Throwable {
        this.pid = pid;
        this.audioClient = client;
        this.operation = operation;

        // IAudioClient::Initialize(ShareMode, StreamFlags, hnsBufferDuration, hnsPeriodicity, WAVEFORMATEX*, GUID*)
        int hr = (int) Com.vt(client, 3, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                .invoke(client, 0 /* SHARED */, AUDCLNT_STREAMFLAGS_LOOPBACK, BUFFER_DURATION, 0L,
                        waveFormat(), MemorySegment.NULL);
        if (hr != 0) {
            throw new WasapiException("IAudioClient.Initialize", hr);
        }

        MemorySegment capOut = Com.ARENA.allocate(ValueLayout.ADDRESS);
        hr = (int) Com.vt(client, 14, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                .invoke(client, Com.guid(IID_IAUDIO_CAPTURE_CLIENT), capOut);
        if (hr != 0) {
            throw new WasapiException("GetService(IAudioCaptureClient)", hr);
        }
        this.captureClient = Com.ptr(capOut);

        this.getBuffer = Com.vt(captureClient, 3, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.releaseBuffer = Com.vt(captureClient, 4, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.nextPacketSize = Com.vt(captureClient, 5, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.readStop = Com.vt(client, 11, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        this.framesOut = Com.ARENA.allocate(ValueLayout.JAVA_INT);
        this.pData = Com.ARENA.allocate(ValueLayout.ADDRESS);
        this.flagsOut = Com.ARENA.allocate(ValueLayout.JAVA_INT);
        this.devPos = Com.ARENA.allocate(ValueLayout.JAVA_LONG);
        this.qpcPos = Com.ARENA.allocate(ValueLayout.JAVA_LONG);

        hr = (int) Com.vt(client, 10, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                .invoke(client);
        if (hr != 0) {
            throw new WasapiException("IAudioClient.Start", hr);
        }
    }

    /**
     * 打开某个进程的采集流。
     *
     * @throws WasapiException 目标进程不是管理员、已退出、或该 Windows 版本不支持
     */
    public static ProcessLoopback open(int pid) throws WasapiException {
        return open(pid, MODE_INCLUDE);
    }

    /**
     * @param loopbackMode {@link #MODE_INCLUDE} 只采该进程；{@link #MODE_EXCLUDE} 采除它以外的一切
     */
    public static ProcessLoopback open(int pid, int loopbackMode) throws WasapiException {
        if (!Com.init()) {
            throw new WasapiException("初始化 COM", 0x800401F0);
        }
        if (pid <= 0) {
            throw new WasapiException("打开进程循环回采（PID 无效）", 0x80070057);
        }
        synchronized (OPEN_LOCK) {
            try {
                prepareStubs();
                pendingLatch = new CountDownLatch(1);
                pendingClient = MemorySegment.NULL;
                pendingInnerHr = -1;
                pendingOuterHr = -1;

                MemorySegment handler = buildHandler();
                MemorySegment params = Com.ARENA.allocate(12);
                params.set(ValueLayout.JAVA_INT, 0, PROCESS_LOOPBACK);
                params.set(ValueLayout.JAVA_INT, 4, pid);
                params.set(ValueLayout.JAVA_INT, 8, loopbackMode);

                MemorySegment propvar = Com.ARENA.allocate(24);
                propvar.set(ValueLayout.JAVA_SHORT_UNALIGNED, 0, (short) 65 /* VT_BLOB */);
                propvar.set(ValueLayout.JAVA_INT_UNALIGNED, 8, 12);
                propvar.set(ValueLayout.ADDRESS, 16, params);

                MethodHandle activate = activationHandle();
                MemorySegment devicePath = Com.ARENA.allocateFrom("VAD\\Process_Loopback",
                        java.nio.charset.StandardCharsets.UTF_16LE);
                MemorySegment opOut = Com.ARENA.allocate(ValueLayout.ADDRESS);

                int hr = (int) activate.invoke(devicePath, Com.guid(IID_IAUDIO_CLIENT), propvar, handler, opOut);
                if (hr != 0) {
                    throw new WasapiException("ActivateAudioInterfaceAsync", hr);
                }
                if (!pendingLatch.await(6, TimeUnit.SECONDS)) {
                    throw new WasapiException("等待激活回调（超时）", 0x80004005);
                }
                if (pendingOuterHr != 0) {
                    throw new WasapiException("GetActivateResult", pendingOuterHr);
                }
                if (pendingInnerHr != 0) {
                    throw new WasapiException("激活进程采集接口", pendingInnerHr);
                }
                MemorySegment client = pendingClient;
                if (client.address() == 0) {
                    throw new WasapiException("激活进程采集接口（空指针）", 0x80004005);
                }
                MemorySegment op = Com.ptr(opOut);
                return new ProcessLoopback(pid, client, op);
            } catch (WasapiException e) {
                throw e;
            } catch (Throwable t) {
                throw new WasapiException("打开进程循环回采（" + t.getClass().getSimpleName() + "）", 0x80004005);
            } finally {
                pendingLatch = null;
            }
        }
    }

    /**
     * 读一段单声道 PCM（已解交织、已做 L/R 平均）。
     *
     * @return 写入的采样点数；0 = 目前没数据
     */
    public int read(short[] out, int off, int len) throws Throwable {
        if (closed) {
            return 0;
        }
        if (pendingLen == 0 && !pull()) {
            return 0;
        }
        int n = Math.min(len, pendingLen);
        System.arraycopy(pending, 0, out, off, n);
        System.arraycopy(pending, n, pending, 0, pendingLen - n);
        pendingLen -= n;
        return n;
    }

    public int pid() {
        return pid;
    }

    public long framesDelivered() {
        return framesDelivered;
    }

    public long silentPackets() {
        return silentPackets;
    }

    /** 目标进程还在不在。 */
    public boolean targetAlive() {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** 停流并释放 COM 对象。必须由读线程调用（或读线程已退出后调用）。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            readStop.invoke(audioClient);
        } catch (Throwable ignored) {
        }
        Com.release(captureClient);
        Com.release(audioClient);
        Com.release(operation);
    }

    // ---------------------------------------------------------------- 内部

    /** 拉一个包进 pending。 */
    private boolean pull() throws Throwable {
        if ((int) nextPacketSize.invoke(captureClient, framesOut) != 0) {
            return false;
        }
        if (framesOut.get(ValueLayout.JAVA_INT, 0) <= 0) {
            return false;
        }

        int hr = (int) getBuffer.invoke(captureClient, pData, framesOut, flagsOut, devPos, qpcPos);
        if (hr != 0) {
            return false;
        }
        int frames = framesOut.get(ValueLayout.JAVA_INT, 0);
        if (frames <= 0) {
            return false;
        }
        int flags = flagsOut.get(ValueLayout.JAVA_INT, 0);

        if (pending.length < frames) {
            pending = new short[Math.max(frames, pending.length * 2)];
        }

        if ((flags & BUFFER_FLAG_SILENT) != 0) {
            // 🔴 静音包的数据指针是无效的，不能读，只能自己填 0
            java.util.Arrays.fill(pending, 0, frames, (short) 0);
            silentPackets++;
        } else {
            long bytes = (long) frames * BLOCK_ALIGN;
            MemorySegment data = Com.ptr(pData).reinterpret(bytes);
            for (int i = 0; i < frames; i++) {
                short l = data.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) i * 4);
                short r = data.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) i * 4 + 2);
                pending[i] = (short) (((int) l + (int) r) / 2);
            }
            if ((flags & BUFFER_FLAG_DATA_DISCONTINUITY) != 0) {
                // 断流（换设备/进程刚起来），这一包直接丢掉，避免爆音
                releaseBuffer.invoke(captureClient, frames);
                pendingLen = 0;
                return false;
            }
        }
        framesDelivered += frames;
        pendingLen = frames;
        releaseBuffer.invoke(captureClient, frames);
        return true;
    }

    /** WAVEFORMATEX：process loopback 只认 48k/16bit/2ch。 */
    private static MemorySegment waveFormat() {
        MemorySegment wfx = Com.ARENA.allocate(18);
        wfx.set(ValueLayout.JAVA_SHORT_UNALIGNED, 0, (short) 1);                 // WAVE_FORMAT_PCM
        wfx.set(ValueLayout.JAVA_SHORT_UNALIGNED, 2, (short) CHANNELS);
        wfx.set(ValueLayout.JAVA_INT_UNALIGNED, 4, SAMPLE_RATE);
        wfx.set(ValueLayout.JAVA_INT_UNALIGNED, 8, SAMPLE_RATE * BLOCK_ALIGN);   // nAvgBytesPerSec
        wfx.set(ValueLayout.JAVA_SHORT_UNALIGNED, 12, (short) BLOCK_ALIGN);
        wfx.set(ValueLayout.JAVA_SHORT_UNALIGNED, 14, (short) BITS);
        wfx.set(ValueLayout.JAVA_SHORT_UNALIGNED, 16, (short) 0);                // cbSize
        return wfx;
    }

    private static MethodHandle activationHandle;

    private static MethodHandle activationHandle() throws Throwable {
        if (activationHandle == null) {
            activationHandle = Com.LINKER.downcallHandle(
                    java.lang.foreign.SymbolLookup.libraryLookup("mmdevapi", Com.ARENA)
                            .find("ActivateAudioInterfaceAsync").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        }
        return activationHandle;
    }

    private static void prepareStubs() throws Throwable {
        if (upcallQueryInterface != null) {
            return;
        }
        MethodHandles.Lookup lk = MethodHandles.lookup();
        upcallQueryInterface = lk.findStatic(ProcessLoopback.class, "onQueryInterface",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
        upcallAddRef = lk.findStatic(ProcessLoopback.class, "onAddRef",
                MethodType.methodType(int.class, MemorySegment.class));
        upcallRelease = lk.findStatic(ProcessLoopback.class, "onRelease",
                MethodType.methodType(int.class, MemorySegment.class));
        upcallActivateCompleted = lk.findStatic(ProcessLoopback.class, "onActivateCompleted",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
    }

    /** 造一个最小的 IActivateAudioInterfaceCompletionHandler COM 对象。 */
    private static MemorySegment buildHandler() {
        MemorySegment obj = Com.ARENA.allocate(16);
        MemorySegment vtbl = Com.ARENA.allocate(4L * Com.PTR);
        long p = Com.PTR;
        vtbl.set(ValueLayout.ADDRESS, 0, Com.LINKER.upcallStub(upcallQueryInterface,
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), Com.ARENA));
        vtbl.set(ValueLayout.ADDRESS, p, Com.LINKER.upcallStub(upcallAddRef,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), Com.ARENA));
        vtbl.set(ValueLayout.ADDRESS, 2 * p, Com.LINKER.upcallStub(upcallRelease,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), Com.ARENA));
        vtbl.set(ValueLayout.ADDRESS, 3 * p, Com.LINKER.upcallStub(upcallActivateCompleted,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), Com.ARENA));
        obj.set(ValueLayout.ADDRESS, 0, vtbl);
        return obj;
    }

    // ---------------------------------------------------------------- COM 回调

    private static int onQueryInterface(MemorySegment self, MemorySegment riid, MemorySegment ppv) {
        // 🔴 必须回自己。回 E_NOINTERFACE 会让 Windows 判定对象非法（E_ILLEGAL_METHOD_CALL）。
        ppv.reinterpret(Com.PTR).set(ValueLayout.ADDRESS, 0, self);
        return 0;
    }

    private static int onAddRef(MemorySegment self) {
        return 1;
    }

    private static int onRelease(MemorySegment self) {
        return 1;
    }

    private static int onActivateCompleted(MemorySegment self, MemorySegment operation) {
        int inner = -1;
        int outer = -1;
        try {
            MemorySegment hrOut = Com.ARENA.allocate(ValueLayout.JAVA_INT);
            MemorySegment clientOut = Com.ARENA.allocate(ValueLayout.ADDRESS);
            outer = (int) Com.vt(operation, 3, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                    .invoke(operation, hrOut, clientOut);
            inner = hrOut.get(ValueLayout.JAVA_INT, 0);
            if (outer == 0 && inner == 0) {
                pendingClient = Com.ptr(clientOut);
            }
        } catch (Throwable t) {
            outer = -1;
        } finally {
            pendingOuterHr = outer;
            pendingInnerHr = inner;
            CountDownLatch l = pendingLatch;
            if (l != null) {
                l.countDown();
            }
        }
        return 0;
    }
}
