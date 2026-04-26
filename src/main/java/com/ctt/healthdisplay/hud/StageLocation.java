package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.network.StagePayload;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * v6.5.6 · 客户端关卡位置展示层。
 *
 * <h2>架构变更（相对 v6.5.5）</h2>
 * <p>v6.5.5 让客户端直接读 scoreboard 自行判定关卡，问题：vanilla 不同步玩家
 * {@code scoreboardTags} 到客户端，{@code ClientPlayerEntity#getCommandTags()} 永远空，
 * 导致 {@code isCtt} 永远 false → 休息室和关卡都被识别成 LOBBY"主大厅"。
 *
 * <p>v6.5.6 改为：
 * <ul>
 *   <li>服务端 {@link com.ctt.healthdisplay.server.StageProbeServer} 权威计算 ——
 *       它能拿到玩家真实 scoreboardTags 与 server scoreboard。</li>
 *   <li>diff 推送 {@link StagePayload} 到本玩家客户端。</li>
 *   <li>客户端 {@code ClientStageLocation} 在 receiver 里缓存最新一次 payload。</li>
 *   <li>本类 {@link #probe()} 改为读那个缓存。</li>
 * </ul>
 *
 * <p>这样集成服务器（本地存档）和专用服务器都走相同的网络路径，无需特殊分支。
 *
 * <h2>枚举编码契约</h2>
 * {@link Kind} 与 {@link GameOverPhase} 的 ordinal **必须**与
 * {@link com.ctt.healthdisplay.server.StageProbeServer} 中的常量严格对齐。
 * 改这俩枚举的顺序时，请同步更新服务端常量。
 */
public final class StageLocation {

    private StageLocation() {}

    /** 关卡大类。ordinal 与服务端 StageProbeServer.K_* 对齐 —— 不要随意调整顺序。 */
    public enum Kind {
        LOBBY,         // 不在 CTT 局
        BREAK_ROOM,    // 休息室
        STAGE_BOSS,    // 大 boss 关
        STAGE_MBOSS,   // 小 boss 关
        STAGE_DUNGEON, // 普通副本
        STAGE_SHOP,    // 商店
        STAGE_ALLY,    // 盟友 / NPC
        STAGE_MISC,    // 杂项 / 训练场 / 教程
        MINIGAME,      // 大厅小游戏
        GAME_OVER,     // 死亡倒计时 / Continue 屏 / 最终锁定
        UNKNOWN
    }

    /** GameOver 子状态。ordinal 与服务端 GO_* 对齐。 */
    public enum GameOverPhase { NONE, COUNTDOWN, CONTINUE, LOCKED }

    private static final Kind[] KINDS = Kind.values();
    private static final GameOverPhase[] PHASES = GameOverPhase.values();

    /**
     * 一次探测结果。
     */
    public record Snapshot(
            Kind kind,
            int tier,
            int floor,
            int stageNum,
            int breakRoomId,
            int miniGameId,
            GameOverPhase gameOverPhase,
            boolean checkpoint
    ) {
        public static Snapshot unknown() {
            return new Snapshot(Kind.UNKNOWN, 0, 0, 0, 0, 0, GameOverPhase.NONE, false);
        }

        /** 由网络包字段反序列化（由 ClientStageLocation receiver 调用）。 */
        public static Snapshot fromPayload(StagePayload p) {
            int ki = clamp(p.kind(), 0, KINDS.length - 1);
            int gi = clamp(p.gameOverPhase(), 0, PHASES.length - 1);
            return new Snapshot(
                    KINDS[ki],
                    p.tier(),
                    p.floor(),
                    p.stageNum(),
                    p.breakRoomId() & 0xFF,
                    p.miniGameId() & 0xFF,
                    PHASES[gi],
                    p.checkpoint()
            );
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        /**
         * 渲染用富文本：例如 {@code "Boss · T1·F5 · #15"}（紫）/ {@code "休息室 · 主塔"}（黄）。
         * 颜色与 {@link Kind} 一一对应，便于在 HUD 上肉眼分类。
         */
        public Text formatted() {
            return switch (kind) {
                case LOBBY -> Text.literal("\u4e3b\u5927\u5385").formatted(Formatting.GRAY);

                case BREAK_ROOM -> Text.literal(
                        "\u4f11\u606f\u5ba4 \u00b7 " + breakRoomName(breakRoomId)
                                + (checkpoint ? "  \u2606\u5b58\u6863\u70b9" : "")
                ).formatted(Formatting.YELLOW);

                case STAGE_BOSS  -> stageLine("Boss",        tier, floor, stageNum, kind, Formatting.LIGHT_PURPLE);
                case STAGE_MBOSS -> stageLine("MiniBoss",    tier, floor, stageNum, kind, Formatting.DARK_PURPLE);
                case STAGE_DUNGEON -> stageLine("\u526f\u672c", tier, floor, stageNum, kind, Formatting.AQUA);   // 副本
                case STAGE_SHOP  -> stageLine("\u5546\u5e97",   tier, floor, stageNum, kind, Formatting.GOLD);   // 商店
                case STAGE_ALLY  -> stageLine("\u76df\u53cb",   tier, floor, stageNum, kind, Formatting.GREEN);  // 盟友
                case STAGE_MISC  -> stageLine("\u6742\u9879",   tier, floor, stageNum, kind, Formatting.WHITE);  // 杂项

                case MINIGAME -> Text.literal(
                        "\u5c0f\u6e38\u620f \u00b7 " + miniGameName(miniGameId)
                ).formatted(Formatting.AQUA);

                case GAME_OVER -> Text.literal(
                        "Game Over \u00b7 " + gameOverPhaseName(gameOverPhase)
                ).formatted(Formatting.RED);

                case UNKNOWN -> Text.literal("\u672a\u77e5").formatted(Formatting.DARK_GRAY);
            };
        }

        /**
         * 渲染单条 STAGE_* 类型行：{@code "<前缀> · T1·F2 · #7  ◇本地化关卡名"}。
         * 关卡名缺失时省略后半段。
         */
        private static Text stageLine(String prefix, int tier, int floor, int stageNum,
                                       Kind kind, Formatting color) {
            String head = String.format("%s \u00b7 T%d\u00b7F%d \u00b7 #%d",
                    prefix, tier, floor, stageNum);
            String localizedName = StageNameRegistry.localizedName(kind, stageNum);
            String full = (localizedName == null || localizedName.isEmpty())
                    ? head
                    : head + "  \u25c7" + localizedName;  // ◇ 分隔关卡名
            return Text.literal(full).formatted(color);
        }

        private static String breakRoomName(int id) {
            return switch (id) {
                case 0 -> "\u4e3b\u5854 (The Tower)";
                case 1 -> "Arced Void";
                case 2 -> "\u7279\u6b8a\u4e0d\u53ef\u5b58\u6863";
                case 3 -> "World War Bee";
                case 4 -> "Oculus Forest";
                case 5 -> "\u7279\u6b8a";
                case 6 -> "Magum Trials";
                default -> "?#" + id;
            };
        }

        private static String miniGameName(int id) {
            return switch (id) {
                case 1 -> "Lazys Bow Training";
                case 2 -> "Horse Race";
                case 3 -> "Jelly Trials";
                case 4 -> "Magum Trials";
                case 5 -> "Snowball Civilization";
                case 6 -> "Heart of Otherside";
                case 7 -> "Muck Survivor";
                case 8 -> "Love Is A Battlefield";
                default -> "#" + id;
            };
        }

        private static String gameOverPhaseName(GameOverPhase p) {
            return switch (p) {
                case COUNTDOWN -> "\u5012\u8ba1\u65f6";
                case CONTINUE  -> "Continue \u7eed\u547d";
                case LOCKED    -> "\u9501\u5b9a";
                case NONE      -> "?";
            };
        }
    }

    /**
     * 客户端探测：直接返回 {@link ClientStageLocation} 的最新缓存。
     * 失败 / 未收到任何 payload 时返回 {@link Snapshot#unknown()}.
     */
    public static Snapshot probe() {
        return ClientStageLocation.current();
    }
}
