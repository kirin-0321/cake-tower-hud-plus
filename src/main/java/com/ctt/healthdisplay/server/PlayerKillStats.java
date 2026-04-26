package com.ctt.healthdisplay.server;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v6.4.0 · 按玩家击杀 / 助攻累计器。
 *
 * <h2>数据语义</h2>
 * <ul>
 *   <li><b>kills</b>：该玩家的致死一击命中数（方案 C · 最后一击 = kill）。
 *       只有 L1~L6 + L7b（已分类层）的致死归属才计入玩家账户。</li>
 *   <li><b>assists</b>：该玩家对某 victim 造成过**已分类**伤害、但 killer 不是自己的事件数。
 *       同一 victim 一次死亡最多让每位助攻者 +1（不会每击 +1）。</li>
 *   <li><b>bossKills</b>：victim 死亡时带 {@code Boss} tag 的击杀数，单独子计。</li>
 *   <li><b>unattributedKills</b>：致死归属进 L7 / L8 / L8b / L9 的击杀（全局汇总，不计入玩家账户）。
 *       和 {@link PlayerDamageStats#addUnattributed} 的策略一致。</li>
 * </ul>
 *
 * <h2>会话控制</h2>
 * <p>默认**和 {@link PlayerDamageStats} 共享 session 状态**：
 * {@link #syncStart}、{@link #syncClear}、{@link #syncStop} 由
 * {@code PlayerDamageStats.start/clear/stop} 调用。面板按钮只操作 damageStats，
 * killStats 被动跟随，避免两套控制钮 UI 复杂度。
 *
 * <h2>线程安全</h2>
 * <p>所有字段用 {@link AtomicLong} / {@link AtomicInteger} / {@link ConcurrentHashMap}。
 * 快照返回不可变 record。
 */
public final class PlayerKillStats {

    /** victim 种类（阶段 ② 先粗分 BOSS / MOB / UNKNOWN，阶段 ⑤ 细化 PLAYER / TEST_DUMMY 等）。 */
    public enum VictimKind {
        MOB, BOSS, UNKNOWN
    }

    /** 单玩家击杀累计条目。 */
    public static final class Entry {
        public final UUID uuid;
        public volatile String name;
        final AtomicInteger kills = new AtomicInteger();
        final AtomicInteger bossKills = new AtomicInteger();
        final AtomicInteger assists = new AtomicInteger();
        final AtomicLong lastKillTick = new AtomicLong(-1);
        /** 各归属层命中次数（与 AttackerProbe.Layer.ordinal 对齐）。 */
        final int[] layerCounts = new int[AttackerProbe.Layer.values().length];
        private final Object layerLock = new Object();

        Entry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        void addLayer(AttackerProbe.Layer layer) {
            synchronized (layerLock) { layerCounts[layer.ordinal()]++; }
        }

        int[] snapshotLayers() {
            synchronized (layerLock) { return layerCounts.clone(); }
        }
    }

    /** 渲染 / 外部读取用的单玩家快照行。 */
    public record PlayerRow(
            UUID uuid,
            String name,
            int kills,
            int bossKills,
            int assists,
            long lastKillTick,
            int[] layerCounts
    ) {}

    /** 整体快照。 */
    public record Snapshot(
            boolean live,
            boolean frozen,
            long startTick,
            long startMs,
            long durationMs,
            List<PlayerRow> players,
            int totalKills,
            int totalBossKills,
            int totalAssists,
            int unattributedKills
    ) {}

    private static final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private static final AtomicInteger unattributedKills = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean live = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean frozen = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final AtomicLong startTick = new AtomicLong();
    private static final AtomicLong startMs = new AtomicLong();

    // ===== v6.6.0 · M1 关卡分桶（仅"已分类击杀 / 助攻"分桶；unattributedKills 保留 session 级）=====
    static final class StageState {
        final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    }
    private static final Map<StageKey, StageState> stageBuckets = new ConcurrentHashMap<>();

    private PlayerKillStats() {}

    // =========================================================================
    //  会话控制（被 PlayerDamageStats piggy-back 调用）
    // =========================================================================

    /** 跟随 PlayerDamageStats.start 清零并开启。 */
    public static void syncStart() {
        entries.clear();
        stageBuckets.clear();
        unattributedKills.set(0);
        VictimDamageContributors.clearAll();
        startMs.set(System.currentTimeMillis());
        startTick.set(DamageProbe.currentTick());
        frozen.set(false);
        live.set(true);
    }

    /** 跟随 PlayerDamageStats.stop 冻结。 */
    public static void syncStop() {
        live.set(false);
        frozen.set(true);
    }

    /**
     * 跟随 PlayerDamageStats.clear 抹零并重置 session flag。
     *
     * <p>v6.6.6 hotfix · 必须强制 {@code live=true, frozen=false} 与
     * {@link PlayerDamageStats#clear()} 内的同名字段保持一致——否则当
     * persistence 还原出 {@code kill.live=false} 时（例如玩家曾点过 L 键
     * ⏹ stop 按钮把 live=false 写进了 ctt_stats.dat），T1F1 触发的
     * auto-clear 不会把 kill.live 改回 true，本类的 recordKill / recordAssist
     * 入口 {@code if (!live.get()) return} 永远拦截，击杀就再也不统计了。
     */
    public static void syncClear() {
        entries.clear();
        stageBuckets.clear();
        unattributedKills.set(0);
        VictimDamageContributors.clearAll();
        startMs.set(System.currentTimeMillis());
        startTick.set(DamageProbe.currentTick());
        frozen.set(false);
        live.set(true);
    }

    public static void setFrozen(boolean f) { frozen.set(f); }
    public static boolean isLive() { return live.get(); }
    public static boolean isFrozen() { return frozen.get(); }

    // =========================================================================
    //  写入路径（由 VictimTombstone 调用）
    // =========================================================================

    /**
     * 记入一次已分类击杀。
     * <p>v6.6.0 · M1 · 入口先查 {@link StageBoundaryDispatcher#isCollecting}：
     * 击杀者不在战斗关时拦截（不应该发生 —— 击杀总在战斗关里产生 —— 但保留安全网）；
     * stage 分桶把击杀计入对应 {@link StageKey} 的 bucket。
     *
     * @param stageKey 调用方可传 {@code null}，自动从 dispatcher 取真值
     * @param layer    致死一击的归属层（决定是否进该玩家 kills 字段）
     * @param victimKind BOSS / MOB / UNKNOWN
     */
    public static void recordKill(UUID killerUuid, String killerName,
                                  StageKey stageKey, AttackerProbe.Layer layer,
                                  VictimKind victimKind, long tick) {
        if (!live.get() || frozen.get()) return;
        if (killerUuid == null) return;
        if (!StageBoundaryDispatcher.isCollecting(killerUuid)) return;
        if (stageKey == null) stageKey = StageBoundaryDispatcher.currentStageKey(killerUuid);

        // 写入 1：session 总
        Entry e = entries.computeIfAbsent(killerUuid,
                u -> new Entry(u, killerName == null ? "?" : killerName));
        if (killerName != null && !killerName.isEmpty()) e.name = killerName;
        e.kills.incrementAndGet();
        if (victimKind == VictimKind.BOSS) e.bossKills.incrementAndGet();
        e.lastKillTick.set(tick);
        e.addLayer(layer);

        // 写入 2：stage bucket
        if (stageKey != null) {
            StageState bucket = stageBuckets.computeIfAbsent(stageKey, k -> new StageState());
            Entry be = bucket.entries.computeIfAbsent(killerUuid,
                    u -> new Entry(u, killerName == null ? "?" : killerName));
            if (killerName != null && !killerName.isEmpty()) be.name = killerName;
            be.kills.incrementAndGet();
            if (victimKind == VictimKind.BOSS) be.bossKills.incrementAndGet();
            be.lastKillTick.set(tick);
            be.addLayer(layer);
        }
    }

    /** 记入一次助攻（同 victim 同玩家一次死亡最多 +1）。 */
    public static void recordAssist(UUID assistUuid, String assistName,
                                    StageKey stageKey, long tick) {
        if (!live.get() || frozen.get()) return;
        if (assistUuid == null) return;
        if (!StageBoundaryDispatcher.isCollecting(assistUuid)) return;
        if (stageKey == null) stageKey = StageBoundaryDispatcher.currentStageKey(assistUuid);

        Entry e = entries.computeIfAbsent(assistUuid,
                u -> new Entry(u, assistName == null ? "?" : assistName));
        if (assistName != null && !assistName.isEmpty()) e.name = assistName;
        e.assists.incrementAndGet();

        if (stageKey != null) {
            StageState bucket = stageBuckets.computeIfAbsent(stageKey, k -> new StageState());
            Entry be = bucket.entries.computeIfAbsent(assistUuid,
                    u -> new Entry(u, assistName == null ? "?" : assistName));
            if (assistName != null && !assistName.isEmpty()) be.name = assistName;
            be.assists.incrementAndGet();
        }
    }

    /** 致死归属未分类（L7/L8/L8b/L9 或完全丢失）。仅 session 级，不分桶。 */
    public static void recordUnattributedKill(StageKey stageKey, VictimKind victimKind, long tick) {
        if (!live.get() || frozen.get()) return;
        unattributedKills.incrementAndGet();
    }

    // =========================================================================
    //  读取路径（渲染端）
    // =========================================================================

    public static Snapshot snapshot() {
        List<PlayerRow> rows = new ArrayList<>(entries.size());
        int totalKills = 0, totalBossKills = 0, totalAssists = 0;

        for (Entry e : entries.values()) {
            int k = e.kills.get();
            int bk = e.bossKills.get();
            int a = e.assists.get();
            rows.add(new PlayerRow(e.uuid, e.name, k, bk, a, e.lastKillTick.get(), e.snapshotLayers()));
            totalKills += k;
            totalBossKills += bk;
            totalAssists += a;
        }

        // 默认按 kills 降序，次排序 assists
        rows.sort(Comparator.comparingInt(PlayerRow::kills)
                .thenComparingInt(PlayerRow::assists).reversed());

        long now = System.currentTimeMillis();
        long startedAt = startMs.get();
        long dur = startedAt > 0 ? (now - startedAt) : 0;

        return new Snapshot(
                live.get(), frozen.get(),
                startTick.get(), startedAt, dur,
                Collections.unmodifiableList(rows),
                totalKills, totalBossKills, totalAssists,
                unattributedKills.get()
        );
    }

    /** 便捷查询：某玩家当前 kills（面板合并行用）。 */
    public static int getKills(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? 0 : e.kills.get();
    }

    /** 便捷查询：某玩家当前 assists。 */
    public static int getAssists(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? 0 : e.assists.get();
    }

    /**
     * v6.6.0 · M1 · 单关切片快照。
     *
     * <p>仅含该 {@link StageKey} 内累计的击杀 / 助攻；{@code unattributedKills}
     * 不分桶，切片视图里恒为 0。
     */
    public static Snapshot snapshotOf(StageKey stageKey) {
        StageState bucket = stageKey == null ? null : stageBuckets.get(stageKey);
        long now = System.currentTimeMillis();
        long startedAt = startMs.get();
        long dur = startedAt > 0 ? (now - startedAt) : 0;
        if (bucket == null) {
            return new Snapshot(
                    live.get(), frozen.get(),
                    startTick.get(), startedAt, dur,
                    Collections.emptyList(), 0, 0, 0, 0
            );
        }
        List<PlayerRow> rows = new ArrayList<>(bucket.entries.size());
        int totalKills = 0, totalBossKills = 0, totalAssists = 0;
        for (Entry e : bucket.entries.values()) {
            int k = e.kills.get();
            int bk = e.bossKills.get();
            int a = e.assists.get();
            rows.add(new PlayerRow(e.uuid, e.name, k, bk, a, e.lastKillTick.get(), e.snapshotLayers()));
            totalKills += k;
            totalBossKills += bk;
            totalAssists += a;
        }
        rows.sort(Comparator.comparingInt(PlayerRow::kills)
                .thenComparingInt(PlayerRow::assists).reversed());
        return new Snapshot(
                live.get(), frozen.get(),
                startTick.get(), startedAt, dur,
                Collections.unmodifiableList(rows),
                totalKills, totalBossKills, totalAssists,
                0
        );
    }

    /** v6.6.0 · M1 · 单玩家 × 单关 击杀数（HUD 队友面板用）。 */
    public static int getKillsAt(UUID uuid, StageKey stageKey) {
        if (stageKey == null) return 0;
        StageState bucket = stageBuckets.get(stageKey);
        if (bucket == null) return 0;
        Entry e = bucket.entries.get(uuid);
        return e == null ? 0 : e.kills.get();
    }

    /** v6.6.0 · M1 · 单玩家 × 单关 助攻数。 */
    public static int getAssistsAt(UUID uuid, StageKey stageKey) {
        if (stageKey == null) return 0;
        StageState bucket = stageBuckets.get(stageKey);
        if (bucket == null) return 0;
        Entry e = bucket.entries.get(uuid);
        return e == null ? 0 : e.assists.get();
    }

    // =========================================================================
    //  v6.6.0 · M4 · K 表格"☠B"列直读 helper
    //  设计 §6.4 总表把 Boss 击杀拆出独立紫色列，只跟 ☠ 全局列并行展示。
    // =========================================================================

    /** 该玩家本局 session 的 Boss 击杀数（victim 死亡时带 Boss tag）。 */
    public static int getBossKills(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? 0 : e.bossKills.get();
    }

    /** 该玩家在某 stageKey 的 Boss 击杀数。 */
    public static int getBossKillsAt(UUID uuid, StageKey stageKey) {
        if (stageKey == null) return 0;
        StageState bucket = stageBuckets.get(stageKey);
        if (bucket == null) return 0;
        Entry e = bucket.entries.get(uuid);
        return e == null ? 0 : e.bossKills.get();
    }

    /** v6.6.0 · M4 · 当前已记录击杀 / 助攻数据的所有 stageKey（K 表格分关页用）。 */
    public static java.util.Set<StageKey> recordedStageKeys() {
        return java.util.Collections.unmodifiableSet(stageBuckets.keySet());
    }

    /**
     * v6.6.0 · M4 · 取 session 净活跃时长 ms（K 表格右上角"Session: HH:MM:SS"用）。
     * 大厅 / 小游戏 / GameOver 期间 {@link PlayerDamageStats#setFrozen} 暂停累计，
     * 实现"回大厅 → 计时器停摆但数据保留"的语义。
     */
    public static long sessionDurationMs() {
        return PlayerDamageStats.activeDurationMs();
    }

    // =========================================================================
    //  v6.6.1 · M2 · NBT 持久化
    // =========================================================================

    public static NbtCompound toNbt() {
        NbtCompound t = new NbtCompound();
        t.putLong("startMs", startMs.get());
        t.putLong("startTick", startTick.get());
        t.putBoolean("live", live.get());
        t.putBoolean("frozen", frozen.get());
        t.putInt("uKills", unattributedKills.get());

        NbtList players = new NbtList();
        for (Entry e : entries.values()) players.add(entryToNbt(e));
        t.put("players", players);

        NbtList stages = new NbtList();
        for (Map.Entry<StageKey, StageState> en : stageBuckets.entrySet()) {
            NbtCompound s = new NbtCompound();
            s.put("key", en.getKey().toNbt());
            NbtList sp = new NbtList();
            for (Entry e : en.getValue().entries.values()) sp.add(entryToNbt(e));
            s.put("players", sp);
            stages.add(s);
        }
        t.put("stages", stages);
        return t;
    }

    public static void fromNbt(NbtCompound t) {
        entries.clear();
        stageBuckets.clear();
        unattributedKills.set(0);
        if (t == null || t.isEmpty()) return;

        startMs.set(t.getLong("startMs"));
        startTick.set(t.getLong("startTick"));
        // v6.6.6 hotfix · 不再从 NBT 还原 live / frozen（同 PlayerDamageStats.fromNbt 注释）。
        unattributedKills.set(t.getInt("uKills"));

        NbtList players = t.getList("players", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < players.size(); i++) {
            Entry e = entryFromNbt(players.getCompound(i));
            if (e != null) entries.put(e.uuid, e);
        }

        NbtList stages = t.getList("stages", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < stages.size(); i++) {
            NbtCompound s = stages.getCompound(i);
            StageKey key = StageKey.fromNbt(s.getCompound("key"));
            if (key == null || StageKey.isSession(key)) continue;
            StageState bucket = new StageState();
            NbtList sp = s.getList("players", NbtElement.COMPOUND_TYPE);
            for (int j = 0; j < sp.size(); j++) {
                Entry e = entryFromNbt(sp.getCompound(j));
                if (e != null) bucket.entries.put(e.uuid, e);
            }
            stageBuckets.put(key, bucket);
        }
    }

    private static NbtCompound entryToNbt(Entry e) {
        NbtCompound c = new NbtCompound();
        c.putUuid("u", e.uuid);
        c.putString("n", e.name == null ? "?" : e.name);
        c.putInt("k", e.kills.get());
        c.putInt("bk", e.bossKills.get());
        c.putInt("a", e.assists.get());
        c.putLong("lkt", e.lastKillTick.get());
        int[] lc = e.snapshotLayers();
        long[] lcLong = new long[lc.length];
        for (int i = 0; i < lc.length; i++) lcLong[i] = lc[i];
        c.putLongArray("lc", lcLong);
        return c;
    }

    private static Entry entryFromNbt(NbtCompound c) {
        if (c == null || !c.containsUuid("u")) return null;
        UUID uuid = c.getUuid("u");
        String name = c.getString("n");
        if (name.isEmpty()) name = "?";
        Entry e = new Entry(uuid, name);
        e.kills.set(c.getInt("k"));
        e.bossKills.set(c.getInt("bk"));
        e.assists.set(c.getInt("a"));
        e.lastKillTick.set(c.getLong("lkt"));
        long[] lc = c.getLongArray("lc");
        for (int i = 0; i < e.layerCounts.length && i < lc.length; i++) {
            e.layerCounts[i] = (int) Math.min(Integer.MAX_VALUE, lc[i]);
        }
        return e;
    }
}
