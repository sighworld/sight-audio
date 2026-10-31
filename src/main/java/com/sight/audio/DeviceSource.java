package com.sight.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟声卡模式：从任意一个录音设备读 PCM。
 *
 * <p>这条是<b>后备路线</b>。主路线是 WASAPI process loopback（按进程抓，不用装驱动），
 * 但如果玩家系统太旧、或者 FFM 被安全策略挡住，这条路还能救急 —— 那时玩家装个
 * VB-CABLE，用 {@code /sa device CABLE} 切回来即可。</p>
 */
final class DeviceSource implements Source {

    private final TargetDataLine line;
    private final String name;

    /** 一帧 = 960 采样点 = 1920 字节（16 bit 单声道）。 */
    private static final int FRAME_BYTES = 960 * 2;
    private final byte[] bytes = new byte[FRAME_BYTES];

    private DeviceSource(TargetDataLine line, String name) {
        this.line = line;
        this.name = name;
    }

    static DeviceSource open(String nameFragment) throws Exception {
        AudioFormat format = new AudioFormat(48_000f, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        String needle = nameFragment == null ? "" : nameFragment.toLowerCase();

        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (!mi.getName().toLowerCase().contains(needle)) {
                continue;
            }
            Mixer mixer = AudioSystem.getMixer(mi);
            if (!mixer.isLineSupported(info)) {
                continue;
            }
            Line raw = mixer.getLine(info);
            if (raw instanceof TargetDataLine tdl) {
                tdl.open(format, 960 * 2 * 20);   // 400 ms
                tdl.start();
                return new DeviceSource(tdl, mi.getName());
            }
        }
        throw new IllegalStateException("找不到名字包含「" + nameFragment + "」且支持 48kHz 单声道的录音设备");
    }

    /** 列出所有能当输入用的设备名，配置错时打给玩家看。 */
    static List<String> listRecordable() {
        AudioFormat format = new AudioFormat(48_000f, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        List<String> out = new ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mi).isLineSupported(info)) {
                out.add(mi.getName());
            }
        }
        return out;
    }

    @Override
    public int read(short[] out, int off, int len) throws Throwable {
        int wantBytes = Math.min(len, 960) * 2;
        int got = 0;
        while (got < wantBytes) {
            int n = line.read(bytes, got, wantBytes - got);
            if (n <= 0) {
                break;
            }
            got += n;
        }
        int samples = got / 2;
        for (int i = 0; i < samples; i++) {
            int lo = bytes[i * 2] & 0xFF;
            int hi = bytes[i * 2 + 1];
            out[off + i] = (short) ((hi << 8) | lo);
        }
        return samples;
    }

    @Override
    public String describe() {
        return name;
    }

    @Override
    public void unblock() {
        try {
            line.stop();
            line.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        unblock();
    }
}
