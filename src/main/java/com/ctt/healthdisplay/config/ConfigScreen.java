package com.ctt.healthdisplay.config;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class ConfigScreen extends Screen {

    private final Screen parent;

    private int mateX, mateY;
    private boolean horizontal;
    private boolean draggingMate = false;
    private double mateDragOX, mateDragOY;

    private int statsX, statsY;
    private boolean draggingStats = false;
    private double statsDragOX, statsDragOY;

    private int refreshInterval;
    private boolean showHeadHP;
    private int mobHPMode;
    private int mobBarHalfWidth;
    private int teammateBarHalfWidth;
    private int statsColumns;
    private int statsVisibility;
    private boolean hidePersonalBar;
    private boolean hideTeamBar;
    private boolean hideMobBars;

    // v7.0.0 · 客户端探针 (ClientDamageProbe) 开关
    private boolean clientDamageHudHeader;
    private boolean clientDamageDebugChat;
    // v7.1.2 · 队友血条下方"每人伤害/击杀"双行嵌入 HUD（OFF/ONLY_STAGE/ONLY_SESSION/BOTH）
    private int embeddedHudMode;
    // v7.1.0 · 客户端击杀报告聊天（计数本身始终开启，不暴露开关）
    private boolean clientKillDebugChat;

    private static final int[] INTERVAL_OPTIONS = {1, 2, 3, 5, 10, 15, 30};
    private static final int[] BAR_WIDTH_OPTIONS = {30, 40, 52, 60, 70, 80};
    private static final int[] MATE_BAR_WIDTH_OPTIONS = {15, 20, 25, 30, 40, 50};

    private static final int HEAD_SIZE = 8;
    private static final int MATE_BAR_WIDTH = 60;
    private static final int MATE_BAR_HEIGHT = 3;
    private static final int V_ENTRY_HEIGHT = 16;
    private static final int H_ENTRY_WIDTH = 80;

    private static final int STATS_LINE_HEIGHT = 10;
    private static final String[] SAMPLE_STATS = {
            "\u5c5e\u6027 / Stats",
            "113\u2764", "-1\ud83d\udee1", "3\u2605",
            "10\u2764%", "2\ud83d\udde1", "15\u26a1"
    };

    private static final String[] SAMPLE_NAMES = {"Steve", "Alex", "Notch"};
    private static final int[] SAMPLE_HP = {75, 120, 30};
    private static final int[] SAMPLE_MAX = {100, 200, 100};

    private static final int OPTION_BTN_W = 200;
    private static final int OPTION_BTN_H = 20;
    private static final int OPTION_SPACING = 24;

    private int optionPanelX;
    private int optionPanelY;
    private int optionScrollOffset = 0;
    private int optionContentHeight = 0;

    public ConfigScreen(Screen parent) {
        super(Text.translatable("ctt-health-display.config.title"));
        this.parent = parent;
    }

    private boolean firstInit = true;

    @Override
    protected void init() {
        ModConfig cfg = ModConfig.INSTANCE;
        if (firstInit) {
            mateX = (int) (cfg.teammateX * width);
            mateY = (int) (cfg.teammateY * height);
            horizontal = cfg.horizontalLayout;
            statsX = (int) (cfg.statsX * width);
            statsY = (int) (cfg.statsY * height);
            refreshInterval = cfg.autoRefreshIntervalSeconds;
            showHeadHP = cfg.showTeammateHeadHP;
            mobHPMode = cfg.mobHeadHPMode;
            mobBarHalfWidth = cfg.mobBarHalfWidth;
            teammateBarHalfWidth = cfg.teammateBarHalfWidth;
            statsColumns = cfg.statsColumns;
            statsVisibility = cfg.statsVisibility;
            hidePersonalBar = cfg.hidePersonalBar;
            hideTeamBar = cfg.hideTeamBar;
            hideMobBars = cfg.hideMobBars;
            // v7.0.0 · 客户端探针开关（ClientDamageProbe）
            clientDamageHudHeader = cfg.clientDamageHudHeader;
            clientDamageDebugChat = cfg.clientDamageDebugChat;
            // v7.1.2 · 嵌入式 HUD（每人伤害击杀双行）4 段开关
            embeddedHudMode = cfg.embeddedHudMode;
            // v7.1.0 · 客户端击杀报告聊天（计数本身始终运行，无需开关）
            clientKillDebugChat = cfg.clientKillDebugChat;
            // v6.6.4 · M5 · 服务端字段（broadcastXxxInChat / useRedHeartsTally /
            // filterXxx 等）从此版本起搬到二级子屏 {@link ServerConfigScreen}。
            // 主屏只保留客户端 HUD 偏好，避免把"客户端能改的"和"客户端改不到"混排。
            firstInit = false;
        }

        optionPanelX = width / 2 + 10;
        optionPanelY = 36;

        int x = optionPanelX;
        int y = optionPanelY - optionScrollOffset;
        int btnW = Math.min(OPTION_BTN_W, width / 2 - 20);

        addDrawableChild(ButtonWidget.builder(headHPBtnText(), btn -> {
            showHeadHP = !showHeadHP;
            btn.setMessage(headHPBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(mobHPBtnText(), btn -> {
            mobHPMode = (mobHPMode + 1) % 3;
            btn.setMessage(mobHPBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(layoutBtnText(), btn -> {
            horizontal = !horizontal;
            btn.setMessage(layoutBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(refreshBtnText(), btn -> {
            int idx = 0;
            for (int i = 0; i < INTERVAL_OPTIONS.length; i++) {
                if (INTERVAL_OPTIONS[i] == refreshInterval) { idx = i; break; }
            }
            refreshInterval = INTERVAL_OPTIONS[(idx + 1) % INTERVAL_OPTIONS.length];
            btn.setMessage(refreshBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(mobBarWidthBtnText(), btn -> {
            int idx = 0;
            for (int i = 0; i < BAR_WIDTH_OPTIONS.length; i++) {
                if (BAR_WIDTH_OPTIONS[i] == mobBarHalfWidth) { idx = i; break; }
            }
            mobBarHalfWidth = BAR_WIDTH_OPTIONS[(idx + 1) % BAR_WIDTH_OPTIONS.length];
            btn.setMessage(mobBarWidthBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(mateBarWidthBtnText(), btn -> {
            int idx = 0;
            for (int i = 0; i < MATE_BAR_WIDTH_OPTIONS.length; i++) {
                if (MATE_BAR_WIDTH_OPTIONS[i] == teammateBarHalfWidth) { idx = i; break; }
            }
            teammateBarHalfWidth = MATE_BAR_WIDTH_OPTIONS[(idx + 1) % MATE_BAR_WIDTH_OPTIONS.length];
            btn.setMessage(mateBarWidthBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(statsColumnsBtnText(), btn -> {
            statsColumns = statsColumns == 1 ? 2 : 1;
            btn.setMessage(statsColumnsBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(statsVisBtnText(), btn -> {
            statsVisibility = (statsVisibility + 1) % 3;
            btn.setMessage(statsVisBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING + 6;

        addDrawableChild(ButtonWidget.builder(hidePersonalBtnText(), btn -> {
            hidePersonalBar = !hidePersonalBar;
            btn.setMessage(hidePersonalBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(hideTeamBtnText(), btn -> {
            hideTeamBar = !hideTeamBar;
            btn.setMessage(hideTeamBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(hideMobBarsBtnText(), btn -> {
            hideMobBars = !hideMobBars;
            btn.setMessage(hideMobBarsBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING + 6;

        // v7.0.0 · 客户端伤害探针 (ClientDamageProbe) ——
        //   - HUD 顶部聚合行：⚔ 全局 · ⚔ 当前关 ⚡ 5sDPS/s
        //   - 聊天栏粒子流水：每个 DamageShower 粒子在本地聊天打 [CDP] 日志（调试用，会刷屏）
        // 默认 hud=on / debugChat=off。
        addDrawableChild(ButtonWidget.builder(clientDamageHudHeaderBtnText(), btn -> {
            clientDamageHudHeader = !clientDamageHudHeader;
            btn.setMessage(clientDamageHudHeaderBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        // v7.1.2 · 每人伤害击杀双行（嵌入式 HUD）4 段循环按钮：
        //   关闭 → 仅本关 → 仅本局 → 全部显示 → 关闭 ...
        // 默认 BOTH（全部显示）；设计语义见 ModConfig.embeddedHudMode 注释。
        addDrawableChild(ButtonWidget.builder(embeddedHudModeBtnText(), btn -> {
            embeddedHudMode = (embeddedHudMode + 1) % 4;
            btn.setMessage(embeddedHudModeBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(clientDamageDebugChatBtnText(), btn -> {
            clientDamageDebugChat = !clientDamageDebugChat;
            btn.setMessage(clientDamageDebugChatBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        // v7.1.0 · 客户端击杀计数本身始终开启（无开关），仅暴露"是否打聊天报告"
        addDrawableChild(ButtonWidget.builder(clientKillDebugChatBtnText(), btn -> {
            clientKillDebugChat = !clientKillDebugChat;
            btn.setMessage(clientKillDebugChatBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING + 6;

        // v6.6.4 · M5 · 服务器配置二级子屏入口（broadcast / useRedHearts / filter 等都进了那边）。
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("ctt-health-display.config.btn.open_server_config"),
                btn -> {
                    // 同步保存当前主屏未提交的修改，避免打开子屏时丢失
                    saveMainOnly();
                    client.setScreen(new ServerConfigScreen(this));
                }
        ).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_BTN_H;

        optionContentHeight = y - (optionPanelY - optionScrollOffset);

        int bottomY = height - 28;
        int cx = width / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("ctt-health-display.config.btn.reset_position"), btn -> {
            mateX = (int) (0.00625f * width);
            mateY = (int) (0.26912183f * height);
            statsX = (int) (0.0046875f * width);
            statsY = (int) (0.014164306f * height);
        }).dimensions(cx - 102, bottomY, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("ctt-health-display.config.btn.done"), btn -> saveAndClose())
                .dimensions(cx + 2, bottomY, 100, 20).build());
    }

    private Text layoutBtnText() {
        return Text.translatable(
                "ctt-health-display.config.option.teammate_layout",
                Text.translatable(horizontal
                        ? "ctt-health-display.config.value.layout.horizontal"
                        : "ctt-health-display.config.value.layout.vertical")
        );
    }

    private Text refreshBtnText() {
        return Text.translatable("ctt-health-display.config.option.refresh_interval_seconds", refreshInterval);
    }

    private Text headHPBtnText() {
        return Text.translatable(
                "ctt-health-display.config.option.teammate_head_hp",
                Text.translatable(showHeadHP
                        ? "ctt-health-display.config.value.on"
                        : "ctt-health-display.config.value.off")
        );
    }

    private Text mobHPBtnText() {
        Text label = switch (mobHPMode) {
            case ModConfig.MOB_HP_MODE_ALL -> Text.translatable("ctt-health-display.config.value.mob_hp_mode.all");
            case ModConfig.MOB_HP_MODE_NEAREST -> Text.translatable("ctt-health-display.config.value.mob_hp_mode.nearest");
            default -> Text.translatable("ctt-health-display.config.value.mob_hp_mode.off");
        };
        return Text.translatable("ctt-health-display.config.option.mob_head_hp", label);
    }

    private Text mobBarWidthBtnText() {
        return Text.translatable("ctt-health-display.config.option.mob_bar_width", mobBarHalfWidth * 2);
    }

    private Text mateBarWidthBtnText() {
        return Text.translatable("ctt-health-display.config.option.teammate_bar_width", teammateBarHalfWidth * 2);
    }

    private Text statsColumnsBtnText() {
        return Text.translatable("ctt-health-display.config.option.stats_columns", statsColumns);
    }

    private Text statsVisBtnText() {
        Text label = switch (statsVisibility) {
            case 0 -> Text.translatable("ctt-health-display.config.value.stats_visibility.always");
            case 1 -> Text.translatable("ctt-health-display.config.value.stats_visibility.inventory");
            default -> Text.translatable("ctt-health-display.config.value.stats_visibility.hidden");
        };
        return Text.translatable("ctt-health-display.config.option.stats_visibility", label);
    }

    private Text hidePersonalBtnText() {
        return Text.translatable(
                "ctt-health-display.config.option.hide_personal_bossbar",
                Text.translatable(hidePersonalBar
                        ? "ctt-health-display.config.value.yes"
                        : "ctt-health-display.config.value.no")
        );
    }

    private Text hideTeamBtnText() {
        return Text.translatable(
                "ctt-health-display.config.option.hide_team_bossbar",
                Text.translatable(hideTeamBar
                        ? "ctt-health-display.config.value.yes"
                        : "ctt-health-display.config.value.no")
        );
    }

    private Text hideMobBarsBtnText() {
        return Text.translatable(
                "ctt-health-display.config.option.hide_mob_bossbars",
                Text.translatable(hideMobBars
                        ? "ctt-health-display.config.value.yes"
                        : "ctt-health-display.config.value.no")
        );
    }

    private Text clientDamageHudHeaderBtnText() {
        return Text.translatable(
                "ctt-health-display.config.option.client_damage_hud_header",
                Text.translatable(clientDamageHudHeader
                        ? "ctt-health-display.config.value.on"
                        : "ctt-health-display.config.value.off")
        );
    }

    private Text embeddedHudModeBtnText() {
        String stateKey = switch (embeddedHudMode) {
            case ModConfig.EMBED_OFF          -> "ctt-health-display.config.value.embedded_hud_mode.off";
            case ModConfig.EMBED_ONLY_STAGE   -> "ctt-health-display.config.value.embedded_hud_mode.only_stage";
            case ModConfig.EMBED_ONLY_SESSION -> "ctt-health-display.config.value.embedded_hud_mode.only_session";
            default                            -> "ctt-health-display.config.value.embedded_hud_mode.both";
        };
        return Text.translatable("ctt-health-display.config.option.embedded_hud_mode",
                Text.translatable(stateKey));
    }

    private Text clientDamageDebugChatBtnText() {
        return Text.translatable(
                "ctt-health-display.config.option.client_damage_debug_chat",
                Text.translatable(clientDamageDebugChat
                        ? "ctt-health-display.config.value.on"
                        : "ctt-health-display.config.value.off")
        );
    }

    private Text clientKillDebugChatBtnText() {
        return Text.translatable(
                "ctt-health-display.config.option.client_kill_debug_chat",
                Text.translatable(clientKillDebugChat
                        ? "ctt-health-display.config.value.on"
                        : "ctt-health-display.config.value.off")
        );
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, this.title, width / 2, 8, 0xFFFFFF);

        int dividerX = width / 2;
        context.fill(dividerX - 1, 24, dividerX, height - 36, 0x40FFFFFF);

        context.drawTextWithShadow(
                textRenderer,
                Text.translatable("ctt-health-display.config.hint_drag"),
                8, 26, 0xFF888888
        );

        context.drawTextWithShadow(
                textRenderer,
                Text.translatable("ctt-health-display.config.header_options"),
                optionPanelX, 26, 0xFF888888
        );

        renderMatePreview(context);
        renderStatsPreview(context);

        int visibleH = height - 36 - 36;
        int maxScroll = Math.max(0, optionContentHeight - visibleH);
        if (maxScroll > 0) {
            int scrollTrackX = width - 4;
            int scrollTrackTop = optionPanelY;
            int scrollTrackH = visibleH;
            context.fill(scrollTrackX, scrollTrackTop, scrollTrackX + 3, scrollTrackTop + scrollTrackH, 0x30FFFFFF);
            int thumbH = Math.max(10, scrollTrackH * visibleH / optionContentHeight);
            int thumbY = scrollTrackTop + (int) ((float) optionScrollOffset / maxScroll * (scrollTrackH - thumbH));
            context.fill(scrollTrackX, thumbY, scrollTrackX + 3, thumbY + thumbH, 0x80FFFFFF);
        }
    }

    private void renderMatePreview(DrawContext context) {
        int pw = getMatePanelW();
        int ph = getMatePanelH();
        drawPanelFrame(
                context, mateX, mateY, pw, ph, draggingMate,
                Text.translatable("ctt-health-display.config.preview.teammate_health").getString()
        );

        int x = mateX, y = mateY + 10;
        for (int i = 0; i < SAMPLE_NAMES.length; i++) {
            int pct = Math.round((float) SAMPLE_HP[i] * 100 / SAMPLE_MAX[i]);
            context.fill(x, y, x + HEAD_SIZE, y + HEAD_SIZE, 0xFF808080);
            int tx = x + HEAD_SIZE + 2;
            context.drawTextWithShadow(textRenderer, SAMPLE_NAMES[i], tx, y, 0xFFFFFFFF);
            int nw = textRenderer.getWidth(SAMPLE_NAMES[i]);
            context.drawTextWithShadow(textRenderer, SAMPLE_HP[i] + "/" + SAMPLE_MAX[i], tx + nw + 4, y, hpTextColor(pct));
            int barY = y + HEAD_SIZE + 1;
            context.fill(x - 1, barY - 1, x + MATE_BAR_WIDTH + 1, barY + MATE_BAR_HEIGHT + 1, 0xA0000000);
            context.fill(x, barY, x + MATE_BAR_WIDTH, barY + MATE_BAR_HEIGHT, 0x50181818);
            int fill = Math.max(0, Math.min(MATE_BAR_WIDTH, Math.round(pct / 100f * MATE_BAR_WIDTH)));
            if (fill > 0) context.fill(x, barY, x + fill, barY + MATE_BAR_HEIGHT, hpBarColor(pct));
            if (horizontal) x += H_ENTRY_WIDTH; else y += V_ENTRY_HEIGHT;
        }
    }

    private int getMatePanelW() {
        return horizontal ? H_ENTRY_WIDTH * SAMPLE_NAMES.length : MATE_BAR_WIDTH + 2;
    }

    private int getMatePanelH() {
        int content = horizontal ? HEAD_SIZE + 1 + MATE_BAR_HEIGHT + 2 : V_ENTRY_HEIGHT * SAMPLE_NAMES.length;
        return content + 10;
    }

    private void renderStatsPreview(DrawContext context) {
        int pw = getStatsPanelW();
        int ph = getStatsPanelH();
        drawPanelFrame(
                context, statsX, statsY, pw, ph, draggingStats,
                Text.translatable("ctt-health-display.config.preview.stats_panel").getString()
        );

        int sx = statsX + 3;
        int sy = statsY + 10;

        if (statsColumns <= 1) {
            for (int i = 0; i < SAMPLE_STATS.length; i++) {
                int color = i == 0 ? 0xFFFFAA00 : 0xFFFFFFFF;
                context.drawTextWithShadow(textRenderer, SAMPLE_STATS[i], sx, sy, color);
                sy += STATS_LINE_HEIGHT;
            }
        } else {
            int colW = getStatsColWidth() + 6;
            int rows = (SAMPLE_STATS.length + 1) / 2;
            for (int i = 0; i < SAMPLE_STATS.length; i++) {
                int col = i / rows;
                int row = i % rows;
                int color = i == 0 ? 0xFFFFAA00 : 0xFFFFFFFF;
                context.drawTextWithShadow(textRenderer, SAMPLE_STATS[i],
                        sx + col * colW, sy + row * STATS_LINE_HEIGHT, color);
            }
        }
    }

    private int getStatsColWidth() {
        int maxW = 0;
        for (String s : SAMPLE_STATS) maxW = Math.max(maxW, textRenderer.getWidth(s));
        return maxW;
    }

    private int getStatsPanelW() {
        if (statsColumns <= 1) {
            return getStatsColWidth() + 6;
        } else {
            return (getStatsColWidth() + 6) * 2 + 6;
        }
    }

    private int getStatsPanelH() {
        if (statsColumns <= 1) {
            return SAMPLE_STATS.length * STATS_LINE_HEIGHT + 12;
        } else {
            int rows = (SAMPLE_STATS.length + 1) / 2;
            return rows * STATS_LINE_HEIGHT + 12;
        }
    }

    private void drawPanelFrame(DrawContext context, int x, int y, int w, int h, boolean active, String label) {
        int bg = active ? 0x40FFFFFF : 0x20FFFFFF;
        context.fill(x - 3, y - 3, x + w + 3, y + h + 3, bg);
        int border = active ? 0xC055FF55 : 0x80FFFFFF;
        context.fill(x - 3, y - 3, x + w + 3, y - 2, border);
        context.fill(x - 3, y + h + 2, x + w + 3, y + h + 3, border);
        context.fill(x - 3, y - 3, x - 2, y + h + 3, border);
        context.fill(x + w + 2, y - 3, x + w + 3, y + h + 3, border);
        context.drawTextWithShadow(textRenderer, label, x, y, 0xFF55FF55);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX < width / 2) {
            if (hitTest(mouseX, mouseY, statsX, statsY, getStatsPanelW(), getStatsPanelH())) {
                draggingStats = true;
                statsDragOX = mouseX - statsX;
                statsDragOY = mouseY - statsY;
                return true;
            }
            if (hitTest(mouseX, mouseY, mateX, mateY, getMatePanelW(), getMatePanelH())) {
                draggingMate = true;
                mateDragOX = mouseX - mateX;
                mateDragOY = mouseY - mateY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0) {
            int maxDragX = width / 2 - 10;
            if (draggingStats) {
                statsX = clamp((int) (mouseX - statsDragOX), 0, maxDragX);
                statsY = clamp((int) (mouseY - statsDragOY), 0, height - getStatsPanelH());
                return true;
            }
            if (draggingMate) {
                mateX = clamp((int) (mouseX - mateDragOX), 0, maxDragX);
                mateY = clamp((int) (mouseY - mateDragOY), 0, height - getMatePanelH());
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) { draggingMate = false; draggingStats = false; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX > width / 2) {
            int visibleH = height - 36 - 36;
            int maxScroll = Math.max(0, optionContentHeight - visibleH);
            optionScrollOffset = clamp(optionScrollOffset - (int) (verticalAmount * 10), 0, maxScroll);
            clearAndInit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private boolean hitTest(double mx, double my, int px, int py, int pw, int ph) {
        return mx >= px - 3 && mx <= px + pw + 3 && my >= py - 3 && my <= py + ph + 3;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 把当前主屏的所有客户端字段写回 {@link ModConfig#INSTANCE} 并落盘 JSON。
     * <p>由 {@link #saveAndClose} 与"打开服务器配置子屏"前的同步点共用，
     * 保证用户切到子屏 / 子屏返回时不会丢主屏未提交的修改。
     */
    private void saveMainOnly() {
        ModConfig.INSTANCE.teammateX = (float) mateX / width;
        ModConfig.INSTANCE.teammateY = (float) mateY / height;
        ModConfig.INSTANCE.horizontalLayout = horizontal;
        ModConfig.INSTANCE.statsX = (float) statsX / width;
        ModConfig.INSTANCE.statsY = (float) statsY / height;
        ModConfig.INSTANCE.autoRefreshIntervalSeconds = refreshInterval;
        ModConfig.INSTANCE.showTeammateHeadHP = showHeadHP;
        ModConfig.INSTANCE.mobHeadHPMode = mobHPMode;
        // 同步旧字段，防止有外部脚本/逻辑继续读 showMobHeadHP；新代码内部一律读 mobHeadHPMode。
        ModConfig.INSTANCE.showMobHeadHP = (mobHPMode != ModConfig.MOB_HP_MODE_OFF);
        ModConfig.INSTANCE.mobBarHalfWidth = mobBarHalfWidth;
        ModConfig.INSTANCE.teammateBarHalfWidth = teammateBarHalfWidth;
        ModConfig.INSTANCE.statsColumns = statsColumns;
        ModConfig.INSTANCE.statsVisibility = statsVisibility;
        ModConfig.INSTANCE.hidePersonalBar = hidePersonalBar;
        ModConfig.INSTANCE.hideTeamBar = hideTeamBar;
        ModConfig.INSTANCE.hideMobBars = hideMobBars;
        // v7.0.0 · 客户端探针开关
        ModConfig.INSTANCE.clientDamageHudHeader = clientDamageHudHeader;
        ModConfig.INSTANCE.clientDamageDebugChat = clientDamageDebugChat;
        // v7.1.2 · 嵌入式 HUD（每人伤害击杀双行）4 段
        ModConfig.INSTANCE.embeddedHudMode = embeddedHudMode;
        // v7.1.0 · 客户端击杀报告聊天（计数本身始终开启，无字段）
        ModConfig.INSTANCE.clientKillDebugChat = clientKillDebugChat;
        ModConfig.INSTANCE.save();
    }

    private void saveAndClose() {
        saveMainOnly();
        client.setScreen(parent);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private static int hpTextColor(int pct) {
        if (pct <= 25) return 0xFFFF6666;
        if (pct <= 50) return 0xFFFFDD44;
        return 0xFFFFFFFF;
    }

    private static int hpBarColor(int pct) {
        if (pct <= 25) return 0xFFAA1515;
        if (pct <= 50) return 0xFFCC6620;
        if (pct <= 75) return 0xFFD83030;
        return 0xFFE84040;
    }
}
