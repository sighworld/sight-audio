package com.sight.audio;

/**
 * 采集线程与 SVC 取帧回调之间的抖动缓冲。
 *
 * <p>两边节奏是独立的：采集按声卡时钟走（大约每 10 ms 来一包），SVC 按自己的
 * 20 ms 定时器来取。中间必须有缓冲吸收相位差，否则会出现「这一帧有两帧的数据、
 * 下一帧没有」的撕裂感。</p>
 *
 * <p>两个边界行为是故意的：</p>
 * <ul>
 *   <li><b>读空 → 返回 null</b>：表示这一帧什么都不合并，玩家的真实麦克风照常通过，
 *       不会因为没声音就把话筒静音；</li>
 *   <li><b>写满 → 丢最旧的</b>：宁可丢 20 ms 的声音，也不要让延迟越积越大。
 *       直播类应用里「越听越延迟」比「偶尔卡一下」难受得多。</li>
 * </ul>
 */
public final class FrameBuffer {

    private short[][] ring;
    private int writeIdx;
    private int readIdx;
    private int size;

    public FrameBuffer(int capacityFrames) {
        this.ring = new short[Math.max(2, capacityFrames)][];
    }

    /** 扩容/重建（帧数改了的时候用）。会丢掉已有内容。 */
    public synchronized void resize(int capacityFrames) {
        this.ring = new short[Math.max(2, capacityFrames)][];
        this.writeIdx = 0;
        this.readIdx = 0;
        this.size = 0;
    }

    /** 推一帧。内部复制，调用方可以复用同一个数组。 */
    public synchronized void push(short[] frame) {
        if (ring.length == 0) {
            return;
        }
        if (size == ring.length) {
            ring[readIdx] = null;
            readIdx = (readIdx + 1) % ring.length;
            size--;
        }
        ring[writeIdx] = frame.clone();
        writeIdx = (writeIdx + 1) % ring.length;
        size++;
    }

    /** 取一帧；空返回 {@code null}。 */
    public synchronized short[] poll() {
        if (size <= 0) {
            return null;
        }
        short[] frame = ring[readIdx];
        ring[readIdx] = null;
        readIdx = (readIdx + 1) % ring.length;
        size--;
        return frame;
    }

    public synchronized void clear() {
        java.util.Arrays.fill(ring, null);
        writeIdx = 0;
        readIdx = 0;
        size = 0;
    }

    public synchronized int size() {
        return size;
    }
}
