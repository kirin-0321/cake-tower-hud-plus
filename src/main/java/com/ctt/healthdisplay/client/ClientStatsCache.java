package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.network.StatsSnapshotPayload;
import com.ctt.healthdisplay.server.AttackerProbe;
import com.ctt.healthdisplay.server.CttStatsServer;
import com.ctt.healthdisplay.server.PlayerDamageStats;
import com.ctt.healthdisplay.server.PlayerDpsTracker;
import com.ctt.healthdisplay.server.PlayerKillStats;
import com.ctt.healthdisplay.server.PlayerTakenStats;
import com.ctt.healthdisplay.server.StageBoundaryDispatcher;
import com.ctt.healthdisplay.server.StageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * v6.6.5 · M6 · 客户端 stats 数据镜像层。
 *
 * <h2>动机</h2>
 * <p>v6.6.0 ~ v6.6.3 期间为图省事，所有客户端代码（HUD / K 表 / L 面板）都直接静态调
 * {@code com.ctt.healthdisplay.server.*} 的 stats 类。这只在 <i>集成服务器</i>（host
 * 自己开 LAN 主机 + 单机）下成立，因为 client + server 同 JVM。专用服务器 / LAN 远程
 * 客户端等场景下 server 类的静态字段是空的，HUD 显示 0 数据。
 *
 * <p>本类作为<i>统一接入点</i>：
 * <ul>
 *   <li>集成服务器（{@link CttStatsServer#getServer()} != null）→ 委托原 server 静态调用，
 *       零开销零行为变化（仍是过去的"同 JVM 直读"路径）。</li>
 *   <li>专用服务器 / LAN 远程客户端 → 读最近一次收到的 {@link StatsSnapshotPayload}
 *       缓存，构造 server-shape 的 Snapshot record 给调用方。</li>
 * </ul>
 *
 * <h2>API 命名约定</h2>
 * <p>原 server 类有同名方法（{@code snapshot()}、{@code recordedStageKeys()} 等），
 * 这里 mirror 用<b>类名前缀</b>区分：{@code damageSnapshot()} / {@code killSnapshot()}
 * / {@code takenSnapshot()}；玩家维度的 getter 因签名唯一保留原名（{@code getDealt} /
 * {@code getKills} 等）。
 *
 * <h2>降级行为（dedicated 模式 payload 缺失字段）</h2>
 * <ul>
 *   <li>{@code unattributed*} / {@code globalLayerCounts}：payload 不传 → 一律 0。
 *       L 键开发面板的诊断字段在 dedicated 场景显示 0，但 HUD/K 表不依赖这些。</li>
 *   <li>{@code lastKillTick} / {@code lastTakenTick}：dedicated 模式恒为 0
 *       （客户端不需要 tick 精度，仅 last-Xxx-event "时新性"标记）。</li>
 *   <li>{@code layerCounts}：每行用 zero-filled 数组，长度 = Layer.values().length。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>{@link #latest} 用 {@code volatile}：networking receiver（client tick 线程）写，
 * HUD 渲染（render thread）读，写入是单一替换原子，读出后构造 record 是局部副本，
 * 无并发问题。
 */
public final class ClientStatsCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stats-cache");

    /** 最近一次收到的 payload；null = 还没收到任何 sync 包（开局或断线后）。 */
    private static volatile StatsSnapshotPayload latest;

    private ClientStatsCache() {}

    // =========================================================================
    //  接收端 / 生命周期
    // =========================================================================

    /** 由 {@code ClientPlayNetworking.registerGlobalReceiver} 在收到包时调用。 */
    public static void update(StatsSnapshotPayload payload) {
        latest = payload;
    }

    /** 切服 / 断线时调；防止悬挂上一局数据。 */
    public static void reset() { latest = null; }

    /**
     * 是否运行在集成服务器（host 端 / 单机本地）。
     * 专用服务器 client 与 LAN 远程 client 永远返回 false。
     */
    public static boolean isIntegrated() {
        return CttStatsServer.getServer() != null;
    }

    /** 当前缓存（可能为 null）。供"是否已有数据"判断。 */
    public static StatsSnapshotPayload latest() { return latest; }

    // =========================================================================
    //  全局 session 状态
    // =========================================================================

    /**
     * v6.6.0 · M4 · 净活跃时长。
     *
     * <p>dedicated 模式直接读 payload 里 server 算好的 {@code activeDurationMs}：
     * 1 Hz 推送间隔下 HUD 数字每秒跳一次，体感够用；不试图做"client 端动态追加"
     * （会受 client/server 时钟漂移污染）。
     */
    public static long sessionDurationMs() {
        if (isIntegrated()) return PlayerKillStats.sessionDurationMs();
        StatsSnapshotPayload p = latest;
        return p == null ? 0L : p.activeDurationMs();
    }

    /** 同 {@link #sessionDurationMs()}（旧调用风格保留）。 */
    public static long activeDurationMs() { return sessionDurationMs(); }

    public static boolean isLive() {
        if (isIntegrated()) return PlayerDamageStats.snapshot().live();
        StatsSnapshotPayload p = latest;
        return p != null && p.live();
    }

    public static boolean isFrozen() {
        if (isIntegrated()) return PlayerDamageStats.isFrozen();
        StatsSnapshotPayload p = latest;
        return p != null && p.frozen();
    }

    // =========================================================================
    //  StageBoundaryDispatcher 镜像
    // =========================================================================

    public static StageKey representativeStageKey() {
        if (isIntegrated()) return StageBoundaryDispatcher.representativeStageKey();
        StatsSnapshotPayload p = latest;
        if (p == null) return null;
        return stageAt(p, p.representativeStageIdx());
    }

    public static StageKey lastSeenStageKey(UUID uuid) {
        if (isIntegrated()) return StageBoundaryDispatcher.lastSeenStageKey(uuid);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null) return null;
        var e = findPlayer(p, uuid);
        if (e == null) return null;
        return stageAt(p, e.lastSeenStageIdx());
    }

    public static long stageEnterMs(StageKey key) {
        if (isIntegrated()) return StageBoundaryDispatcher.stageEnterMs(key);
        StatsSnapshotPayload p = latest;
        if (p == null) return 0L;
        int idx = stageIndexFor(p, key);
        return idx < 0 ? 0L : p.stages().get(idx).enterMs();
    }

    public static long stageDurationMs(StageKey key) {
        if (isIntegrated()) return StageBoundaryDispatcher.stageDurationMs(key);
        StatsSnapshotPayload p = latest;
        if (p == null) return 0L;
        int idx = stageIndexFor(p, key);
        if (idx < 0) return 0L;
        var s = p.stages().get(idx);
        long endpoint = s.exitMs() > s.enterMs()
                ? (s.inProgress() ? System.currentTimeMillis() : s.exitMs())
                : System.currentTimeMillis();
        return Math.max(0L, endpoint - s.enterMs());
    }

    public static boolean isStageInProgress(StageKey key) {
        if (isIntegrated()) return StageBoundaryDispatcher.isStageInProgress(key);
        StatsSnapshotPayload p = latest;
        if (p == null) return false;
        int idx = stageIndexFor(p, key);
        return idx >= 0 && p.stages().get(idx).inProgress();
    }

    /** 三家 stats 已记录的 stageKey 并集（K 表分关页用）。 */
    public static Set<StageKey> recordedStageKeys() {
        if (isIntegrated()) {
            Set<StageKey> all = new HashSet<>();
            all.addAll(PlayerDamageStats.recordedStageKeys());
            all.addAll(PlayerKillStats.recordedStageKeys());
            all.addAll(PlayerTakenStats.recordedStageKeys());
            return all;
        }
        StatsSnapshotPayload p = latest;
        if (p == null) return Collections.emptySet();
        Set<StageKey> out = new HashSet<>(p.stages().size());
        for (var s : p.stages()) out.add(s.toKey());
        return out;
    }

    // =========================================================================
    //  PlayerDamageStats 镜像
    // =========================================================================

    public static long getDealt(UUID uuid) {
        if (isIntegrated()) return PlayerDamageStats.getDealt(uuid);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null) return 0L;
        var e = findPlayer(p, uuid);
        return e == null ? 0L : e.dealt();
    }

    public static long getDealtAt(UUID uuid, StageKey key) {
        if (isIntegrated()) return PlayerDamageStats.getDealtAt(uuid, key);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null || key == null) return 0L;
        var e = findPlayer(p, uuid);
        if (e == null) return 0L;
        int idx = stageIndexFor(p, key);
        var r = findRow(e, idx);
        return r == null ? 0L : r.dealt();
    }

    /**
     * v6.6.7 · 该玩家最近 5 秒造成的伤害总量。HUD 关行 DPS = sum / 5。
     * <p>不按 stageKey 切片：滑窗本身只表达"近期手感"，跨关瞬间会同时反映前后两关
     * 末尾 + 开头，5 秒后自然衰减为只剩当前关。
     */
    public static long recent5sSum(UUID uuid) {
        if (isIntegrated()) return PlayerDpsTracker.recent5sSum(uuid);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null) return 0L;
        var e = findPlayer(p, uuid);
        return e == null ? 0L : e.recent5sSum();
    }

    /** 该玩家"最近 5 秒滑窗 DPS" = recent5sSum / 5。整数四舍五入。 */
    public static int recent5sDps(UUID uuid) {
        return Math.round(recent5sSum(uuid) / (float) PlayerDpsTracker.WINDOW_SECONDS);
    }

    public static PlayerDamageStats.Snapshot damageSnapshot() {
        if (isIntegrated()) return PlayerDamageStats.snapshot();
        return buildDamageSnapshot(latest, /*scope*/ null);
    }

    public static PlayerDamageStats.Snapshot damageSnapshotOf(StageKey key) {
        if (isIntegrated()) return PlayerDamageStats.snapshotOf(key);
        return buildDamageSnapshot(latest, key);
    }

    private static PlayerDamageStats.Snapshot buildDamageSnapshot(StatsSnapshotPayload p, StageKey scopeKey) {
        int layerLen = AttackerProbe.Layer.values().length;
        if (p == null) {
            return new PlayerDamageStats.Snapshot(
                    false, false, 0, 0, 0,
                    Collections.emptyList(),
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0,
                    new long[layerLen]
            );
        }

        boolean stageScope = scopeKey != null;
        int scopeIdx = stageScope ? stageIndexFor(p, scopeKey) : -1;
        if (stageScope && scopeIdx < 0) {
            // 该 stageKey 未在 payload 中：返回空切片快照（与 server 端行为一致）
            return emptyDamageSnapshotFromPayload(p, layerLen);
        }

        List<PlayerDamageStats.PlayerRow> rows = new ArrayList<>();
        long totalConfirmed = 0L;
        int totalEvents = 0;
        int totalMaxHit = 0;
        for (var pe : p.players()) {
            long dealt;
            int events;
            int maxHit;
            if (stageScope) {
                var r = findRow(pe, scopeIdx);
                if (r == null) continue;
                dealt = r.dealt();
                events = r.dealtEvents();
                maxHit = r.dealtMaxHit();
            } else {
                dealt = pe.dealt();
                events = pe.dealtEvents();
                maxHit = pe.dealtMaxHit();
            }
            // 跳过完全无数据的玩家行，与 server 端 snapshot()/snapshotOf() 收敛
            if (dealt == 0 && events == 0 && maxHit == 0) continue;
            rows.add(new PlayerDamageStats.PlayerRow(
                    pe.uuid(), pe.name() == null ? "?" : pe.name(),
                    dealt, events, maxHit,
                    new int[layerLen]
            ));
            totalConfirmed += dealt;
            totalEvents += events;
            if (maxHit > totalMaxHit) totalMaxHit = maxHit;
        }
        rows.sort(Comparator.comparingLong(PlayerDamageStats.PlayerRow::confirmed).reversed());

        long durationMs = computeGrossDuration(p);
        return new PlayerDamageStats.Snapshot(
                p.live(), p.frozen(),
                p.startTick(), p.startMs(), durationMs,
                Collections.unmodifiableList(rows),
                0, 0, 0, 0,    // unattributed* (dedicated 模式不传，恒 0)
                0, 0, 0, 0,    // unattributed*Events
                totalConfirmed, totalEvents, totalMaxHit,
                new long[layerLen]
        );
    }

    private static PlayerDamageStats.Snapshot emptyDamageSnapshotFromPayload(StatsSnapshotPayload p, int layerLen) {
        return new PlayerDamageStats.Snapshot(
                p.live(), p.frozen(),
                p.startTick(), p.startMs(), computeGrossDuration(p),
                Collections.emptyList(),
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0,
                new long[layerLen]
        );
    }

    // =========================================================================
    //  PlayerKillStats 镜像
    // =========================================================================

    public static int getKills(UUID uuid) {
        if (isIntegrated()) return PlayerKillStats.getKills(uuid);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null) return 0;
        var e = findPlayer(p, uuid);
        return e == null ? 0 : e.kills();
    }

    public static int getKillsAt(UUID uuid, StageKey key) {
        if (isIntegrated()) return PlayerKillStats.getKillsAt(uuid, key);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null || key == null) return 0;
        var e = findPlayer(p, uuid);
        if (e == null) return 0;
        var r = findRow(e, stageIndexFor(p, key));
        return r == null ? 0 : r.kills();
    }

    public static int getBossKills(UUID uuid) {
        if (isIntegrated()) return PlayerKillStats.getBossKills(uuid);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null) return 0;
        var e = findPlayer(p, uuid);
        return e == null ? 0 : e.bossKills();
    }

    public static int getBossKillsAt(UUID uuid, StageKey key) {
        if (isIntegrated()) return PlayerKillStats.getBossKillsAt(uuid, key);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null || key == null) return 0;
        var e = findPlayer(p, uuid);
        if (e == null) return 0;
        var r = findRow(e, stageIndexFor(p, key));
        return r == null ? 0 : r.bossKills();
    }

    public static int getAssists(UUID uuid) {
        if (isIntegrated()) return PlayerKillStats.getAssists(uuid);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null) return 0;
        var e = findPlayer(p, uuid);
        return e == null ? 0 : e.assists();
    }

    public static int getAssistsAt(UUID uuid, StageKey key) {
        if (isIntegrated()) return PlayerKillStats.getAssistsAt(uuid, key);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null || key == null) return 0;
        var e = findPlayer(p, uuid);
        if (e == null) return 0;
        var r = findRow(e, stageIndexFor(p, key));
        return r == null ? 0 : r.assists();
    }

    public static PlayerKillStats.Snapshot killSnapshot() {
        if (isIntegrated()) return PlayerKillStats.snapshot();
        return buildKillSnapshot(latest, /*scope*/ null);
    }

    public static PlayerKillStats.Snapshot killSnapshotOf(StageKey key) {
        if (isIntegrated()) return PlayerKillStats.snapshotOf(key);
        return buildKillSnapshot(latest, key);
    }

    private static PlayerKillStats.Snapshot buildKillSnapshot(StatsSnapshotPayload p, StageKey scopeKey) {
        int layerLen = AttackerProbe.Layer.values().length;
        if (p == null) {
            return new PlayerKillStats.Snapshot(
                    false, false, 0, 0, 0,
                    Collections.emptyList(),
                    0, 0, 0, 0
            );
        }
        boolean stageScope = scopeKey != null;
        int scopeIdx = stageScope ? stageIndexFor(p, scopeKey) : -1;
        if (stageScope && scopeIdx < 0) {
            return new PlayerKillStats.Snapshot(
                    p.live(), p.frozen(),
                    p.startTick(), p.startMs(), computeGrossDuration(p),
                    Collections.emptyList(),
                    0, 0, 0, 0
            );
        }

        List<PlayerKillStats.PlayerRow> rows = new ArrayList<>();
        int totalKills = 0, totalBoss = 0, totalAssists = 0;
        for (var pe : p.players()) {
            int kills, boss, assists;
            if (stageScope) {
                var r = findRow(pe, scopeIdx);
                if (r == null) continue;
                kills = r.kills(); boss = r.bossKills(); assists = r.assists();
            } else {
                kills = pe.kills(); boss = pe.bossKills(); assists = pe.assists();
            }
            if (kills == 0 && boss == 0 && assists == 0) continue;
            rows.add(new PlayerKillStats.PlayerRow(
                    pe.uuid(), pe.name() == null ? "?" : pe.name(),
                    kills, boss, assists,
                    0L,                  // lastKillTick: 客户端不需精确 tick
                    new int[layerLen]
            ));
            totalKills += kills; totalBoss += boss; totalAssists += assists;
        }
        rows.sort(Comparator.comparingInt(PlayerKillStats.PlayerRow::kills)
                .thenComparingInt(PlayerKillStats.PlayerRow::assists).reversed());

        return new PlayerKillStats.Snapshot(
                p.live(), p.frozen(),
                p.startTick(), p.startMs(), computeGrossDuration(p),
                Collections.unmodifiableList(rows),
                totalKills, totalBoss, totalAssists,
                stageScope ? 0 : p.unattributedKills()
        );
    }

    // =========================================================================
    //  PlayerTakenStats 镜像
    // =========================================================================

    public static long getTaken(UUID uuid) {
        if (isIntegrated()) return PlayerTakenStats.getTaken(uuid);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null) return 0L;
        var e = findPlayer(p, uuid);
        return e == null ? 0L : e.taken();
    }

    public static long getTakenAt(UUID uuid, StageKey key) {
        if (isIntegrated()) return PlayerTakenStats.getTakenAt(uuid, key);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null || key == null) return 0L;
        var e = findPlayer(p, uuid);
        if (e == null) return 0L;
        var r = findRow(e, stageIndexFor(p, key));
        return r == null ? 0L : r.taken();
    }

    public static int getMaxHit(UUID uuid) {
        if (isIntegrated()) return PlayerTakenStats.getMaxHit(uuid);
        StatsSnapshotPayload p = latest;
        if (p == null || uuid == null) return 0;
        var e = findPlayer(p, uuid);
        return e == null ? 0 : e.takenMaxHit();
    }

    public static PlayerTakenStats.Snapshot takenSnapshot() {
        if (isIntegrated()) return PlayerTakenStats.snapshot();
        return buildTakenSnapshot(latest, /*scope*/ null);
    }

    public static PlayerTakenStats.Snapshot takenSnapshotOf(StageKey key) {
        if (isIntegrated()) return PlayerTakenStats.snapshotOf(key);
        return buildTakenSnapshot(latest, key);
    }

    private static PlayerTakenStats.Snapshot buildTakenSnapshot(StatsSnapshotPayload p, StageKey scopeKey) {
        if (p == null) {
            return new PlayerTakenStats.Snapshot(
                    false, false, 0, 0, 0,
                    Collections.emptyList(),
                    0, 0, 0
            );
        }
        boolean stageScope = scopeKey != null;
        int scopeIdx = stageScope ? stageIndexFor(p, scopeKey) : -1;
        if (stageScope && scopeIdx < 0) {
            return new PlayerTakenStats.Snapshot(
                    p.live(), p.frozen(),
                    p.startTick(), p.startMs(), computeGrossDuration(p),
                    Collections.emptyList(),
                    0, 0, 0
            );
        }

        List<PlayerTakenStats.PlayerRow> rows = new ArrayList<>();
        long totalTaken = 0L;
        int totalEvents = 0, totalMaxHit = 0;
        for (var pe : p.players()) {
            long taken;
            int events, maxHit;
            if (stageScope) {
                var r = findRow(pe, scopeIdx);
                if (r == null) continue;
                taken = r.taken(); events = r.takenEvents(); maxHit = r.takenMaxHit();
            } else {
                taken = pe.taken(); events = pe.takenEvents(); maxHit = pe.takenMaxHit();
            }
            if (taken == 0 && events == 0 && maxHit == 0) continue;
            rows.add(new PlayerTakenStats.PlayerRow(
                    pe.uuid(), pe.name() == null ? "?" : pe.name(),
                    taken, events, maxHit,
                    0L                      // lastTakenTick：客户端不需要
            ));
            totalTaken += taken;
            totalEvents += events;
            if (maxHit > totalMaxHit) totalMaxHit = maxHit;
        }
        rows.sort(Comparator.comparingLong(PlayerTakenStats.PlayerRow::taken).reversed());

        return new PlayerTakenStats.Snapshot(
                p.live(), p.frozen(),
                p.startTick(), p.startMs(), computeGrossDuration(p),
                Collections.unmodifiableList(rows),
                totalTaken, totalEvents, totalMaxHit
        );
    }

    // =========================================================================
    //  内部 helpers
    // =========================================================================

    private static StatsSnapshotPayload.PlayerEntry findPlayer(StatsSnapshotPayload p, UUID uuid) {
        if (p == null || uuid == null) return null;
        for (var e : p.players()) {
            if (Objects.equals(e.uuid(), uuid)) return e;
        }
        return null;
    }

    private static StatsSnapshotPayload.StageRow findRow(StatsSnapshotPayload.PlayerEntry e, int stageIdx) {
        if (e == null || stageIdx < 0) return null;
        for (var r : e.stageRows()) {
            if (r.stageIdx() == stageIdx) return r;
        }
        return null;
    }

    private static StageKey stageAt(StatsSnapshotPayload p, int idx) {
        if (p == null || idx < 0 || idx >= p.stages().size()) return null;
        return p.stages().get(idx).toKey();
    }

    private static int stageIndexFor(StatsSnapshotPayload p, StageKey key) {
        if (p == null || key == null) return -1;
        List<StatsSnapshotPayload.StageEntry> stages = p.stages();
        for (int i = 0; i < stages.size(); i++) {
            if (sameStage(stages.get(i).toKey(), key)) return i;
        }
        return -1;
    }

    private static boolean sameStage(StageKey a, StageKey b) {
        if (a == null || b == null) return a == b;
        return Objects.equals(a.gameId(), b.gameId())
            && Objects.equals(a.tier(), b.tier())
            && Objects.equals(a.floor(), b.floor())
            && Objects.equals(a.stageType(), b.stageType())
            && Objects.equals(a.stageNum(), b.stageNum());
    }

    /** 与 server 端 {@code snapshot().durationMs} 一致：gross = now - startMs。 */
    private static long computeGrossDuration(StatsSnapshotPayload p) {
        long startedAt = p.startMs();
        return startedAt > 0 ? Math.max(0L, System.currentTimeMillis() - startedAt) : 0L;
    }
}
