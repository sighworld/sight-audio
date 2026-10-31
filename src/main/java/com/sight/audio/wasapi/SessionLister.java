package com.sight.audio.wasapi;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 枚举「现在谁在出声」。
 *
 * <p>做法是问 Windows 要<b>默认播放设备</b>上的音频会话列表，再从每个会话查它的进程 ID。
 * 这就是系统音量合成器（音量 mixer）里那一列程序名的来源，也是唯一可靠的途径 ——
 * Windows 不提供「进程 X 是否在放声音」的直接查询。</p>
 *
 * <p>接口链：</p>
 * <pre>
 * CoCreateInstance(IMMDeviceEnumerator)
 *   → GetDefaultAudioEndpoint(eRender, eConsole)
 *     → IMMDevice.Activate(IID_IAudioSessionManager2)
 *       → GetSessionEnumerator → GetCount / GetSession(i)
 *         → QueryInterface(IID_IAudioSessionControl2)
 *           → GetState / GetProcessId / IsSystemSoundsSession
 * </pre>
 */
public final class SessionLister {

    private static final String CLSID_MMDEV_ENUM = "{BCDE0395-E52F-467C-8E3D-C4579291692E}";
    private static final String IID_MMDEV_ENUM = "{A95664D2-9614-4F35-A746-DE8DB63617E6}";
    private static final String IID_SESSION_MGR2 = "{77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F}";
    private static final String IID_SESSION_CTRL2 = "{BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D}";

    private static final int STATE_ACTIVE = 1;

    private SessionLister() {
    }

    /**
     * 列出当前有音频会话的进程（已按 PID 去重，正在出声的排在前面）。
     *
     * @throws WasapiException 拿不到设备或会话管理器
     */
    public static List<AudioSession> list() throws WasapiException {
        if (!Com.init()) {
            throw new WasapiException("初始化 COM", 0x800401F0);
        }
        try {
            MemorySegment enumerator = Com.createInstance(CLSID_MMDEV_ENUM, IID_MMDEV_ENUM);

            MemorySegment devOut = Com.ARENA.allocate(ValueLayout.ADDRESS);
            int hr = (int) Com.vt(enumerator, 4, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                    .invoke(enumerator, 0 /* eRender */, 0 /* eConsole */, devOut);
            if (hr != 0) {
                throw new WasapiException("GetDefaultAudioEndpoint", hr);
            }
            MemorySegment device = Com.ptr(devOut);
            try {
                return walkSessions(device);
            } finally {
                Com.release(device);
                Com.release(enumerator);
            }
        } catch (WasapiException e) {
            throw e;
        } catch (Throwable t) {
            throw new WasapiException("枚举音频会话（" + t + "）", 0x80004005);
        }
    }

    private static List<AudioSession> walkSessions(MemorySegment device) throws Throwable {
        MemorySegment mgrOut = Com.ARENA.allocate(ValueLayout.ADDRESS);
        int hr = (int) Com.vt(device, 3, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                .invoke(device, Com.guid(IID_SESSION_MGR2), 23 /* CLSCTX_ALL */, MemorySegment.NULL, mgrOut);
        if (hr != 0) {
            throw new WasapiException("Activate(IAudioSessionManager2)", hr);
        }
        MemorySegment mgr = Com.ptr(mgrOut);

        MemorySegment enumOut = Com.ARENA.allocate(ValueLayout.ADDRESS);
        hr = (int) Com.vt(mgr, 5, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                .invoke(mgr, enumOut);
        if (hr != 0) {
            Com.release(mgr);
            throw new WasapiException("GetSessionEnumerator", hr);
        }
        MemorySegment sessEnum = Com.ptr(enumOut);

        Map<Integer, AudioSession> byPid = new LinkedHashMap<>();
        try {
            MemorySegment countOut = Com.ARENA.allocate(ValueLayout.JAVA_INT);
            Com.vt(sessEnum, 3, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                    .invoke(sessEnum, countOut);
            int count = countOut.get(ValueLayout.JAVA_INT, 0);

            MemorySegment ctrl2Guid = Com.guid(IID_SESSION_CTRL2);
            FunctionDescriptor qiDesc = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

            for (int i = 0; i < count; i++) {
                MemorySegment ctrlOut = Com.ARENA.allocate(ValueLayout.ADDRESS);
                if ((int) Com.vt(sessEnum, 4, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
                        .invoke(sessEnum, i, ctrlOut) != 0) {
                    continue;
                }
                MemorySegment ctrl = Com.ptr(ctrlOut);
                try {
                    MemorySegment stateOut = Com.ARENA.allocate(ValueLayout.JAVA_INT);
                    Com.vt(ctrl, 3, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                            .invoke(ctrl, stateOut);
                    boolean active = stateOut.get(ValueLayout.JAVA_INT, 0) == STATE_ACTIVE;

                    MemorySegment ctrl2Out = Com.ARENA.allocate(ValueLayout.ADDRESS);
                    // 🔴 QueryInterface 必须走 ctrl 自己的虚表 0 号槽
                    if ((int) Com.vt(ctrl, 0, qiDesc).invoke(ctrl, ctrl2Guid, ctrl2Out) != 0) {
                        continue;
                    }
                    MemorySegment ctrl2 = Com.ptr(ctrl2Out);
                    try {
                        MemorySegment pidOut = Com.ARENA.allocate(ValueLayout.JAVA_INT);
                        int pid = -1;
                        if ((int) Com.vt(ctrl2, 14, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                                .invoke(ctrl2, pidOut) == 0) {
                            pid = pidOut.get(ValueLayout.JAVA_INT, 0);
                        }
                        if (pid <= 0) {
                            continue;   // 系统音（pid 0）或查询失败，跳过
                        }
                        boolean sys = (int) Com.vt(ctrl2, 15, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                        ValueLayout.ADDRESS))
                                .invoke(ctrl2) == 0;
                        if (sys) {
                            continue;
                        }
                        String name = resolveName(pid, ctrl);
                        AudioSession old = byPid.get(pid);
                        if (old == null || (!old.active() && active)) {
                            byPid.put(pid, new AudioSession(pid, name, active, false));
                        }
                    } finally {
                        Com.release(ctrl2);
                    }
                } finally {
                    Com.release(ctrl);
                }
            }
        } finally {
            Com.release(sessEnum);
            Com.release(mgr);
        }

        List<AudioSession> out = new ArrayList<>(byPid.values());
        out.sort((a, b) -> {
            if (a.active() != b.active()) {
                return a.active() ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        return out;
    }

    /** 先问进程自己叫什么（最准），问不到再问会话的显示名，都不行就报 PID。 */
    private static String resolveName(int pid, MemorySegment ctrl) {
        try {
            Optional<ProcessHandle> h = ProcessHandle.of(pid);
            if (h.isPresent()) {
                Optional<String> cmd = h.get().info().command();
                if (cmd.isPresent() && !cmd.get().isBlank()) {
                    String s = cmd.get();
                    return s.substring(Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/')) + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            MemorySegment nameOut = Com.ARENA.allocate(ValueLayout.ADDRESS);
            // IAudioSessionControl::GetDisplayName(LPWSTR*) → 虚表 4
            if ((int) Com.vt(ctrl, 4, FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                    .invoke(ctrl, nameOut) == 0) {
                String s = Com.takeString(Com.ptr(nameOut));
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return "进程 " + pid;
    }
}
