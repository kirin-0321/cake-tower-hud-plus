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
 * v6.5.2 · 按玩家伤害累计器（测试面板数据源）。
 *
 * <h2>数据语义</h2>
 * <ul>
 *   <li><b>confirmed</b>：L1~L7 已分类伤害，完整整数累加（v6.5.9 起 L8 不再进玩家账户）。</li>
 *   <li><b>events</b>：本玩家被归属到的事件条数。</li>
 *   <li><b>maxHit</b>：该玩家单次命中最大值。</li>
 *   <li><b>unattributedNone</b>：L9_NONE 真未归属伤害（兜底匹配全挂）。</li>
 *   <li><b>unattributedFiltered</b>：L9_FILTER 黑名单数值（怪物初始化 / 形态切换假伤害）。</li>
 *   <li><b>unattributedHeal</b>：L9_HEAL 绿色回血粒子。</li>
 *   <li><b>unattributedCarry</b>：v6.5.9 · L8_LAST_HITTER carry 兜底归属（剧情 set 假伤害实测误命中过多，
 *       从玩家账户中剥离独立成 L9-CARRY 桶；击杀归属仍由 VictimTombstone 用 L8 兜底）。</li>
 * </ul>
 *
 * <h2>百分比 / grandTotal 定义（v6.5.9 修订）</h2>
 * <p>{@code grandTotal} = sum(L1..L7) = 玩家个人 confirmed 之和。
 * <b>L8 carry 与 L9 三子层均不进 grandTotal</b>，玩家百分比 = self / grandTotal。
 * 这样保证：黑名单 / 回血粒子 / 剧情 set 假伤害都不会"塌方"分母让玩家伤害百分比变 0.x%。
 *
 * <h2>v6.5.2 重构</h2>
 * <ul>
 *   <li>删除 L8b 分摊相关字段（{@code sharedMilli} / {@code l8bEvents} / {@code addShared}）。
 *       召唤物分摊归属层已整层删除，玩家个人不再有"小数伤害"。</li>
 *   <li>{@code addUnattributed} 改名为 {@link #addUnclassified}，按 L9 子层分桶。
 *       原 deprecated signature 仍保留以防遗漏调用点。</li>
 * </ul>
 *
 * <h2>v6.6.0 · M1 关卡分桶</h2>
 * <ul>
 *   <li>{@link #add} 入口先查 {@link StageBoundaryDispatcher#isCollecting}：
 *       玩家不在战斗关（休息室 / 大厅 / Game Over / MiniGame）一律拦截，
 *       实现设计 §3.1 的"休息室期间不采集"铁律。</li>
 *   <li>新增并行存储 {@link #stageBuckets}：每个战斗 {@link StageKey} 一份独立的
 *       {@link StageState}，{@code add} 时双写（session 总 + stage bucket）。</li>
 *   <li>新增 {@link #snapshotOf(StageKey)} 提供单关切片视图；
 *       L 键调试面板 [C] 切片按钮使用此 API。</li>
 *   <li>L8 carry / L9 三子层 <b>不分桶</b>（保留 session 级诊断语义）；
 *       stage 切片视图里 unattr 字段恒为 0。</li>
 * </ul>
 *
 * <h2>会话控制</h2>
 * <p>和 {@link DamageProbe} 的 session 独立。
 *
 * <h2>线程安全</h2>
 * <p>所有字段使用 {@link AtomicLong} / {@link ConcurrentHashMap} 保证一致性。
 */
public final class PlayerDamageStats {

    /** 单玩家累计条目。所有字段并发安全。v6.5.2 删除 sharedMilli / l8bEvents。 */
    public static final class Entry {
        public final UUID uuid;
        public volatile String name; // 缓存最近一次看到的玩家名
        final AtomicLong confirmed = new AtomicLong();
        final AtomicInteger events = new AtomicInteger();
        final AtomicInteger maxHit = new AtomicInteger();
        final int[] layerCounts = new int[AttackerProbe.Layer.values().length];
        private final Object layerLock = new Object();

        Entry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        void addLayer(AttackerProbe.Layer l) {
            synchronized (layerLock) {
                layerCounts[l.ordinal()]++;
            }
        }

        int[] snapshotLayers() {
            synchronized (layerLock) {
                return layerCounts.clone();
            }
        }
    }

    /**
     * 快照（不可变）—— 渲染端一次性拿到，避免迭代时被写入干扰。
     * v6.5.9 · 改写：grandTotal = sum(L1..L7)；L8 carry 与 L9 三子层独立暴露。
     *
     * @param totalConfirmed 所有玩家 confirmed 之和（= grandTotal，进度条分母）
     * @param totalEvents 所有玩家事件总数（不含 L8 carry / L9）
     * @param unattributedNone L9_NONE 数值
     * @param unattributedFiltered L9_FILTER 数值（黑名单）
     * @param unattributedHeal L9_HEAL 数值（回血）
     * @param unattributedCarry v6.5.9 · L8 carry 兜底数值（不进玩家账户）
     * @param unattributedNoneEvents L9_NONE 事件数
     * @param unattributedFilteredEvents L9_FILTER 事件数
     * @param unattributedHealEvents L9_HEAL 事件数
     * @param unattributedCarryEvents v6.5.9 · L8 carry 事件数
     */
    public record Snapshot(
            boolean live,
            boolean frozen,
            long startTick,
            long startMs,
            long durationMs,
            List<PlayerRow> players,
            long unattributedNone,
            long unattributedFiltered,
            long unattributedHeal,
            long unattributedCarry,
            int unattributedNoneEvents,
            int unattributedFilteredEvents,
            int unattributedHealEvents,
            int unattributedCarryEvents,
            long totalConfirmed,
            int totalEvents,
            int totalMaxHit,
            long[] globalLayerCounts
    ) {
        /** v6.5.9 · grandTotal = sum(L1..L7) = totalConfirmed。L8 carry / L9 子层均不进。 */
        public long grandTotal() { return totalConfirmed; }
        /** v6.5.9 · 所有"非玩家账户"伤害合计（NONE + FILTER + HEAL + CARRY）。仅信息展示。 */
        public long unattributedAll() {
            return unattributedNone + unattributedFiltered + unattributedHeal + unattributedCarry;
        }
        /** 黑名单 + 回血合计（"被过滤的伪事件"）。供面板摘要行展示。 */
        public long filteredTotal() {
            return unattributedFiltered + unattributedHeal;
        }
        public int unattributedAllEvents() {
            return unattributedNoneEvents + unattributedFilteredEvents
                 + unattributedHealEvents + unattributedCarryEvents;
        }
    }

    /** 单玩家快照行（排序后用于渲染）。v6.5.2 · 删除 shared / l8bEvents 字段。 */
    public record PlayerRow(
            UUID uuid,
            String name,
            long confirmed,
            int events,
            int maxHit,
            int[] layerCounts
    ) {
        /** v6.5.2 · 玩家百分比 = self.confirmed / grandTotal（= sum(L1..L8)）。 */
        public double percent(long grandTotal) {
            if (grandTotal <= 0) return 0;
            return 100.0 * confirmed / grandTotal;
        }
    }

    private static final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private static final AtomicBoolean live = new AtomicBoolean(false);
    private static final AtomicBoolean frozen = new AtomicBoolean(false);
    private static final AtomicLong startTick = new AtomicLong();
    private static final AtomicLong startMs = new AtomicLong();
    /**
     * v6.6.0 · M4 hotfix · 净活跃时长记账（frozen 期不计入）。
     * <ul>
     *   <li>{@link #activeAccumMs}：到目前为止累计的活跃 ms（解冻 ↔ 冻结切换时累加）</li>
     *   <li>{@link #unfrozenSinceMs}：本次解冻 / 首次 start 的墙钟时间</li>
     * </ul>
     * 公式：{@code activeDurationMs = activeAccumMs + (frozen ? 0 : now - unfrozenSinceMs)}
     */
    private static final AtomicLong activeAccumMs = new AtomicLong(0);
    private static final AtomicLong unfrozenSinceMs = new AtomicLong(0);
    // v6.5.2 · L9 三子层各自累加，不再合一桶。
    private static final AtomicLong unattributedNone = new AtomicLong();
    private static final AtomicInteger unattributedNoneEvents = new AtomicInteger();
    private static final AtomicLong unattributedFiltered = new AtomicLong();
    private static final AtomicInteger unattributedFilteredEvents = new AtomicInteger();
    private static final AtomicLong unattributedHeal = new AtomicLong();
    private static final AtomicInteger unattributedHealEvents = new AtomicInteger();
    // v6.5.9 · L8_LAST_HITTER carry 兜底从玩家账户剥离，独立桶。
    private static final AtomicLong unattributedCarry = new AtomicLong();
    private static final AtomicInteger unattributedCarryEvents = new AtomicInteger();
    private static final AtomicLong[] globalLayerCounts = new AtomicLong[AttackerProbe.Layer.values().length];
    static {
        for (int i = 0; i < globalLayerCounts.length; i++) globalLayerCounts[i] = new AtomicLong();
    }

    // ===== v6.6.0 · M1 关卡分桶 =====
    /**
     * 单关 bucket（仅记录"已分类玩家伤害"L1..L7；L8 carry / L9 不分桶）。
     * 字段语义与外层 session 累计一致，但作用域限定在某个 {@link StageKey}。
     */
    static final class StageState {
        final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
        final AtomicLong[] layerCounts = new AtomicLong[AttackerProbe.Layer.values().length];
        StageState() {
            for (int i = 0; i < layerCounts.length; i++) layerCounts[i] = new AtomicLong();
        }
    }
    /** 每 {@link StageKey} 一份独立 stage 累计；不在战斗关时不写入。 */
    private static final Map<StageKey, StageState> stageBuckets = new ConcurrentHashMap<>();

    private PlayerDamageStats() {}

    // =========================================================================
    //  会话控制
    //  v6.4.0 · start/stop/clear 联动 PlayerKillStats（共享 session 状态）。
    // =========================================================================
    public static void start() {
        resetState();
        long now = System.currentTimeMillis();
        startMs.set(now);
        startTick.set(DamageProbe.currentTick());
        activeAccumMs.set(0);
        unfrozenSinceMs.set(now);
        frozen.set(false);
        live.set(true);
        PlayerKillStats.syncStart();
        PlayerTakenStats.syncStart();
        PlayerDpsTracker.clearAll();
    }

    public static void stop() {
        live.set(false);
        frozen.set(true);
        PlayerKillStats.syncStop();
        PlayerTakenStats.syncStop();
        PlayerDpsTracker.clearAll();
    }

    public static void clear() {
        resetState();
        long now = System.currentTimeMillis();
        startMs.set(now);
        startTick.set(DamageProbe.currentTick());
        activeAccumMs.set(0);
        unfrozenSinceMs.set(now);
        // v6.6.0 hotfix · clear 视作"重新开始一轮"，强制解冻并保持 live=true，
        // 这样 T1F1 进入触发的 auto-clear 立刻能采集后续伤害。
        frozen.set(false);
        live.set(true);
        PlayerKillStats.syncClear();
        PlayerTakenStats.syncClear();
        PlayerDpsTracker.clearAll();
    }

    /** v6.5.2 · 重置所有累计状态（start / clear 共用）。 */
    private static void resetState() {
        entries.clear();
        unattributedNone.set(0);
        unattributedNoneEvents.set(0);
        unattributedFiltered.set(0);
        unattributedFilteredEvents.set(0);
        unattributedHeal.set(0);
        unattributedHealEvents.set(0);
        unattributedCarry.set(0);
        unattributedCarryEvents.set(0);
        for (AtomicLong c : globalLayerCounts) c.set(0);
        // v6.6.0 · M1 · 同步清空所有 stage bucket。
        stageBuckets.clear();
    }

    public static void setFrozen(boolean f) {
        boolean was = frozen.getAndSet(f);
        if (was != f) {
            long now = System.currentTimeMillis();
            if (f) {
                // unfrozen → frozen：把这段活跃时长封入累加器
                long since = unfrozenSinceMs.get();
                if (since > 0) activeAccumMs.addAndGet(Math.max(0, now - since));
            } else {
                // frozen → unfrozen：开新一段活跃区间
                unfrozenSinceMs.set(now);
            }
        }
        PlayerKillStats.setFrozen(f);
        PlayerTakenStats.setFrozen(f);
    }
    public static boolean isLive() { return live.get(); }
    public static boolean isFrozen() { return frozen.get(); }

    /**
     * v6.6.0 · M4 hotfix · 净活跃时长（frozen 期被扣除）。
     * 适合给 K 表格"Session: HH:MM:SS"显示——大厅/小游戏期间计时停摆。
     */
    public static long activeDurationMs() {
        long acc = activeAccumMs.get();
        if (frozen.get()) return acc;
        long since = unfrozenSinceMs.get();
        if (since <= 0) return acc;
        return acc + Math.max(0, System.currentTimeMillis() - since);
    }

    // =========================================================================
    //  写入路径（由 AttackerProbe 调用）
    //  v6.4.0 · 所有写入方法新增 StageKey stageKey 参数占位（阶段 ①）。
    //           当前阶段一律传 null；阶段 ④ 开始真正填五元组并按 stageKey 分桶。
    //           旧 signature 保留为 deprecated 委托（防未来出现漏改调用点的编译错）。
    // =========================================================================

    /**
     * 归属 L1~L7 的已分类伤害：整数累加进玩家账户。
     * <p>v6.5.9 · L8_LAST_HITTER 不再进玩家账户（实测剧情 set 假伤害误命中过多），
     * 此方法内若误传 L8 会被自动转路由到 {@link #addCarry(int)}。
     * <p>v6.6.0 · M1 · 入口先查 {@link StageBoundaryDispatcher#isCollecting}，
     * 玩家不在战斗关一律拦截（"休息室不采集"铁律）；同时同步写入 stage bucket 实现关卡分桶。
     *
     * @param stageKey 调用方可传 {@code null}，由本方法自动从 dispatcher 取真值
     */
    public static void add(UUID playerUuid, String playerName, StageKey stageKey,
                           int damage, AttackerProbe.Layer layer) {
        if (!live.get() || frozen.get()) return;
        if (playerUuid == null || damage <= 0) return;
        if (layer == null || layer.isUnclassified()) {
            // 防御性：不应该走到这里——但保险起见路由到 L9_NONE 桶
            addUnclassified(AttackerProbe.Layer.L9_NONE, damage);
            return;
        }
        // v6.5.9 · L8 carry 一律走 addCarry，不进玩家账户。
        if (layer == AttackerProbe.Layer.L8_LAST_HITTER) {
            addCarry(damage);
            return;
        }
        // v6.6.0 · M1 · 关卡边界拦截：休息室 / 大厅 / Game Over / MiniGame 全部不采集。
        if (!StageBoundaryDispatcher.isCollecting(playerUuid)) return;
        // 调用方未提供 stageKey 时，自动从 dispatcher 取（玩家在战斗关时一定非 null）
        if (stageKey == null) stageKey = StageBoundaryDispatcher.currentStageKey(playerUuid);

        // 写入 1：session 总累计（既有逻辑）
        Entry e = entries.computeIfAbsent(playerUuid, u -> new Entry(u, playerName != null ? playerName : "?"));
        if (playerName != null) e.name = playerName;
        e.confirmed.addAndGet(damage);
        e.events.incrementAndGet();
        updateMax(e.maxHit, damage);
        e.addLayer(layer);
        globalLayerCounts[layer.ordinal()].incrementAndGet();

        // v6.6.7 · 同步进 DPS 滑窗 ring（HUD 关行 · 最近 5 秒 / 5 = DPS）
        PlayerDpsTracker.onDealt(playerUuid, damage);

        // 写入 2：stage bucket（M1 新增 · 关卡分桶）
        if (stageKey != null) {
            StageState bucket = stageBuckets.computeIfAbsent(stageKey, k -> new StageState());
            Entry be = bucket.entries.computeIfAbsent(playerUuid,
                    u -> new Entry(u, playerName != null ? playerName : "?"));
            if (playerName != null) be.name = playerName;
            be.confirmed.addAndGet(damage);
            be.events.incrementAndGet();
            updateMax(be.maxHit, damage);
            be.addLayer(layer);
            bucket.layerCounts[layer.ordinal()].incrementAndGet();
        }
    }

    /**
     * v6.5.9 · L8_LAST_HITTER carry 兜底专用累计。
     * <ul>
     *   <li>不进玩家账户（{@link Entry#confirmed} / events / maxHit / layerCounts 全部不动）。</li>
     *   <li>不进 grandTotal（玩家百分比分母不被 carry 拉低）。</li>
     *   <li>仅累计独立桶 {@code unattributedCarry} + {@code globalLayerCounts[L8]}（保留诊断粒度）。</li>
     * </ul>
     * 击杀归属维度仍由 {@link com.ctt.healthdisplay.server.VictimTombstone} 用 L8 兜底，
     * 与本方法解耦。
     */
    public static void addCarry(int damage) {
        if (!live.get() || frozen.get()) return;
        if (damage <= 0) return;
        unattributedCarry.addAndGet(damage);
        unattributedCarryEvents.incrementAndGet();
        globalLayerCounts[AttackerProbe.Layer.L8_LAST_HITTER.ordinal()].incrementAndGet();
    }

    /** @deprecated 保留旧 signature，v6.4.0 起请使用带 StageKey 的版本。 */
    @Deprecated
    public static void add(UUID playerUuid, String playerName, int damage, AttackerProbe.Layer layer) {
        add(playerUuid, playerName, null, damage, layer);
    }

    /**
     * v6.5.2 · 未分类伤害（L9 子层）累加。按 layer 分桶到 NONE / FILTER / HEAL，
     * 不进玩家账户、不进 grandTotal。
     *
     * @param layer 必须是 L9_NONE / L9_FILTER / L9_HEAL 之一；其他值会被路由到 L9_NONE。
     */
    public static void addUnclassified(AttackerProbe.Layer layer, int damage) {
        if (!live.get() || frozen.get()) return;
        if (damage <= 0) return;
        AttackerProbe.Layer key = (layer != null && layer.isUnclassified())
                ? layer : AttackerProbe.Layer.L9_NONE;
        switch (key) {
            case L9_FILTER -> {
                unattributedFiltered.addAndGet(damage);
                unattributedFilteredEvents.incrementAndGet();
            }
            case L9_HEAL -> {
                unattributedHeal.addAndGet(damage);
                unattributedHealEvents.incrementAndGet();
            }
            default -> {
                unattributedNone.addAndGet(damage);
                unattributedNoneEvents.incrementAndGet();
            }
        }
        globalLayerCounts[key.ordinal()].incrementAndGet();
    }

    /** @deprecated v6.5.2 · 改用 {@link #addUnclassified(AttackerProbe.Layer, int)}。 */
    @Deprecated
    public static void addUnattributed(StageKey stageKey, int damage) {
        addUnclassified(AttackerProbe.Layer.L9_NONE, damage);
    }

    /** @deprecated v6.5.2 · 改用 {@link #addUnclassified(AttackerProbe.Layer, int)}。 */
    @Deprecated
    public static void addUnattributed(int damage) {
        addUnclassified(AttackerProbe.Layer.L9_NONE, damage);
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
        long totalConfirmed = 0;
        int totalEvents = 0;
        int totalMaxHit = 0;

        for (Entry e : entries.values()) {
            long cf = e.confirmed.get();
            int ev = e.events.get();
            int mh = e.maxHit.get();
            rows.add(new PlayerRow(e.uuid, e.name, cf, ev, mh, e.snapshotLayers()));
            totalConfirmed += cf;
            totalEvents += ev;
            if (mh > totalMaxHit) totalMaxHit = mh;
        }

        rows.sort(Comparator.comparingLong(PlayerRow::confirmed).reversed());

        long[] layerCountsCopy = new long[globalLayerCounts.length];
        for (int i = 0; i < globalLayerCounts.length; i++) layerCountsCopy[i] = globalLayerCounts[i].get();

        long now = System.currentTimeMillis();
        long startedAt = startMs.get();
        long dur = startedAt > 0 ? (now - startedAt) : 0;

        return new Snapshot(
                live.get(), frozen.get(),
                startTick.get(), startedAt, dur,
                Collections.unmodifiableList(rows),
                unattributedNone.get(), unattributedFiltered.get(),
                unattributedHeal.get(), unattributedCarry.get(),
                unattributedNoneEvents.get(), unattributedFilteredEvents.get(),
                unattributedHealEvents.get(), unattributedCarryEvents.get(),
                totalConfirmed,
                totalEvents, totalMaxHit,
                layerCountsCopy
        );
    }

    /**
     * v6.6.0 · M1 · 单关切片快照。
     *
     * <p>仅包含该 {@link StageKey} 期间累计的玩家伤害（L1..L7）；
     * L8 carry / L9 三子层不分桶——切片视图里这些字段恒为 0。
     *
     * <p>{@code stageKey == null} 或 bucket 不存在（玩家尚未进过该关）时返回空快照
     * （players 空 / totalConfirmed 0），界面应显示"暂无数据"占位。
     */
    public static Snapshot snapshotOf(StageKey stageKey) {
        StageState bucket = stageKey == null ? null : stageBuckets.get(stageKey);
        if (bucket == null) {
            long now = System.currentTimeMillis();
            long startedAt = startMs.get();
            long dur = startedAt > 0 ? (now - startedAt) : 0;
            return new Snapshot(
                    live.get(), frozen.get(),
                    startTick.get(), startedAt, dur,
                    Collections.emptyList(),
                    0, 0, 0, 0,
                    0, 0, 0, 0,
                    0, 0, 0,
                    new long[AttackerProbe.Layer.values().length]
            );
        }

        List<PlayerRow> rows = new ArrayList<>(bucket.entries.size());
        long totalConfirmed = 0;
        int totalEvents = 0;
        int totalMaxHit = 0;
        for (Entry e : bucket.entries.values()) {
            long cf = e.confirmed.get();
            int ev = e.events.get();
            int mh = e.maxHit.get();
            rows.add(new PlayerRow(e.uuid, e.name, cf, ev, mh, e.snapshotLayers()));
            totalConfirmed += cf;
            totalEvents += ev;
            if (mh > totalMaxHit) totalMaxHit = mh;
        }
        rows.sort(Comparator.comparingLong(PlayerRow::confirmed).reversed());

        long[] layerCountsCopy = new long[bucket.layerCounts.length];
        for (int i = 0; i < bucket.layerCounts.length; i++) layerCountsCopy[i] = bucket.layerCounts[i].get();

        long now = System.currentTimeMillis();
        long startedAt = startMs.get();
        long dur = startedAt > 0 ? (now - startedAt) : 0;
        return new Snapshot(
                live.get(), frozen.get(),
                startTick.get(), startedAt, dur,
                Collections.unmodifiableList(rows),
                0, 0, 0, 0,
                0, 0, 0, 0,
                totalConfirmed,
                totalEvents, totalMaxHit,
                layerCountsCopy
        );
    }

    /** v6.6.0 · M1 · 当前已记录数据的所有 stageKey（只读视图，UI / 持久化用）。 */
    public static java.util.Set<StageKey> recordedStageKeys() {
        return java.util.Collections.unmodifiableSet(stageBuckets.keySet());
    }

    // =========================================================================
    //  v6.6.0 · M3 · HUD 单玩家直读 helper
    //  HealthBarRenderer 每帧渲染队友 stats 行调用，避免每次构建整份 Snapshot 列表。
    // =========================================================================

    /**
     * 该玩家本局 session 的累计 confirmed 伤害（grandTotal 视角，sum(L1..L7)）。
     * 玩家从未上榜返回 0。
     */
    public static long getDealt(UUID uuid) {
        if (uuid == null) return 0L;
        Entry e = entries.get(uuid);
        return e == null ? 0L : e.confirmed.get();
    }

    /**
     * 该玩家在某个 stageKey 的累计 confirmed 伤害。{@code stageKey == null} 或
     * 玩家未在该关上榜返回 0。
     */
    public static long getDealtAt(UUID uuid, StageKey stageKey) {
        if (uuid == null || stageKey == null) return 0L;
        StageState bucket = stageBuckets.get(stageKey);
        if (bucket == null) return 0L;
        Entry e = bucket.entries.get(uuid);
        return e == null ? 0L : e.confirmed.get();
    }

    // =========================================================================
    //  v6.6.1 · M2 · NBT 持久化
    //  注意：本类的 toNbt / fromNbt 仅负责"伤害"维度。session 起始时间 / live / frozen
    //  作为整 session 共享字段写入；stage bucket 列表与 session entries 平行展开。
    // =========================================================================

    /**
     * 把当前 session 的所有伤害状态序列化为一个 NbtCompound。
     * Manager 层拼到 root 时会嵌进 {@code session.damage} 子节点。
     */
    public static NbtCompound toNbt() {
        NbtCompound t = new NbtCompound();
        t.putLong("startMs", startMs.get());
        t.putLong("startTick", startTick.get());
        t.putLong("activeAccumMs", activeAccumMs.get());
        t.putLong("unfrozenSinceMs", unfrozenSinceMs.get());
        t.putBoolean("live", live.get());
        t.putBoolean("frozen", frozen.get());

        t.putLong("uNone", unattributedNone.get());
        t.putInt("uNoneEv", unattributedNoneEvents.get());
        t.putLong("uFilt", unattributedFiltered.get());
        t.putInt("uFiltEv", unattributedFilteredEvents.get());
        t.putLong("uHeal", unattributedHeal.get());
        t.putInt("uHealEv", unattributedHealEvents.get());
        t.putLong("uCarry", unattributedCarry.get());
        t.putInt("uCarryEv", unattributedCarryEvents.get());

        long[] glc = new long[globalLayerCounts.length];
        for (int i = 0; i < glc.length; i++) glc[i] = globalLayerCounts[i].get();
        t.putLongArray("glc", glc);

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
            long[] slc = new long[en.getValue().layerCounts.length];
            for (int i = 0; i < slc.length; i++) slc[i] = en.getValue().layerCounts[i].get();
            s.putLongArray("lc", slc);
            stages.add(s);
        }
        t.put("stages", stages);
        return t;
    }

    /** 从 NBT 还原 session 状态。失败安全：缺字段 → 0/empty 默认值。 */
    public static void fromNbt(NbtCompound t) {
        // 全清后重灌（避免与 start() 已经填的初值叠加）
        resetState();
        if (t == null || t.isEmpty()) return;

        startMs.set(t.getLong("startMs"));
        startTick.set(t.getLong("startTick"));
        activeAccumMs.set(t.getLong("activeAccumMs"));
        // unfrozenSinceMs 重启后用当前 wall-clock 起算更安全（旧值的 epoch 可能跨会期失真）
        unfrozenSinceMs.set(System.currentTimeMillis());
        // v6.6.6 hotfix · 不再从 NBT 还原 live / frozen 标志：这两个 flag 由 server lifecycle
        //   (PlayerDamageStats.start + setFrozen) + StageBoundaryDispatcher 全程管理。
        //   历史 bug：用户在 L 键面板点过 ⏹ stop 按钮把 live=false 持久化进 ctt_stats.dat 后，
        //   重启服务器 fromNbt 把 live=false 灌回内存，dispatcher 的 setFrozen(false) 只动 frozen
        //   不动 live，T1F1 触发的 syncClear 又不重置 live，导致 stats 永远不再记录。
        //   持久化层只该负责"恢复计数数据"，不该参与"会话是否在采集"的状态。

        unattributedNone.set(t.getLong("uNone"));
        unattributedNoneEvents.set(t.getInt("uNoneEv"));
        unattributedFiltered.set(t.getLong("uFilt"));
        unattributedFilteredEvents.set(t.getInt("uFiltEv"));
        unattributedHeal.set(t.getLong("uHeal"));
        unattributedHealEvents.set(t.getInt("uHealEv"));
        unattributedCarry.set(t.getLong("uCarry"));
        unattributedCarryEvents.set(t.getInt("uCarryEv"));

        long[] glc = t.getLongArray("glc");
        for (int i = 0; i < globalLayerCounts.length && i < glc.length; i++) {
            globalLayerCounts[i].set(glc[i]);
        }

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
            long[] slc = s.getLongArray("lc");
            for (int k = 0; k < bucket.layerCounts.length && k < slc.length; k++) {
                bucket.layerCounts[k].set(slc[k]);
            }
            stageBuckets.put(key, bucket);
        }
    }

    private static NbtCompound entryToNbt(Entry e) {
        NbtCompound c = new NbtCompound();
        c.putUuid("u", e.uuid);
        c.putString("n", e.name == null ? "?" : e.name);
        c.putLong("c", e.confirmed.get());
        c.putInt("ev", e.events.get());
        c.putInt("mh", e.maxHit.get());
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
        e.confirmed.set(c.getLong("c"));
        e.events.set(c.getInt("ev"));
        e.maxHit.set(c.getInt("mh"));
        long[] lc = c.getLongArray("lc");
        for (int i = 0; i < e.layerCounts.length && i < lc.length; i++) {
            e.layerCounts[i] = (int) Math.min(Integer.MAX_VALUE, lc[i]);
        }
        return e;
    }
}
