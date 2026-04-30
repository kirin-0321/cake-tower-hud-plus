package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.network.StagePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * v6.5.6 · 服务端权威关卡位置探测器。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>每 tick 读 server-side {@link Scoreboard} 的 14 个 holder（来自地图数据包 CTT objective）。</li>
 *   <li>对每个在线玩家，根据 {@link ServerPlayerEntity#getCommandTags()} 含 {@code "CTT"} 与否
 *       + holder 状态计算 {@link StagePayload}。</li>
 *   <li>diff 检测：只在 payload 与上次推送不同（或 60 tick 心跳）时才推，避免每 tick 浪费带宽。</li>
 *   <li>玩家加入时强制推一次完整 payload，避免客户端首屏空白。</li>
 * </ul>
 *
 * <h2>判定优先级</h2>
 * <ol>
 *   <li>玩家无 {@code CTT} tag → {@code LOBBY}</li>
 *   <li>v8.1.0 · {@code #LobbyMiniGame == 4} 且 {@code collectMagumTrials} 开启 →
 *       MT 专用路径（tier 用 {@code #MagumTrialDifficulty}，标准 stage holder 检测，
 *       payload {@code inMagumTrials = true}），详见 {@code docs/MAGUM_TRIALS_STAGE_TRACKING.md}</li>
 *   <li>{@code #LobbyMiniGame > 0} 其他值 → {@code MINIGAME}</li>
 *   <li>{@code #GameOver >= 1} → {@code GAME_OVER}（细分 COUNTDOWN/CONTINUE/LOCKED）</li>
 *   <li>任一 stage holder &gt; 0 → STAGE_*（Boss > MBoss > Dungeon > Shop > Ally > Misc）</li>
 *   <li>holders 全 0 → {@code BREAK_ROOM}</li>
 * </ol>
 *
 * <h2>枚举编码</h2>
 * 与客户端 {@code com.ctt.healthdisplay.hud.StageLocation.Kind / GameOverPhase} 的 ordinal 对齐：
 * <pre>
 * Kind:           LOBBY=0 BREAK_ROOM=1 STAGE_BOSS=2 STAGE_MBOSS=3 STAGE_DUNGEON=4
 *                 STAGE_SHOP=5 STAGE_ALLY=6 STAGE_MISC=7 MINIGAME=8 GAME_OVER=9 UNKNOWN=10
 * GameOverPhase:  NONE=0 COUNTDOWN=1 CONTINUE=2 LOCKED=3
 * </pre>
 */
public final class StageProbeServer {

    private StageProbeServer() {}

    // 与 hud/StageLocation.Kind ordinal 严格一致（不要乱改 Kind 顺序）。
    private static final byte K_LOBBY         = 0;
    private static final byte K_BREAK_ROOM    = 1;
    private static final byte K_STAGE_BOSS    = 2;
    private static final byte K_STAGE_MBOSS   = 3;
    private static final byte K_STAGE_DUNGEON = 4;
    private static final byte K_STAGE_SHOP    = 5;
    private static final byte K_STAGE_ALLY    = 6;
    private static final byte K_STAGE_MISC    = 7;
    private static final byte K_MINIGAME      = 8;
    private static final byte K_GAME_OVER     = 9;

    private static final byte GO_NONE      = 0;
    private static final byte GO_COUNTDOWN = 1;
    private static final byte GO_CONTINUE  = 2;
    private static final byte GO_LOCKED    = 3;

    /** 每个玩家上次推送的 payload，用于 diff 检测。玩家断线时移除。 */
    private static final Map<UUID, StagePayload> LAST_SENT = new HashMap<>();

    /** 强制心跳间隔：即便 payload 未变化也每 60 tick (3s) 推一次，作为容错。 */
    private static final long HEARTBEAT_TICKS = 60L;
    private static long tickCounter = 0L;

    /**
     * 玩家加入时清缓存 + 立即推一次完整 payload。
     * 由 {@code ServerPlayConnectionEvents.JOIN} 调用。
     */
    public static void onJoin(ServerPlayerEntity player) {
        LAST_SENT.remove(player.getUuid());
    }

    /**
     * 玩家断线时清缓存。
     * 由 {@code ServerPlayConnectionEvents.DISCONNECT} 调用。
     */
    public static void onDisconnect(UUID uuid) {
        LAST_SENT.remove(uuid);
        StageBoundaryDispatcher.onDisconnect(uuid);
    }

    /**
     * 服务端每 tick 调用：扫 holder + 给每个玩家算 snapshot + diff 推送。
     * 注册点：{@code ServerTickEvents.END_SERVER_TICK}.
     */
    public static void tick(MinecraftServer server) {
        tickCounter++;

        Scoreboard sb = server.getScoreboard();
        // 全局 holder（所有玩家共享）。
        int tier        = readScore(sb, "#Tier",         "CTT");
        int floor       = readScore(sb, "#Floor",        "CTT");
        int boss        = readScore(sb, "#Boss",         "CTT");
        int mboss       = readScore(sb, "#MBoss",        "CTT");
        int dungeon     = readScore(sb, "#Dungeon",      "CTT");
        int shop        = readScore(sb, "#Shop",         "CTT");
        int ally        = readScore(sb, "#Ally",         "CTT");
        int misc        = readScore(sb, "#Misc",         "CTT");
        int breakRoomId = readScore(sb, "#BreakRoomID",  "CTT");
        int checkpoint  = readScore(sb, "#CheckPoint",   "CTT");
        int gameOver    = readScore(sb, "#GameOver",     "CTT");
        int miniGame    = readScore(sb, "#LobbyMiniGame","CTT");
        // v8.1.0 · MT 难度：拼写在脚本里就是 "MagumTrialDifficulty"，objective="GameScores"。
        // 1..10 对应 MT 难度档位；0 = 还没启动过 MT。仅在 miniGame==4 + collectMagumTrials 时被使用。
        int mtDifficulty = readScore(sb, "#MagumTrialDifficulty", "GameScores");
        // v6.6.1 · M2 · GameID 探测：注意 holder/objective 与其他 holder 反过来 ——
        // 地图脚本 `scoreboard players add #CTT GameID 1` 表明 fakeplayer="#CTT"、objective="GameID"。
        // 0 表示地图未跑过 gamestart（lobby 启动后第一把会变 1）。
        int gameId      = readScore(sb, "#CTT",          "GameID");
        StageBoundaryDispatcher.updateGameId(gameId);

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            StagePayload next = computePayload(p, tier, floor, boss, mboss, dungeon,
                    shop, ally, misc, breakRoomId, gameOver, miniGame, checkpoint, mtDifficulty);
            // v6.6.0 · M1 · 关卡边界派发器同步：每 tick 喂一次 payload，dispatcher 内部 diff
            // 驱动 enter/exit 事件并维护"该玩家是否应被采集 + 当前 stageKey"缓存。
            StageBoundaryDispatcher.updateFromPayload(p, next);
            StagePayload prev = LAST_SENT.get(p.getUuid());
            boolean heartbeat = prev != null
                    && (tickCounter % HEARTBEAT_TICKS == 0);
            if (prev == null || !Objects.equals(prev, next) || heartbeat) {
                ServerPlayNetworking.send(p, next);
                LAST_SENT.put(p.getUuid(), next);
            }
        }
    }

    /**
     * v8.1.0 · 路由分支变化：
     * <ol>
     *   <li>玩家无 CTT tag → LOBBY（不变）</li>
     *   <li>{@code miniGame == 4} 且 {@link com.ctt.healthdisplay.config.ServerConfig#collectMagumTrials} 开启
     *       → 走 MT 专用路径：tier 维度替换为 {@code mtDifficulty}，stage holder 同走标准检测，
     *       payload 上 {@code inMagumTrials = true}；下游 {@link StageBoundaryDispatcher} 据此加 {@code mt_} 前缀</li>
     *   <li>{@code miniGame > 0} 其他值 / 上述配置关 → 老 MINIGAME 黑箱</li>
     *   <li>其余分支（GAME_OVER / STAGE_* / BREAK_ROOM）逻辑不变</li>
     * </ol>
     */
    private static StagePayload computePayload(
            ServerPlayerEntity p,
            int tier, int floor,
            int boss, int mboss, int dungeon, int shop, int ally, int misc,
            int breakRoomId, int gameOver, int miniGame, int checkpoint,
            int mtDifficulty
    ) {
        boolean isCtt = p.getCommandTags().contains("CTT");
        boolean cp = checkpoint == 1;

        if (!isCtt) {
            return new StagePayload(K_LOBBY, tier, floor, 0,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);
        }

        // v8.1.0 · MT 分关采集分支
        boolean mtMode = (miniGame == 4)
                && com.ctt.healthdisplay.config.ServerConfig.INSTANCE.collectMagumTrials;
        if (mtMode) {
            // tier 维度覆写为 MT 难度（1..10）。mtDifficulty=0 时（地图启动前）保留 0，
            // 客户端 HUD 文案显示 "T0" 也无碍——StageKey 由 dispatcher 用同样数字构造。
            int mtTier = mtDifficulty;

            if (gameOver >= 1) {
                byte phase;
                if (gameOver >= 100)      phase = GO_LOCKED;
                else if (gameOver == 99)  phase = GO_CONTINUE;
                else                       phase = GO_COUNTDOWN;
                return new StagePayload(K_GAME_OVER, mtTier, floor, gameOver,
                        (byte) breakRoomId, (byte) miniGame, phase, cp, true);
            }
            if (boss > 0)
                return new StagePayload(K_STAGE_BOSS, mtTier, floor, boss,
                        (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, true);
            if (mboss > 0)
                return new StagePayload(K_STAGE_MBOSS, mtTier, floor, mboss,
                        (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, true);
            if (dungeon > 0)
                return new StagePayload(K_STAGE_DUNGEON, mtTier, floor, dungeon,
                        (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, true);
            if (shop > 0)
                return new StagePayload(K_STAGE_SHOP, mtTier, floor, shop,
                        (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, true);
            if (ally > 0)
                return new StagePayload(K_STAGE_ALLY, mtTier, floor, ally,
                        (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, true);
            if (misc > 0)
                return new StagePayload(K_STAGE_MISC, mtTier, floor, misc,
                        (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, true);
            // 全 0 → MT 中央选关区，按 BREAK_ROOM 路径（不采集）
            return new StagePayload(K_BREAK_ROOM, mtTier, floor, 0,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, true);
        }

        if (miniGame > 0) {
            return new StagePayload(K_MINIGAME, tier, floor, 0,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);
        }

        if (gameOver >= 1) {
            byte phase;
            if (gameOver >= 100)      phase = GO_LOCKED;
            else if (gameOver == 99)  phase = GO_CONTINUE;
            else                       phase = GO_COUNTDOWN;
            return new StagePayload(K_GAME_OVER, tier, floor, gameOver,
                    (byte) breakRoomId, (byte) miniGame, phase, cp, false);
        }

        if (boss > 0)
            return new StagePayload(K_STAGE_BOSS, tier, floor, boss,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);
        if (mboss > 0)
            return new StagePayload(K_STAGE_MBOSS, tier, floor, mboss,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);
        if (dungeon > 0)
            return new StagePayload(K_STAGE_DUNGEON, tier, floor, dungeon,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);
        if (shop > 0)
            return new StagePayload(K_STAGE_SHOP, tier, floor, shop,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);
        if (ally > 0)
            return new StagePayload(K_STAGE_ALLY, tier, floor, ally,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);
        if (misc > 0)
            return new StagePayload(K_STAGE_MISC, tier, floor, misc,
                    (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);

        return new StagePayload(K_BREAK_ROOM, tier, floor, 0,
                (byte) breakRoomId, (byte) miniGame, GO_NONE, cp, false);
    }

    /**
     * 读 fake-player 形式的 dummy score（如 {@code #Tier} on objective {@code CTT}）。
     *
     * <p>v8.0.0 性能修复：原实现遍历 {@code sb.getKnownScoreHolders()}（CTT 地图上可达
     * 数百到上千 holder）做线性查找，每 tick 13 次调用 = ~1300+ 次字符串比较 / tick，
     * 是服务端 TPS 下降的最大单点贡献。改用 {@link ScoreHolder#fromName(String)} 直接
     * 哈希查询：vanilla {@link Scoreboard#getScore} 内部走的是 {@code HashMap<ScoreHolder>}
     * 路径，O(1)。
     */
    private static int readScore(Scoreboard sb, String fakePlayerName, String objectiveName) {
        ScoreboardObjective obj = sb.getNullableObjective(objectiveName);
        if (obj == null) return 0;
        ReadableScoreboardScore s = sb.getScore(ScoreHolder.fromName(fakePlayerName), obj);
        return s != null ? s.getScore() : 0;
    }
}
