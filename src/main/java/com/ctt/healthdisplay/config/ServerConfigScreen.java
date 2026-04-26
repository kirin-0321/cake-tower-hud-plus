package com.ctt.healthdisplay.config;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * v6.6.4 · M5 · 服务器配置二级子屏。
 *
 * <h2>设计动机</h2>
 * <p>{@link ServerConfig} 已经独立成 {@code config/ctt-health-display-server.json}，
 * 但客户端 ConfigScreen 仍然需要一个入口让单机用户能可视化地调整服务端策略
 * （专用服务器的远程客户端写这里只改本地拷贝，不影响远程服务端 —— 这种
 * 局限性同 v6.5.x 时期的"广播 3 开关"）。
 *
 * <h2>放进二级菜单的原因</h2>
 * <ul>
 *   <li><b>视觉分离</b>：主屏只放纯客户端 HUD 偏好，不会和服务端策略混杂</li>
 *   <li><b>语义提示</b>：进了 "服务器配置 ▶" 用户就知道这些是会写到服务器
 *       JSON 的字段，专用客户端改它们没用</li>
 *   <li><b>可扩展</b>：后续 ServerConfig 加字段（例如新的过滤器）只在本子屏添按钮</li>
 * </ul>
 *
 * <h2>覆盖字段（v6.6.4）</h2>
 * <ol>
 *   <li>broadcastDamageInChat / broadcastKillsInChat / broadcastTakenInChat —— 3 档广播</li>
 *   <li>broadcastTakenThreshold —— 6 档 cycle (1/5/10/20/50/100)</li>
 *   <li>useRedHeartsTally —— 主数据源切换</li>
 *   <li>filterInitHpJumps / filterSuspectVictims —— 2 档过滤总闸</li>
 *   <li>suspectVictimDamageThreshold —— 6 档 cycle (100/200/400/800/1500/3000)</li>
 * </ol>
 *
 * <p>{@code initHpJumpValues} 与 {@code suspectVictims} 是数组类型，UI 编辑成本高，
 * 只显示当前内容（read-only），需要修改请直接编辑 server JSON。
 */
public class ServerConfigScreen extends Screen {

    private final Screen parent;

    private boolean broadcastDamage;
    private boolean broadcastKills;
    private boolean broadcastTaken;
    private int broadcastTakenThreshold;
    private boolean useRedHearts;
    private boolean filterInitHpJumps;
    private boolean filterSuspectVictims;
    private int suspectThreshold;

    private static final int[] TAKEN_THRESHOLDS    = {1, 5, 10, 20, 50, 100};
    private static final int[] SUSPECT_THRESHOLDS = {100, 200, 400, 800, 1500, 3000};

    private static final int OPTION_BTN_W = 280;
    private static final int OPTION_BTN_H = 20;
    private static final int OPTION_SPACING = 24;

    private int optionScrollOffset = 0;
    private int optionContentHeight = 0;

    public ServerConfigScreen(Screen parent) {
        super(Text.translatable("ctt-health-display.config.server.title"));
        this.parent = parent;
    }

    private boolean firstInit = true;

