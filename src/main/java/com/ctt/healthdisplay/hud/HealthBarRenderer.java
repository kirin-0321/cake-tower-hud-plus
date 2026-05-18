package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.client.ClientDamageProbe;
import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.health.HealthData;
import com.ctt.healthdisplay.health.StatsData;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.UUID;

public class HealthBarRenderer {

    private static final Identifier TOWER_TOKEN_TEXTURE = Identifier.of("ctt-health-display", "textures/custom/tower_token.png");
    private static final int COIN_ICON_SIZE = 9;

    // 果冻斯旺动量图标，直接复用地图资源包里 Swan Jelly 的 "air_dash" 贴图（16x16 红色双箭头）。
    // 与 Coins 图标渲染规模保持一致：屏幕上画成 9x9，匹配 8px 文字行基线。
    // 纹理本身保持原色（不使用 setShaderColor 染色），仅文字采用粉色 light_purple。
    private static final Identifier VELOCITY_ICON_TEXTURE = Identifier.of("ctt-health-display", "textures/custom/velocity_icon.png");
    private static final int VELOCITY_ICON_SIZE = 9;
    private static final int VELOCITY_ICON_SRC = 16;

    private static final int BAR_WIDTH = 81;
    private static final int BAR_HEIGHT = 5;

    private static final int MATE_BAR_WIDTH = 80;
    private static final int MATE_BAR_HEIGHT = 7;
    private static final int HEAD_SIZE = 8;
    private static final int MATE_ENTRY_SPACING = 20;
    private static final int H_ENTRY_WIDTH = 100;
    // v6.6 · M3 嵌入式 HUD：每个 stats 行（关: / 局:）在 bar 下方占 9px（8 字号 + 1 padding）。
    private static final int STATS_ROW_HEIGHT = 9;

    private static final int HP_FULL = 0xFFE84040;
    private static final int HP_HIGH = 0xFFD83030;
    private static final int HP_MID  = 0xFFCC6620;
    private static final int HP_LOW  = 0xFFAA1515;

    private static final int MANA_FULL = 0xFF3090DD;
    private static final int MANA_HIGH = 0xFF2878C0;
    private static final int MANA_MID  = 0xFF2060A0;
    private static final int MANA_LOW  = 0xFF184880;

    // Joey 鲜血条渐变（对齐截图里 bossbar 上 (Blood 100/100) 的深红背景色系）。
    // 主 HP 条是亮红 (E84040)；这里特意压暗到 dark_red 色域，一眼和 HP 区分开。
    private static final int BLOOD_FULL = 0xFFB00020;
    private static final int BLOOD_HIGH = 0xFF8B0016;
    private static final int BLOOD_MID  = 0xFF6B000F;
    private static final int BLOOD_LOW  = 0xFF4A0009;

    private static final int BAR_BORDER = 0xA0000000;
    private static final int EMPTY_FILL = 0x50181818;

    private static final int COLOR_RED_HEART   = 0xC0E84040;
    private static final int COLOR_SOUL_HEART  = 0xB0DDCC22;
    private static final int COLOR_BLACK_HEART = 0xB0550088;
    private static final int COLOR_BLUE_HEART  = 0xB04488EE;

    private static final int TEXT_WHITE  = 0xFFFFFFFF;
    private static final int TEXT_RED    = 0xFFFF6666;
    private static final int TEXT_YELLOW = 0xFFFFDD44;
    private static final int TEXT_GREEN  = 0xFF66FF66;
    private static final int TEXT_AQUA   = 0xFF55DDFF;
    // v5.3.3：自己在队友面板里的名字色，金色 (§6 gold)，和真队友（白）拉开区分。
    private static final int TEXT_GOLD   = 0xFFFFAA00;
    // 果冻斯旺 (ClassPassive=14) 动量顶行文字颜色。取 bossbar 原生色 light_purple (#FF55FF)，与 HUD 语义同步。
    private static final int TEXT_PINK   = 0xFFFF55FF;
    private static final int ICON_HEART  = 0xFFFF4444;

    // v6.6 · M3 嵌入式 HUD 配色（设计 §5.3 · 图标固定色，数字按身份变色）
    private static final int STATS_PREFIX_GREY = 0xFFAAAAAA; // "关:" / "局:" 前缀
    private static final int STATS_DEALT_ICON  = 0xFFFFFFFF; // ⚔ 白
    private static final int STATS_TAKEN_ICON  = 0xFFCCCCCC; // ⛨ 灰白
    private static final int STATS_KILL_ICON   = 0xFFFF8080; // ☠ 淡红
    private static final int STATS_OFFLINE     = 0x80AAAAAA; // 离线整行 50% 灰

    private static long lastFlashTime = 0;
    private static boolean flashOn = false;

    private static StatsData currentStatsData;

