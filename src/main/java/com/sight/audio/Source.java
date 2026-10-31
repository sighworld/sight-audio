package com.sight.audio;

/** 一路 PCM 来源。两种实现：按进程（WASAPI）和按设备（虚拟声卡）。 */
public interface Source extends AutoCloseable {

    /**
     * 读单声道 48 kHz PCM。
     *
     * @return 写入的采样数；0 = 暂时没数据（调用方应短暂等待再试）
     */
    int read(short[] out, int off, int len) throws Throwable;

    /** 给玩家看的来源描述。 */
    String describe();

    /** 源头已经没了（目标进程退出等），上层应停止采集。 */
    default boolean dead() {
        return false;
    }

    /** 从其它线程强行解除阻塞（设备模式用；进程模式非阻塞，不需要）。 */
    default void unblock() {
    }

    @Override
    void close();
}
