package com.ctt.healthdisplay.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * v6.6.0 · M4 · K 键统计表格面板（设计 §6）。
 *
 * <p>Tab 1 总表 + Tab 2 分关表，鼠标滚轮上下滚，Esc / 再按 N 关闭。
 * 列头点击切换排序（仅总表；分关表固定 (tier→floor) 升序）。
 *
 * <h3>布局</h3>
 * <pre>
 *  ┌──────────────────────────── PANEL ────────────────────────────┐
 *  │  [当前关卡富文本]                              Session: HH:MM:SS │  ← 顶部信息条
 *  │  [总表]  [分关表]                                                │  ← Tab 切换条
 *  ├──────────────────────────────────────────────────────────────┤
 *  │  [头] 玩家       ⚔ ▼     ⛨     ☠   ☠B  🤝   ⏱                │  ← 列头
 *  │  ─────────────────────────────────────────────────────────  │
 *  │  ...玩家行...                                                 │  ← 滚动区
 *  │  ─────────────────────────────────────────────────────────  │
 *  │  [全队]          ...                                          │  ← 表底（仅总表）
 *  └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>颜色（设计 §6.8）</h3>
 * <ul>
 *   <li>背景：{@code #181818} ~55% 透明（vanilla Tab 风）</li>
 *   <li>自己：金色 {@code #FFAA00} + 行底纹淡金</li>
 *   <li>离线：{@code #888888} + 名字后追加 {@code [离线]}</li>
 *   <li>当前关组：金边描线（分关表）</li>
 * </ul>
 */
public final class StatsTableScreen extends Screen {

    public enum Tab { TOTAL, STAGE }

    private Tab tab = Tab.TOTAL;
    private StatsTableData.SortBy sortBy = StatsTableData.SortBy.DEALT;
    private boolean sortAscending = false;
    private double scrollY;

    // ========== 视觉常量 ==========
    private static final int BG_COLOR     = 0x90181818;
    private static final int LINE_COLOR   = 0x60FFFFFF;
    private static final int HEAD_BG      = 0xC0202020;
    private static final int FOOT_BG      = 0x40FFAA00;
    private static final int SELF_ROW_BG  = 0x30FFAA00;
    private static final int CURRENT_BG   = 0x40FFD550;

    private static final int TEXT_WHITE   = 0xFFE8E8E8;
    private static final int TEXT_GREY    = 0xFF888888;
    private static final int TEXT_GOLD    = 0xFFFFAA00;
    private static final int TEXT_LIGHT_GOLD = 0xFFFFCC66;
    private static final int TEXT_PURPLE  = 0xFFCC66FF;
    private static final int TEXT_OFFLINE = 0xFF888888;
    private static final int TEXT_ICON_DEALT = 0xFFFFFFFF;
    private static final int TEXT_ICON_TAKEN = 0xFFCCCCCC;
    private static final int TEXT_ICON_KILL  = 0xFFFF8080;
    private static final int TEXT_ICON_BOSSK = 0xFFCC66FF;
    private static final int TEXT_ICON_ASSIST= 0xFFFFD55A;

    private static final int PANEL_PAD_X  = 12;
    private static final int PANEL_PAD_Y  = 10;
    private static final int ROW_HEIGHT   = 12;
    private static final int HEAD_ROW_HEIGHT = 14;
    private static final int HEAD_SIZE    = 8;

    private static final int TAB_W = 60;
    private static final int TAB_H = 14;
    private static final int TAB_GAP = 4;

    // 总表列定义（x 偏移相对于 panel 左 + PANEL_PAD_X）
    private record Col(String header, int x, int w, StatsTableData.SortBy sortKey, int iconColor, boolean rightAlign) {}
    private static final Col[] TOTAL_COLS = new Col[] {
            // [头像]列宽 10；后面文本列等距
            new Col("\u73a9\u5bb6",     22,  86, null,                                  TEXT_WHITE,        false),
            new Col("\u2694",          110,  56, StatsTableData.SortBy.DEALT,           TEXT_ICON_DEALT,   true),
            new Col("\u26E8",          168,  46, StatsTableData.SortBy.TAKEN,           TEXT_ICON_TAKEN,   true),
            new Col("\u2620",          216,  30, StatsTableData.SortBy.KILLS,           TEXT_ICON_KILL,    true),
            new Col("\u2620B",         248,  30, StatsTableData.SortBy.BOSS_KILLS,      TEXT_ICON_BOSSK,   true),
            new Col("\ud83e\udd1d",    280,  30, StatsTableData.SortBy.ASSISTS,         TEXT_ICON_ASSIST,  true),
            new Col("\u23f1",          312,  46, StatsTableData.SortBy.DURATION,        TEXT_WHITE,        true),
    };

    // 分关表列定义（layer/name 单独首列处理；后续列对齐总表减少视觉跳动）
    // v6.6.7 · 列宽收紧：玩家 72→60（英文名 ~50px 后留 10px 即可，中文名 16-32px 绰绰有余），
    // ⏱ 40→34（MM:SS 仅 5 字符 ~18px），保证总末端 ≤ 360（PANEL_W - PANEL_PAD_X * 2），
    // 不再被右侧滚动条裁掉。
    private static final Col[] STAGE_COLS = new Col[] {
            new Col("\u5c42",            0,  34, null,                                  TEXT_WHITE,        false),
            new Col("\u5173\u5361",     34,  76, null,                                  TEXT_WHITE,        false),
            new Col("\u73a9\u5bb6",    122,  60, null,                                  TEXT_WHITE,        false),
            new Col("\u2694",          184,  46, null,                                  TEXT_ICON_DEALT,   true),
            new Col("\u26E8",          232,  40, null,                                  TEXT_ICON_TAKEN,   true),
            new Col("\u2620",          274,  24, null,                                  TEXT_ICON_KILL,    true),
            new Col("\ud83e\udd1d",    300,  24, null,                                  TEXT_ICON_ASSIST,  true),
            new Col("\u23f1",          326,  34, null,                                  TEXT_WHITE,        true),
    };

    private static final int PANEL_W = 384;

    public StatsTableScreen() {
        super(Text.literal("CTT \u7edf\u8ba1\u8868"));
    }

    @Override
    public boolean shouldPause() { return false; }

    // =========================================================================
    //  主渲染
    // =========================================================================
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_W) / 2;
        int panelY = 20;
        int panelH = this.height - 40;

        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, BG_COLOR);
        ctx.drawBorder(panelX, panelY, PANEL_W, panelH, LINE_COLOR);

        TextRenderer tr = this.textRenderer;

        int y = panelY + PANEL_PAD_Y;
        // 顶部信息：当前关卡 + Session 计时器
        StageLocation.Snapshot loc = StageLocation.probe();
        Text locText = loc.formatted();
        ctx.drawTextWithShadow(tr, locText, panelX + PANEL_PAD_X, y, 0xFFFFFFFF);

        long sessionMs = client == null ? 0L : com.ctt.healthdisplay.client.ClientStatsCache.sessionDurationMs();
        String sessLbl = "Session: " + StatsTableData.formatDuration(sessionMs);
        int sessW = tr.getWidth(sessLbl);
        ctx.drawTextWithShadow(tr, sessLbl, panelX + PANEL_W - PANEL_PAD_X - sessW, y, TEXT_LIGHT_GOLD);
        y += 14;

        // Tab 切换条
        int tabsY = y;
        drawTab(ctx, tr, "\u603b\u8868",  panelX + PANEL_PAD_X,                         tabsY, tab == Tab.TOTAL, mouseX, mouseY);
        drawTab(ctx, tr, "\u5206\u5173\u8868", panelX + PANEL_PAD_X + TAB_W + TAB_GAP, tabsY, tab == Tab.STAGE, mouseX, mouseY);
        y += TAB_H + 6;

        // 分隔线
        ctx.fill(panelX + 1, y - 2, panelX + PANEL_W - 1, y - 1, LINE_COLOR);

        int contentTop = y;
        int contentBottom = panelY + panelH - PANEL_PAD_Y - (tab == Tab.TOTAL ? ROW_HEIGHT + 2 : 0); // 总表底留 [全队]

        // 列头（分别画总/分）
        int headerH;
        if (tab == Tab.TOTAL) {
            headerH = drawTotalHeader(ctx, tr, panelX + PANEL_PAD_X, y);
        } else {
            headerH = drawStageHeader(ctx, tr, panelX + PANEL_PAD_X, y);
        }
        y += headerH;

        // 内容区（带滚动裁剪）
        int rowsTop = y;
        ctx.enableScissor(panelX + 1, rowsTop, panelX + PANEL_W - 1, contentBottom);
        int contentH = (tab == Tab.TOTAL)
                ? renderTotalRows(ctx, tr, panelX + PANEL_PAD_X, rowsTop - (int) scrollY)
                : renderStageRows(ctx, tr, panelX + PANEL_PAD_X, rowsTop - (int) scrollY);
        ctx.disableScissor();

        // 表底 [全队]（仅总表）
        if (tab == Tab.TOTAL) {
            int footY = panelY + panelH - PANEL_PAD_Y - ROW_HEIGHT;
            drawTotalFooter(ctx, tr, panelX + PANEL_PAD_X, footY);
        }

        // 滚动条
        int viewH = contentBottom - rowsTop;
        if (contentH > viewH) {
            int trackX = panelX + PANEL_W - 4;
            ctx.fill(trackX, rowsTop, trackX + 2, contentBottom, 0x40FFFFFF);
            int thumbH = Math.max(10, viewH * viewH / contentH);
            int thumbY = rowsTop + (int) ((viewH - thumbH) * scrollY / (contentH - viewH));
            ctx.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFFAAAAAA);
        }

        // 底部提示
        ctx.drawText(tr, Text.literal("\u70b9\u51fb\u5217\u5934\u6392\u5e8f \u00b7 \u6eda\u8f6e\u6eda\u52a8 \u00b7 N / Esc \u5173\u95ed"),
                panelX + PANEL_PAD_X, panelY + panelH + 2, 0xFFAAAAAA, false);
    }

    // =========================================================================
    //  Tab
    // =========================================================================
    private void drawTab(DrawContext ctx, TextRenderer tr, String label, int x, int y, boolean active, int mx, int my) {
        boolean hovered = mx >= x && mx < x + TAB_W && my >= y && my < y + TAB_H;
        int bg = active ? 0xC0FFAA00 : (hovered ? 0x60808080 : 0x60404040);
        ctx.fill(x, y, x + TAB_W, y + TAB_H, bg);
        ctx.drawBorder(x, y, TAB_W, TAB_H, active ? 0xFFFFAA00 : LINE_COLOR);
        int lw = tr.getWidth(label);
        int color = active ? 0xFF202020 : TEXT_WHITE;
        ctx.drawText(tr, label, x + (TAB_W - lw) / 2, y + (TAB_H - 8) / 2, color, false);
    }

    private boolean tabClick(double mx, double my) {
        int panelX = (this.width - PANEL_W) / 2;
        int tabsY = 20 + PANEL_PAD_Y + 14;
        if (my < tabsY || my >= tabsY + TAB_H) return false;
        int totalX = panelX + PANEL_PAD_X;
        int stageX = totalX + TAB_W + TAB_GAP;
        if (mx >= totalX && mx < totalX + TAB_W) {
            if (tab != Tab.TOTAL) { tab = Tab.TOTAL; scrollY = 0; }
            return true;
        }
        if (mx >= stageX && mx < stageX + TAB_W) {
            if (tab != Tab.STAGE) { tab = Tab.STAGE; scrollY = 0; }
            return true;
        }
        return false;
    }

    // =========================================================================
    //  总表
    // =========================================================================
    private int drawTotalHeader(DrawContext ctx, TextRenderer tr, int x, int y) {
        ctx.fill(x - 2, y - 1, x + PANEL_W - PANEL_PAD_X * 2 + 2, y + HEAD_ROW_HEIGHT - 1, HEAD_BG);
        for (Col c : TOTAL_COLS) {
            String head = c.header;
            int colX = x + c.x;
            int textColor = c.iconColor;
            // 当前排序列加箭头
            if (c.sortKey == sortBy) {
                head = head + (sortAscending ? "\u25b2" : "\u25bc");
                textColor = TEXT_LIGHT_GOLD;
            }
            int hw = tr.getWidth(head);
            int drawX = c.rightAlign ? colX + c.w - hw : colX;
            ctx.drawTextWithShadow(tr, head, drawX, y + 3, textColor);
        }
        return HEAD_ROW_HEIGHT;
    }

    private int renderTotalRows(DrawContext ctx, TextRenderer tr, int x, int yStart) {
        StatsTableData.Total data = StatsTableData.buildTotal(sortBy, sortAscending);
        UUID self = StatsTableData.selfUuid();
        int y = yStart;
        for (StatsTableData.PlayerRow row : data.rows()) {
            boolean isSelf = row.uuid().equals(self);
            boolean offline = StatsTableData.isOffline(row.uuid());
            if (isSelf) {
                ctx.fill(x - 2, y - 1, x + PANEL_W - PANEL_PAD_X * 2 + 2, y + ROW_HEIGHT - 1, SELF_ROW_BG);
            }
            int textCol = offline ? TEXT_OFFLINE : (isSelf ? TEXT_GOLD : TEXT_WHITE);

            // 头像
            drawHead(ctx, row.uuid(), x + 2, y);
            // 玩家名
            String displayName = offline ? (row.name() + " [\u79bb\u7ebf]") : row.name();
            ctx.drawText(tr, displayName, x + TOTAL_COLS[0].x, y + 2, textCol, true);
            // 数字列
            drawRightAligned(ctx, tr, compact(row.dealt()),     x + TOTAL_COLS[1].x, y + 2, TOTAL_COLS[1].w, textCol);
            drawRightAligned(ctx, tr, compact(row.taken()),     x + TOTAL_COLS[2].x, y + 2, TOTAL_COLS[2].w, textCol);
            drawRightAligned(ctx, tr, Integer.toString(row.kills()),     x + TOTAL_COLS[3].x, y + 2, TOTAL_COLS[3].w, textCol);
            drawRightAligned(ctx, tr, Integer.toString(row.bossKills()), x + TOTAL_COLS[4].x, y + 2, TOTAL_COLS[4].w, offline ? TEXT_OFFLINE : TEXT_PURPLE);
            drawRightAligned(ctx, tr, Integer.toString(row.assists()),   x + TOTAL_COLS[5].x, y + 2, TOTAL_COLS[5].w, textCol);
            drawRightAligned(ctx, tr, StatsTableData.formatDuration(row.durationMs()),
                                                                  x + TOTAL_COLS[6].x, y + 2, TOTAL_COLS[6].w, textCol);
            y += ROW_HEIGHT;
        }
        return y - yStart;
    }

    private void drawTotalFooter(DrawContext ctx, TextRenderer tr, int x, int y) {
        StatsTableData.Total data = StatsTableData.buildTotal(sortBy, sortAscending);
        StatsTableData.PlayerRow t = data.teamSum();
        ctx.fill(x - 2, y - 1, x + PANEL_W - PANEL_PAD_X * 2 + 2, y + ROW_HEIGHT - 1, FOOT_BG);
        int color = TEXT_LIGHT_GOLD;
        ctx.drawText(tr, t.name(), x + TOTAL_COLS[0].x, y + 2, color, true);
        drawRightAligned(ctx, tr, compact(t.dealt()),     x + TOTAL_COLS[1].x, y + 2, TOTAL_COLS[1].w, color);
        drawRightAligned(ctx, tr, compact(t.taken()),     x + TOTAL_COLS[2].x, y + 2, TOTAL_COLS[2].w, color);
        drawRightAligned(ctx, tr, Integer.toString(t.kills()),     x + TOTAL_COLS[3].x, y + 2, TOTAL_COLS[3].w, color);
        drawRightAligned(ctx, tr, Integer.toString(t.bossKills()), x + TOTAL_COLS[4].x, y + 2, TOTAL_COLS[4].w, color);
        drawRightAligned(ctx, tr, Integer.toString(t.assists()),   x + TOTAL_COLS[5].x, y + 2, TOTAL_COLS[5].w, color);
        drawRightAligned(ctx, tr, StatsTableData.formatDuration(t.durationMs()),
                                                              x + TOTAL_COLS[6].x, y + 2, TOTAL_COLS[6].w, color);
    }

    // =========================================================================
    //  分关表
    // =========================================================================
    private int drawStageHeader(DrawContext ctx, TextRenderer tr, int x, int y) {
        ctx.fill(x - 2, y - 1, x + PANEL_W - PANEL_PAD_X * 2 + 2, y + HEAD_ROW_HEIGHT - 1, HEAD_BG);
        for (Col c : STAGE_COLS) {
            String head = c.header;
            int colX = x + c.x;
            int hw = tr.getWidth(head);
            int drawX = c.rightAlign ? colX + c.w - hw : colX;
            ctx.drawTextWithShadow(tr, head, drawX, y + 3, c.iconColor);
        }
        return HEAD_ROW_HEIGHT;
    }

    private int renderStageRows(DrawContext ctx, TextRenderer tr, int x, int yStart) {
        StatsTableData.Stage data = StatsTableData.buildStage();
        UUID self = StatsTableData.selfUuid();
        int y = yStart;
        if (data.blocks().isEmpty()) {
            ctx.drawText(tr, Text.literal("\u5c1a\u65e0\u6570\u636e \u00b7 \u8fdb\u5165\u7b2c\u4e00\u5173\u540e\u5f00\u59cb\u7edf\u8ba1"),
                    x, y + 8, TEXT_GREY, false);
            return 24;
        }
        for (StatsTableData.StageBlock block : data.blocks()) {
            // 关卡头部行：在第一玩家行内合并显示"层 / 关卡"
            int blockTop = y;
            for (int i = 0; i < block.rows().size(); i++) {
                StatsTableData.PlayerRow row = block.rows().get(i);
                boolean isSelf = row.uuid().equals(self);
                boolean offline = StatsTableData.isOffline(row.uuid());
                int textCol = offline ? TEXT_OFFLINE : (isSelf ? TEXT_GOLD : TEXT_WHITE);

                // 进行中关：底纹淡金（整组）
                if (block.inProgress()) {
                    ctx.fill(x - 2, y - 1, x + PANEL_W - PANEL_PAD_X * 2 + 2, y + ROW_HEIGHT - 1, CURRENT_BG);
                }
                if (isSelf) {
                    ctx.fill(x - 2, y - 1, x + PANEL_W - PANEL_PAD_X * 2 + 2, y + ROW_HEIGHT - 1, SELF_ROW_BG);
                }

                // 同关多人时仅首行画 层 / 关卡
                if (i == 0) {
                    String layerText = block.tierFloorLabel() + (block.inProgress() ? " \u2b50" : "");
                    ctx.drawText(tr, layerText, x + STAGE_COLS[0].x, y + 2, TEXT_LIGHT_GOLD, true);
                    String name = block.localizedName();
                    if (name.length() > 12) name = name.substring(0, 11) + "\u2026";
                    if (block.inProgress()) name = name + " \u2606";
                    ctx.drawText(tr, name, x + STAGE_COLS[1].x, y + 2, TEXT_LIGHT_GOLD, true);
                }
                // 头像 + 名字
                drawHead(ctx, row.uuid(), x + STAGE_COLS[2].x - 10, y);
                String displayName = offline ? (row.name() + " [\u79bb\u7ebf]") : row.name();
                ctx.drawText(tr, displayName, x + STAGE_COLS[2].x, y + 2, textCol, true);
                // 数字
                drawRightAligned(ctx, tr, compact(row.dealt()),    x + STAGE_COLS[3].x, y + 2, STAGE_COLS[3].w, textCol);
                drawRightAligned(ctx, tr, compact(row.taken()),    x + STAGE_COLS[4].x, y + 2, STAGE_COLS[4].w, textCol);
                drawRightAligned(ctx, tr, Integer.toString(row.kills()),  x + STAGE_COLS[5].x, y + 2, STAGE_COLS[5].w, textCol);
                drawRightAligned(ctx, tr, Integer.toString(row.assists()),x + STAGE_COLS[6].x, y + 2, STAGE_COLS[6].w, textCol);
                drawRightAligned(ctx, tr, StatsTableData.formatDuration(row.durationMs()),
                                                                       x + STAGE_COLS[7].x, y + 2, STAGE_COLS[7].w, textCol);
                y += ROW_HEIGHT;
            }
            // 分组之间留 2 像素分隔线
            ctx.fill(x - 2, y, x + PANEL_W - PANEL_PAD_X * 2 + 2, y + 1, LINE_COLOR);
            y += 3;

            // 进行中组金边
            if (block.inProgress()) {
                ctx.drawBorder(x - 3, blockTop - 2,
                        PANEL_W - PANEL_PAD_X * 2 + 5,
                        (y - blockTop) + 2,
                        TEXT_GOLD);
            }
        }
        return y - yStart;
    }

    // =========================================================================
    //  通用工具
    // =========================================================================
    private static String compact(long v) { return TeammateStatsLine.compact(v); }

    private static void drawRightAligned(DrawContext ctx, TextRenderer tr, String text, int colX, int y, int colW, int color) {
        int tw = tr.getWidth(text);
        ctx.drawText(tr, text, colX + colW - tw, y, color, true);
    }

    /** 画 8x8 玩家头（直接复用 PlayerListEntry 皮肤纹理）。 */
    private void drawHead(DrawContext ctx, UUID uuid, int x, int y) {
        if (client == null || client.getNetworkHandler() == null) {
            ctx.fill(x, y, x + HEAD_SIZE, y + HEAD_SIZE, 0xFF808080);
            return;
        }
        PlayerListEntry entry = null;
        for (var e : client.getNetworkHandler().getPlayerList()) {
            if (e.getProfile().getId().equals(uuid)) { entry = e; break; }
        }
        if (entry == null) {
            ctx.fill(x, y, x + HEAD_SIZE, y + HEAD_SIZE, 0xFF808080);
            return;
        }
        SkinTextures sk = entry.getSkinTextures();
        Identifier tex = sk.texture();
        ctx.drawTexture(RenderLayer::getGuiTextured, tex, x, y, 8.0f, 8.0f, HEAD_SIZE, HEAD_SIZE, 64, 64);
        ctx.drawTexture(RenderLayer::getGuiTextured, tex, x, y, 40.0f, 8.0f, HEAD_SIZE, HEAD_SIZE, 64, 64);
    }

    // =========================================================================
    //  输入
    // =========================================================================
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mx, my, button);
        // Tab
        if (tabClick(mx, my)) return true;
        // 总表列头排序
        if (tab == Tab.TOTAL) {
            int panelX = (this.width - PANEL_W) / 2;
            int headerY = headerY();
            if (my >= headerY && my < headerY + HEAD_ROW_HEIGHT) {
                int colBaseX = panelX + PANEL_PAD_X;
                for (Col c : TOTAL_COLS) {
                    if (c.sortKey == null) continue;
                    if (mx >= colBaseX + c.x && mx < colBaseX + c.x + c.w) {
                        if (sortBy == c.sortKey) sortAscending = !sortAscending;
                        else { sortBy = c.sortKey; sortAscending = false; }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private int headerY() {
        return 20 + PANEL_PAD_Y + 14 + TAB_H + 6;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollY = Math.max(0, scrollY - verticalAmount * 12);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_N || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public static boolean isOpen(MinecraftClient mc) {
        return mc != null && mc.currentScreen instanceof StatsTableScreen;
    }
}
