package com.sight.audio.wasapi;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Windows COM 的最小可用底座（纯 FFM，无 JNA、无 DLL）。
 *
 * <h2>为什么用 FFM 而不是 JNA</h2>
 * JDK 25 自带的 java.lang.foreign 能直接声明 C 函数、读写结构体、做 upcall（Java 方法当
 * C 回调）。JNA 要多带一个 1.6 MB 的 jar 和一堆 native 桩；FFM 什么都不用带，
 * 玩家端就少一个依赖。
 *
 * <h2>三条必须遵守的规矩（都是实测踩出来的）</h2>
 * <ol>
 *   <li><b>每个线程用 COM 前都要 CoInitializeEx</b>，即使主线程已经初始化过。
 *       漏掉会拿到 {@code 0x800401F0 = CO_E_NOTINITIALIZED}。</li>
 *   <li><b>QueryInterface 必须走对象自己的虚表 0 号槽</b>。拿别的接口的虚表去调，
 *       {@code this} 类型不对，会静默返回失败（表现为 pid 永远是 -1）。</li>
 *   <li><b>upcall 收到的指针 byteSize 是 0，写之前必须 reinterpret</b>，
 *       否则 {@code IndexOutOfBoundsException} 或更糟。</li>
 * </ol>
 *
 * <h2>内存策略</h2>
 * 全部用 {@link Arena#ofShared()} 并且永不关闭。因为 COM 对象和 upcall 桩的生命周期
 * 由 Windows 那边决定，我们无法精确知道它什么时候不再回调；提前释放 = 段错误。
 * 每次打开采集只多占几百字节，一个游戏会话几十次也就十几 KB，不值得冒崩溃的险。
 */
public final class Com {

    public static final Linker LINKER = Linker.nativeLinker();

    /** 全局堆：跨线程共享、永不释放。 */
    public static final Arena ARENA = Arena.ofShared();

    public static final long PTR = ValueLayout.ADDRESS.byteSize();

    private static final ThreadLocal<Boolean> INITIALIZED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** FFM 在这个平台/这个 JDK 上能不能用。false 时整条 WASAPI 路线自动退化。 */
    private static final boolean AVAILABLE;

    private static MethodHandle coInit;
    private static MethodHandle coCreate;
    private static MethodHandle coTaskFree;
    private static String loadError = "";

    static {
        boolean ok;
        try {
            coInit = LINKER.downcallHandle(
                    SymbolLookup.libraryLookup("ole32", ARENA).find("CoInitializeEx").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            coCreate = LINKER.downcallHandle(
                    SymbolLookup.libraryLookup("ole32", ARENA).find("CoCreateInstance").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            coTaskFree = LINKER.downcallHandle(
                    SymbolLookup.libraryLookup("ole32", ARENA).find("CoTaskMemFree").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            ok = true;
        } catch (Throwable t) {
            loadError = String.valueOf(t);
            ok = false;
        }
        AVAILABLE = ok;
    }

    private Com() {
    }

    public static boolean available() {
        return AVAILABLE;
    }

    public static String loadError() {
        return loadError;
    }

    // ------------------------------------------------------------------ 初始化

    /**
     * 当前线程初始化 COM（幂等）。
     *
     * @return true = 可用
     */
    public static boolean init() {
        if (!AVAILABLE) {
            return false;
        }
        if (Boolean.TRUE.equals(INITIALIZED.get())) {
            return true;
        }
        try {
            int hr = (int) coInit.invoke(MemorySegment.NULL, 0);   // COINIT_MULTITHREADED
            // S_OK / S_FALSE / RPC_E_CHANGED_MODE 都算能用
            if (hr == 0 || hr == 1 || hr == 0x80010106) {
                INITIALIZED.set(Boolean.TRUE);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 结构体

    /** 生成一个 16 字节 GUID。格式 {8-4-4-4-12}。 */
    public static MemorySegment guid(String spec) {
        String s = spec.replace("{", "").replace("}", "");
        String[] p = s.split("-");
        MemorySegment g = ARENA.allocate(16);
        g.set(ValueLayout.JAVA_INT_UNALIGNED, 0, (int) Long.parseUnsignedLong(p[0], 16));
        g.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, (short) Integer.parseInt(p[1], 16));
        g.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, (short) Integer.parseInt(p[2], 16));
        String tail = p[3] + p[4];
        for (int i = 0; i < 8; i++) {
            g.set(ValueLayout.JAVA_BYTE, 8 + i, (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16));
        }
        return g;
    }

    /** CoCreateInstance 的简写。 */
    public static MemorySegment createInstance(String clsid, String iid) throws Throwable {
        MemorySegment out = ARENA.allocate(ValueLayout.ADDRESS);
        int hr = (int) coCreate.invoke(guid(clsid), MemorySegment.NULL, 23 /* CLSCTX_ALL */, guid(iid), out);
        if (hr != 0) {
            throw new WasapiException("CoCreateInstance(" + clsid + ")", hr);
        }
        return ptr(out);
    }

    // ------------------------------------------------------------------ 虚表

    /** 取 COM 对象虚表第 {@code index} 个方法。 */
    public static MethodHandle vt(MemorySegment obj, int index, FunctionDescriptor desc) {
        MemorySegment vtbl = obj.reinterpret(PTR).get(ValueLayout.ADDRESS, 0).reinterpret(1024);
        MemorySegment fn = vtbl.get(ValueLayout.ADDRESS, index * PTR).reinterpret(1024);
        return LINKER.downcallHandle(fn, desc);
    }

    /** Release（虚表 2 号槽）。空指针安全。 */
    public static void release(MemorySegment obj) {
        if (obj == null || obj.address() == 0) {
            return;
        }
        try {
            vt(obj, 2, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)).invoke(obj);
        } catch (Throwable ignored) {
        }
    }

    /** 读 {@code MemorySegment**} 形式出参里的指针。 */
    public static MemorySegment ptr(MemorySegment out) {
        return out.get(ValueLayout.ADDRESS, 0);
    }

    /** 读 {@code LPWSTR}（COM 用 CoTaskMemAlloc 分配的宽字符串）并释放。 */
    public static String takeString(MemorySegment strPtr) {
        if (strPtr == null || strPtr.address() == 0) {
            return null;
        }
        try {
            String s = strPtr.reinterpret(4096).getString(0, java.nio.charset.StandardCharsets.UTF_16LE);
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                coTaskFree.invoke(strPtr);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 错误

    /** HRESULT 转人话。 */
    public static String describe(int hr) {
        return switch (hr) {
            case 0 -> "成功";
            case 0x80070005 -> "拒绝访问（目标以管理员身份运行，权限更高）";
            case 0x800401F0 -> "COM 未初始化";
            case 0x80070057 -> "参数不合法";
            case 0x80004005 -> "未指明的错误";
            case 0x88890001 -> "音频格式不支持";
            case 0x88890004 -> "音频设备已失效（切换了默认播放设备）";
            case 0x88890008 -> "不支持的共享模式";
            case 0x8889000A -> "Initialize 之前不能这样调";
            case 0x88890019 -> "进程已退出或没有可采集的音频";
            case 0x8000000E -> "非法方法调用";
            case 0x80004002 -> "不支持该接口";
            default -> "未知错误";
        };
    }
}
