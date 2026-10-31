import com.sight.audio.wasapi.AudioSession;
import com.sight.audio.wasapi.ProcessLoopback;
import com.sight.audio.wasapi.SessionLister;

import java.util.List;

/**
 * 独立冒烟测试：只依赖 com.sight.audio.wasapi，不需要 Minecraft。
 * 用法：java WasapiSmoke [目标PID]
 */
public class WasapiSmoke {

    public static void main(String[] args) throws Throwable {
        if (args.length == 0) {
            System.out.println("=== 枚举正在出声的进程 ===");
            List<AudioSession> list = SessionLister.list();
            if (list.isEmpty()) {
                System.out.println("（空）");
            }
            for (int i = 0; i < list.size(); i++) {
                AudioSession s = list.get(i);
                System.out.printf("[%d] pid=%-6d %-28s %s%n",
                        i + 1, s.pid(), s.name(), s.active() ? "● 正在出声" : "○ 空闲");
            }
            return;
        }

        int pid;
        int seconds;
        int mode = ProcessLoopback.MODE_INCLUDE;
        if ("exclude".equals(args[0])) {
            pid = Integer.parseInt(args[1]);
            seconds = args.length > 2 ? Integer.parseInt(args[2]) : 3;
            mode = ProcessLoopback.MODE_EXCLUDE;
        } else {
            pid = Integer.parseInt(args[0]);
            seconds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        }
        System.out.println("=== 采集 PID " + pid + "，模式 " + (mode == 0 ? "INCLUDE" : "EXCLUDE")
                + "，" + seconds + " 秒 ===");

        try (ProcessLoopback lp = ProcessLoopback.open(pid, mode)) {
            short[] buf = new short[960];
            long peak = 0;
            double sumSq = 0;
            long samples = 0;
            long frames = 0;
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                int n = lp.read(buf, 0, buf.length);
                if (n <= 0) {
                    Thread.sleep(6);
                    continue;
                }
                frames += n;
                for (int i = 0; i < n; i++) {
                    int v = buf[i];
                    int abs = Math.abs(v);
                    if (abs > peak) peak = abs;
                    sumSq += (double) v * v;
                    samples++;
                }
            }
            System.out.println("采集帧数 = " + frames + "（" + seconds + " 秒理论 " + (seconds * 48000) + "）");
            System.out.printf("峰值 = %d / 32767，RMS = %.1f%n", peak, samples == 0 ? 0 : Math.sqrt(sumSq / samples));
            System.out.println(peak > 50 ? "✅ 有声音" : "⚠️ 静音或该进程没在放");
        }
    }
}
