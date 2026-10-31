package com.sight.audio;

import com.sight.audio.wasapi.AudioSession;
import com.sight.audio.wasapi.SessionLister;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 分享面板：一屏搞定「选谁 → 开始 → 调音量」。
 *
 * <h2>为什么全部自绘</h2>
 * 26.2 的界面改成了两阶段管线：{@code extractBackground()} 画底，
 * {@code extractRenderState(GuiGraphicsExtractor,…)} 画内容，最后才轮到各个 widget。
 * 与其去适配 {@code Button}/{@code ObjectSelectionList} 在这版里的一堆变动
 * （{@code onClick(MouseButtonEvent, boolean)}、{@code extractWidgetRenderState}…），
 * 不如用最稳的两个原语自己画：{@code fill()} 和 {@code text()}。
 * 它们从 1.x 到现在语义没变过，26.3 升级也不至于整块失效。
 *
 * <h2>交互</h2>
 * 单击选中，双击立刻开始/停止；底下一排按钮；音量滑块可拖；滚轮翻列表。
 */
public final class SightAudioScreen extends Screen {

    // ---------------------------------------------------------------- 版式常量

    private static final int PANEL_W = 348;
    private static final int PANEL_H = 250;
    private static final int ROW_H = 18;
    private static final int LIST_H = 120;

    // 调色（ARGB）
    private static final int C_DIM = 0xB4000000;
    private static final int C_PANEL = 0xF00E1418;
    private static final int C_BORDER = 0xFF2E6B75;
    private static final int C_TITLE = 0xFF7DF9FF;
    private static final int C_TEXT = 0xFFE6E6E6;
    private static final int C_MUTED = 0xFF8A9099;
    private static final int C_ROW_HOVER = 0xFF1C2830;
    private static final int C_ROW_SEL = 0xFF17454F;
    private static final int C_LIVE = 0xFF66DD88;
    private static final int C_WARN = 0xFFFF6B6B;
    private static final int C_BTN = 0xFF1E2A31;
    private static final int C_BTN_HOVER = 0xFF2C4049;
    private static final int C_BTN_OFF = 0xFF161C20;

    // ---------------------------------------------------------------- 状态

    /**
     * 列表一行。
     *
     * <p>{@code label} 是**已经截断好**的显示名 —— 截断要用 {@code font.width()}，
     * 那个是逐字形测量，放在每帧的绘制里会明显吃 CPU，所以只在刷新数据时算一次。</p>
     */
    private record Row(String name, String label, int pid, boolean active, boolean system) {
    }

    private List<Row> rows = List.of();
    private String error = "";
    private boolean loading;
    private long lastRefreshAt;
    private int scroll;

    /** 当前选中项的 PID；null = 没选；{@link CaptureManager#PID_SYSTEM} = 整个系统。 */
    private Integer selectedPid;
    private boolean draggingSlider;

    // 版式（init 时算好，鼠标事件复用同一份）
    private int x0;
    private int y0;
    private int listLeft;
    private int listRight;
    private int listTop;
    private int sliderX;
    private int sliderY;
    private static final int SLIDER_W = 168;
    private static final int SLIDER_H = 8;
    private int btnY;
    private static final int BTN_H = 20;
    private int startX;
    private static final int START_W = 116;
    private int refreshX;
    private static final int REFRESH_W = 74;
    private int closeX;
    private static final int CLOSE_W = 74;

