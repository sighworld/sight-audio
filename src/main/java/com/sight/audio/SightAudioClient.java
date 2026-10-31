package com.sight.audio;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sight.audio.wasapi.AudioSession;
import com.sight.audio.wasapi.SessionLister;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端入口。
 *
 * <h2>两个热键，职责分开</h2>
 * <ul>
 *   <li><b>F9</b> —— 打开分享面板（选程序、调音量、看状态）；</li>
 *   <li><b>F10</b> —— 快速开关，不开面板，直接用上次选的那个。</li>
 * </ul>
 *
 * <h2>为什么面板自绘</h2>
 * 见 {@link SightAudioScreen} 的类注释：26.2 的渲染管线换了两阶段，
 * 只用 {@code fill} / {@code text} 两个原语最不容易随小版本失效。
 */
public final class SightAudioClient implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("sight-audio");

    /** 打开面板。 */
    private static final int OPEN_KEY = GLFW.GLFW_KEY_F9;

    /** 快速开关。 */
    private static final int TOGGLE_KEY = GLFW.GLFW_KEY_F10;

    /** 最近一次聊天天列表，{@code pick <n>} 按下标取。 */
    private static volatile List<AudioSession> lastList = List.of();
    private static volatile boolean scanning;

    private boolean openKeyWasDown;
    private boolean toggleKeyWasDown;

    @Override
    public void onInitializeClient() {
        Config.load();

        CaptureManager.feedback = SightAudioClient::toast;

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        // 🔴 10-29 根字面量必须叫 sa，不能叫 sightaudio：
        //    Fabric 的客户端命令在**本地先匹配**，一旦注册了 /sightaudio，
        //    服务端的同名命令（sight-core 的语音管理页）对整个客户端就彻底失效了
        //    —— 连 OP 也打不开，因为请求根本不会发到服务端。
        //    老大 10-29 报的就是这个：「服务器也用 /sightaudio 打开页面，mod 也用，现在只能打开 mod 的」。
        //    现在的分工：/sa = 客户端面板（玩家）／ /sa = 服务端管理页（OP）。
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("sa")
                        .executes(ctx -> {
                            openScreen();
                            return 1;
                        })
                        .then(ClientCommands.literal("list").executes(ctx -> {
                            runList();
                            return 1;
                        }))
                        .then(ClientCommands.literal("pick")
                                .then(ClientCommands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            pick(IntegerArgumentType.getInteger(ctx, "index"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("system").executes(ctx -> {
                            Config.lastPid = CaptureManager.PID_SYSTEM;
                            Config.lastName = "整个系统（不含本游戏）";
                            Config.save();
                            CaptureManager.startSystem();
                            return 1;
                        }))
                        .then(ClientCommands.literal("device")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            Config.device = name;
                                            Config.save();
                                            CaptureManager.startDevice(name);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            CaptureManager.stop();
                            say(text("已停止分享", ChatFormatting.GRAY));
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(ctx -> {
                            status(ctx.getSource());
                            return 1;
                        }))
                        .then(ClientCommands.literal("vol")
                                .then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 300))
                                        .executes(ctx -> {
                                            int p = IntegerArgumentType.getInteger(ctx, "percent");
                                            Config.volume = p / 100.0;
                                            Config.save();
                                            say(text("音量 " + p + "%", ChatFormatting.GREEN));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("buf")
                                .then(ClientCommands.argument("frames", IntegerArgumentType.integer(1, 50))
                                        .executes(ctx -> {
                                            int f = IntegerArgumentType.getInteger(ctx, "frames");
                                            Config.bufferFrames = f;
                                            Config.save();
                                            say(text("缓冲 " + f + " 帧（" + (f * 20) + " ms），下次开启生效",
                                                    ChatFormatting.GREEN));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("help").executes(ctx -> {
                            help(ctx.getSource());
                            return 1;
                        }))));

        if (Config.autoStart && CaptureManager.hasLastTarget()) {
            CaptureManager.startLast();
        }

        LOG.info("[sight-audio] 初始化完成：F9 面板 / F10 开关，上次目标 {}（PID {}）",
                Config.lastName, Config.lastPid);
    }

    // ------------------------------------------------------------------ 热键

    private void onTick(Minecraft client) {
        // 聊天框 / 面板 / 任何界面打开时一律不响应热键，免得打字时误触
        Window window = client.getWindow();
        if (client.gui.screen() != null || window == null || !window.isFocused()) {
            openKeyWasDown = false;
            toggleKeyWasDown = false;
            return;
        }

        boolean openDown = InputConstants.isKeyDown(window, OPEN_KEY);
        if (openDown && !openKeyWasDown) {
            openScreen();
        }
        openKeyWasDown = openDown;

        boolean toggleDown = InputConstants.isKeyDown(window, TOGGLE_KEY);
        if (toggleDown && !toggleKeyWasDown) {
            if (CaptureManager.isActive()) {
                CaptureManager.stop();
                toast("§7[sight-audio] 已停止分享");
            } else if (CaptureManager.hasLastTarget()) {
                CaptureManager.startLast();
            } else {
                openScreen();   // 还没选过，直接开面板
            }
        }
        toggleKeyWasDown = toggleDown;
    }

    private static void openScreen() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.setScreen(new SightAudioScreen()));
    }

    // ------------------------------------------------------------------ 命令

    private static void help(FabricClientCommandSource ignored) {
        say(text("【Sight Audio】把电脑上某个软件的声音分享给附近的人", ChatFormatting.GOLD));
        say(text("F9 打开面板 · F10 快速开关 · /sa 也是打开面板", ChatFormatting.GRAY));
        say(text("/sa list 聊天栏列表 · pick <编号> · system 整个系统 · off 停止", ChatFormatting.DARK_GRAY));
        say(text("/sa vol <0-300> 音量 · buf <1-50> 缓冲 · device <名字> 用虚拟声卡", ChatFormatting.DARK_GRAY));
    }

    private static void status(FabricClientCommandSource ignored) {
        say(text("状态：" + (CaptureManager.isActive() ? "开启" : "关闭"),
                CaptureManager.isActive() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        say(text("  来源：" + CaptureManager.describe(), ChatFormatting.GRAY));
        say(text("  缓冲：" + CaptureManager.buffered() + " / " + Config.bufferFrames
                + " 帧，音量 " + Math.round(Config.volume * 100) + "%", ChatFormatting.GRAY));
        String err = CaptureManager.lastError();
        if (err != null && !err.isEmpty()) {
            say(text("  上次错误：" + err, ChatFormatting.RED));
        }
    }

    /** 枚举是 COM 调用（几十毫秒），放后台线程，结果回主线程打印。 */
    private static void runList() {
        if (scanning) {
            return;
        }
        scanning = true;
        Thread t = new Thread(() -> {
            List<AudioSession> found = new ArrayList<>();
            String error = null;
            try {
                found.addAll(SessionLister.list());
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String finalError = error;
            Minecraft.getInstance().execute(() -> {
                scanning = false;
                if (finalError != null) {
                    say(text("扫描失败：" + finalError, ChatFormatting.RED));
                    return;
                }
                lastList = List.copyOf(found);
                showList(found);
            });
        }, "sight-audio-list");
        t.setDaemon(true);
        t.start();
    }

    private static void showList(List<AudioSession> found) {
        if (found.isEmpty()) {
            say(text("没找到正在出声的软件。先随便放点声音（哪怕点一下播放键）再按 F9", ChatFormatting.YELLOW));
            return;
        }
        say(text("点下面任意一行，就把那个软件的声音分享出去：", ChatFormatting.GOLD));
        for (int i = 0; i < found.size(); i++) {
            AudioSession s = found.get(i);
            String tag = s.active() ? "  ● 正在出声" : "  ○ 空闲";
            MutableComponent line = Component.literal("  " + (i + 1) + ". " + s.name() + tag);
            line.withStyle(Style.EMPTY
                    .withColor(s.active() ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY)
                    .withClickEvent(new ClickEvent.RunCommand("/sa pick " + (i + 1))));
            say(line);
        }
        say(text("（程序刚打开还没出声的话，让它先放一下再来）", ChatFormatting.DARK_GRAY));
    }

    private static void pick(int index) {
        List<AudioSession> list = lastList;
        if (list.isEmpty()) {
            say(text("还没有候选，先输入 /sa list", ChatFormatting.RED));
            return;
        }
        if (index < 1 || index > list.size()) {
            say(text("编号超出范围（1~" + list.size() + "）", ChatFormatting.RED));
            return;
        }
        AudioSession s = list.get(index - 1);
        if (!alive(s.pid())) {
            say(text("「" + s.name() + "」已经不在了，重新按 F9 刷新一下", ChatFormatting.RED));
            return;
        }
        Config.lastPid = s.pid();
        Config.lastName = s.name();
        Config.save();
        CaptureManager.startProcess(s.pid(), s.name());
    }

    // ------------------------------------------------------------------ 输出

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static MutableComponent text(String s, ChatFormatting color) {
        return Component.literal(s).withStyle(color);
    }

    /** 写进聊天栏（带点击事件，必须走 ChatComponent；走动作栏就点不了了）。 */
    private static void say(Component c) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.gui != null && client.gui.hud != null) {
                client.gui.hud.getChat().addClientSystemMessage(c);
            }
        });
    }

    /** 简短提示走动作栏，不刷屏。 */
    private static void toast(String s) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.literal(s));
            }
        });
    }
}
