package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.network.StatsSnapshotPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v6.6.5 · M6 · 服务端 → 客户端 stats 全量快照打包 + 推送。
 *
 * <h2>触发</h2>
 * <ul>
 *   <li>{@link #tickPushIfDue}：在 {@code END_SERVER_TICK} 注册，每 {@value #PUSH_INTERVAL_TICKS}
 *       tick (≈ 1 Hz) 给所有在线玩家广播一次。</li>
 *   <li>{@link #pushTo}：玩家 JOIN 时立即推一次首屏，UI 不需要等下一个心跳。</li>
 * </ul>
 *
 * <h2>设计</h2>
 * <ul>
 *   <li>每次打包从三家 stats（{@link PlayerDamageStats} / {@link PlayerKillStats}
 *       / {@link PlayerTakenStats}）的 {@code snapshot()} + {@code snapshotOf(stageKey)}
 *       读出全量数据，按 {@code stageKey} 索引化压缩进 {@link StatsSnapshotPayload}。</li>
 *   <li>玩家行只放有数据的（dealt / taken / kills / bossKills / assists / events 任一非零）。
 *       per-stage row 同理 → 包大小自动随实际数据增长，空 session 时 ~50 bytes。</li>
 *   <li>所有 {@code stageKey} 元数据（enterMs/exitMs/inProgress）都从 {@link StageBoundaryDispatcher}
 *       读，与 client 端 K 表"分关页"显示一致。</li>
 * </ul>
 *
 * <h2>性能</h2>
 * <p>整库扫描每秒一次，4 玩家 × 20 stage 实测 build 用时 < 1 ms。打包后字节流广播给
 * 所有玩家用同一份 payload 实例（PacketCodec 编码缓存），CPU 开销可忽略。
 */
public final class StatsSnapshotBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stats-broadcast");

    /** 推送间隔（tick）。20 tick = 1 秒（v6.6.5 拍板：1 Hz）。 */
    private static final int PUSH_INTERVAL_TICKS = 20;

    private static int tickCounter = 0;

    private StatsSnapshotBroadcaster() {}

    // =========================================================================
    //  外部入口
    // =========================================================================

    /** {@code END_SERVER_TICK} 钩子：到时间就广播。 */
    public static void tickPushIfDue(MinecraftServer server) {
        if (++tickCounter < PUSH_INTERVAL_TICKS) return;
        tickCounter = 0;
        if (server == null) return;
        // 没玩家在线就跳过 build（节省 CPU；下次有人 JOIN 时单独触发首推）
        if (server.getPlayerManager().getPlayerList().isEmpty()) return;
        StatsSnapshotPayload payload;
        try {
            payload = build();
        } catch (Throwable t) {
            LOGGER.warn("[CTT Stats Sync] build payload failed: {}", t.toString());
            return;
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            sendSafely(p, payload);
        }
    }

    /** 单个玩家首推（{@code JOIN} 钩子用）。 */
    public static void pushTo(ServerPlayerEntity player) {
        if (player == null) return;
        StatsSnapshotPayload payload;
        try {
            payload = build();
        } catch (Throwable t) {
            LOGGER.warn("[CTT Stats Sync] build payload (JOIN) failed: {}", t.toString());
            return;
        }
        sendSafely(player, payload);
    }

    private static void sendSafely(ServerPlayerEntity p, StatsSnapshotPayload payload) {
        try {
            ServerPlayNetworking.send(p, payload);
        } catch (Throwable t) {
            LOGGER.warn("[CTT Stats Sync] send to {} failed: {}",
                    p.getName().getString(), t.toString());
        }
    }

    // =========================================================================
    //  打包
    // =========================================================================

    private static StatsSnapshotPayload build() {
        // ---- 1) stage 表（所有 stageKey 排序去重 → 索引）----
        Set<StageKey> allKeys = new LinkedHashSet<>();
        allKeys.addAll(PlayerDamageStats.recordedStageKeys());
        allKeys.addAll(PlayerKillStats.recordedStageKeys());
        allKeys.addAll(PlayerTakenStats.recordedStageKeys());

        List<StageKey> sortedKeys = new ArrayList<>(allKeys);
        sortedKeys.sort(Comparator
                .comparingInt((StageKey k) -> parseInt(k.tier()))
                .thenComparingInt(k -> parseInt(k.floor()))
                .thenComparingInt(k -> parseInt(k.stageNum()))
                .thenComparing(k -> str(k.stageType()))
                .thenComparing(k -> str(k.gameId())));

        Map<StageKey, Integer> stageIdx = new HashMap<>(sortedKeys.size());
        List<StatsSnapshotPayload.StageEntry> stages = new ArrayList<>(sortedKeys.size());
        // 每 stage 只取一次 snapshotOf，避免 O(玩家 × 关) 重复构造（snapshotOf 会
        // 复制整个 entries map，4 玩家 × 20 关 = 80 次会成为热点）。
        Map<StageKey, Map<UUID, PlayerDamageStats.PlayerRow>> dmgStageRows = new HashMap<>();
        Map<StageKey, Map<UUID, PlayerTakenStats.PlayerRow>>  takStageRows = new HashMap<>();
        Map<StageKey, Map<UUID, PlayerKillStats.PlayerRow>>   kilStageRows = new HashMap<>();
        for (StageKey k : sortedKeys) {
            stageIdx.put(k, stages.size());
            long enter = StageBoundaryDispatcher.stageEnterMs(k);
            long exit  = StageBoundaryDispatcher.stageExitMs(k);
            boolean inProg = StageBoundaryDispatcher.isStageInProgress(k);
            stages.add(StatsSnapshotPayload.StageEntry.fromKey(k, enter, exit, inProg));

            Map<UUID, PlayerDamageStats.PlayerRow> dr = new HashMap<>();
            for (var r : PlayerDamageStats.snapshotOf(k).players()) dr.put(r.uuid(), r);
            dmgStageRows.put(k, dr);

            Map<UUID, PlayerTakenStats.PlayerRow> tr = new HashMap<>();
            for (var r : PlayerTakenStats.snapshotOf(k).players()) tr.put(r.uuid(), r);
            takStageRows.put(k, tr);

            Map<UUID, PlayerKillStats.PlayerRow> kr = new HashMap<>();
            for (var r : PlayerKillStats.snapshotOf(k).players()) kr.put(r.uuid(), r);
            kilStageRows.put(k, kr);
        }

        // representative stageKey 索引
        StageKey repKey = StageBoundaryDispatcher.representativeStageKey();
        int repIdx = repKey == null ? -1 : stageIdx.getOrDefault(repKey, -1);

        // ---- 2) 玩家全集（三家 union）----
        // 用 server snapshot 拿"name"，并保留 PlayerDamageStats.PlayerRow 里的 events/maxHit
        // 字段（getter 没暴露这些，只能从 snapshot.players() 拿）。
        var dmgSnap = PlayerDamageStats.snapshot();
        var kilSnap = PlayerKillStats.snapshot();
        var takSnap = PlayerTakenStats.snapshot();

        // UUID → 各家 row 的索引（缺则 null）
        Map<UUID, PlayerDamageStats.PlayerRow> dmgRows = new HashMap<>();
        for (var r : dmgSnap.players()) dmgRows.put(r.uuid(), r);
        Map<UUID, PlayerKillStats.PlayerRow>   kilRows = new HashMap<>();
        for (var r : kilSnap.players()) kilRows.put(r.uuid(), r);
        Map<UUID, PlayerTakenStats.PlayerRow>  takRows = new HashMap<>();
        for (var r : takSnap.players()) takRows.put(r.uuid(), r);

        Set<UUID> allUuids = new LinkedHashSet<>();
        Map<UUID, String> names = new HashMap<>();
        for (var r : dmgSnap.players()) { allUuids.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }
        for (var r : kilSnap.players()) { allUuids.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }
        for (var r : takSnap.players()) { allUuids.add(r.uuid()); names.putIfAbsent(r.uuid(), r.name()); }

        // 该玩家的 lastSeenStage 即便没数据，也要包含进 player table 让 client HUD 能显示"上一关"。
        // 但 lastSeenStage 来自 dispatcher，遍历 PLAYER_STATES.keys 在这里没法拿 → 我们容忍：
        // 如果玩家完全无 stats 数据但有 lastSeen，HUD 显示 EMPTY 是合理的（设计 §5.5 兜底）。

        List<StatsSnapshotPayload.PlayerEntry> playerEntries = new ArrayList<>(allUuids.size());
        for (UUID uuid : allUuids) {
            var pd = dmgRows.get(uuid);
            var pk = kilRows.get(uuid);
            var pt = takRows.get(uuid);

            long dealt   = pd == null ? 0L : pd.confirmed();
            int dealtEv  = pd == null ? 0  : pd.events();
            int dealtMax = pd == null ? 0  : pd.maxHit();
            long taken   = pt == null ? 0L : pt.taken();
            int takenEv  = pt == null ? 0  : pt.events();
            int takenMax = pt == null ? 0  : pt.maxHit();
            int kills    = pk == null ? 0  : pk.kills();
            int boss     = pk == null ? 0  : pk.bossKills();
            int assists  = pk == null ? 0  : pk.assists();

            // lastSeen idx
            StageKey lastSeen = StageBoundaryDispatcher.lastSeenStageKey(uuid);
            int lastIdx = lastSeen == null ? -1 : stageIdx.getOrDefault(lastSeen, -1);

            // per-stage rows（用预构造的 stage row 缓存，避免 O(玩家 × 关) snapshot）
            List<StatsSnapshotPayload.StageRow> rows = new ArrayList<>();
            for (StageKey sk : sortedKeys) {
                var sdR = dmgStageRows.get(sk).get(uuid);
                var stR = takStageRows.get(sk).get(uuid);
                var kR  = kilStageRows.get(sk).get(uuid);
                long sd  = sdR == null ? 0L : sdR.confirmed();
                int sdEv = sdR == null ? 0  : sdR.events();
                int sdMax= sdR == null ? 0  : sdR.maxHit();
                long st_ = stR == null ? 0L : stR.taken();
                int stEv = stR == null ? 0  : stR.events();
                int stMax= stR == null ? 0  : stR.maxHit();
                int kk   = kR  == null ? 0  : kR.kills();
                int kb   = kR  == null ? 0  : kR.bossKills();
                int ka   = kR  == null ? 0  : kR.assists();
                if (sd == 0 && st_ == 0 && kk == 0 && kb == 0 && ka == 0
                        && sdEv == 0 && sdMax == 0 && stEv == 0 && stMax == 0) continue;

                rows.add(new StatsSnapshotPayload.StageRow(
                        stageIdx.get(sk), sd, st_, kk, kb, ka,
                        sdEv, sdMax, stEv, stMax
                ));
            }

            // v6.6.7 · HUD 关行 DPS 数据源：最近 5 秒伤害量
            long recent5sSum = PlayerDpsTracker.recent5sSum(uuid);

            playerEntries.add(new StatsSnapshotPayload.PlayerEntry(
                    uuid, names.getOrDefault(uuid, "?"),
                    dealt, taken, kills, boss, assists,
                    dealtEv, dealtMax, takenEv, takenMax,
                    lastIdx,
                    rows,
                    recent5sSum
            ));
        }

        // ---- 3) 全局 session 字段 ----
        long startMs = dmgSnap.startMs();
        long startTick = dmgSnap.startTick();
        boolean live = dmgSnap.live();
        boolean frozen = dmgSnap.frozen();
        long activeDur = PlayerDamageStats.activeDurationMs();
        int unKills = kilSnap.unattributedKills();

        return new StatsSnapshotPayload(
                StatsSnapshotPayload.CURRENT_VERSION,
                startMs, activeDur, startTick,
                live, frozen,
                unKills, repIdx,
                stages, playerEntries
        );
    }

    // =========================================================================
    //  小工具
    // =========================================================================

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String str(String s) { return s == null ? "" : s; }
}
