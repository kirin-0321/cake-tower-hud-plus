package com.ctt.healthdisplay.server;

import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * v8.4.0 · Cake Team Towers 玩家属性面板常量表。
 *
 * <h2>背景</h2>
 * <p>地图 datapack 的 {@code function misc/view_stats.mcfunction}（CTT 4.0.x，195 行）由 ~80 条
 * {@code tellraw @s} 组成，每条对应一个属性 objective、一个图标字符、一组正负值颜色
 * 与一段 {@code hoverEvent.contents} 文本。{@link ViewStatsBuilder} 在服务端走表
 * 直接构造视觉等价的 {@link net.minecraft.text.Text} 行，把整张属性面板压到一个
 * {@link com.ctt.healthdisplay.network.PlayerStatsPayload} 里推送给客户端，
 * 避开"客户端每 N 秒 {@code /trigger ViewStats} → 100 行 chat → 客户端正则解析"
 * 这条性能噩梦路径。
 *
 * <h2>本表范围</h2>
 * <p>仅包含<b>普通属性条目</b>：{@code Strength}、{@code AttackSpeed}、各种 Armor、
 * Mana 系列、Karma 系列、Skulls 等"读 1 个 objective、按正负走 2 套颜色"的标准模式。
 * 下面这些非标条目在 builder 里硬编码，<b>不</b>列入本表：
 * <ul>
 *   <li>4 色心 (RedHearts/SoulHearts/BlackHearts/BlueHearts) —— 单向（仅正数显示）</li>
 *   <li>{@code CrackedHearts} —— 负数显示成 {@code PinkHearts}，需要取反计算</li>
 *   <li>{@code NegMaxHealth} —— 单向，颜色单一灰色</li>
 *   <li>{@code SpeedAmplifier} —— bold + 比较 {@code ≠100}，且联动 {@code SpeedRaw} 行</li>
 *   <li>{@code Size} / {@code Gravity} —— 图标方向跟正负相反（{@code ↑/↓}、{@code ⇑/⇓}）</li>
 *   <li>{@code TowersRegen} —— derived = Regen + Broccoli + BroccoliW（仅 {@code tag=CTT} 玩家）</li>
 *   <li>{@code #Skulls CTT} —— fake player score</li>
 *   <li>{@code CelestialKarma} —— 受 {@code #ServerTier CT == 3} 条件门控</li>
 *   <li>状态效果（{@code tag=Burnt} 等 30 多个），与"属性面板"语义无关，暂不还原</li>
 *   <li>"Game time" 时间行 —— builder 在末尾按需拼接</li>
 * </ul>
 *
 * <h2>条目字段</h2>
 * <ul>
 *   <li>{@code objective}：scoreboard objective 名（与 datapack 一一对应）</li>
 *   <li>{@code icon}：单字符或多字符（含 Unicode BMP 私用 \\uExxx 与 emoji surrogate pair）</li>
 *   <li>{@code positiveColor}：score ≥ 1 时的颜色（{@link TextColor}）</li>
 *   <li>{@code negativeColor}：score ≤ -1 时的颜色，{@code null} 表示"该条目只在正值时显示"</li>
 * </ul>
 *
 * <p>注意：本表只保存 <i>形状信息</i>，<b>不</b>保存运行时分值——分值由
 * {@link ViewStatsBuilder} 每个 tick 调 {@link ScoreboardReader#readOrZero} 现读。
 */
public final class ViewStatsRegistry {

    private ViewStatsRegistry() {}

    /** datapack 通用"未达到/缺失"颜色，所有 negative 分支以及缺省 gray 都用这个。 */
    public static final TextColor GRAY = TextColor.fromFormatting(Formatting.GRAY);

    /**
     * 单条属性的形状描述。{@code negativeColor == null} 时本条目只在正值（≥1）显示，
     * 用于 {@code Healing} / {@code Bitches} 等"datapack 只有 1..N 分支"的属性。
     *
     * <p>设计成 immutable record + 静态 List，启动加载一次后零分配热路径。
     */
    public record StatEntry(
            String objective,
            String icon,
            TextColor positiveColor,
            TextColor negativeColor
    ) {
        /** 当 datapack 同时有 negative 与 positive 分支时使用。 */
        public static StatEntry both(String objective, String icon,
                                     TextColor pos, TextColor neg) {
            return new StatEntry(objective, icon, pos, neg);
        }

        /** 仅 positive 分支（datapack 没有 "..-1" tellraw 的属性）。 */
        public static StatEntry posOnly(String objective, String icon, TextColor pos) {
            return new StatEntry(objective, icon, pos, null);
        }

        public boolean hasNegativeBranch() {
            return negativeColor != null;
        }
    }

    // ---- 常用颜色快捷别名（datapack 用 vanilla Formatting + 偶尔 hex）----
    private static final TextColor RED          = TextColor.fromFormatting(Formatting.RED);
    private static final TextColor YELLOW       = TextColor.fromFormatting(Formatting.YELLOW);
    private static final TextColor BLACK        = TextColor.fromFormatting(Formatting.BLACK);
    private static final TextColor BLUE         = TextColor.fromFormatting(Formatting.BLUE);
    private static final TextColor GOLD         = TextColor.fromFormatting(Formatting.GOLD);
    private static final TextColor AQUA         = TextColor.fromFormatting(Formatting.AQUA);
    private static final TextColor GREEN        = TextColor.fromFormatting(Formatting.GREEN);
    private static final TextColor DARK_GREEN   = TextColor.fromFormatting(Formatting.DARK_GREEN);
    private static final TextColor DARK_RED     = TextColor.fromFormatting(Formatting.DARK_RED);
    private static final TextColor DARK_PURPLE  = TextColor.fromFormatting(Formatting.DARK_PURPLE);
    private static final TextColor LIGHT_PURPLE = TextColor.fromFormatting(Formatting.LIGHT_PURPLE);
    private static final TextColor WHITE        = TextColor.fromFormatting(Formatting.WHITE);
    private static final TextColor PINK_FF87FF  = TextColor.fromRgb(0xFF87FF);

    // ---- 常用图标快捷别名 ----
    private static final String ICON_SWORD   = "\uD83D\uDDE1"; // 🗡 surrogate pair (U+1F5E1)
    private static final String ICON_BOW     = "\uD83C\uDFF9"; // 🏹
    private static final String ICON_SHIELD  = "\uD83D\uDEE1"; // 🛡
    private static final String ICON_HEART   = "\u2764";       // ❤
    private static final String ICON_SKULL   = "\u2620";       // ☠
    private static final String ICON_PLANE   = "\u2708";       // ✈
    private static final String ICON_BOLT    = "\u26A1";       // ⚡
    private static final String ICON_PENCIL  = "\u270F";       // ✏

    private static final String ICON_FIRE_ARMOR     = ICON_SHIELD + "\uD83D\uDD25"; // 🛡🔥
    private static final String ICON_WATER_ARMOR    = ICON_SHIELD + "\u2693";        // 🛡⚓
    private static final String ICON_ELEC_ARMOR     = ICON_SHIELD + "\u26A1";        // 🛡⚡
    private static final String ICON_ICE_ARMOR      = ICON_SHIELD + "\u2603";        // 🛡☃
    private static final String ICON_DARK_ARMOR     = ICON_SHIELD + "\u2620";        // 🛡☠
    private static final String ICON_LIGHT_ARMOR    = ICON_SHIELD + "\u2600";        // 🛡☀ (☀ = U+2600)

    private static final String ICON_HEAL_PERCENT   = "\u2764%"; // ❤%
    private static final String ICON_REGEN          = "\uE004";  // private use
    private static final String ICON_FISH           = "\uE003";
    private static final String ICON_BEE            = "\uE001";
    private static final String ICON_KARMA_LIGHT    = "\uE010";
    private static final String ICON_KARMA_DARK     = "\uE011";

    /**
     * datapack 的"普通"属性条目，<b>按 view_stats.mcfunction 出现顺序排列</b>。
     * 修改顺序 = 修改 chat 输出顺序，请保持与地图 datapack 一致以便玩家肌肉记忆。
     */
    public static final List<StatEntry> ENTRIES = List.of(
            StatEntry.both("ClassStat",        ICON_SWORD,  GOLD,         GRAY),
            StatEntry.both("Strength",         ICON_SWORD,  RED,          GRAY),
            StatEntry.both("AttackSpeed",      ICON_SWORD,  BLUE,         GRAY),
            StatEntry.both("AttackRange",      ICON_SWORD,  DARK_GREEN,   GRAY),
            StatEntry.both("StaminaSpeed",     ICON_SWORD,  GREEN,        GRAY),
            StatEntry.both("Archery",          ICON_BOW,    GREEN,        GRAY),
            StatEntry.both("Defence",          ICON_SHIELD, BLUE,         GRAY),
            StatEntry.both("TrueArmor",        ICON_SHIELD, WHITE,        GRAY),
            StatEntry.both("Healing",          ICON_HEART,  LIGHT_PURPLE, GRAY),
            // TowersRegen = Regen + Broccoli + BroccoliW（derived），builder 自己算
            StatEntry.both("HealPercent",      ICON_HEAL_PERCENT, RED,    GRAY),
            StatEntry.both("ExtraHealing",     ICON_HEAL_PERCENT, LIGHT_PURPLE, GRAY),
            StatEntry.both("TrueFireArmor",    ICON_FIRE_ARMOR,   WHITE,        GRAY),
            StatEntry.both("FireArmor",        ICON_FIRE_ARMOR,   GOLD,         GRAY),
            StatEntry.both("WaterArmor",       ICON_WATER_ARMOR,  BLUE,         GRAY),
            StatEntry.both("ElectricArmor",    ICON_ELEC_ARMOR,   YELLOW,       GRAY),
            StatEntry.both("IceArmor",         ICON_ICE_ARMOR,    AQUA,         GRAY),
            StatEntry.both("DarkArmor",        ICON_DARK_ARMOR,   DARK_PURPLE,  GRAY),
            StatEntry.both("LightArmor",       ICON_LIGHT_ARMOR,  WHITE,        GRAY),
            StatEntry.both("MaxSpeed",         ICON_PLANE,        AQUA,         GRAY),
            // SpeedAmplifier / SpeedRaw 在 builder 里特殊处理
            // Size / Gravity 同上（双向图标）
            StatEntry.both("ManaPower",        ICON_PENCIL,       GOLD,         GRAY),
            StatEntry.both("ManaRechargeSpeed",ICON_PENCIL,       AQUA,         GRAY),
            StatEntry.both("Jump",             ICON_PLANE,        GREEN,        GRAY),
            StatEntry.both("DoubleJump",       ICON_PLANE,        PINK_FF87FF,  GRAY),
            StatEntry.both("ActiveCharge",     ICON_BOLT,         YELLOW,       GRAY),
            StatEntry.both("MaxActiveCharge",  ICON_BOLT,         GOLD,         GRAY),
            StatEntry.both("ActiveSpeed",      ICON_BOLT,         WHITE,        GRAY),
            StatEntry.both("Summoning",        ICON_SKULL,        DARK_PURPLE,  GRAY),
            StatEntry.both("Fishing",          ICON_FISH,         AQUA,         GRAY),
            StatEntry.both("BeePoints",        ICON_BEE,          YELLOW,       GRAY),
            StatEntry.both("AntPoints",        ICON_BEE,          BLACK,        GRAY),
            StatEntry.both("Bitches",          ICON_HEART,        DARK_RED,     GRAY),
            StatEntry.both("LightKarma",       ICON_KARMA_LIGHT,  AQUA,         GRAY),
            StatEntry.both("NeutralKarma",     ICON_KARMA_LIGHT,  DARK_PURPLE,  GRAY),
            StatEntry.both("DarkKarma",        ICON_KARMA_DARK,   DARK_RED,     GRAY)
            // CelestialKarma 走 ServerTier 条件，builder 单独处理
    );

    /** 四色心序列（顺序 = datapack 输出 = HUD 主血条层级叠加顺序，固定）。 */
    public static final List<HeartEntry> HEARTS = List.of(
            new HeartEntry("RedHearts",   RED),
            new HeartEntry("SoulHearts",  YELLOW),
            new HeartEntry("BlackHearts", BLACK),
            new HeartEntry("BlueHearts",  BLUE)
    );

    /** 心条专用 record（datapack 都只有正值分支）。 */
    public record HeartEntry(String objective, TextColor color) {}
}
