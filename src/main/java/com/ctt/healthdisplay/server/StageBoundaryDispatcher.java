package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.network.StagePayload;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * v6.6.0 · M1 · 关卡边界派发器（服务端）。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>缓存每玩家"当前 {@link StageKey}"，由 {@link StageProbeServer} 每 tick 计算 payload 后调用
 *       {@link #updateFromPayload(ServerPlayerEntity, StagePayload)} 推送变化。</li>
 *   <li>暴露 {@link #currentStageKey(UUID)} 与 {@link #isCollecting(UUID)} 给三家 stats
 *       (PlayerDamageStats / PlayerKillStats / PlayerTakenStats) 在 {@code add()} 入口查询：
 *       <ul>
 *         <li>不在 CTT 局 / 大厅 / 休息室 / Game Over / MiniGame → {@code isCollecting} 返回 false，
 *             stats 写入直接拦截，确保设计 §3.1 的"休息室期间不采集"铁律。</li>
 *         <li>在 STAGE_* 战斗关 → 返回当前 stageKey，stats 把伤害分桶到该 key 的 bucket。</li>
 *       </ul>
 *   </li>
 *   <li>派发关卡边界事件给订阅者（M1 仅日志；M2 持久化里程碑会订阅做 NBT flush）：
 *       {@link #onStageEnter} / {@link #onStageExit} / {@link #onSessionChange}。</li>
 * </ul>
 *
 * <h2>StageKey 构造（M1 简化版）</h2>
 * <p>因 {@link StagePayload} 当前不携带 GameID，本里程碑使用四元组：
 * {@code (null, tier, floor, stageType, stageNum)}。GameID 维度由 M2（持久化）补上：届时
 * {@link StageProbeServer} 会顺带读 {@code #CTT GameID} 并填到 payload，dispatcher 据此切片
 * session 边界并触发 {@link #onSessionChange}。
 *
 * <h2>线程安全</h2>
 * <p>写入仅从 server tick 主线程通过 {@link StageProbeServer#tick(net.minecraft.server.MinecraftServer)} 触发；
 * 读取来自所有 stats add 路径（同 server tick 主线程）。仍用 {@link ConcurrentHashMap} 防御
 * 客户端断线 receiver 的并发写。
 */
public final class StageBoundaryDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stage-bd");

    private StageBoundaryDispatcher() {}

    // 所有 STAGE_* kind 的 ordinal（与 StageLocation.Kind / StageProbeServer 常量对齐）。
    // 这些 kind 表示玩家正在战斗关，应当采集。
    private static final int K_LOBBY         = 0;
    private static final int K_BREAK_ROOM    = 1;
    private static final int K_STAGE_BOSS    = 2;
    private static final int K_STAGE_MBOSS   = 3;
    private static final int K_STAGE_DUNGEON = 4;
    private static final int K_STAGE_SHOP    = 5;
    private static final int K_STAGE_ALLY    = 6;
    private static final int K_STAGE_MISC    = 7;
    private static final int K_MINIGAME      = 8;
    private static final int K_GAME_OVER     = 9;

    /** 每玩家当前 stage 状态。值不可空，但可能是 NULL_STATE 表示"不在战斗关"。 */
    private static final Map<UUID, PlayerStage> PLAYER_STATES = new ConcurrentHashMap<>();

    /**
     * 每玩家最近一次进入过的 stageKey（v6.6 · M3 · 嵌入式 HUD 用）。
     * 战斗中 = 当前 stageKey；休息室 = 退出前最后一关；大厅 / 从未进过战斗关 = null。
     * 设计 §5.5："休息室期间'关:'行显示上一关数据，进入新关瞬间清零开始累计"。
     */
    private static final Map<UUID, StageKey> LAST_SEEN_STAGE = new ConcurrentHashMap<>();

    /** 玩家不在战斗关时的占位状态（休息室 / 大厅 / Game Over 等）。 */
    private static final PlayerStage NULL_STATE = new PlayerStage(null, false, -1);

    /** 关卡进入事件订阅者（{@code accept(stageKey)}）。 */
    private static final CopyOnWriteArrayList<Consumer<StageKey>> ENTER_LISTENERS = new CopyOnWriteArrayList<>();
    /** 关卡退出事件订阅者。 */
    private static final CopyOnWriteArrayList<Consumer<StageKey>> EXIT_LISTENERS  = new CopyOnWriteArrayList<>();
    /** Session 切换事件订阅者（M2 持久化才真正派发；M1 不触发）。 */
    private static final CopyOnWriteArrayList<Runnable> SESSION_LISTENERS         = new CopyOnWriteArrayList<>();

    /**
     * 每 {@link StageKey} 的进入墙钟时间（首次 enter 时记录，后续重入不动）。
     * v6.6.0 · M4 · 分关表 ⏱ 列求 stage 持续时间用：
     * <pre>duration = (exit_ms ?? now_ms) - enter_ms</pre>
     */
    private static final Map<StageKey, Long> STAGE_ENTER_MS = new ConcurrentHashMap<>();
    /** 每 {@link StageKey} 最近一次离开墙钟时间（exit 时更新；进行中关此值始终 ≤ enter_ms）。 */
    private static final Map<StageKey, Long> STAGE_EXIT_MS  = new ConcurrentHashMap<>();

    /**
     * v6.6.1 · M2 · 当前 session 的 GameID（来自 {@code #CTT GameID} scoreboard）。
     * <ul>
     *   <li>{@code 0} = 地图刚启动还没跑过 gamestart（保留 null 进 StageKey）</li>
     *   <li>跳变 → 触发 {@link #SESSION_LISTENERS}，由持久化层归档旧数据并 reset</li>
     * </ul>
     * <p>由 {@link StageProbeServer#tick} 每 tick 调 {@link #updateGameId(int)} 推送，
     * 由 {@link #computeState} 填进 {@link StageKey#gameId()}。
     */
    private static volatile int currentGameId = 0;
    /** 上一次 tick 见到的 GameID，用于 diff 触发 onSessionChange。 */
    private static volatile int lastSeenGameId = 0;

    /** 单玩家的 stage 缓存项。 */
    private record PlayerStage(StageKey stageKey, boolean collecting, int kindOrdinal) {}

    // ---------------------------------------------------------------------
    //  写路径：StageProbeServer 每 tick 调用
    // ---------------------------------------------------------------------

    /**
     * 由 {@link StageProbeServer#tick} 在算完 payload 后调用。
     * 内部 diff 上次状态，必要时派发 {@link #onStageEnter} / {@link #onStageExit}。
     */
    public static void updateFromPayload(ServerPlayerEntity player, StagePayload payload) {
        if (player == null || payload == null) return;
        UUID uuid = player.getUuid();
        PlayerStage next = computeState(payload);
        PlayerStage prev = PLAYER_STATES.put(uuid, next);
        if (prev == null) prev = NULL_STATE;

        // diff：从无到有 / 从有到无 / 切换不同战斗关 → 派发对应事件
        boolean wasCollecting = prev.collecting;
        boolean nowCollecting = next.collecting;

        long now = System.currentTimeMillis();
        StageKey lastSeenForUuid = LAST_SEEN_STAGE.get(uuid);
        if (!wasCollecting && nowCollecting) {
            LOGGER.info("[CTT BD] {} entered stage {}", player.getName().getString(), next.stageKey);
            // v6.6.0 hotfix · 进入 T1F1 且上一段历史不是 T1F1 → 视为"新一轮游戏" → 全局自动清零
            maybeAutoClearForNewRun(next.stageKey, lastSeenForUuid);
            LAST_SEEN_STAGE.put(uuid, next.stageKey);
            STAGE_ENTER_MS.putIfAbsent(next.stageKey, now);
            fire(ENTER_LISTENERS, next.stageKey);
        } else if (wasCollecting && !nowCollecting) {
            LOGGER.info("[CTT BD] {} exited stage {} (now kindOrdinal={})",
                    player.getName().getString(), prev.stageKey, next.kindOrdinal);
            // 退出关 → lastSeen 保持不变（HUD 休息室行需要"上一关"做切片）
            STAGE_EXIT_MS.put(prev.stageKey, now);
            fire(EXIT_LISTENERS, prev.stageKey);
            // v6.6.0 hotfix · 战斗关 → 大厅/休息室/Game Over 都广播一次本关战绩。
            // 死亡（→ GAME_OVER）也算 EXIT，与用户决策 C2"死亡也广播"对齐；同 stageKey
            // 在 broadcaster 内部去重避免 4 人同 tick 退场刷屏。
            StageReportBroadcaster.onStageExit(prev.stageKey);
        } else if (wasCollecting && nowCollecting
                && !sameStage(prev.stageKey, next.stageKey)) {
            // 不经过休息室直接切关（理论上罕见）：先 exit 再 enter
            LOGGER.info("[CTT BD] {} switched stage {} -> {}",
                    player.getName().getString(), prev.stageKey, next.stageKey);
            // v6.6.0 hotfix · 同上：从非 T1F1 直切 T1F1 也判新轮
            maybeAutoClearForNewRun(next.stageKey, prev.stageKey);
            STAGE_EXIT_MS.put(prev.stageKey, now);
            fire(EXIT_LISTENERS, prev.stageKey);
            // v6.6.0 hotfix · 关到关切换（包括 T1F1 → T1F1 重打）也广播 prev 关战绩。
            StageReportBroadcaster.onStageExit(prev.stageKey);
            LAST_SEEN_STAGE.put(uuid, next.stageKey);
            STAGE_ENTER_MS.putIfAbsent(next.stageKey, now);
            fire(ENTER_LISTENERS, next.stageKey);
        }

        // v6.6.0 hotfix · 全员都在大厅/小游戏/GameOver → frozen=true（K 表格 ⏱ 暂停、数据保留）
        updateGlobalFrozenFromPlayers();
    }

    /**
     * v6.6.0 hotfix · 进 T1F1 自动清零判定。
     * <p>条件：{@code next} 是 T1F1 且 {@code prev} 不是 T1F1（或为 null）。
     * 保证从大厅/休息室/任意非 T1 关进 T1F1 都触发，但 T1F1 内部反复切不重复触发。
     *
     * <p>注意：当前是"任一玩家进 T1F1"就清零；多人本地集成服务器场景下队伍同步，
     * 视觉上几乎一致；专用服务器多队伍场景需要 M2 GameID 切片才能精准。
     */
    private static void maybeAutoClearForNewRun(StageKey next, StageKey prev) {
        if (!isT1F1(next)) return;
        if (isT1F1(prev)) return;
        if (PlayerDamageStats.isFrozen()) {
            // 解冻 + 清零；clear() 里会把 unfrozenSinceMs 重置到 now，无需手工调整
        }
        LOGGER.info("[CTT BD] new run detected (T1F1 entry from {}); auto-clearing all stats.", prev);
        PlayerDamageStats.clear();
    }

    private static boolean isT1F1(StageKey k) {
        return k != null && "1".equals(k.tier()) && "1".equals(k.floor());
    }

    /**
     * v6.6.0 hotfix · 当所有在线玩家都处于 LOBBY / MINIGAME / GAME_OVER（也即"完全离开 CTT 局"）
     * → 冻结 stats（K 表格 ⏱ 计时停摆 + 写入路径拦截，但已采集数据保留）。
     * 反过来，只要任何一个玩家进入 BREAK_ROOM / STAGE_*，立刻解冻。
     *
     * <p>玩家集为空（无人在线）时不动 frozen，避免空闲服务器一直冻结。
     */
    private static void updateGlobalFrozenFromPlayers() {
        if (PLAYER_STATES.isEmpty()) return;
        boolean allOff = true;
        for (PlayerStage s : PLAYER_STATES.values()) {
            int k = s.kindOrdinal;
            // 只要还有人在 BREAK_ROOM / STAGE_* / UNKNOWN（兜底视为可能在战斗）→ 不冻
            if (k == K_BREAK_ROOM
                    || k == K_STAGE_BOSS  || k == K_STAGE_MBOSS
                    || k == K_STAGE_DUNGEON || k == K_STAGE_SHOP
                    || k == K_STAGE_ALLY  || k == K_STAGE_MISC) {
                allOff = false;
                break;
            }
        }
        if (allOff != PlayerDamageStats.isFrozen()) {
            PlayerDamageStats.setFrozen(allOff);
            LOGGER.info("[CTT BD] frozen={} (allOff={} · LOBBY/MINIGAME/GAMEOVER occupancy)", allOff, allOff);
        }
    }

    /** 玩家断线：清缓存（视作退出战斗关，触发一次 exit）。 */
    public static void onDisconnect(UUID uuid) {
        PlayerStage prev = PLAYER_STATES.remove(uuid);
        LAST_SEEN_STAGE.remove(uuid);
        if (prev != null && prev.collecting) {
            LOGGER.info("[CTT BD] disconnect → exit stage {}", prev.stageKey);
            fire(EXIT_LISTENERS, prev.stageKey);
        }
        // v6.6.0 hotfix · 断线后重新评估 frozen 状态（剩余玩家可能全在大厅）
        updateGlobalFrozenFromPlayers();
    }

    /**
     * 由 payload 算 PlayerStage：在 STAGE_* 时构造 stageKey 并标记 collecting=true，
     * 否则返回 NULL_STATE。
     *
     * <p>v6.6.8 · 关卡级屏蔽：命中 {@link com.ctt.healthdisplay.config.ServerConfig#blockedStages}
     * 的关返回 NULL_STATE，三家 stats 的 isCollecting → false，写入路径全部静默。
     * 默认屏蔽 The Race / 赛马（{@code dungeon:47}），原因见 ServerConfig 同字段 JavaDoc。
     */
    private static PlayerStage computeState(StagePayload p) {
        int k = p.kind();
        boolean isStage = (k == K_STAGE_BOSS || k == K_STAGE_MBOSS
                       ||  k == K_STAGE_DUNGEON || k == K_STAGE_SHOP
                       ||  k == K_STAGE_ALLY    || k == K_STAGE_MISC);
        if (!isStage) {
            return new PlayerStage(null, false, k);
        }
        String stageType = stageTypeFromKind(k);
        String stageNumStr = Integer.toString(p.stageNum());

        if (isStageBlocked(stageType, stageNumStr)) {
            // 视为"非战斗关"：三家 stats 全部不采集；上一关的 EXIT 事件仍照常派发，
            // 让 StageReportBroadcaster 在进入屏蔽关瞬间把上一关战绩广播完整。
            return new PlayerStage(null, false, k);
        }

        // v6.6.1 · M2 · gameId 由 StageProbeServer 通过 updateGameId(...) 同步进来。
        // 0 视作"地图未跑过 gamestart"（lobby 阶段不进战斗关，此分支几乎走不到，
        // 但万一脚本顺序异常就保持 null 避免污染同关多 session 的数据）。
        int gid = currentGameId;
        String gameIdStr = gid > 0 ? Integer.toString(gid) : null;
        StageKey key = new StageKey(
                gameIdStr,
                Integer.toString(p.tier()),
                Integer.toString(p.floor()),
                stageType,
                stageNumStr
        );
        return new PlayerStage(key, true, k);
    }

    /**
     * v6.6.8 · 关卡黑名单匹配。配置项 {@link com.ctt.healthdisplay.config.ServerConfig#blockedStages}
     * 是 {@code "stageType:stageNum"} 字符串数组，逐条线性匹配。
     *
     * <p>数组规模在个位数，匹配开销可忽略（每个玩家每 tick 一次调用）。
     */
    private static boolean isStageBlocked(String stageType, String stageNum) {
        String[] blocked = com.ctt.healthdisplay.config.ServerConfig.INSTANCE.blockedStages;
        if (blocked == null || blocked.length == 0) return false;
        String key = stageType + ":" + stageNum;
        for (String b : blocked) {
            if (b == null || b.isEmpty()) continue;
            if (b.equals(key)) return true;
        }
        return false;
    }

    /** kind ordinal → stageType 短串（用作 StageKey 第 4 字段）。 */
    private static String stageTypeFromKind(int k) {
        return switch (k) {
            case K_STAGE_BOSS    -> "boss";
            case K_STAGE_MBOSS   -> "mboss";
            case K_STAGE_DUNGEON -> "dungeon";
            case K_STAGE_SHOP    -> "shop";
            case K_STAGE_ALLY    -> "ally";
            case K_STAGE_MISC    -> "misc";
            default              -> "?";
        };
    }

    private static boolean sameStage(StageKey a, StageKey b) {
        if (a == null || b == null) return a == b;
        return java.util.Objects.equals(a.tier(), b.tier())
                && java.util.Objects.equals(a.floor(), b.floor())
                && java.util.Objects.equals(a.stageType(), b.stageType())
                && java.util.Objects.equals(a.stageNum(), b.stageNum());
    }

    // ---------------------------------------------------------------------
    //  读路径：三家 stats 在 add() 入口调用
    // ---------------------------------------------------------------------

    /**
     * 该玩家当前 stageKey；不在战斗关返回 {@code null}。
     * 三家 stats 在收到 {@code stageKey == null} 的入参时会调用此方法自查实际 stage。
     */
    public static StageKey currentStageKey(UUID playerUuid) {
        if (playerUuid == null) return null;
        PlayerStage s = PLAYER_STATES.get(playerUuid);
        return s == null ? null : s.stageKey;
    }

    /**
     * 该玩家当前是否应被采集（在战斗关 = 是）。
     * 三家 stats 的 {@code add()} 入口先 check 此方法，false 直接 return 实现"休息室不采集"。
     *
     * <p>对于 attackerUuid 不在 PLAYER_STATES 的情况（玩家刚断线 / 还没收到首条 payload）：
     * 保守返回 false 避免数据污染。
     */
    public static boolean isCollecting(UUID playerUuid) {
        if (playerUuid == null) return false;
        PlayerStage s = PLAYER_STATES.get(playerUuid);
        return s != null && s.collecting;
    }

    /**
     * 全队"是否有任何玩家在战斗关"。供 {@link DamageProbe} 这种全局 probe 用：当全员都在
     * 休息室/大厅时整条采集流水线可以静默。
     */
    public static boolean anyCollecting() {
        for (PlayerStage s : PLAYER_STATES.values()) {
            if (s.collecting) return true;
        }
        return false;
    }

    /**
     * 取"当前队伍代表 stageKey"——任意一个正在战斗的玩家的 stageKey。
     * 客户端通过此方法（其实是经 {@link com.ctt.healthdisplay.hud.ClientStageLocation} 镜像的 stageKey）
     * 查 L 键面板"当前关切片"。
     *
     * <p>多玩家分散在不同关时返回首个找到的——M1 阶段单机 / 队友同关场景占绝对多数，
     * 此简化够用。M3 队友 HUD 改为按玩家维度查 stageKey 时再细化。
     */
    public static StageKey representativeStageKey() {
        for (PlayerStage s : PLAYER_STATES.values()) {
            if (s.collecting) return s.stageKey;
        }
        return null;
    }

    /**
     * 该 stageKey 进入时刻（墙钟 ms）。从未进入过返回 0。
     * v6.6.0 · M4 · 分关表 ⏱ 列计算用。
     */
    public static long stageEnterMs(StageKey stageKey) {
        if (stageKey == null) return 0L;
        Long v = STAGE_ENTER_MS.get(stageKey);
        return v == null ? 0L : v;
    }

    /**
     * 该 stageKey 最后一次退出时刻（墙钟 ms）。从未退出过 / 从未进入过返回 0。
     * v6.6.5 · M6 · {@link com.ctt.healthdisplay.server.StatsSnapshotBroadcaster} 打包用。
     */
    public static long stageExitMs(StageKey stageKey) {
        if (stageKey == null) return 0L;
        Long v = STAGE_EXIT_MS.get(stageKey);
        return v == null ? 0L : v;
    }

    /**
     * 该 stageKey 持续时间 ms（{@code exit_ms - enter_ms}；进行中关 = {@code now - enter_ms}）。
     * 从未进入过返回 0。
     */
    public static long stageDurationMs(StageKey stageKey) {
        if (stageKey == null) return 0L;
        Long enter = STAGE_ENTER_MS.get(stageKey);
        if (enter == null) return 0L;
        Long exit  = STAGE_EXIT_MS.get(stageKey);
        long endpoint;
        if (exit != null && exit >= enter) {
            // 已退出过：取最后一次 exit
            endpoint = exit;
            // 但若仍有玩家在该关 → 进行中，取 now
            for (PlayerStage s : PLAYER_STATES.values()) {
                if (s.collecting && sameStage(s.stageKey, stageKey)) {
                    endpoint = System.currentTimeMillis();
                    break;
                }
            }
        } else {
            endpoint = System.currentTimeMillis();
        }
        return Math.max(0L, endpoint - enter);
    }

    /**
     * 该 stageKey 当前是否还有玩家在打（用于分关表"⭐ (进行中)"高亮）。
     */
    public static boolean isStageInProgress(StageKey stageKey) {
        if (stageKey == null) return false;
        for (PlayerStage s : PLAYER_STATES.values()) {
            if (s.collecting && sameStage(s.stageKey, stageKey)) return true;
        }
        return false;
    }

    /**
     * 该玩家"最近一次进入过"的 stageKey（v6.6 · M3 · 嵌入式 HUD §5.5 用）。
     * <ul>
     *   <li>玩家正在战斗关 → 当前 stageKey</li>
     *   <li>玩家在休息室 → 退出前的最后一关 stageKey（让"关:"行显示上一关数据）</li>
     *   <li>从未进入过任何战斗关（开局 / 大厅）→ {@code null}（HUD 应隐藏"关:"行）</li>
     * </ul>
     */
    public static StageKey lastSeenStageKey(UUID playerUuid) {
        if (playerUuid == null) return null;
        PlayerStage cur = PLAYER_STATES.get(playerUuid);
        if (cur != null && cur.collecting) return cur.stageKey;
        return LAST_SEEN_STAGE.get(playerUuid);
    }

    // ---------------------------------------------------------------------
    //  事件订阅
    // ---------------------------------------------------------------------

    public static void onStageEnter(Consumer<StageKey> listener) { ENTER_LISTENERS.add(listener); }
    public static void onStageExit (Consumer<StageKey> listener) { EXIT_LISTENERS.add(listener); }

    // ---------------------------------------------------------------------
    //  v6.6.1 · M2 · GameID / session 控制
    // ---------------------------------------------------------------------

    /**
     * 由 {@link StageProbeServer#tick} 每 tick 调用，推送当前 {@code #CTT GameID} 值。
     * 跳变（且新值 &gt; 0）→ 派发 {@link #SESSION_LISTENERS}；
     * 持久化层订阅此事件做"旧 session 归档进 history[] + 三家 stats reset"。
     */
    public static void updateGameId(int newGameId) {
        currentGameId = newGameId;
        int prev = lastSeenGameId;
        if (newGameId != prev) {
            // 仅在"已经见过非 0 的旧值"时归档：服务器启动从 0 → 真值不算 session 切换。
            if (prev > 0 && newGameId > 0) {
                LOGGER.info("[CTT BD] session CHANGE detected (#CTT GameID {} -> {})", prev, newGameId);
                fireSession();
            } else if (newGameId > 0) {
                LOGGER.info("[CTT BD] session ESTABLISHED (#CTT GameID = {})", newGameId);
            }
            lastSeenGameId = newGameId;
        }
    }

    /** 当前 sessionId 的字符串形式（与 {@link StageKey#gameId()} 同步）；0 → null。 */
    public static String currentSessionIdString() {
        int g = currentGameId;
        return g > 0 ? Integer.toString(g) : null;
    }

    /** 当前 sessionId 的整数形式（持久化层归档用）；未建立 session 时返回 0。 */
    public static int currentGameIdInt() {
        return currentGameId;
    }

    /** 服务端启动后从 NBT 还原 sessionId，避免假性 session 切换误报。 */
    public static void restoreGameId(int gameId) {
        currentGameId = gameId;
        lastSeenGameId = gameId;
    }

    /**
     * v6.6.1 · M2 · session 切换 / 归档时调用：清空 stage 时间表与运行时 enter/exit 缓存。
     * 仅清"时间表 + per-player stage 缓存"，不动 listener / sessionId 字段（那些由 caller 处理）。
     */
    public static void clearStageTimeTables() {
        STAGE_ENTER_MS.clear();
        STAGE_EXIT_MS.clear();
        // PLAYER_STATES / LAST_SEEN_STAGE 不清：玩家可能正在某关里挂着，
        // 让 dispatcher 在下一 tick 重新填即可（避免归档瞬间触发假性 exit 事件）。
    }

    /**
     * 把 STAGE_ENTER_MS / STAGE_EXIT_MS 序列化为 NBT。
     * 仅用于 manager 把"当前 session 的 stage 时间表"打包持久化。
     */
    public static NbtCompound timeTablesToNbt() {
        NbtCompound t = new NbtCompound();
        NbtList enters = new NbtList();
        for (Map.Entry<StageKey, Long> e : STAGE_ENTER_MS.entrySet()) {
            NbtCompound c = new NbtCompound();
            c.put("k", e.getKey().toNbt());
            c.putLong("v", e.getValue());
            enters.add(c);
        }
        t.put("enter", enters);
        NbtList exits = new NbtList();
        for (Map.Entry<StageKey, Long> e : STAGE_EXIT_MS.entrySet()) {
            NbtCompound c = new NbtCompound();
            c.put("k", e.getKey().toNbt());
            c.putLong("v", e.getValue());
            exits.add(c);
        }
        t.put("exit", exits);
        return t;
    }

    /** 从 NBT 还原 STAGE_ENTER_MS / STAGE_EXIT_MS（先清空再灌）。 */
    public static void timeTablesFromNbt(NbtCompound t) {
        STAGE_ENTER_MS.clear();
        STAGE_EXIT_MS.clear();
        if (t == null || t.isEmpty()) return;
        NbtList enters = t.getList("enter", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < enters.size(); i++) {
            NbtCompound c = enters.getCompound(i);
            StageKey k = StageKey.fromNbt(c.getCompound("k"));
            if (k == null || StageKey.isSession(k)) continue;
            STAGE_ENTER_MS.put(k, c.getLong("v"));
        }
        NbtList exits = t.getList("exit", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < exits.size(); i++) {
            NbtCompound c = exits.getCompound(i);
            StageKey k = StageKey.fromNbt(c.getCompound("k"));
            if (k == null || StageKey.isSession(k)) continue;
            STAGE_EXIT_MS.put(k, c.getLong("v"));
        }
    }

    private static void fireSession() {
        for (Runnable r : SESSION_LISTENERS) {
            try { r.run(); }
            catch (Throwable t) { LOGGER.warn("[CTT BD] session listener threw: {}", t.toString()); }
        }
    }

    public static void onSessionChange(Runnable listener)        { SESSION_LISTENERS.add(listener); }

    private static void fire(CopyOnWriteArrayList<Consumer<StageKey>> list, StageKey key) {
        for (Consumer<StageKey> l : list) {
            try { l.accept(key); }
            catch (Throwable t) { LOGGER.warn("[CTT BD] listener threw: {}", t.toString()); }
        }
    }

    // ---------------------------------------------------------------------
    //  诊断 / 测试
    // ---------------------------------------------------------------------

    /** 调试：当前所有玩家的 stageKey 字符串映射。 */
    public static Map<UUID, String> debugSnapshot() {
        Map<UUID, String> out = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, PlayerStage> e : PLAYER_STATES.entrySet()) {
            PlayerStage s = e.getValue();
            out.put(e.getKey(),
                    s.collecting ? String.valueOf(s.stageKey) : "(not-collecting kind=" + s.kindOrdinal + ")");
        }
        return out;
    }
}
