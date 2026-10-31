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

    // 调色（ARGB）—— 深色磨砂玻璃（参考 DangoMusic 的 liquid-glass 风格）
    //
    // 🔴 四条原则，改之前先读：
    //   ① 面板是<b>深色中性</b>（近黑，带一点点冷调），不是彩色玻璃 —— 彩色大面积铺开
    //      会显得廉价，而且和玩家背后的世界画面打架。
    //   ② 🔴 <b>深色底上叠白 alpha 会立刻变成灰白块</b>：0x33FFFFFF 在近黑面板上
    //      看起来就是「一整条白条」，不是「淡淡的选中」。所以亮色一律压在 0x0D~0x22，
    //      层次靠「比周围亮一点点」堆，不靠「白」。
    //   ③ 边框极淡（0x1A 左右）。玻璃的边靠"细",不靠"亮"。
    //   ④ 品牌青（C_TITLE）只出现在标题、滑块、进行中状态这类少量强调上，绝不整圈描边。
    // ⚠️ 磨砂程度跟随玩家的「菜单背景模糊度」设置；拖到 0 时退化成半透明，不会崩。
    private static final int C_TINT = 0x1406090D;        // 全屏薄纱（很淡的冷色）
    private static final int C_PANEL_TOP = 0x8F252A31;   // 玻璃底：上（受光侧稍亮）
    private static final int C_PANEL_BOT = 0x85181C22;   // 玻璃底：下
    private static final int C_OUTLINE = 0x1AFFFFFF;     // 外框：极淡白
    private static final int C_BORDER_HI = 0x3AFFFFFF;   // 顶部高光
    private static final int C_SHADOW_1 = 0x3D000000;    // 外阴影（近）
    private static final int C_SHADOW_2 = 0x24000000;    // 外阴影（中）
    private static final int C_SHADOW_3 = 0x12000000;    // 外阴影（远）
    private static final int C_BORDER = 0x1AFFFFFF;      // 通用描边（淡白）
    private static final int C_LIST_BG = 0x1C000000;     // 列表凹槽（只比面板暗一点）
    private static final int C_TRACK = 0x33000000;       // 滑块轨道
    private static final int C_TITLE = 0xFF7DF9FF;       // 品牌青 —— 只给标题
    private static final int C_TEXT = 0xFFF0F0F2;
    private static final int C_MUTED = 0xFF9A9AA2;
    private static final int C_ROW_HOVER = 0x12FFFFFF;
    private static final int C_ROW_SEL = 0x1FFFFFFF;     // 选中：淡白，不是白条
    private static final int C_LIVE = 0xFF66DD88;
    private static final int C_WARN = 0xFFFF6B6B;
    private static final int C_BTN = 0x14FFFFFF;
    private static final int C_BTN_HOVER = 0x22FFFFFF;
    private static final int C_BTN_OFF = 0x0D000000;

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
        // 🔴 磨砂的关键一步：先把背后的世界模糊掉。
        //    原版 Screen#extractBackground 只在「主菜单」调它 —— 反编译字节码看得很清楚：
        //      isInGameUi() == true ⇒ 只画 extractTransparentBackground，不模糊；
        //      否则才 panorama + blurred + menuBackground。
        //    也就是说：游戏内打开界面时，原版默认<b>不给</b>模糊，必须自己调。
        //    它跟随玩家的「菜单背景模糊度」（Options.getMenuBackgroundBlurriness），
        //    拖到 0 时这里是无操作，退化成半透明 —— 可接受，不会崩。
        extractBlurredBackground(g);

        // 压暗：原版那套从深到浅的渐变，保证任何亮背景上文字都读得清
        extractTransparentBackground(g);

        // 主题冷色薄纱：整屏偏青，和面板同调（很淡，只是染色）
        g.fill(0, 0, width, height, C_TINT);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        glassPanel(g, x0, y0, x0 + PANEL_W, y0 + PANEL_H);

        g.text(font, "Sight Audio · 分享我电脑上的声音", x0 + 10, y0 + 8, C_TITLE);
        g.text(font, "单击选中，双击直接开始 · 再按 F9 关闭", x0 + 10, y0 + 21, C_MUTED);

        drawList(g, mouseX, mouseY);
        drawVolume(g, mouseX, mouseY);
        drawButtons(g, mouseX, mouseY);
        drawStatus(g);
    }

    /**
     * 磨砂玻璃卡片：外阴影 + 垂直渐变底 + 顶部 1px 高光 + 半透明描边。
     *
     * <p>层次是「阴影 → 玻璃 → 高光 → 边」四步叠加。阴影用三层递减 alpha 冒充模糊半径 ——
     * Minecraft 的 {@code fill} 只能画实心矩形，没有真正的模糊，靠叠层骗眼睛即可。</p>
     */
    /**
     * 面板圆角半径。
     *
     * <p>DangoMusic 那种观感的关键就是这个数<b>别太小</b>：8 以下看着像"没做圆角"，
     * 16 以上在 250px 高的面板上会吃掉太多内容区。12 是这块尺寸的甜点。</p>
     */
    private static final int PANEL_RADIUS = 12;

    /**
     * 磨砂玻璃卡片：外阴影 + 圆角 + 1px 亮边 + 垂直渐变 + 顶部高光。
     *
     * <p>层次是「阴影 → 亮边环 → 玻璃 → 高光」四步叠加。阴影和亮边都用 {@link #roundRect}
     * 画，因为直角阴影配圆角面板会露出四个尖角。</p>
     */
    private void glassPanel(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        final int r = PANEL_RADIUS;

        // ① 外阴影：三层递减，冒充"模糊半径" —— fill 画不出真模糊，只能叠层骗眼睛
        roundRect(g, x1 - 7, y1 - 5, x2 + 7, y2 + 9, r + 7, C_SHADOW_3, C_SHADOW_3);
        roundRect(g, x1 - 4, y1 - 3, x2 + 4, y2 + 5, r + 4, C_SHADOW_2, C_SHADOW_2);
        roundRect(g, x1 - 2, y1 - 1, x2 + 2, y2 + 2, r + 2, C_SHADOW_1, C_SHADOW_1);

        // ② 1px 亮边：整块铺一圈淡白，等会儿用内层玻璃把中间盖掉，只剩 1px 的环
        roundRect(g, x1, y1, x2, y2, r, C_OUTLINE, C_OUTLINE);

        // ③ 玻璃本体：内缩 1px，上亮下暗的垂直渐变（受光感）
        roundRect(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(0, r - 1), C_PANEL_TOP, C_PANEL_BOT);

        // ④ 顶部高光：只画中间平直那一段。🔴 别让它伸进圆角区，那里每行都在缩进，
        //    2px 高的条会画成阶梯状的锯齿。
        roundRect(g, x1 + r, y1 + 1, x2 - r, y1 + 2, 1, C_BORDER_HI, C_BORDER_HI);
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // 列表区：比面板再暗一点点的玻璃凹槽，圆角比面板小一档（同心圆角看着才顺）
        roundRect(g, listLeft - 2, listTop - 2, listRight + 2, listTop + LIST_H + 2, 8,
                C_LIST_BG, C_LIST_BG);

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
                roundRect(g, listLeft, ry, listRight, ry + ROW_H - 1, 5, C_ROW_SEL, C_ROW_SEL);
            } else if (idx == hovered) {
                roundRect(g, listLeft, ry, listRight, ry + ROW_H - 1, 5, C_ROW_HOVER, C_ROW_HOVER);
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
            g.fill(bx, listTop + 2, bx + 2, listTop + 2 + trackH, C_TRACK);
            g.fill(bx, barY, bx + 2, barY + barH, C_BORDER);
        }
    }

    private void drawVolume(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, "音量", x0 + 10, sliderY - 1, C_MUTED);
        final int tr = SLIDER_H / 2;          // 胶囊形：半径 = 高度的一半
        // 轨道：半透明凹槽
        roundRect(g, sliderX, sliderY, sliderX + SLIDER_W, sliderY + SLIDER_H, tr, C_TRACK, C_TRACK);
        int fillW = (int) Math.round(SLIDER_W * Math.min(1.0, Config.volume / 3.0));
        // 填充：亮青渐变，和玻璃底融在一起
        if (fillW > 0) {
            roundRect(g, sliderX, sliderY, sliderX + fillW, sliderY + SLIDER_H, tr,
                    0xD06FDDEC, 0xB048B8C8);
        }
        int handleX = sliderX + fillW;
        boolean over = mouseX >= sliderX - 2 && mouseX <= sliderX + SLIDER_W + 2
                && mouseY >= sliderY - 5 && mouseY <= sliderY + SLIDER_H + 5;
        // 滑块：外圈半透明光晕 + 实心白芯（hover 时更大更亮）
        if (over || draggingSlider) {
            roundRect(g, handleX - 5, sliderY - 4, handleX + 5, sliderY + SLIDER_H + 4, 6,
                    0x33FFFFFF, 0x33FFFFFF);
        }
        roundRect(g, handleX - 3, sliderY - 3, handleX + 3, sliderY + SLIDER_H + 3, 4,
                over || draggingSlider ? 0xFFFFFFFF : 0xFFE8FBFF,
                over || draggingSlider ? 0xFFFFFFFF : 0xFFE8FBFF);
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
        final int r = 6;
        int bg = !enabled ? C_BTN_OFF : (hover ? C_BTN_HOVER : C_BTN);

        // 🔴 不画整圈边框。之前用「铺满边框色 + 内层盖住」的做法，但内层是半透明的，
        //    根本盖不住 ⇒ 按钮整块变成 accent 那个高亮色（hover 时是一片亮青）。
        //    玻璃按钮靠「比底稍亮的填充 + 顶部一线高光」就够，加边框反而脏。
        roundRect(g, x, y, x + w, y + h, r, bg, bg);

        if (enabled) {
            int hi = hover ? 0x40FFFFFF : 0x1FFFFFFF;
            roundRect(g, x + r, y + 1, x + w - r, y + 2, 1, hi, hi);
        }

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
     * 圆角矩形（逐行扫描 + 垂直渐变）。
     *
     * <p>🔴 为什么要这么写：{@code GuiGraphicsExtractor} 只有 {@link #fill} /
     * {@code fillGradient} 两个<b>实心矩形</b>原语 —— javap 看过整张方法表，
     * 既没有 {@code blit}（画纹理）也没有任何圆角 API。所以圆角只能拿 1px 高的横条
     * 一行行堆：每行按圆的方程算出左右该缩进多少，颜色按行号在两端色之间插值。</p>
     *
     * <p>代价：一块 348×250 的面板 = 250 次 fill。GUI 一帧几百个 draw 命令是正常量级，
     * 但<b>别在每帧重建的超长列表里用</b>——那个数量级会真的掉帧。</p>
     *
     * @param radius 圆角半径（像素），会被裁到宽高的一半以内
     */
    private static void roundRect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2,
                                  int radius, int colorTop, int colorBottom) {
        int h = y2 - y1;
        int w = x2 - x1;
        if (h <= 0 || w <= 0) {
            return;
        }
        // 🔴 用 (h-1)/2、(w-1)/2 而不是 h/2、w/2：半径等于半宽时，第一行的缩进正好等于半宽，
        //    算出来宽度 0 —— 那一行等于白画，小控件（滑块手柄才 6px 宽）会看起来缺角。
        //    留 1px 余量，保证最窄的那行至少还有 1 像素。
        int r = Math.max(0, Math.min(radius, Math.min((h - 1) / 2, (w - 1) / 2)));
        for (int i = 0; i < h; i++) {
            int inset = 0;
            if (r > 0) {
                if (i < r) {
                    inset = r - roundSqrt(r * r - (r - i) * (r - i));
                } else if (i >= h - r) {
                    int k = i - (h - r) + 1;              // 1..r
                    inset = r - roundSqrt(Math.max(0, r * r - k * k));
                }
            }
            int color = lerpArgb(colorTop, colorBottom, h == 1 ? 0f : (float) i / (h - 1));
            int y = y1 + i;
            g.fill(x1 + inset, y, x2 - inset, y + 1, color);
        }
    }

    private static int roundSqrt(int v) {
        return (int) Math.round(Math.sqrt(Math.max(0, v)));
    }

    /** ARGB 线性插值（alpha 也一起插，否则半透明渐变的边缘会发灰）。 */
    private static int lerpArgb(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int oa = Math.round(aa + (ba - aa) * t);
        int or = Math.round(ar + (br - ar) * t);
        int og = Math.round(ag + (bg - ag) * t);
        int ob = Math.round(ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
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