    @Override
    protected void init() {
        if (firstInit) {
            ServerConfig cfg = ServerConfig.INSTANCE;
            broadcastDamage         = cfg.broadcastDamageInChat;
            broadcastKills          = cfg.broadcastKillsInChat;
            broadcastTaken          = cfg.broadcastTakenInChat;
            broadcastTakenThreshold = cfg.broadcastTakenThreshold;
            useRedHearts            = cfg.useRedHeartsTally;
            filterInitHpJumps       = cfg.filterInitHpJumps;
            filterSuspectVictims    = cfg.filterSuspectVictims;
            suspectThreshold        = cfg.suspectVictimDamageThreshold;
            firstInit = false;
        }

        int btnW = Math.min(OPTION_BTN_W, width - 40);
        int x = (width - btnW) / 2;
        int y = 50 - optionScrollOffset;

        // === 聊天广播组 ===
        addDrawableChild(ButtonWidget.builder(broadcastDamageBtnText(), btn -> {
            broadcastDamage = !broadcastDamage;
            btn.setMessage(broadcastDamageBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(broadcastKillsBtnText(), btn -> {
            broadcastKills = !broadcastKills;
            btn.setMessage(broadcastKillsBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(broadcastTakenBtnText(), btn -> {
            broadcastTaken = !broadcastTaken;
            btn.setMessage(broadcastTakenBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(broadcastTakenThresholdBtnText(), btn -> {
            broadcastTakenThreshold = cycleNext(TAKEN_THRESHOLDS, broadcastTakenThreshold);
            btn.setMessage(broadcastTakenThresholdBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING + 6;

        // === 主数据源 ===
        addDrawableChild(ButtonWidget.builder(useRedHeartsBtnText(), btn -> {
            useRedHearts = !useRedHearts;
            btn.setMessage(useRedHeartsBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING + 6;

        // === 过滤器组 ===
        addDrawableChild(ButtonWidget.builder(filterInitHpJumpsBtnText(), btn -> {
            filterInitHpJumps = !filterInitHpJumps;
            btn.setMessage(filterInitHpJumpsBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(filterSuspectVictimsBtnText(), btn -> {
            filterSuspectVictims = !filterSuspectVictims;
            btn.setMessage(filterSuspectVictimsBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_SPACING;

        addDrawableChild(ButtonWidget.builder(suspectThresholdBtnText(), btn -> {
            suspectThreshold = cycleNext(SUSPECT_THRESHOLDS, suspectThreshold);
            btn.setMessage(suspectThresholdBtnText());
        }).dimensions(x, y, btnW, OPTION_BTN_H).build());
        y += OPTION_BTN_H;

        optionContentHeight = y - (50 - optionScrollOffset);

        int bottomY = height - 28;
        int cx = width / 2;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("ctt-health-display.config.server.btn.cancel"),
                btn -> client.setScreen(parent)
        ).dimensions(cx - 102, bottomY, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("ctt-health-display.config.btn.done"),
                btn -> saveAndClose()
        ).dimensions(cx + 2, bottomY, 100, 20).build());
    }

    private static int cycleNext(int[] arr, int cur) {
        int idx = 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == cur) { idx = i; break; }
        }
        return arr[(idx + 1) % arr.length];
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, this.title, width / 2, 8, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("ctt-health-display.config.server.subtitle"),
                width / 2, 24, 0xFFAAAAAA);

        int infoY = height - 60;
        ServerConfig cfg = ServerConfig.INSTANCE;
        String arrLine1 = Text.translatable(
                "ctt-health-display.config.server.info.init_hp_values",
                java.util.Arrays.toString(cfg.initHpJumpValues)).getString();
        String arrLine2 = Text.translatable(
                "ctt-health-display.config.server.info.suspect_victims",
                String.join(", ", cfg.suspectVictims)).getString();
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(arrLine1), width / 2, infoY,     0xFF888888);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(arrLine2), width / 2, infoY + 10, 0xFF888888);
    }

    private void saveAndClose() {
        ServerConfig cfg = ServerConfig.INSTANCE;
        cfg.broadcastDamageInChat        = broadcastDamage;
        cfg.broadcastKillsInChat         = broadcastKills;
        cfg.broadcastTakenInChat         = broadcastTaken;
        cfg.broadcastTakenThreshold      = broadcastTakenThreshold;
        cfg.useRedHeartsTally            = useRedHearts;
        cfg.filterInitHpJumps            = filterInitHpJumps;
        cfg.filterSuspectVictims         = filterSuspectVictims;
        cfg.suspectVictimDamageThreshold = suspectThreshold;
        cfg.save();
        client.setScreen(parent);
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int visibleH = height - 36 - 70; // 顶部 50 + 底部留 ~70 给 info 行 + 按钮
        int maxScroll = Math.max(0, optionContentHeight - visibleH);
        if (maxScroll > 0) {
            optionScrollOffset = clamp(optionScrollOffset - (int) (verticalAmount * 10), 0, maxScroll);
            clearAndInit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ===== Button labels =====
    private Text broadcastDamageBtnText() {
        return Text.translatable("ctt-health-display.config.option.broadcast_damage", onOff(broadcastDamage));
    }
    private Text broadcastKillsBtnText() {
        return Text.translatable("ctt-health-display.config.option.broadcast_kills", onOff(broadcastKills));
    }
    private Text broadcastTakenBtnText() {
        return Text.translatable("ctt-health-display.config.option.broadcast_taken", onOff(broadcastTaken));
    }
    private Text broadcastTakenThresholdBtnText() {
        return Text.translatable("ctt-health-display.config.server.option.broadcast_taken_threshold", broadcastTakenThreshold);
    }
    private Text useRedHeartsBtnText() {
        return Text.translatable("ctt-health-display.config.server.option.use_red_hearts_tally", onOff(useRedHearts));
    }
    private Text filterInitHpJumpsBtnText() {
        return Text.translatable("ctt-health-display.config.server.option.filter_init_hp_jumps", onOff(filterInitHpJumps));
    }
    private Text filterSuspectVictimsBtnText() {
        return Text.translatable("ctt-health-display.config.server.option.filter_suspect_victims", onOff(filterSuspectVictims));
    }
    private Text suspectThresholdBtnText() {
        return Text.translatable("ctt-health-display.config.server.option.suspect_victim_threshold", suspectThreshold);
    }

    private Text onOff(boolean v) {
        return Text.translatable(v
                ? "ctt-health-display.config.value.on"
                : "ctt-health-display.config.value.off");
    }
}
