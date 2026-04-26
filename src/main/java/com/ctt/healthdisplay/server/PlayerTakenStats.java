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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v6.5.0 · 按玩家承伤（挨打）累计器。
 *
 * <h2>数据语义</h2>
 * <ul>
 *   <li><b>taken</b>：护甲减免后、蓝/黑/灵/红四层血扣除前的"本次应扣血量"累加。
 *       数据源是地图 {@code damage.mcfunction} line 983 每 tick 写入的
 *       {@code DamageTook = Damage} scoreboard，由 {@link PlayerTakenProbe}
 *       在 {@code END_SERVER_TICK} 时扫描在线 CTT 玩家后调用 {@link #addTaken}。</li>
 *   <li><b>events</b>：有效命中事件数（每 tick DamageTook&gt;0 算一次）。</li>
 *   <li><b>maxHit</b>：该玩家被打到的最大单次伤害值。</li>
 * </ul>
 *
 * <h2>和造伤口径的一致性</h2>
 * <p>地图 {@code damage.mcfunction} 先写 {@code DamageTook = Damage}（line 983），
 * 再 summon {@code DamageShower}（line 1021-1025）并把其 score 设为 {@code Damage}
 * （line 1028）。两个 score 是<b>同一 tick 内的同一个 Damage 值</b>：
 * 因此 PlayerTakenStats 的统计口径和 {@link PlayerDamageStats}（来自 DamageShower）
 * 严格对称 —— 满足 V6_STATS_DEV_PLAN §2 的"造伤 = 承伤"铁律。
 *
 * <h2>会话控制</h2>
 * <p>和 {@link PlayerKillStats} 一样采用 piggy-back 模式：{@link #syncStart}、
 * {@link #syncClear}、{@link #syncStop} 由 {@link PlayerDamageStats} 的同名按钮触发，
 * 保证三个 stats（造伤 / 击杀 / 承伤）共享同一时间窗口。
 *
 * <h2>线程安全</h2>
 * <p>写入路径来自 {@link PlayerTakenProbe#tickEnd}（服务端主线程），
 * 读取来自客户端渲染线程（integrated server 共享 JVM）。
 * 所有字段使用 {@link AtomicLong} / {@link ConcurrentHashMap} 保证一致性。
 */
public final class PlayerTakenStats {

    public static final class Entry {
        public final UUID uuid;
        public volatile String name;
        final AtomicLong taken = new AtomicLong();
        final AtomicInteger events = new AtomicInteger();
        final AtomicInteger maxHit = new AtomicInteger();
        final AtomicLong lastTakenTick = new AtomicLong(-1);

        Entry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    /** 单玩家快照行。 */
    public record PlayerRow(
            UUID uuid,
            String name,
            long taken,
            int events,
            int maxHit,
            long lastTakenTick
    ) {
        public double avg() { return events > 0 ? (double) taken / events : 0.0; }
    }

    /** 整体快照。 */
    public record Snapshot(
            boolean live,
            boolean frozen,
            long startTick,
            long startMs,
            long durationMs,
            List<PlayerRow> players,
            long totalTaken,
            int totalEvents,
            int totalMaxHit
    ) {}

    private static final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private static final AtomicBoolean live = new AtomicBoolean(false);
    private static final AtomicBoolean frozen = new AtomicBoolean(false);
    private static final AtomicLong startTick = new AtomicLong();
    private static final AtomicLong startMs = new AtomicLong();

    // ===== v6.6.0 · M1 关卡分桶 =====
    static final class StageState {
        final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    }
    private static final Map<StageKey, StageState> stageBuckets = new ConcurrentHashMap<>();

    private PlayerTakenStats() {}

    // =========================================================================
    //  会话控制（被 PlayerDamageStats piggy-back 调用）
    // =========================================================================

    public static void syncStart() {
        entries.clear();
        stageBuckets.clear();
        startMs.set(System.currentTimeMillis());
        startTick.set(DamageProbe.currentTick());
        frozen.set(false);
        live.set(true);
    }

    public static void syncStop() {
        live.set(false);
        frozen.set(true);
    }

    /**
     * 跟随 PlayerDamageStats.clear 抹零并重置 session flag。
     *
     * <p>v6.6.6 hotfix · 必须强制 {@code live=true, frozen=false} 与
     * {@link PlayerDamageStats#clear()} 内的同名字段保持一致——否则当
     * persistence 还原出 {@code taken.live=false} 时（例如玩家曾点过 L 键
     * ⏹ stop 按钮把 live=false 写进了 ctt_stats.dat），T1F1 触发的
     * auto-clear 不会把 taken.live 改回 true，{@link #addTaken} 入口
     * {@code if (!live.get()) return} 永远拦截，承伤就再也不统计了。
     */
    public static void syncClear() {
        entries.clear();
        stageBuckets.clear();
        startMs.set(System.currentTimeMillis());
        startTick.set(DamageProbe.currentTick());
        frozen.set(false);
        live.set(true);
    }

    public static void setFrozen(boolean f) { frozen.set(f); }
    public static boolean isLive() { return live.get(); }
    public static boolean isFrozen() { return frozen.get(); }

    // =========================================================================
    //  写入路径（由 PlayerTakenProbe 调用）
    // =========================================================================

    /**
     * 记一次承伤。damage 是地图 {@code DamageTook} scoreboard 的本 tick 值
     * （护甲减免后、四层血扣除前）。
     * <p>v6.6.0 · M1 · 入口先查 {@link StageBoundaryDispatcher#isCollecting}：
     * 玩家不在战斗关一律拦截（休息室假人挨打不计）；同时 stage 分桶。
     */
    public static void addTaken(UUID playerUuid, String playerName, int damage, long tick) {
        if (!live.get() || frozen.get()) return;
        if (playerUuid == null || damage <= 0) return;
        if (!StageBoundaryDispatcher.isCollecting(playerUuid)) return;
        StageKey stageKey = StageBoundaryDispatcher.currentStageKey(playerUuid);

        Entry e = entries.computeIfAbsent(playerUuid,
                u -> new Entry(u, playerName == null ? "?" : playerName));
        if (playerName != null && !playerName.isEmpty()) e.name = playerName;
        e.taken.addAndGet(damage);
        e.events.incrementAndGet();
        updateMax(e.maxHit, damage);
        e.lastTakenTick.set(tick);

        if (stageKey != null) {
            StageState bucket = stageBuckets.computeIfAbsent(stageKey, k -> new StageState());
            Entry be = bucket.entries.computeIfAbsent(playerUuid,
                    u -> new Entry(u, playerName == null ? "?" : playerName));
            if (playerName != null && !playerName.isEmpty()) be.name = playerName;
            be.taken.addAndGet(damage);
            be.events.incrementAndGet();
            updateMax(be.maxHit, damage);
            be.lastTakenTick.set(tick);
        }
    }

    private static void updateMax(AtomicInteger target, int candidate) {
        int cur;
        do {
            cur = target.get();
            if (candidate <= cur) return;
        } while (!target.compareAndSet(cur, candidate));
    }

    // =========================================================================
    //  读取路径（渲染端）
    // =========================================================================

    public static Snapshot snapshot() {
        List<PlayerRow> rows = new ArrayList<>(entries.size());
        long totalTaken = 0;
        int totalEvents = 0;
        int totalMaxHit = 0;

        for (Entry e : entries.values()) {
            long t = e.taken.get();
            int ev = e.events.get();
            int mh = e.maxHit.get();
            rows.add(new PlayerRow(e.uuid, e.name, t, ev, mh, e.lastTakenTick.get()));
            totalTaken += t;
            totalEvents += ev;
            if (mh > totalMaxHit) totalMaxHit = mh;
        }

        rows.sort(Comparator.comparingLong(PlayerRow::taken).reversed());

        long now = System.currentTimeMillis();
        long startedAt = startMs.get();
        long dur = startedAt > 0 ? (now - startedAt) : 0;

        return new Snapshot(
                live.get(), frozen.get(),
                startTick.get(), startedAt, dur,
                Collections.unmodifiableList(rows),
                totalTaken, totalEvents, totalMaxHit
        );
    }

    /** 便捷查询：某玩家当前 taken（面板合并行用）。 */
    public static long getTaken(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? 0 : e.taken.get();
    }

    /** 便捷查询：某玩家单次峰值。 */
    public static int getMaxHit(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? 0 : e.maxHit.get();
    }

    /** v6.6.0 · M1 · 单关切片快照。 */
    public static Snapshot snapshotOf(StageKey stageKey) {
        StageState bucket = stageKey == null ? null : stageBuckets.get(stageKey);
        long now = System.currentTimeMillis();
        long startedAt = startMs.get();
        long dur = startedAt > 0 ? (now - startedAt) : 0;
        if (bucket == null) {
            return new Snapshot(
                    live.get(), frozen.get(),
                    startTick.get(), startedAt, dur,
                    Collections.emptyList(), 0, 0, 0
            );
        }
        List<PlayerRow> rows = new ArrayList<>(bucket.entries.size());
        long totalTaken = 0;
        int totalEvents = 0;
        int totalMaxHit = 0;
        for (Entry e : bucket.entries.values()) {
            long t = e.taken.get();
            int ev = e.events.get();
            int mh = e.maxHit.get();
            rows.add(new PlayerRow(e.uuid, e.name, t, ev, mh, e.lastTakenTick.get()));
            totalTaken += t;
            totalEvents += ev;
            if (mh > totalMaxHit) totalMaxHit = mh;
        }
        rows.sort(Comparator.comparingLong(PlayerRow::taken).reversed());
        return new Snapshot(
                live.get(), frozen.get(),
                startTick.get(), startedAt, dur,
                Collections.unmodifiableList(rows),
                totalTaken, totalEvents, totalMaxHit
        );
    }

    /** v6.6.0 · M1 · 单玩家 × 单关 承伤。 */
    public static long getTakenAt(UUID uuid, StageKey stageKey) {
        if (stageKey == null) return 0;
        StageState bucket = stageBuckets.get(stageKey);
        if (bucket == null) return 0;
        Entry e = bucket.entries.get(uuid);
        return e == null ? 0 : e.taken.get();
    }

    /** v6.6.0 · M4 · 当前已记录承伤数据的所有 stageKey（K 表格分关页用）。 */
    public static java.util.Set<StageKey> recordedStageKeys() {
        return java.util.Collections.unmodifiableSet(stageBuckets.keySet());
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
        if (t == null || t.isEmpty()) return;

        startMs.set(t.getLong("startMs"));
        startTick.set(t.getLong("startTick"));
        // v6.6.6 hotfix · 不再从 NBT 还原 live / frozen（同 PlayerDamageStats.fromNbt 注释）。

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
        c.putLong("t", e.taken.get());
        c.putInt("ev", e.events.get());
        c.putInt("mh", e.maxHit.get());
        c.putLong("ltt", e.lastTakenTick.get());
        return c;
    }

    private static Entry entryFromNbt(NbtCompound c) {
        if (c == null || !c.containsUuid("u")) return null;
        UUID uuid = c.getUuid("u");
        String name = c.getString("n");
        if (name.isEmpty()) name = "?";
        Entry e = new Entry(uuid, name);
        e.taken.set(c.getLong("t"));
        e.events.set(c.getInt("ev"));
        e.maxHit.set(c.getInt("mh"));
        e.lastTakenTick.set(c.getLong("ltt"));
        return e;
    }
}