    public static void render(DrawContext context, RenderTickCounter tickCounter, HealthData data, StatsData statsData) {
        currentStatsData = statsData;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.options.hudHidden) return;

        data.update();
        if (!data.isAvailable()) return;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int leftX = screenWidth / 2 - 91;
        int rightX = screenWidth / 2 + 10;
        int barY = screenHeight - 39;

        if (data.isPersonalAvailable()) {
            renderHealthBar(context, client.textRenderer, data, leftX, barY);

            // Joey (hasBlood) 优先：下方条改为深红鲜血条；上方 ✦<mana> 文字仍由 drawManaText 写出，不受影响。
            // 其他职业：走原来的法力条行为。
            if (data.hasBlood) {
                renderBloodBar(context, client.textRenderer, data, rightX, barY);
            } else if (data.hasMana()) {
                renderManaBar(context, client.textRenderer, data, rightX, barY);
            }
        }

        if (!data.teammates.isEmpty()) {
            renderTeammates(context, client.textRenderer, data, client, screenWidth, screenHeight);
        }
    }

    // ── Main health bar ──

    private static void renderHealthBar(DrawContext context, TextRenderer textRenderer, HealthData data, int x, int y) {
        // 分母分档（v5.1.12 起）：maxHP ≤ 50 锁 50；50 < maxHP ≤ 100 锁 100；maxHP > 100 用真实值。
        // 低 maxHP 时让血条更"灵敏"（每点血都占更长一截像素）；≤ 0 / 负数情况也走最低档 50，避免除零且显示合理。
        // 红/灵魂/黑/蓝四层彩色条共用同一个 effectiveMax（见 drawLayeredBar）。
        int effectiveMax = computeEffectiveMax(data.maxHP);

        context.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, BAR_BORDER);
        context.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, EMPTY_FILL);

        if (currentStatsData != null && currentStatsData.hasHeartData()) {
            drawLayeredBar(context, x, y, effectiveMax,
                    data.allHearts,
                    currentStatsData.getSoulHearts(),
                    currentStatsData.getBlackHearts(),
                    currentStatsData.getBlueHearts());
        } else {
            float pct = Math.min(100f, (float) data.allHearts / effectiveMax * 100f);
            int fillPx = Math.max(0, Math.min(BAR_WIDTH, Math.round(pct / 100f * BAR_WIDTH)));
            if (fillPx > 0) {
                int pctInt = Math.round(pct);
                int color = getHealthColor(pctInt);
                context.fill(x, y, x + fillPx, y + BAR_HEIGHT, color);
                int highlight = (color & 0x00FFFFFF) | 0x30000000;
                context.fill(x, y, x + fillPx, y + 1, highlight);
            }
        }

        drawHealthText(context, textRenderer, data, x, y);
    }

    private static void drawLayeredBar(DrawContext context, int x, int y, int maxHP,
                                       int allHearts, int soul, int black, int blue) {
        drawBarLayer(context, x, y, Math.min(100f, (float) allHearts / maxHP * 100f), COLOR_RED_HEART);
        drawBarLayer(context, x, y, Math.min(100f, (float) soul / maxHP * 100f), COLOR_SOUL_HEART);
        drawBarLayer(context, x, y, Math.min(100f, (float) black / maxHP * 100f), COLOR_BLACK_HEART);
        drawBarLayer(context, x, y, Math.min(100f, (float) blue / maxHP * 100f), COLOR_BLUE_HEART);
    }

    private static void drawBarLayer(DrawContext context, int x, int y, float pct, int color) {
        if (pct <= 0) return;
        int fillPx = Math.max(0, Math.min(BAR_WIDTH, Math.round(pct / 100f * BAR_WIDTH)));
        if (fillPx > 0) {
            context.fill(x, y, x + fillPx, y + BAR_HEIGHT, color);
        }
    }

    private static void renderManaBar(DrawContext context, TextRenderer textRenderer, HealthData data, int x, int y) {
        float manaPct = data.getDisplayMana() / Math.max(1, data.getDisplayMaxMana()) * 100f;
        drawBar(context, x, y, BAR_WIDTH, BAR_HEIGHT, manaPct, data.getManaPercent(), BarKind.MANA);
        drawManaText(context, textRenderer, data, x, y);
    }

    // Joey 专属：下方条渲染为鲜血条，上方文字行仍然走 drawManaText（Lives + Coins + ✦Mana/MaxMana），
    // 所以玩家仍然能在同一个位置看到法力值，只是下方槽位从"蓝条 + 蓝数字"换成了"深红条 + 红色 Blood 数字"。
    private static void renderBloodBar(DrawContext context, TextRenderer textRenderer, HealthData data, int x, int y) {
        int denom = Math.max(1, data.maxBlood);
        float bloodPct = (float) data.blood / denom * 100f;
        int rawPct = Math.max(0, Math.min(100, Math.round(bloodPct)));
        drawBar(context, x, y, BAR_WIDTH, BAR_HEIGHT, bloodPct, rawPct, BarKind.BLOOD);

        // 条内数值：直接写 blood/maxBlood，白字描边 + 黑阴影，排版与法力条保持一致。
        String bloodLabel = data.blood + "/" + data.maxBlood;
        int labelW = textRenderer.getWidth(bloodLabel);
        drawOutlinedText(context, textRenderer, bloodLabel, x + (BAR_WIDTH - labelW) / 2, y + (BAR_HEIGHT - 8) / 2, TEXT_WHITE);

        drawManaText(context, textRenderer, data, x, y);
    }

    // ── Teammate panel ──

    private static void renderTeammates(DrawContext context, TextRenderer textRenderer, HealthData data, MinecraftClient client, int screenWidth, int screenHeight) {
        ModConfig config = ModConfig.INSTANCE;
        int x = Math.max(0, Math.min(screenWidth - MATE_BAR_WIDTH, (int) (config.teammateX * screenWidth)));
        int y = Math.max(0, Math.min(screenHeight - MATE_ENTRY_SPACING, (int) (config.teammateY * screenHeight)));

        // v7.0.0 · P0 客户端探针：HUD 顶部聚合行（⚔ 全局 · ⚔ 当前关 ⚡ 5sDPS/s）。
        // 依据 CLIENT_SIDE_STATS_PROPOSAL §X "P0 客户端探针骨架"。
        // 仅在垂直布局 + 配置开关 + 探针有数据时绘制；绘制后 y 下移 STATS_ROW_HEIGHT 让队友行不重叠。
        if (config.clientDamageHudHeader && !config.horizontalLayout
                && ClientDamageProbe.INSTANCE.hasAnyData()) {
            drawCdpHeaderRow(context, textRenderer, x, y);
            y += STATS_ROW_HEIGHT;
        }

        // v6.6 · M3：水平布局下 stats 行被强制隐藏（横排队友本来就挤）。
        boolean statsAllowed = !config.horizontalLayout && config.embeddedHudMode != ModConfig.EMBED_OFF;

        for (HealthData.TeammateData mate : data.teammates) {
            int pct = mate.getPercent();

            // Row 1: Head + Name + Lives hearts
            drawPlayerHead(context, client, mate.name, x, y);

            int textX = x + HEAD_SIZE + 2;
            context.drawTextWithShadow(textRenderer, mate.name, textX, y, mate.isSelf ? TEXT_GOLD : TEXT_WHITE);

            {
                String livesStr = "\u2764" + mate.lives;
                int livesW = textRenderer.getWidth(livesStr);
                context.drawTextWithShadow(textRenderer, livesStr, x + MATE_BAR_WIDTH - livesW, y, TEXT_GREEN);
            }

            // Row 2: Health bar with HP text inside
            int barY = y + HEAD_SIZE + 1;
            // v8.4.0 · 服务端 TeamHeartsBroadcaster 推过四色心 + 客户端开关打开 → 走 layered，
            // 与玩家自己主血条 drawLayeredBar 视觉一致；否则旧 OVERFLOW_COLORS 单色多槽。
            if (ModConfig.INSTANCE.showTeammateLayeredHearts && mate.hasLayeredHearts()) {
                drawTeammateBarLayered(context, x, barY, MATE_BAR_WIDTH, MATE_BAR_HEIGHT, mate);
            } else {
                drawTeammateBar(context, x, barY, MATE_BAR_WIDTH, MATE_BAR_HEIGHT, mate.hp, mate.maxHP);
            }

            String hpStr = mate.maxHP > 0 ? mate.hp + "/" + mate.maxHP : String.valueOf(mate.hp);
            int hpW = textRenderer.getWidth(hpStr);
            drawOutlinedText(context, textRenderer, hpStr,
                    x + (MATE_BAR_WIDTH - hpW) / 2,
                    barY + (MATE_BAR_HEIGHT - 8) / 2,
                    TEXT_WHITE);

            // Row 3 / 4: v6.6 · M3 嵌入式 HUD stats 行
            int statsRows = 0;
            if (statsAllowed) {
                UUID uuid = resolveUuid(client, mate.name);
                boolean offline = (client.getNetworkHandler() == null
                        || client.getNetworkHandler().getPlayerListEntry(mate.name) == null);
                int rowY = barY + MATE_BAR_HEIGHT + 1;

                // v6.6.6 · 自动隐藏空行：配置允许该行 + 该行有数据（dealt/taken/kills/assists 任一非 0）才画。
                // 大厅 / 第一关刚开打但还没造成伤害 / 上一关被清空（重置 stats）等场景下，
                // 自动收回行高，队友面板回到只有名字 + HP 条的紧凑形态；其余场景按配置原样显示。
                // v6.6.6 · 自动隐藏空行：配置允许该行 + 该行有数据（dealt/taken/kills/assists 任一非 0）才画。
                // 大厅 / 第一关刚开打但还没造成伤害 / 上一关被清空（重置 stats）等场景下，
                // 自动收回行高，队友面板回到只有名字 + HP 条的紧凑形态；其余场景按配置原样显示。
                // v6.6.7 · 去掉 "关:" / "局:" 前缀（用户决策：图标已足够区分语义，节省横向空间）。
                int mode = config.embeddedHudMode;
                if (mode == ModConfig.EMBED_ONLY_STAGE || mode == ModConfig.EMBED_BOTH) {
                    TeammateStatsLine.Line line = TeammateStatsLine.ofStage(uuid);
                    if (line.hasData()) {
                        // v6.6.7 · 关行末尾追加 · <dps>/s（DPS=0 也画占位，保持视觉宽度稳定）
                        drawStatsRow(context, textRenderer, "", line, x, rowY, mate.isSelf, offline, true);
                        rowY += STATS_ROW_HEIGHT;
                        statsRows++;
                    }
                }
                if (mode == ModConfig.EMBED_ONLY_SESSION || mode == ModConfig.EMBED_BOTH) {
                    TeammateStatsLine.Line line = TeammateStatsLine.ofSession(uuid);
                    if (line.hasData()) {
                        drawStatsRow(context, textRenderer, "", line, x, rowY, mate.isSelf, offline, false);
                        statsRows++;
                    }
                }
            }

            if (config.horizontalLayout) {
                x += H_ENTRY_WIDTH;
            } else {
                y += MATE_ENTRY_SPACING + statsRows * STATS_ROW_HEIGHT;
            }
        }
    }

    /**
     * v6.6 · M3：通过玩家名字解析 UUID（HealthData.TeammateData 不含 UUID，用 PlayerListEntry 反查）。
     * 玩家断线 / PlayerList 缺项时返回 null，stats 行会显示 0。
     */
    private static UUID resolveUuid(MinecraftClient client, String name) {
        if (client.getNetworkHandler() == null) return null;
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(name);
        return entry == null ? null : entry.getProfile().getId();
    }

    /**
     * v6.6 · M3：画一行 stats 行 {@code [⚔ 12.3k ⛨ 4.5k ☠ 7/3]}。
     *
     * <ul>
     *   <li>前缀（v6.6.7 默认空字符串；保留参数以便未来回归"关:/局:"标签）灰色</li>
     *   <li>图标 ⚔/⛨/☠ 固定色</li>
     *   <li>数字本体：自己 → 金；离线 → 半透明灰；普通队友 → 白</li>
     *   <li>行宽固定贴齐 {@link #MATE_BAR_WIDTH}，超长会被 {@code ⚔}/{@code ⛨}/{@code ☠} 自然换行截断
     *       （compact 数字格式确保 4-5 字符以内 → 实测 80px 足够容纳两段 4 位 + 一段 K/A）</li>
     *   <li>v6.6.7 · {@code showDps=true}（关行）末尾追加 {@code · <dps>/s}；DPS=0 也画占位
     *       保持视觉宽度稳定（用户拍板：方案 b）。session 行传 false 不画。</li>
     * </ul>
     */
    private static void drawStatsRow(DrawContext context, TextRenderer textRenderer,
                                     String prefix, TeammateStatsLine.Line line,
                                     int x, int y, boolean isSelf, boolean offline,
                                     boolean showDps) {
        int valueColor = offline ? STATS_OFFLINE : (isSelf ? TEXT_GOLD : TEXT_WHITE);
        int prefixColor = offline ? STATS_OFFLINE : STATS_PREFIX_GREY;
        int dealtIcon   = offline ? STATS_OFFLINE : STATS_DEALT_ICON;
        int takenIcon   = offline ? STATS_OFFLINE : STATS_TAKEN_ICON;
        int killIcon    = offline ? STATS_OFFLINE : STATS_KILL_ICON;

        int curX = x;
        // 前缀（v6.6.7 起默认为空 → 跳过绘制和 2px 间距，节省横向空间）
        if (prefix != null && !prefix.isEmpty()) {
            context.drawTextWithShadow(textRenderer, prefix, curX, y, prefixColor);
            curX += textRenderer.getWidth(prefix) + 2;
        }

        // ⚔ <dealt>
        String swordIcon = "\u2694";
        context.drawTextWithShadow(textRenderer, swordIcon, curX, y, dealtIcon);
        curX += textRenderer.getWidth(swordIcon) + 1;
        String dealtStr = TeammateStatsLine.compact(line.dealt());
        context.drawTextWithShadow(textRenderer, dealtStr, curX, y, valueColor);
        curX += textRenderer.getWidth(dealtStr) + 3;

        // ⛨ <taken>（用 U+26E8 黑十字盾，更通用的字体覆盖；与设计 §5.3 一致语义）
        String shieldIcon = "\u26E8";
        context.drawTextWithShadow(textRenderer, shieldIcon, curX, y, takenIcon);
        curX += textRenderer.getWidth(shieldIcon) + 1;
        String takenStr = TeammateStatsLine.compact(line.taken());
        context.drawTextWithShadow(textRenderer, takenStr, curX, y, valueColor);
        curX += textRenderer.getWidth(takenStr) + 3;

        // ☠ <K>/<A>
        String skullIcon = "\u2620";
        context.drawTextWithShadow(textRenderer, skullIcon, curX, y, killIcon);
        curX += textRenderer.getWidth(skullIcon) + 1;
        String kaStr = line.kills() + "/" + line.assists();
        context.drawTextWithShadow(textRenderer, kaStr, curX, y, valueColor);
        curX += textRenderer.getWidth(kaStr) + 3;

        // v6.6.7 · 关行末尾 · <dps>/s（最近 5 秒造成伤害量 / 5）
        // DPS=0 也画占位，保持视觉宽度稳定（与设计决策 b 一致）。
        if (showDps) {
            String sep = "\u00b7";  // 中点
            context.drawTextWithShadow(textRenderer, sep, curX, y, prefixColor);
            curX += textRenderer.getWidth(sep) + 1;
            String dpsStr = TeammateStatsLine.compact(line.dps()) + "/s";
            context.drawTextWithShadow(textRenderer, dpsStr, curX, y, valueColor);
        }
    }

    /**
     * v7.0.0 · P0 客户端探针 HUD 顶部聚合行。
     * <p>v7.1.0 · 布局：{@code ⚔ <本层伤害> ☠ <本层击杀> ⚡ <5sDPS>/s}。
     * <ul>
     *   <li>⚔ 本层伤害：{@link ClientDamageProbe#getStageTotal()}</li>
     *   <li>☠ 本层击杀：{@link ClientDamageProbe#getStageKills()}（始终显示）</li>
     *   <li>⚡ 5s DPS：{@link ClientDamageProbe#getRecent5sDps()}</li>
     * </ul>
     * 全局数字仍可在 K 表 / 面板查看。绘制配色与"特殊聚合行"语义对齐：⚔ 金、☠ 淡红、⚡ 黄。
     */
    private static void drawCdpHeaderRow(DrawContext context, TextRenderer tr, int x, int y) {
        ClientDamageProbe probe = ClientDamageProbe.INSTANCE;
        String stageStr  = TeammateStatsLine.compact(probe.getStageTotal());
        String killStr   = TeammateStatsLine.compact(probe.getStageKills());
        String dpsStr    = TeammateStatsLine.compact(probe.getRecent5sDps()) + "/s";

        int cx = x;
        // ⚔ <本层伤害>
        String swordIcon = "\u2694";
        context.drawTextWithShadow(tr, swordIcon, cx, y, STATS_DEALT_ICON);
        cx += tr.getWidth(swordIcon) + 1;
        context.drawTextWithShadow(tr, stageStr, cx, y, TEXT_GOLD);
        cx += tr.getWidth(stageStr) + 2;

        // ☠ <本层击杀>
        String killIcon = "\u2620";
        context.drawTextWithShadow(tr, killIcon, cx, y, STATS_KILL_ICON);
        cx += tr.getWidth(killIcon) + 1;
        context.drawTextWithShadow(tr, killStr, cx, y, TEXT_GOLD);
        cx += tr.getWidth(killStr) + 2;

        // ⚡ <DPS>/s
        String boltIcon = "\u26A1";
        context.drawTextWithShadow(tr, boltIcon, cx, y, TEXT_YELLOW);
        cx += tr.getWidth(boltIcon);
        context.drawTextWithShadow(tr, dpsStr, cx, y, TEXT_YELLOW);
    }

    private static void drawPlayerHead(DrawContext context, MinecraftClient client, String playerName, int x, int y) {
        try {
            if (client.getNetworkHandler() == null) {
                drawFallbackHead(context, x, y);
                return;
            }
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(playerName);
            if (entry == null) {
                drawFallbackHead(context, x, y);
                return;
            }

            SkinTextures textures = entry.getSkinTextures();
            Identifier texture = textures.texture();

            context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, 8.0f, 8.0f, HEAD_SIZE, HEAD_SIZE, 64, 64);
            context.drawTexture(RenderLayer::getGuiTextured, texture, x, y, 40.0f, 8.0f, HEAD_SIZE, HEAD_SIZE, 64, 64);
        } catch (Exception e) {
            drawFallbackHead(context, x, y);
        }
    }

    private static void drawFallbackHead(DrawContext context, int x, int y) {
        context.fill(x, y, x + HEAD_SIZE, y + HEAD_SIZE, 0xFF808080);
    }

    // ── Shared bar drawing ──

    private static void drawTeammateBar(DrawContext context, int x, int y, int w, int h, int hp, int maxHP) {
        context.fill(x - 1, y - 1, x + w + 1, y + h + 1, BAR_BORDER);
        context.fill(x, y, x + w, y + h, EMPTY_FILL);

        if (hp <= 0 || maxHP <= 0) return;

        int topIdx = (hp - 1) / maxHP;
        int hpInTop = hp - topIdx * maxHP;
        int topFillPx = Math.max(1, Math.min(w, Math.round((float) hpInTop / maxHP * w)));

        if (topIdx > 0 && topFillPx < w) {
            int bgColorIdx = Math.min(topIdx - 1, OVERFLOW_COLORS.length - 1);
            context.fill(x + topFillPx, y, x + w, y + h, OVERFLOW_COLORS[bgColorIdx]);
        }

        int topColorIdx = Math.min(topIdx, OVERFLOW_COLORS.length - 1);
        context.fill(x, y, x + topFillPx, y + h, OVERFLOW_COLORS[topColorIdx]);
    }

    /**
     * v8.4.0 · 队友 4 色心叠加版血条（与玩家自己主血条 {@link #drawLayeredBar} 视觉一致）。
     *
     * <p>仅在 {@link com.ctt.healthdisplay.client.ClientTeamHeartsCache} fresh 且服务端
     * 推过该玩家的 soul/black/blue 之一非零时调用。无 mod 服务端 / 队友未参与本局时
     * {@code mate.hasLayeredHearts()} 返回 false，渲染入口会退回 {@link #drawTeammateBar}
     * 老路径。
     *
     * <p>层级顺序 = {@code drawLayeredBar} 同款 = datapack {@code view_stats.mcfunction} 同款：
     * Red → Soul → Black → Blue。每层独立 {@code min(100%, value/maxHP)} 填充。
     */
    private static void drawTeammateBarLayered(DrawContext context, int x, int y, int w, int h,
                                               HealthData.TeammateData mate) {
        context.fill(x - 1, y - 1, x + w + 1, y + h + 1, BAR_BORDER);
        context.fill(x, y, x + w, y + h, EMPTY_FILL);

        if (mate.maxHP <= 0) return;

        // mate.hp 来自 bossbar（vanilla 实时），等价于 RedHearts 当前层 —— 不用 mate.redHeartsServer。
        // mate.redHeartsServer 仅在 hasLayeredHearts() 判定 / 未来扩展时用得到。
        drawBarLayer(context, x, y, w, h, Math.min(100f, (float) mate.hp          / mate.maxHP * 100f), COLOR_RED_HEART);
        drawBarLayer(context, x, y, w, h, Math.min(100f, (float) mate.soulHearts  / mate.maxHP * 100f), COLOR_SOUL_HEART);
        drawBarLayer(context, x, y, w, h, Math.min(100f, (float) mate.blackHearts / mate.maxHP * 100f), COLOR_BLACK_HEART);
        drawBarLayer(context, x, y, w, h, Math.min(100f, (float) mate.blueHearts  / mate.maxHP * 100f), COLOR_BLUE_HEART);
    }

    /** v8.4.0 · 通用尺寸版本（替代旧 {@link #drawBarLayer(DrawContext, int, int, float, int)}
     *  的硬编码 BAR_WIDTH/BAR_HEIGHT 写死），让 layered 渲染可重用于队友条任意宽高。 */
    private static void drawBarLayer(DrawContext context, int x, int y, int w, int h, float pct, int color) {
        if (pct <= 0) return;
        int fillPx = Math.max(0, Math.min(w, Math.round(pct / 100f * w)));
        if (fillPx > 0) {
            context.fill(x, y, x + fillPx, y + h, color);
        }
    }

    private enum BarKind { HEALTH, MANA, BLOOD }

    private static void drawBar(DrawContext context, int x, int y, int w, int h, float displayPct, int rawPct, BarKind kind) {
        context.fill(x - 1, y - 1, x + w + 1, y + h + 1, BAR_BORDER);
        context.fill(x, y, x + w, y + h, EMPTY_FILL);

        int fillPx = Math.max(0, Math.min(w, Math.round(displayPct / 100f * w)));
        if (fillPx > 0) {
            int barColor = switch (kind) {
                case HEALTH -> getHealthColor(rawPct);
                case MANA -> getManaColor(rawPct);
                case BLOOD -> getBloodColor(rawPct);
            };
            context.fill(x, y, x + fillPx, y + h, barColor);
            int highlight = (barColor & 0x00FFFFFF) | 0x30000000;
            context.fill(x, y, x + fillPx, y + 1, highlight);
        }
    }

    // ── Text overlays ──

    private static void drawHealthText(DrawContext context, TextRenderer textRenderer, HealthData data, int barX, int barY) {
        int pct = data.getEffectivePercent();
        int textY = barY - 10;

        if (currentStatsData != null && currentStatsData.hasHeartData()) {
            drawMultiHeartText(context, textRenderer, data, barX, textY);
        } else {
            int heartColor = pct <= 25 ? TEXT_RED : pct <= 50 ? TEXT_YELLOW : ICON_HEART;
            int textColor;
            if (pct <= 25) {
                long now = Util.getMeasuringTimeMs();
                if (now - lastFlashTime > 400) { flashOn = !flashOn; lastFlashTime = now; }
                textColor = flashOn ? 0xFFFF2222 : TEXT_RED;
            } else if (pct <= 50) {
                textColor = TEXT_YELLOW;
            } else {
                textColor = TEXT_WHITE;
            }

            String heart = "\u2764";
            context.drawTextWithShadow(textRenderer, heart, barX, textY, heartColor);
            int heartW = textRenderer.getWidth(heart);

            if (data.hasRawValues()) {
                String hpValue = " " + data.allHearts + "/" + data.maxHP;
                context.drawTextWithShadow(textRenderer, hpValue, barX + heartW, textY, textColor);
            } else {
                context.drawTextWithShadow(textRenderer, " " + pct + "%", barX + heartW, textY, textColor);
            }
        }

        if (data.hasRawValues()) {
            String barLabel = data.allHearts + "/" + data.maxHP;
            int labelW = textRenderer.getWidth(barLabel);
            drawOutlinedText(context, textRenderer, barLabel, barX + (BAR_WIDTH - labelW) / 2, barY + (BAR_HEIGHT - 8) / 2, TEXT_WHITE);
        }
    }

    private static void drawMultiHeartText(DrawContext context, TextRenderer textRenderer, HealthData data, int barX, int textY) {
        String heart = "\u2764";
        int curX = barX;

        int red = currentStatsData.getRedHearts();
        int soul = currentStatsData.getSoulHearts();
        int black = currentStatsData.getBlackHearts();
        int blue = currentStatsData.getBlueHearts();

        curX = drawHeartEntry(context, textRenderer, String.valueOf(red), heart, 0xFFFF4444, curX, textY);
        if (soul > 0) {
            curX = drawHeartEntry(context, textRenderer, String.valueOf(soul), heart, 0xFFFFDD44, curX, textY);
        }
        if (black > 0) {
            curX = drawHeartEntry(context, textRenderer, String.valueOf(black), heart, 0xFF7722AA, curX, textY);
        }
        if (blue > 0) {
            curX = drawHeartEntry(context, textRenderer, String.valueOf(blue), heart, 0xFF5599FF, curX, textY);
        }
    }

    private static int drawHeartEntry(DrawContext context, TextRenderer textRenderer,
                                      String value, String icon, int color, int x, int y) {
        context.drawTextWithShadow(textRenderer, value, x, y, color);
        int valW = textRenderer.getWidth(value);
        context.drawTextWithShadow(textRenderer, icon, x + valW, y, color);
        int iconW = textRenderer.getWidth(icon);
        return x + valW + iconW + 2;
    }

    private static final int[] OVERFLOW_COLORS = {
        0xFFE84040, // 1x red
        0xFFFF8800, // 2x orange
        0xFFFFDD00, // 3x yellow
        0xFF44CC44, // 4x green
        0xFF4488EE, // 5x blue
        0xFFAA44DD  // 6x+ purple
    };

    private static final int COLOR_COIN = 0xFFFFAA00;

    private static void drawManaText(DrawContext context, TextRenderer textRenderer, HealthData data, int barX, int barY) {
        int textY = barY - 10;
        int rightEdge = barX + BAR_WIDTH;

        // Lives: left-aligned, always visible
        String livesIcon = "\u2764";
        context.drawTextWithShadow(textRenderer, livesIcon, barX, textY, TEXT_GREEN);
        int iconW = textRenderer.getWidth(livesIcon);
        String livesVal = String.valueOf(data.lives);
        context.drawTextWithShadow(textRenderer, livesVal, barX + iconW, textY, TEXT_GREEN);
        int livesEndX = barX + iconW + textRenderer.getWidth(livesVal);

        // 右上角：
        //   · 默认：`✦mana/maxMana`（AQUA，纯文本，✦ 图标以字符形式嵌在文本里）
        //   · 果冻斯旺 (hasVelocity)：左边一张 9x9 air_dash 贴图（原色不染色），右边粉色数值 `velocity/maxVelocity`
        // 双者都和左侧 `❤lives` 保持"图标 + 数值"的左右对称视觉，宽度由对应分支独立计算后整体右对齐。
        int manaX;
        if (data.hasVelocity) {
            String velText = data.velocity + "/" + data.maxVelocity;
            int velTextW = textRenderer.getWidth(velText);
            int totalW = VELOCITY_ICON_SIZE + 1 + velTextW;
            int iconX = rightEdge - totalW;
            context.draw();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            context.drawTexture(RenderLayer::getGuiTextured, VELOCITY_ICON_TEXTURE,
                    iconX, textY - 1, 0, 0,
                    VELOCITY_ICON_SIZE, VELOCITY_ICON_SIZE,
                    VELOCITY_ICON_SRC, VELOCITY_ICON_SRC,
                    VELOCITY_ICON_SRC, VELOCITY_ICON_SRC);
            context.draw();
            context.drawTextWithShadow(textRenderer, velText, iconX + VELOCITY_ICON_SIZE + 1, textY, TEXT_PINK);
            manaX = iconX;
        } else {
            String rightText = "\u2726" + data.mana + "/" + data.maxMana;
            int manaW = textRenderer.getWidth(rightText);
            manaX = rightEdge - manaW;
            context.drawTextWithShadow(textRenderer, rightText, manaX, textY, TEXT_AQUA);
        }

        // Coins: centered between lives and mana
        String coinVal = String.valueOf(data.coins);
        int valW = textRenderer.getWidth(coinVal);
        int totalW = COIN_ICON_SIZE + 1 + valW;
        int coinX = (livesEndX + manaX - totalW) / 2;
        context.draw();
        RenderSystem.setShaderColor(1.0f, 0.67f, 0.0f, 1.0f);
        context.drawTexture(RenderLayer::getGuiTextured, TOWER_TOKEN_TEXTURE,
                coinX, textY - 1, 0, 0, COIN_ICON_SIZE, COIN_ICON_SIZE, COIN_ICON_SIZE, COIN_ICON_SIZE);
        context.draw();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        context.drawTextWithShadow(textRenderer, coinVal, coinX + COIN_ICON_SIZE + 1, textY, COLOR_COIN);

        // 条内数值：Joey 时由 renderBloodBar 画 blood/maxBlood，这里直接跳过避免叠字（85/5 0/0 的 bug）。
        if (!data.hasBlood) {
            String barLabel = data.mana + "/" + data.maxMana;
            int labelW = textRenderer.getWidth(barLabel);
            drawOutlinedText(context, textRenderer, barLabel, barX + (BAR_WIDTH - labelW) / 2, barY + (BAR_HEIGHT - 8) / 2, TEXT_WHITE);
        }
    }

    // ── Utility ──

    private static void drawOutlinedText(DrawContext context, TextRenderer textRenderer, String text, int x, int y, int color) {
        int shadow = 0xFF000000;
        context.drawText(textRenderer, text, x + 1, y, shadow, false);
        context.drawText(textRenderer, text, x - 1, y, shadow, false);
        context.drawText(textRenderer, text, x, y + 1, shadow, false);
        context.drawText(textRenderer, text, x, y - 1, shadow, false);
        context.drawText(textRenderer, text, x, y, color, false);
    }

    private static int computeEffectiveMax(int maxHP) {
        if (maxHP <= 50) return 50;
        if (maxHP <= 100) return 100;
        return maxHP;
    }

    private static int getHealthColor(int percent) {
        if (percent <= 25) return HP_LOW;
        if (percent <= 50) return interpolateColor(HP_LOW, HP_MID, (percent - 25) / 25f);
        if (percent <= 75) return interpolateColor(HP_MID, HP_HIGH, (percent - 50) / 25f);
        return interpolateColor(HP_HIGH, HP_FULL, (percent - 75) / 25f);
    }

    private static int getManaColor(int percent) {
        if (percent <= 25) return MANA_LOW;
        if (percent <= 50) return interpolateColor(MANA_LOW, MANA_MID, (percent - 25) / 25f);
        if (percent <= 75) return interpolateColor(MANA_MID, MANA_HIGH, (percent - 50) / 25f);
        return interpolateColor(MANA_HIGH, MANA_FULL, (percent - 75) / 25f);
    }

    private static int getBloodColor(int percent) {
        if (percent <= 25) return BLOOD_LOW;
        if (percent <= 50) return interpolateColor(BLOOD_LOW, BLOOD_MID, (percent - 25) / 25f);
        if (percent <= 75) return interpolateColor(BLOOD_MID, BLOOD_HIGH, (percent - 50) / 25f);
        return interpolateColor(BLOOD_HIGH, BLOOD_FULL, (percent - 75) / 25f);
    }

    private static int interpolateColor(int colorA, int colorB, float t) {
        t = Math.max(0, Math.min(1, t));
        int rA = (colorA >> 16) & 0xFF, gA = (colorA >> 8) & 0xFF, bA = colorA & 0xFF;
        int rB = (colorB >> 16) & 0xFF, gB = (colorB >> 8) & 0xFF, bB = colorB & 0xFF;
        int r = (int) (rA + (rB - rA) * t);
        int g = (int) (gA + (gB - gA) * t);
        int b = (int) (bA + (bB - bA) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
