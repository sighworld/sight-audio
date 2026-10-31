package com.sight.audio;

import com.sight.audio.wasapi.ProcessLoopback;

/** Process loopback 的包装：把「读不足就等一会儿」的责任交给上层循环。 */
final class ProcessSource implements Source {

    private final ProcessLoopback loopback;
    private final String label;
    private int deadTicks;

    ProcessSource(ProcessLoopback loopback, String label) {
        this.loopback = loopback;
        this.label = label;
    }

    @Override
    public int read(short[] out, int off, int len) throws Throwable {
        return loopback.read(out, off, len);
    }

    @Override
    public String describe() {
        return label + "（PID " + loopback.pid() + "）";
    }

    @Override
    public boolean dead() {
        // 连续几次看不到进程才算死，避免瞬时查询失败误判
        if (loopback.targetAlive()) {
            deadTicks = 0;
            return false;
        }
        return ++deadTicks > 3;
    }

    @Override
    public void close() {
        loopback.close();
    }
}
