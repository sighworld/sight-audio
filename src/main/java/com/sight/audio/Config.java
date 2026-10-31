package com.sight.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置：{@code .minecraft/config/sight-audio.json}。
 *
 * <p>现在实现在游戏内用命令改（{@code /sa volume 60}），文件只是持久化落点。</p>
 */
public final class Config {

    /** 上次分享的进程 PID（重启游戏后 F9 直接续上）。-1 = 没有。 */
    public static int lastPid = -1;

    /** 上次分享的进程名，只用于显示。 */
    public static String lastName = "";

    /** 虚拟声卡模式的设备名（子串匹配，大小写不敏感）。 */
    public static String device = "CABLE Output";

    /** 音量倍率。1.0 = 原样。 */
    public static double volume = 1.0;

    /** 进游戏就自动开始采集上次的目标。 */
    public static boolean autoStart = false;

    /** 抖动缓冲帧数（1 帧 = 20ms）。太小会断续，太大会延迟。 */
    public static int bufferFrames = 5;

    private static final Path PATH = Path.of("config", "sight-audio.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Config() {
    }

    public static void load() {
        try {
            if (!Files.exists(PATH)) {
                save();
                return;
            }
            try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                if (o.has("lastPid")) {
                    lastPid = o.get("lastPid").getAsInt();
                }
                if (o.has("lastName")) {
                    lastName = o.get("lastName").getAsString();
                }
                if (o.has("device")) {
                    device = o.get("device").getAsString();
                }
                if (o.has("volume")) {
                    volume = o.get("volume").getAsDouble();
                }
                if (o.has("autoStart")) {
                    autoStart = o.get("autoStart").getAsBoolean();
                }
                if (o.has("bufferFrames")) {
                    bufferFrames = Math.max(1, Math.min(50, o.get("bufferFrames").getAsInt()));
                }
            }
            SightAudioClient.LOG.info("[sight-audio] 配置已读取：lastPid={} volume={} buffer={}",
                    lastPid, volume, bufferFrames);
        } catch (Exception e) {
            SightAudioClient.LOG.error("[sight-audio] 读配置失败，用默认值", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("lastPid", lastPid);
            o.addProperty("lastName", lastName);
            o.addProperty("device", device);
            o.addProperty("volume", volume);
            o.addProperty("autoStart", autoStart);
            o.addProperty("bufferFrames", bufferFrames);
            try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(o, w);
            }
        } catch (Exception e) {
            SightAudioClient.LOG.error("[sight-audio] 写配置失败", e);
        }
    }
}
