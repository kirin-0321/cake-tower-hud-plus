package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.client.ClientStatsCache;
import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.server.AttackerProbe;
import com.ctt.healthdisplay.server.DamageProbe;
import com.ctt.healthdisplay.server.PlayerDamageStats;
import com.ctt.healthdisplay.server.PlayerKillStats;
import com.ctt.healthdisplay.server.PlayerTakenStats;
import com.ctt.healthdisplay.server.StageKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * v6.3.1 · 伤害分配面板渲染器（无背景纯文字风格 · 全屏可交互）。
 *
 * <h2>显示风格</h2>
 * <p>不再画灰色背景 / 标题栏底色 / 分隔线，全部文字走 shadow=true 以保持可读。
 * 按钮是 {@code [▶]} 带方括号的纯文字，hover 时文字变亮黄，非 hover 时浅灰。
 *
 * <h2>调用路径</h2>
 * <ul>
 *   <li>HudRenderCallback：每帧 {@link #drawHud}。</li>
 *   <li>{@link DamagePanelScreen}（L 键 Screen）：自己调 {@link #drawCore}，可拖拽。</li>
 *   <li>任意其他 Screen（聊天 T / 背包 E 等）：通过 ClientModInitializer 注册的
 *       {@code ScreenEvents.AFTER_INIT} 钩子接管 {@link #handleButton} / {@link #hitTestButton}，
 *       让 HUD 面板的按钮在鼠标可见时就可点击，不需要专门打开面板 Screen。</li>
 * </ul>
 */
public final class DamagePanelRenderer {

    // ---- 几何常量 ----
    public static final int PANEL_WIDTH = 280;
    public static final int TITLE_H     = 14;
    // v6.5.5 · 关卡位置行高（位于 title 与 summary 之间）。
    public static final int STAGE_LOC_H = 10;
    public static final int SUMMARY_H   = 12;
    // v6.5.0 · 详情行高 34 → 44：新增第二条 detail 行显示承伤 / 峰值。
    public static final int ROW_H_DETAIL = 44; // name/value + 细节 1 + 细节 2 + 进度条
    public static final int ROW_H_COMPACT = 11;
    // v6.5.2 · 未归属区域行高：详情模式 = 总览 + L9-NONE/FILT/HEAL/CARRY 行（每行 10）
    // v6.5.9 · L8 carry 拆出独立 L9-CARRY 行，高度 42 → 52。
    public static final int UNATTR_H_DETAIL = 52;
    public static final int UNATTR_H_COMPACT = 11;
    public static final int FOOTER_H = 20;
    public static final int PADDING_X = 6;
    public static final int BAR_H = 4;

    // 按钮：纯文字风格，每个按钮固定占 18 px 宽（足够 [X] / [||] 居中），12 高（和标题行对齐）。
    // v6.6.0 · M1 · 第 5 个按钮 = 切片 [S]/[C]（Session / Current Stage）
    public static final int BTN_WIDTH = 18;
    public static final int BTN_HEIGHT = 12;
    public static final int BTN_GAP = 2;
    /**
     * 按钮数量。
     *
     * <p>v6.6.0 hotfix · 移除 ▶/⏹（start/stop）和 ⚑（freeze）两个按钮：
     * 这两个按钮会调 {@link PlayerDamageStats#stop()} / {@link PlayerDamageStats#setFrozen}，
     * 全局关闭三家 stats 的 {@code live} flag 并且不会自动恢复——重启服务器后
     * 持久化层把 {@code live=false} 灌回内存，再没有路径能改回去（v6.6.0 hotfix
     * 后 fromNbt 不再触碰 live/frozen，但点 stop 当场仍会让本场卡死）。
     * 改为面板恒定显示 LIVE，统计数据永远不停。剩下三个按钮：
     * [X] 清零数据、[*] 详情/紧凑、[S]/[C] 切片范围。
     */
    public static final int BTN_COUNT = 3;

    // ---- 颜色（无背景版；文字走 shadow=true 保证在任何世界光线下可读）----
    private static final int COLOR_LIVE     = 0xFF55FF55;
    private static final int COLOR_FROZEN   = 0xFF55D6FF;
    private static final int COLOR_IDLE     = 0xFFC0C0C0;
    private static final int COLOR_TITLE    = 0xFFFFFFFF;
    private static final int COLOR_LABEL    = 0xFFBEBEBE;
    private static final int COLOR_VALUE    = 0xFFFFFFFF;
    private static final int COLOR_PERCENT  = 0xFFFFC248;
    private static final int COLOR_UNATTR   = 0xFFE25858;
    private static final int COLOR_BAR_BG   = 0x40000000;
    private static final int COLOR_BAR_FILL = 0xFFFFC248;
    private static final int COLOR_BTN       = 0xFFD0D0D0;
    private static final int COLOR_BTN_HOVER = 0xFFFFE070;
    // v6.5.0 · 承伤字段用淡红（和未归属/错误的深红区分开）
    private static final int COLOR_TAKEN     = 0xFFFFA0A0;
    // v6.5.2 · L9 三子层各自配色（FILTER 紫 / HEAL 绿 / NONE 深红）
    private static final int COLOR_L9_FILT  = 0xFFB58EEE;
    private static final int COLOR_L9_HEAL  = 0xFF7FE07F;
    // v6.5.9 · L9-CARRY（L8 carry 兜底剥离）配色：浅灰，区别于 NONE 深红
    private static final int COLOR_L9_CARRY = 0xFFB0B0B0;

    private DamagePanelRenderer() {}

    /** 整个面板高度（依赖快照内容 + 详情模式）。 */
    public static int measureHeight(PlayerDamageStats.Snapshot snap, boolean detailed) {
        int h = TITLE_H + STAGE_LOC_H + SUMMARY_H;
        int rowH = detailed ? ROW_H_DETAIL : ROW_H_COMPACT;
        h += snap.players().size() * rowH;
        if (snap.unattributedAll() > 0 || snap.unattributedAllEvents() > 0) {
            h += detailed ? UNATTR_H_DETAIL : UNATTR_H_COMPACT;
        }
        if (detailed) h += FOOTER_H;
        if (snap.players().isEmpty() && snap.unattributedAll() == 0) {
            h += 12; // "暂无数据" 行
        }
        return h;
    }

    /** 计算按钮 #i 在面板内的 (x,y) 相对坐标（面板左上为基准）。 */
    public static int buttonX(int panelX, int buttonIndex) {
        int total = BTN_COUNT * BTN_WIDTH + (BTN_COUNT - 1) * BTN_GAP;
        int startX = panelX + PANEL_WIDTH - PADDING_X - total;
        return startX + buttonIndex * (BTN_WIDTH + BTN_GAP);
    }
    public static int buttonY(int panelY) { return panelY + (TITLE_H - BTN_HEIGHT) / 2; }

    public static int titleBarTop(int panelY)    { return panelY; }
    public static int titleBarBottom(int panelY) { return panelY + TITLE_H; }

    /** 根据 ModConfig 计算面板在屏幕上的 x 坐标。 */
    public static int currentPanelX(MinecraftClient mc) {
        ModConfig cfg = ModConfig.INSTANCE;
        int sw = mc.getWindow().getScaledWidth();
        return Math.max(0, Math.min(sw - PANEL_WIDTH, Math.round(cfg.damagePanelX * sw)));
    }

    /** 根据 ModConfig 计算面板在屏幕上的 y 坐标。 */
    public static int currentPanelY(MinecraftClient mc) {
        ModConfig cfg = ModConfig.INSTANCE;
        int sh = mc.getWindow().getScaledHeight();
        return Math.max(0, Math.min(sh - 20, Math.round(cfg.damagePanelY * sh)));
    }

    /**
     * HUD 回调入口：按 ModConfig 控制可见性 + 坐标；在任意 Screen 打开时也会被调用
     * （HudRenderCallback 在 Screen 之下渲染，所以无需特殊处理）。
     *
     * @param mouseX / mouseY 当前光标屏幕坐标（缩放后，-1 表示没光标）
     */
    public static void drawHud(DrawContext ctx, MinecraftClient mc, double mouseX, double mouseY) {
        ModConfig cfg = ModConfig.INSTANCE;
        if (!cfg.damagePanelHudVisible) return;
        // v6.6.0 · M1 · 切片：根据 ModConfig.damagePanelStageScope 决定取 session 总还是当前关
        PlayerDamageStats.Snapshot snap = currentScopedSnapshot();
        // session 总没数据时直接收起；切片视图即便 session 有数据但当前关还没数据也允许显示
        // （展示空表头 + "暂无数据"占位，方便玩家看到"已切到当前关 + 它是空的"）
        PlayerDamageStats.Snapshot sessionSnap = ClientStatsCache.damageSnapshot();
        if (!sessionSnap.live() && sessionSnap.grandTotal() == 0
                && sessionSnap.unattributedAll() == 0 && sessionSnap.players().isEmpty()) {
            return;
        }
        int x = currentPanelX(mc);
        int y = currentPanelY(mc);

        // Screen 打开（光标可见）时：计算 hover
        int hovered = -1;
        boolean interactive = mc.currentScreen != null;
        if (interactive && mouseX >= 0 && mouseY >= 0) {
            hovered = hitTestButton(mouseX, mouseY, mc);
        }
        drawCore(ctx, mc.textRenderer, snap, x, y, cfg.damagePanelDetailed, hovered, interactive);
    }

    /**
     * v6.6.0 · M1 · 按 ModConfig.damagePanelStageScope 取对应快照：
     * SESSION = 整局总；CURRENT_STAGE = 当前关切片（{@link ClientStatsCache#representativeStageKey}）。
     * 不在战斗关时 scope=CURRENT 也降级为空切片快照。
     *
     * <p>v6.6.5 M6 · 改走 {@link ClientStatsCache}：集成服务器直读，专用服务器 / LAN
     * 远程客户端走 S2C payload 缓存。
     */
    public static PlayerDamageStats.Snapshot currentScopedSnapshot() {
        ModConfig cfg = ModConfig.INSTANCE;
        if (cfg.damagePanelStageScope == ModConfig.STAGE_SCOPE_CURRENT) {
            StageKey key = ClientStatsCache.representativeStageKey();
            return ClientStatsCache.damageSnapshotOf(key);
        }
        return ClientStatsCache.damageSnapshot();
    }

    /**
     * 共享核心绘制（Screen 和 HUD 都调这个）。
     *
     * @param hoveredButton -1 = 无 hover；0..3 = 被 hover 的按钮索引
     * @param interactive   是否处于可交互状态（鼠标可见）：显示 "≡" 拖拽手柄 + 按钮 hover
     */
    public static void drawCore(DrawContext ctx, TextRenderer tr,
                                PlayerDamageStats.Snapshot snap,
                                int x, int y, boolean detailed,
                                int hoveredButton, boolean interactive) {
        drawTitleBar(ctx, tr, snap, x, y, hoveredButton, interactive);

        int cy = y + TITLE_H;
        // v6.5.5 · 关卡位置行（位于 title 与 summary 之间）：来自客户端读 scoreboard 的 StageLocation 探测器。
        drawStageLocation(ctx, tr, x, cy);
        cy += STAGE_LOC_H;
        drawSummary(ctx, tr, snap, x, cy);
        cy += SUMMARY_H;

        if (snap.players().isEmpty() && snap.unattributedAll() == 0) {
            ctx.drawText(tr, Text.literal(snap.frozen()
                            ? "暂停（大厅 / 小游戏 / GameOver）…"
                            : "累计中，暂无归属数据…"),
                    x + PADDING_X, cy + 2, COLOR_LABEL, true);
            cy += 12;
        } else {
            // v6.5.2 · 玩家占比 = self.confirmed / grandTotal（仅 L1~L8）。
            //   L9 (NONE/FILTER/HEAL) 不进 grandTotal，独立展示在下方"未归属"区域。
            long grandTotal = snap.grandTotal();
            for (PlayerDamageStats.PlayerRow row : snap.players()) {
                drawPlayerRow(ctx, tr, row, grandTotal, x, cy, detailed);
                cy += detailed ? ROW_H_DETAIL : ROW_H_COMPACT;
            }
        }

        if (snap.unattributedAll() > 0 || snap.unattributedAllEvents() > 0) {
            drawUnattributed(ctx, tr, snap, x, cy, detailed);
            cy += detailed ? UNATTR_H_DETAIL : UNATTR_H_COMPACT;
        }

        if (detailed) {
            drawLayerFooter(ctx, tr, snap, x, cy + 2);
        }
    }

    private static void drawTitleBar(DrawContext ctx, TextRenderer tr,
                                     PlayerDamageStats.Snapshot snap,
                                     int x, int y, int hoveredButton, boolean interactive) {
        String status;
        int statusColor;
        if (snap.live() && !snap.frozen()) { status = "LIVE";   statusColor = COLOR_LIVE; }
        else if (snap.frozen())            { status = "FROZEN"; statusColor = COLOR_FROZEN; }
        else                                { status = "IDLE";   statusColor = COLOR_IDLE; }

        double durSec = snap.durationMs() / 1000.0;
        String handle = interactive ? "[+] " : "    "; // + 提示可拖拽；非交互时留空保持对齐
        // v6.6.0 · M1 · 标题行追加 [SESSION] / [STAGE] tag，提示当前切片范围
        String scopeTag = ModConfig.INSTANCE.damagePanelStageScope == ModConfig.STAGE_SCOPE_CURRENT
                ? "[STAGE] " : "[SESS] ";
        String title = String.format("%s%sCTT \u4f24\u5bb3\u5206\u914d \u00b7 %.1fs \u00b7 ",
                handle, scopeTag, durSec);
        ctx.drawText(tr, Text.literal(title), x + PADDING_X, y + 3, COLOR_TITLE, true);
        int titleW = tr.getWidth(title);
        ctx.drawText(tr, Text.literal(status), x + PADDING_X + titleW, y + 3, statusColor, true);

        String[] labels = buttonLabels(snap);
        for (int i = 0; i < BTN_COUNT; i++) {
            int bx = buttonX(x, i);
            int by = buttonY(y);
            int color = (i == hoveredButton) ? COLOR_BTN_HOVER : COLOR_BTN;
            int lw = tr.getWidth(labels[i]);
            ctx.drawText(tr, Text.literal(labels[i]),
                    bx + (BTN_WIDTH - lw) / 2, by + 2, color, true);
        }
    }

    /**
     * 按钮文本（方括号样式便于识别可点 + 紧凑）。
     *
     * <p>v6.6.0 hotfix · 顺序：[X] 清零、[*] 详情/紧凑、[S]/[C] 切片。
     * 不再有 start/stop / freeze（参见 {@link #BTN_COUNT}）。
     */
    public static String[] buttonLabels(PlayerDamageStats.Snapshot snap) {
        String scope = ModConfig.INSTANCE.damagePanelStageScope == ModConfig.STAGE_SCOPE_CURRENT
                ? "[S]" : "[C]";
        return new String[] { "[X]", "[*]", scope };
    }

    /** 按钮 tooltip。供 hover 态显示。 */
    public static String[] buttonTooltips(PlayerDamageStats.Snapshot snap) {
        return new String[] {
                "清零累计",
                ModConfig.INSTANCE.damagePanelDetailed ? "切到紧凑模式" : "切到详情模式",
                ModConfig.INSTANCE.damagePanelStageScope == ModConfig.STAGE_SCOPE_CURRENT
                        ? "切到整局视图 (Session)"
                        : "切到当前关视图 (Current Stage)"
        };
    }

    /**
     * v6.5.5 · 关卡位置行：左侧"📍 当前位置:" 标签 + 右侧位置名（颜色随大类）。
     * 数据完全由客户端 {@link StageLocation#probe()} 实时计算，不依赖服务端 sync。
     */
    private static void drawStageLocation(DrawContext ctx, TextRenderer tr, int x, int y) {
        StageLocation.Snapshot loc = StageLocation.probe();
        Text label = Text.literal("\u4f4d\u7f6e: ");
        ctx.drawText(tr, label, x + PADDING_X, y + 1, COLOR_LABEL, true);
        int lw = tr.getWidth(label);
        ctx.drawText(tr, loc.formatted(), x + PADDING_X + lw, y + 1, 0xFFFFFFFF, true);
    }

    private static void drawSummary(DrawContext ctx, TextRenderer tr,
                                    PlayerDamageStats.Snapshot snap, int x, int y) {
        // v6.5.2 · grandTotal = sum(L1..L8)（玩家百分比分母）。L9 三子层独立展示。
        long grandTotal = snap.grandTotal();
        int grandEvents = snap.totalEvents();
        double durSec = snap.durationMs() / 1000.0;
        double dps = durSec > 0.05 ? grandTotal / durSec : 0.0;
        double avg = grandEvents > 0 ? (double) grandTotal / grandEvents : 0.0;
        // v6.6.0 · M1 · 切片对齐：summary 的"承伤总"也按 scope 取
        PlayerTakenStats.Snapshot ts = currentScopedTakenSnapshot();
        String line = String.format("总 %s  事件 %d  DPS %.0f  平均 %.1f  最高 %d  承 %s",
                fmt(grandTotal), grandEvents, dps, avg, snap.totalMaxHit(), fmt(ts.totalTaken()));
        ctx.drawText(tr, Text.literal(line), x + PADDING_X, y + 2, COLOR_LABEL, true);
    }

    /** v6.6.0 · M1 · 按 scope 取 PlayerTakenStats 快照（v6.6.5 M6 · 改走 ClientStatsCache）。 */
    private static PlayerTakenStats.Snapshot currentScopedTakenSnapshot() {
        if (ModConfig.INSTANCE.damagePanelStageScope == ModConfig.STAGE_SCOPE_CURRENT) {
            return ClientStatsCache.takenSnapshotOf(ClientStatsCache.representativeStageKey());
        }
        return ClientStatsCache.takenSnapshot();
    }

    private static void drawPlayerRow(DrawContext ctx, TextRenderer tr,
                                      PlayerDamageStats.PlayerRow row, long grandTotal,
                                      int x, int y, boolean detailed) {
        double pct = row.percent(grandTotal);

        String name = row.name() != null ? row.name() : "?";
        ctx.drawText(tr, Text.literal(name), x + PADDING_X, y + 1, COLOR_VALUE, true);

        String valueStr = fmt(row.confirmed());
        String pctStr   = String.format("%.1f%%", pct);
        int pctW = tr.getWidth(pctStr);
        int valW = tr.getWidth(valueStr);
        int rightX = x + PANEL_WIDTH - PADDING_X;
        ctx.drawText(tr, Text.literal(pctStr),  rightX - pctW,              y + 1, COLOR_PERCENT, true);
        ctx.drawText(tr, Text.literal(valueStr), rightX - pctW - 6 - valW,   y + 1, COLOR_VALUE,   true);

        if (!detailed) return;

        // v6.5.2 · L1~L8 全是已分类硬归属，伤害都进 row.confirmed。L9 不进玩家账户。
        // v6.4.0 · "击杀 / 助攻"字段来自 PlayerKillStats（与 PlayerDamageStats 同步 session）。
        // v6.6.0 · M1 · 切片：CURRENT_STAGE 时读 stage 维度，SESSION 时读总。
        boolean stageScope = ModConfig.INSTANCE.damagePanelStageScope == ModConfig.STAGE_SCOPE_CURRENT;
        StageKey curKey = stageScope ? ClientStatsCache.representativeStageKey() : null;
        int kills = stageScope
                ? ClientStatsCache.getKillsAt(row.uuid(), curKey)
                : ClientStatsCache.getKills(row.uuid());
        int assists = stageScope
                ? ClientStatsCache.getAssistsAt(row.uuid(), curKey)
                : ClientStatsCache.getAssists(row.uuid());
        String detail = String.format("\u4e8b\u4ef6 %d \u00b7 \u6700\u9ad8 %d \u00b7 \u51fb\u6740 %d \u00b7 \u52a9\u653b %d",
                row.events(), row.maxHit(), kills, assists);
        ctx.drawText(tr, Text.literal(detail), x + PADDING_X, y + 12, COLOR_LABEL, true);

        // v6.5.0 · 第二条 detail 行：承伤 / 单次峰值。数据来自 ClientStatsCache（同 session）。
        // v6.6.0 · M1 · 切片：stage 维度时取 getTakenAt；峰值仍用 session（stage 内峰值未单独跟）
        long taken = stageScope
                ? ClientStatsCache.getTakenAt(row.uuid(), curKey)
                : ClientStatsCache.getTaken(row.uuid());
        int takenMax = ClientStatsCache.getMaxHit(row.uuid());
        String taken2 = String.format("\u627f\u4f24 %s \u00b7 \u5355\u6b21\u5cf0 %d",
                fmt(taken), takenMax);
        ctx.drawText(tr, Text.literal(taken2), x + PADDING_X, y + 22, COLOR_TAKEN, true);

        // 进度条：保留（数据可视化必要）——仅剩"黑底 + 前景"两层；不画整面板背景
        int barX = x + PADDING_X;
        int barY = y + 34;
        int barW = PANEL_WIDTH - 2 * PADDING_X;
        ctx.fill(barX, barY, barX + barW, barY + BAR_H, COLOR_BAR_BG);
        if (grandTotal > 0) {
            int fillW = (int) Math.round(barW * (double) row.confirmed() / grandTotal);
            ctx.fill(barX, barY, barX + fillW, barY + BAR_H, COLOR_BAR_FILL);
        }
    }

    private static void drawUnattributed(DrawContext ctx, TextRenderer tr,
                                         PlayerDamageStats.Snapshot snap,
                                         int x, int y, boolean detailed) {
        // v6.5.2 · 未归属总览（NONE + FILTER + HEAL）。
        //   注意：grandTotal 不含 L9，未归属总和与 grandTotal 是并列展示，不再算百分比（无意义）。
        long unAll = snap.unattributedAll();
        ctx.drawText(tr, Text.literal("? 未归属"), x + PADDING_X, y + 1, COLOR_UNATTR, true);
        String valueStr = fmt(unAll);
        int valW = tr.getWidth(valueStr);
        int rightX = x + PANEL_WIDTH - PADDING_X;
        String evStr = String.format("%d 事件", snap.unattributedAllEvents());
        int evW = tr.getWidth(evStr);
        ctx.drawText(tr, Text.literal(evStr),    rightX - evW,             y + 1, COLOR_UNATTR, true);
        ctx.drawText(tr, Text.literal(valueStr), rightX - evW - 6 - valW,  y + 1, COLOR_UNATTR, true);

        if (!detailed) return;

        // 四子层独立行（NONE / FILTER / HEAL / CARRY）
        int unKills = ClientStatsCache.killSnapshot().unattributedKills();
        String noneLine = String.format("L9-NONE %s (%d) \u00b7 \u672a\u5206\u7c7b\u51fb\u6740 %d",
                fmt(snap.unattributedNone()), snap.unattributedNoneEvents(), unKills);
        ctx.drawText(tr, Text.literal(noneLine),
                x + PADDING_X, y + 12, COLOR_UNATTR, true);

        String filtLine = String.format("L9-FILT %s (%d) \u00b7 \u9ed1\u540d\u5355\u8fc7\u6ee4 %s",
                fmt(snap.unattributedFiltered()), snap.unattributedFilteredEvents(),
                java.util.Arrays.toString(com.ctt.healthdisplay.config.ServerConfig.INSTANCE.initHpJumpValues));
        ctx.drawText(tr, Text.literal(filtLine),
                x + PADDING_X, y + 22, COLOR_L9_FILT, true);

        String healLine = String.format("L9-HEAL %s (%d) \u00b7 \u7eff\u8272\u56de\u8840\u7c92\u5b50",
                fmt(snap.unattributedHeal()), snap.unattributedHealEvents());
        ctx.drawText(tr, Text.literal(healLine),
                x + PADDING_X, y + 32, COLOR_L9_HEAL, true);

        // v6.5.9 · L8 carry 兜底剥离行
        String carryLine = String.format("L9-CARRY %s (%d) \u00b7 L8 carry \u517c\u5e95\u5269\u9910",
                fmt(snap.unattributedCarry()), snap.unattributedCarryEvents());
        ctx.drawText(tr, Text.literal(carryLine),
                x + PADDING_X, y + 42, COLOR_L9_CARRY, true);
    }

    private static void drawLayerFooter(DrawContext ctx, TextRenderer tr,
                                        PlayerDamageStats.Snapshot snap, int x, int y) {
        long[] lc = snap.globalLayerCounts();
        AttackerProbe.Layer[] values = AttackerProbe.Layer.values();
        StringBuilder sb = new StringBuilder();
        for (AttackerProbe.Layer l : values) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(l.shortTag()).append('=').append(lc[l.ordinal()]);
        }
        ctx.drawText(tr, Text.literal(sb.toString()), x + PADDING_X, y, COLOR_LABEL, true);
        String hint = "L 开面板 · 任何 Screen 下均可点按钮 · 拖 [+] 移动";
        ctx.drawText(tr, Text.literal(hint), x + PADDING_X, y + 10, 0xFF909090, true);
    }

    // =========================================================================
    //  点击 / 命中检测 · 供 DamagePanelScreen 和 ScreenEvents 钩子共用
    // =========================================================================

    /** 命中按钮。鼠标坐标必须是屏幕缩放后坐标。没命中返回 -1。 */
    public static int hitTestButton(double mx, double my, MinecraftClient mc) {
        int px = currentPanelX(mc);
        int py = currentPanelY(mc);
        int by = buttonY(py);
        if (my < by || my >= by + BTN_HEIGHT) return -1;
        for (int i = 0; i < BTN_COUNT; i++) {
            int bx = buttonX(px, i);
            if (mx >= bx && mx < bx + BTN_WIDTH) return i;
        }
        return -1;
    }

    /**
     * v6.6.7 · 命中"标题栏可拖拽区"——title bar 范围内但不在按钮行。
     * <p>用于聊天栏 / 背包 / 暂停 / 任意 Screen 打开时支持拖动面板。
     */
    public static boolean hitTestTitleBarDraggable(double mx, double my, MinecraftClient mc) {
        int px = currentPanelX(mc);
        int py = currentPanelY(mc);
        if (mx < px || mx >= px + PANEL_WIDTH) return false;
        if (my < titleBarTop(py) || my >= titleBarBottom(py)) return false;
        // 排除按钮行 —— 否则点 [▶]/[X] 等按钮会被误识为拖拽
        int by = buttonY(py);
        if (my >= by && my < by + BTN_HEIGHT) {
            int firstX = buttonX(px, 0);
            int lastX  = buttonX(px, BTN_COUNT - 1) + BTN_WIDTH;
            if (mx >= firstX && mx < lastX) return false;
        }
        return true;
    }

    // =========================================================================
    //  拖拽 state · 供任意 Screen（聊天 T / 背包 E / DamagePanelScreen 自身）共享
    // =========================================================================

    /**
     * v6.6.7 · 全局拖拽状态。volatile 因为 Screen 钩子和 HudRenderCallback 在不同
     * 调用栈里读写（MC 客户端单线程，volatile 仅作为代码可读性提示）。
     */
    private static volatile boolean dragging = false;
    private static int dragDX = 0;
    private static int dragDY = 0;

    /** 是否正处于拖拽中。 */
    public static boolean isDragging() { return dragging; }

    /** 在 (mx, my) 处开始拖拽——记录鼠标到面板左上角的偏移。 */
    public static void beginDrag(MinecraftClient mc, double mx, double my) {
        dragging = true;
        dragDX = (int) (mx - currentPanelX(mc));
        dragDY = (int) (my - currentPanelY(mc));
    }

    /**
     * 每帧 tick 拖拽——按当前鼠标位置更新 ModConfig 坐标百分比。
     * <p>不写盘——pose 改动每帧产生，等 {@link #endDrag()} 松手时再 save 一次。
     */
    public static void tickDrag(MinecraftClient mc, double mx, double my) {
        if (!dragging || mc == null || mc.getWindow() == null) return;
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        if (sw <= 0 || sh <= 0) return;
        int newX = (int) Math.max(0, Math.min(sw - PANEL_WIDTH, mx - dragDX));
        int newY = (int) Math.max(0, Math.min(sh - 20,          my - dragDY));
        ModConfig cfg = ModConfig.INSTANCE;
        cfg.damagePanelX = (float) newX / sw;
        cfg.damagePanelY = (float) newY / sh;
    }

    /** 结束拖拽并立即 save config——避免拖完没松手就退游戏导致位置丢失。 */
    public static void endDrag() {
        if (!dragging) return;
        dragging = false;
        ModConfig.INSTANCE.save();
    }

    /**
     * 处理按钮 idx 的点击动作。
     *
     * <p>v6.6.0 hotfix · 移除原 start/stop / freeze 两个按钮（会全局关闭三家
     * stats 且重启后无法恢复——参见 {@link #BTN_COUNT}）。当前按钮序：
     * <ul>
     *   <li>idx 0 · [X] 清零累计 — 仅清数据，{@link PlayerDamageStats#clear()} 内部
     *       会保持 {@code live=true / frozen=false}，统计永不中断。</li>
     *   <li>idx 1 · [*] 详情 / 紧凑切换</li>
     *   <li>idx 2 · [S] / [C] 切片范围切换</li>
     * </ul>
     *
     * <p>v6.6.5 M6 · 注意：clear 是<b>写操作</b>，仍直接调 server static。集成服务器
     * （host / 单机）下生效；专用服务器 / LAN 远程客户端下只改 client JVM 静态字段，
     * 不会同步到真服务端。
     */
    public static void handleButton(int idx, MinecraftClient mc) {
        switch (idx) {
            case 0 -> {
                PlayerDamageStats.clear();
                if (DamageProbe.isSessionActive()) {
                    DamageProbe.stopSession();
                    DamageProbe.startSession();
                }
                feedback(mc, "伤害分配数据已清零");
            }
            case 1 -> {
                ModConfig cfg = ModConfig.INSTANCE;
                cfg.damagePanelDetailed = !cfg.damagePanelDetailed;
                cfg.save();
                feedback(mc, cfg.damagePanelDetailed ? "详情模式" : "紧凑模式");
            }
            case 2 -> {
                ModConfig cfg = ModConfig.INSTANCE;
                cfg.damagePanelStageScope = (cfg.damagePanelStageScope == ModConfig.STAGE_SCOPE_CURRENT)
                        ? ModConfig.STAGE_SCOPE_SESSION : ModConfig.STAGE_SCOPE_CURRENT;
                cfg.save();
                feedback(mc, cfg.damagePanelStageScope == ModConfig.STAGE_SCOPE_CURRENT
                        ? "切片：当前关 (Current Stage)" : "切片：整局 (Session)");
            }
            default -> {}
        }
    }

    private static void feedback(MinecraftClient mc, String msg) {
        if (mc != null && mc.player != null) {
            mc.player.sendMessage(Text.literal("[CTT] " + msg), true);
        }
    }

    // =========================================================================
    //  小工具
    // =========================================================================
    /** 千位分隔符格式化，便于对大数字读出来。 */
    private static String fmt(long n) {
        if (n < 1000) return Long.toString(n);
        return String.format("%,d", n);
    }
}