    public SightAudioScreen() {
        super(Component.literal("Sight Audio"));
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void init() {
        super.init();
        x0 = (width - PANEL_W) / 2;
        y0 = (height - PANEL_H) / 2;
        listLeft = x0 + 8;
        listRight = x0 + PANEL_W - 8;
        listTop = y0 + 34;
        sliderX = x0 + 48;
        sliderY = y0 + 166;
        btnY = y0 + 184;
        startX = x0 + 10;
        refreshX = startX + START_W + 8;
        closeX = x0 + PANEL_W - 10 - CLOSE_W;
        // 打开面板时沿用当前目标，其次用上次记住的
        if (selectedPid == null) {
            if (CaptureManager.isActive() && CaptureManager.mode() == CaptureManager.Mode.PROCESS) {
                selectedPid = CaptureManager.targetPid();
            } else if (CaptureManager.hasLastTarget()) {
                selectedPid = Config.lastPid;
            }
        }
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        // 程序列表会变（开个播放器、关掉一个），3 秒刷一次，别太频繁
        if (!loading && System.currentTimeMillis() - lastRefreshAt > 3000) {
            refresh();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;   // 边听边看，不要把游戏停了
    }

    // ---------------------------------------------------------------- 数据

    private void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        lastRefreshAt = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            List<AudioSession> found = new ArrayList<>();
            String err = "";
            try {
                found.addAll(SessionLister.list());
            } catch (Throwable e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            List<AudioSession> data = List.copyOf(found);
            String finalErr = err;
            var mc = minecraft;
            if (mc == null) {
                loading = false;
                return;
            }
            mc.execute(() -> {
                if (mc.gui.screen() != this) {
                    loading = false;
                    return;
                }
                error = finalErr;
                applyData(data);
                loading = false;
            });
        }, "sight-audio-screen-list");
        t.setDaemon(true);
        t.start();
    }

    private void applyData(List<AudioSession> found) {
        List<Row> next = new ArrayList<>();
        String sysName = "整个系统（不含本游戏）";
        next.add(new Row(sysName, ellipsize(sysName, 210),
                CaptureManager.PID_SYSTEM, true, true));
        for (AudioSession s : found) {
            next.add(new Row(s.name(), ellipsize(s.name(), 210), s.pid(), s.active(), false));
        }
        rows = List.copyOf(next);
        clampScroll();
        // 选中的东西可能已经不在了
        if (selectedPid != null && indexOfPid(selectedPid) < 0) {
            selectedPid = null;
        }
    }

