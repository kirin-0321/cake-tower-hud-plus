package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.network.PlayerStatsPayload;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * v8.4.0 · 服务端"虚拟 ViewStats"构造器。
 *
 * <h2>职责</h2>
 * <p>给定一个 {@link ServerPlayerEntity}，原地读 scoreboard 并构造一份与
 * 地图 datapack {@code function misc/view_stats.mcfunction} 视觉等价的属性面板
 * （{@link PlayerStatsPayload}）。本类不发包 / 不缓存 / 不持状态——纯函数式。
 *
 * <h2>调用契约</h2>
 * <ul>
 *   <li>{@link #build} 仅可在<b>服务端线程</b>调用（依赖 {@link Scoreboard} 状态读取）。</li>
 *   <li>玩家未加入世界 / scoreboard 缺失 objective 时返回带空 lines 的 payload —— 客户端
 *       receiver 会用 lines.isEmpty() + 心数据全 0 判断"暂无属性数据"并展示 hint。</li>
 *   <li>对每次调用约 35 条 {@link ScoreboardReader#readOrZero}（每条 ~10 ns），
 *       4 人队 5 Hz ≈ 700 次/s ≈ 7 μs/s CPU 占用，可忽略。</li>
 * </ul>
 *
 * <h2>已还原 vs 跳过</h2>
 * <p><b>已还原</b>（v8.4.3 起完整覆盖 HUD 用户可见区）：4 色心 + CrackedHearts/PinkHearts +
 * NegMaxHealth + {@link ViewStatsRegistry#ENTRIES} 全部 ~33 条普通属性（含 v8.4.3 新增的
 * TowersRegen derived）+ SpeedAmplifier + SpeedRaw 条件行 + Size / Gravity 双向 icon +
 * CelestialKarma + {@link ViewStatsRegistry#STATUS_EFFECTS}（v8.4.3 新增的 30+ 个
 * tag-based 状态效果，如 Berserk/Burnt/Dizzy/Cursed/...，用
 * {@link Text#translatable(String)} 让客户端汉化包翻译为"癫狂/燃烧/眩晕/..."）+ #Skulls。
 *
 * <p><b>跳过</b>（玩家几乎不关心，且 HUD 渲染无意义）：datapack 顶部的
 * {@code "Your Stats:"} 标题 + {@code "Hover over an icon..."} 提示（HUD 不是 chat，没 hover）、
 * "Grass Toucher Check" 行、"Game time" 时间行（玩家自己看左下角时间足矣）。
 */
public final class ViewStatsBuilder {

    private ViewStatsBuilder() {}

    private static final TextColor GRAY  = TextColor.fromFormatting(Formatting.GRAY);
    private static final TextColor RED   = TextColor.fromFormatting(Formatting.RED);
    private static final TextColor AQUA  = TextColor.fromFormatting(Formatting.AQUA);
    private static final TextColor DARK_RED     = TextColor.fromFormatting(Formatting.DARK_RED);
    private static final TextColor LIGHT_PURPLE = TextColor.fromFormatting(Formatting.LIGHT_PURPLE);
    private static final TextColor SIZE_DOWN    = TextColor.fromRgb(0x5469ff);
    private static final TextColor SIZE_UP      = TextColor.fromRgb(0xff0005);
    private static final TextColor GRAVITY_UP   = TextColor.fromRgb(0xe854ff);
    private static final TextColor GRAVITY_DOWN = TextColor.fromRgb(0x9500ff);
    private static final TextColor CELESTIAL    = TextColor.fromRgb(0x892EFF);

    // v8.4.0 · 不再插入 datapack 的标题 "Your Stats:" + 提示 "Hover over an icon to see what it is"：
    //   1) HUD 渲染走 StatsRenderer 一行一行画，不是 chat —— hoverEvent 在静态文本上无意义；
    //   2) 标题+提示占用 HUD 顶部 2 行（~20 px）纯装饰空间，挤占属性数据可见区；
    //   3) StatsData.applyServerSnapshot 用 lines.isEmpty() 判定"暂无属性数据"
    //      → 玩家在大厅 / 未参与时直接显示 hint，比"标题+提示但 0 数据"更直观。
    //   4) datapack chat 路径（无服务端 mod 时客户端 fallback 仍发 /trigger ViewStats）
    //      仍由 StatsData.processMessage 解析含这两行的 chat 输出，行为不退化。

    /**
     * 服务端读 scoreboard 并构造完整 payload。
     *
     * <p>玩家离线 / world == null 时返回 lines 空 + 全零的 payload；
     * 客户端收到后会显示"暂无属性数据"hint，行为与本地 {@code StatsData} 一致。
     */
    public static PlayerStatsPayload build(ServerPlayerEntity player) {
        if (player == null || player.getServer() == null) {
            return new PlayerStatsPayload(
                    PlayerStatsPayload.CURRENT_VERSION, 0, 0, 0, 0, List.of());
        }
        Scoreboard sb = player.getServer().getScoreboard();
        if (sb == null) {
            return new PlayerStatsPayload(
                    PlayerStatsPayload.CURRENT_VERSION, 0, 0, 0, 0, List.of());
        }

        List<Text> lines = new ArrayList<>(48);

        // 1. 四色心：先读数值（payload 头字段 + 后面的心条 lines 都要）
        int red   = ScoreboardReader.readOrZero(sb, "RedHearts",   player);
        int soul  = ScoreboardReader.readOrZero(sb, "SoulHearts",  player);
        int black = ScoreboardReader.readOrZero(sb, "BlackHearts", player);
        int blue  = ScoreboardReader.readOrZero(sb, "BlueHearts",  player);
        for (ViewStatsRegistry.HeartEntry h : ViewStatsRegistry.HEARTS) {
            int val = switch (h.objective()) {
                case "RedHearts"   -> red;
                case "SoulHearts"  -> soul;
                case "BlackHearts" -> black;
                case "BlueHearts"  -> blue;
                default -> 0;
            };
            if (val >= 1) {
                lines.add(buildIconLine(val, "\u2764", h.color(), false));
            }
        }

        // 3. CrackedHearts (positive) / PinkHearts (= CrackedHearts × -1)
        int cracked = ScoreboardReader.readOrZero(sb, "CrackedHearts", player);
        if (cracked >= 1) {
            lines.add(buildIconLine(cracked, "\uE026", DARK_RED, false));
        } else if (cracked <= -1) {
            // PinkHearts 显示 |CrackedHearts|，icon 同 \uE026 但 light_purple
            lines.add(buildIconLine(-cracked, "\uE026", LIGHT_PURPLE, false));
        }

        // 4. NegMaxHealth（datapack 只有 1.. 分支）
        int negMax = ScoreboardReader.readOrZero(sb, "NegMaxHealth", player);
        if (negMax >= 1) {
            lines.add(buildIconLine(negMax, "\u2764", GRAY, false));
        }

        // 5. 普通属性表条目
        for (ViewStatsRegistry.StatEntry entry : ViewStatsRegistry.ENTRIES) {
            int val = ScoreboardReader.readOrZero(sb, entry.objective(), player);
            // TowersRegen 是 derived，datapack 实测会被本帧前面 "scoreboard players operation"
            // 算入 TowersRegen 自身的 scoreboard 槽；我们直接读它通常是 0（datapack 不每 tick 算），
            // 退而求其次：读 Regen + Broccoli + BroccoliW（仅 tag=CTT 玩家有效），不依赖 chain 顺序。
            if ("TowersRegen".equals(entry.objective())) {
                int regen     = ScoreboardReader.readOrZero(sb, "Regen",     player);
                int broccoli  = ScoreboardReader.readOrZero(sb, "Broccoli",  player);
                int broccoliW = ScoreboardReader.readOrZero(sb, "BroccoliW", player);
                if (player.getCommandTags().contains("CTT")) {
                    val = regen + broccoli + broccoliW;
                }
            }
            appendDualBranch(lines, val, entry);
        }

        // 6. SpeedAmplifier（bold + ≠100 显示，..99 gray、101.. aqua）
        int speedAmp = ScoreboardReader.readOrZero(sb, "SpeedAmplifier", player);
        if (speedAmp <= 99) {
            lines.add(buildSpeedAmpLine(speedAmp, GRAY));
        } else if (speedAmp >= 101) {
            lines.add(buildSpeedAmpLine(speedAmp, AQUA));
        }
        // 7. 条件行："Speed before Amplifier | <SpeedRaw>"（仅 SpeedAmplifier != 100）
        if (speedAmp != 100) {
            int speedRaw = ScoreboardReader.readOrZero(sb, "SpeedRaw", player);
            MutableText line = Text.literal("Speed before Amplifier | ")
                    .setStyle(Style.EMPTY.withColor(AQUA));
            line.append(Text.literal(String.valueOf(speedRaw))
                    .setStyle(Style.EMPTY.withColor(AQUA)));
            lines.add(line);
        }

        // 8. Size：负值 ⬇ #5469ff，正值 ⬆ #ff0005
        int size = ScoreboardReader.readOrZero(sb, "Size", player);
        if (size <= -1) {
            lines.add(buildIconLine(size, "\u2B07", SIZE_DOWN, false));
        } else if (size >= 1) {
            lines.add(buildIconLine(size, "\u2B06", SIZE_UP, false));
        }

        // 9. Gravity：负值 ⇑ #e854ff，正值 ⇓ #9500ff（datapack 注释里方向就是这样反的，
        //    负值表示"反向重力"用 ⇑ 上箭头，正值表示"加强重力"用 ⇓ 下箭头）
        int gravity = ScoreboardReader.readOrZero(sb, "Gravity", player);
        if (gravity <= -1) {
            lines.add(buildIconLine(gravity, "\u21D1", GRAVITY_UP, false));
        } else if (gravity >= 1) {
            lines.add(buildIconLine(gravity, "\u21D3", GRAVITY_DOWN, false));
        }

        // 10. CelestialKarma（条件门：#ServerTier CT == 3）
        int serverTier = ScoreboardReader.readOrZero(sb, "CT", ScoreHolder.fromName("#ServerTier"));
        if (serverTier == 3) {
            int celest = ScoreboardReader.readOrZero(sb, "CelestialKarma", player);
            if (celest <= -1) {
                lines.add(buildIconLine(celest, "\uE027", GRAY, false));
            } else if (celest >= 1) {
                lines.add(buildIconLine(celest, "\uE027", CELESTIAL, false));
            }
        }

        // 11. v8.4.3 · 状态效果（tag-based）。datapack 在 DarkKarma 之后、Skulls 之前用
        //     `tellraw @s[tag=Xxx] {"translate":"Yyy","color":"#zzz",...}` 输出每个状态行。
        //     这里走 STATUS_EFFECTS 表 + Text.translatable，让客户端汉化资源包翻译
        //     （如 "Berserk" → "癫狂"）。getCommandTags() 返回 Set，contains O(1)，
        //     30 次查询的成本相对差量缓存可忽略。
        java.util.Set<String> tags = player.getCommandTags();
        if (!tags.isEmpty()) {
            for (ViewStatsRegistry.StatusEffectEntry st : ViewStatsRegistry.STATUS_EFFECTS) {
                if (tags.contains(st.tag())) {
                    lines.add(buildStatusEffectLine(st.translateKey(), st.color()));
                }
            }
        }

        // 12. #Skulls CTT（fake player score）
        int skulls = ScoreboardReader.readOrZero(sb, "CTT", ScoreHolder.fromName("#Skulls"));
        if (skulls <= -1) {
            lines.add(buildIconLine(skulls, "\u2620", GRAY, false));
        } else {
            // datapack 是 0.. red ☠；0 也显示
            lines.add(buildIconLine(skulls, "\u2620", RED, false));
        }

        return new PlayerStatsPayload(
                PlayerStatsPayload.CURRENT_VERSION,
                red, soul, black, blue,
                lines);
    }

    /** datapack 双分支处理：score ≤ -1 显示 negative，score ≥ 1 显示 positive，0 不显示。 */
    private static void appendDualBranch(List<Text> lines, int val, ViewStatsRegistry.StatEntry e) {
        if (val <= -1 && e.hasNegativeBranch()) {
            lines.add(buildIconLine(val, e.icon(), e.negativeColor(), false));
        } else if (val >= 1) {
            lines.add(buildIconLine(val, e.icon(), e.positiveColor(), false));
        }
    }

    /**
     * 拼 datapack 的标准两段：{@code [score][icon]} 同色。{@code bold=true} 仅 SpeedAmplifier 用。
     *
     * <p>对应 datapack：{@code [{"score":{...},"color":"X"},{"translate":"icon","color":"X"}]}。
     * 客户端 chat 解析当时是分两段渲染、相同颜色；我们也保持两段 append，方便后续若要
     * 复用 datapack 同款 hoverEvent 时只改本方法。
     */
    private static MutableText buildIconLine(int score, String icon, TextColor color, boolean bold) {
        Style style = Style.EMPTY.withColor(color).withBold(bold);
        MutableText line = Text.literal(String.valueOf(score)).setStyle(style);
        line.append(Text.literal(icon).setStyle(style));
        return line;
    }

    /** SpeedAmplifier 特殊：{@code [score]% Speed Amplifier} 全段 bold + 同色。 */
    private static MutableText buildSpeedAmpLine(int score, TextColor color) {
        Style style = Style.EMPTY.withColor(color).withBold(true);
        MutableText line = Text.literal(String.valueOf(score)).setStyle(style);
        line.append(Text.literal("% Speed Amplifier").setStyle(style));
        return line;
    }

    /**
     * v8.4.3 · 状态效果行：纯 {@link Text#translatable(String)} + 颜色，无数值无图标。
     *
     * <p>对应 datapack：{@code {"translate":"Berserk","color":"dark_red","hoverEvent":{...}}}。
     * 用 translatable 而非 literal 是为了让客户端汉化资源包能把 {@code "Berserk"} 翻译成
     * {@code "癫狂"} ——  Cake Team Towers 资源包内 lang 文件已经定义了所有 status effect
     * 的英文 key → 多语言翻译。在没有翻译时 Minecraft fallback 到 key 原文，
     * 与 datapack chat 路径行为一致。
     */
    private static MutableText buildStatusEffectLine(String translateKey, TextColor color) {
        return Text.translatable(translateKey).setStyle(Style.EMPTY.withColor(color));
    }
}