    private int indexOfPid(int pid) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).pid() == pid) {
                return i;
            }
        }
        return -1;
    }

    private Row selectedRow() {
        if (selectedPid == null) {
            return null;
        }
        int i = indexOfPid(selectedPid);
        return i < 0 ? null : rows.get(i);
    }

    private int visibleRows() {
        return LIST_H / ROW_H;
    }

    private void clampScroll() {
        int max = Math.max(0, rows.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll, max));
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, C_DIM);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // 面板底
        g.fill(x0, y0, x0 + PANEL_W, y0 + PANEL_H, C_PANEL);
        border(g, x0, y0, x0 + PANEL_W, y0 + PANEL_H, C_BORDER);

        g.text(font, "Sight Audio · 分享我电脑上的声音", x0 + 10, y0 + 8, C_TITLE);
        g.text(font, "单击选中，双击直接开始 · 再按 F9 关闭", x0 + 10, y0 + 21, C_MUTED);

        drawList(g, mouseX, mouseY);
        drawVolume(g, mouseX, mouseY);
        drawButtons(g, mouseX, mouseY);
        drawStatus(g);
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(listLeft - 1, listTop - 1, listRight + 1, listTop + LIST_H + 1, 0xFF0A0E11);

        if (error != null && !error.isEmpty()) {
            g.text(font, "扫描失败：" + ellipsize(error, PANEL_W - 30), listLeft + 6, listTop + 8, C_WARN);
            return;
        }
        if (rows.isEmpty()) {
            g.text(font, loading ? "正在扫描…" : "没有找到正在出声的程序",
                    listLeft + 6, listTop + 8, C_MUTED);
            return;
        }

        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < visibleRows(); i++) {
            int idx = scroll + i;
            if (idx >= rows.size()) {
                break;
            }
            Row r = rows.get(idx);
            int ry = listTop + i * ROW_H;
            boolean sel = selectedPid != null && selectedPid == r.pid();
            boolean sharing = CaptureManager.isActive()
                    && CaptureManager.mode() == CaptureManager.Mode.PROCESS
                    && CaptureManager.targetPid() == r.pid();

            if (sel) {
                g.fill(listLeft, ry, listRight, ry + ROW_H - 1, C_ROW_SEL);
                border(g, listLeft, ry, listRight, ry + ROW_H - 1, C_BORDER);
            } else if (idx == hovered) {
                g.fill(listLeft, ry, listRight, ry + ROW_H - 1, C_ROW_HOVER);
            }

            int nameColor = r.system() ? C_TITLE : (r.active() ? C_TEXT : C_MUTED);
            String prefix = sharing ? "▶ " : (r.system() ? "◆ " : "   ");
            g.text(font, prefix + r.label(), listLeft + 4, ry + 5, nameColor);

            if (sharing) {
                g.text(font, "分享中", listRight - 34, ry + 5, C_LIVE);
            } else if (r.system()) {
                g.text(font, "全部", listRight - 24, ry + 5, C_MUTED);
            } else if (r.active()) {
                g.text(font, "正在出声", listRight - 46, ry + 5, C_LIVE);
            } else {
                g.text(font, "空闲", listRight - 28, ry + 5, C_MUTED);
            }
        }

        // 滚动条
        int total = rows.size();
        if (total > visibleRows()) {
            int trackH = LIST_H - 4;
            int barH = Math.max(16, trackH * visibleRows() / total);
            int barY = listTop + 2 + (trackH - barH) * scroll / Math.max(1, total - visibleRows());
            int bx = listRight - 3;
            g.fill(bx, listTop + 2, bx + 2, listTop + 2 + trackH, 0xFF1A2228);
            g.fill(bx, barY, bx + 2, barY + barH, C_BORDER);
        }
    }

    private void drawVolume(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, "音量", x0 + 10, sliderY - 1, C_MUTED);
        g.fill(sliderX, sliderY, sliderX + SLIDER_W, sliderY + SLIDER_H, 0xFF1A2228);
        int fillW = (int) Math.round(SLIDER_W * Math.min(1.0, Config.volume / 3.0));
        g.fill(sliderX, sliderY, sliderX + fillW, sliderY + SLIDER_H, C_TITLE);
        int handleX = sliderX + fillW;
        boolean over = mouseX >= sliderX - 2 && mouseX <= sliderX + SLIDER_W + 2
                && mouseY >= sliderY - 5 && mouseY <= sliderY + SLIDER_H + 5;
        g.fill(handleX - 2, sliderY - 2, handleX + 2, sliderY + SLIDER_H + 2,
                over || draggingSlider ? 0xFFFFFFFF : 0xFFCFE9EF);
        g.text(font, Math.round(Config.volume * 100) + "%",
                sliderX + SLIDER_W + 8, sliderY - 1, C_TEXT);
    }

    private void drawButtons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean active = CaptureManager.isActive();
        drawButton(g, startX, btnY, START_W, BTN_H, active ? "停止分享" : "开始分享",
                inRect(mouseX, mouseY, startX, btnY, START_W, BTN_H), true,
                active ? C_WARN : C_LIVE);

        drawButton(g, refreshX, btnY, REFRESH_W, BTN_H, "刷新",
                inRect(mouseX, mouseY, refreshX, btnY, REFRESH_W, BTN_H), true, C_TITLE);

        drawButton(g, closeX, btnY, CLOSE_W, BTN_H, "关闭",
                inRect(mouseX, mouseY, closeX, btnY, CLOSE_W, BTN_H), true, C_MUTED);
    }

    private void drawButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label,
                            boolean hover, boolean enabled, int accent) {
        int bg = !enabled ? C_BTN_OFF : (hover ? C_BTN_HOVER : C_BTN);
        g.fill(x, y, x + w, y + h, bg);
        border(g, x, y, x + w, y + h, enabled ? (hover ? accent : C_BORDER) : 0xFF2A2F33);
        int color = enabled ? (hover ? 0xFFFFFFFF : accent) : 0xFF5A6068;
        g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, color);
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        int y = btnY + BTN_H + 8;
        boolean active = CaptureManager.isActive();
        String line1 = active ? "● " + CaptureManager.describe() : "○ 未开启分享";
        g.text(font, ellipsize(line1, PANEL_W - 20), x0 + 10, y, active ? C_LIVE : C_MUTED);

        String err = CaptureManager.lastError();
        String line2;
        int color = C_MUTED;
        if (active && !VoicechatHook.isConnected()) {
            // 最常见的一种「看起来在分享但别人听不到」，必须明说
            line2 = "语音还没连上 —— 现在别人听不到，等它连上就自动生效";
            color = C_WARN;
        } else if (err != null && !err.isEmpty()) {
            line2 = "上次错误：" + err;
            color = C_WARN;
        } else if (active) {
            line2 = "缓冲 " + CaptureManager.buffered() + "/" + Config.bufferFrames
                    + " 帧 · F9 关面板 · F10 快速开关 · 走路会自然衰减";
        } else {
            line2 = "选一个程序再点「开始分享」，也可以直接双击那一行";
        }
        g.text(font, ellipsize(line2, PANEL_W - 20), x0 + 10, y + 11, color);
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        double mx = event.x();
        double my = event.y();

        if (inRect(mx, my, startX, btnY, START_W, BTN_H)) {
            toggleShare();
            return true;
        }
        if (inRect(mx, my, refreshX, btnY, REFRESH_W, BTN_H)) {
            refresh();
            return true;
        }
        if (inRect(mx, my, closeX, btnY, CLOSE_W, BTN_H)) {
            onClose();
            return true;
        }
        if (inRect(mx, my, sliderX - 3, sliderY - 6, SLIDER_W + 6, SLIDER_H + 12)) {
            draggingSlider = true;
            setVolumeFromMouse(mx);
            return true;
        }
        int idx = rowAt(mx, my);
        if (idx >= 0) {
            selectedPid = rows.get(idx).pid();
            if (doubleClick) {
                toggleShare();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSlider) {
            setVolumeFromMouse(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingSlider) {
            draggingSlider = false;
            Config.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inRect(mouseX, mouseY, listLeft, listTop, listRight - listLeft, LIST_H)) {
            scroll -= (int) Math.signum(scrollY);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_F9) {
            // 面板开着时热键轮询是关掉的，所以 F9 关闭要在这里接
            onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_F10) {
            toggleShare();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            toggleShare();
            return true;
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- 动作

    private void toggleShare() {
        if (CaptureManager.isActive()) {
            CaptureManager.stop();
            return;
        }
        Row r = selectedRow();
        if (r == null) {
            return;
        }
        if (r.system()) {
            Config.lastPid = CaptureManager.PID_SYSTEM;
            Config.lastName = r.name();
            Config.save();
            CaptureManager.startSystem();
        } else {
            Config.lastPid = r.pid();
            Config.lastName = r.name();
            Config.save();
            CaptureManager.startProcess(r.pid(), r.name());
        }
    }

    private void setVolumeFromMouse(double mouseX) {
        double t = (mouseX - sliderX) / (double) SLIDER_W;
        t = Math.max(0.0, Math.min(1.0, t));
        // 0~300% 映射到滑块全长
        Config.volume = Math.round(t * 300) / 100.0;
    }

    // ---------------------------------------------------------------- 工具

    private int rowAt(double mouseX, double mouseY) {
        if (!inRect(mouseX, mouseY, listLeft, listTop, listRight - listLeft, LIST_H)) {
            return -1;
        }
        int i = (int) ((mouseY - listTop) / ROW_H);
        int idx = scroll + i;
        return idx >= 0 && idx < rows.size() ? idx : -1;
    }

    /**
     * 画 1 像素边框。
     *
     * <p>不用 {@code GuiGraphicsExtractor.outline()}：那个方法在不同版本里参数含义
     * （是 x,y,w,h 还是 x1,y1,x2,y2）并不稳定，画错的代价是整个面板错位。
     * 四次 {@code fill} 语义永远没有歧义。</p>
     */
    private static void border(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    private static boolean inRect(double px, double py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private String ellipsize(String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (font.width(s) <= maxWidth) {
            return s;
        }
        String dots = "…";
        int budget = maxWidth - font.width(dots);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (font.width(sb.toString() + s.charAt(i)) > budget) {
                break;
            }
            sb.append(s.charAt(i));
        }
        return sb + dots;
    }
}
