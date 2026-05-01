package com.ctt.healthdisplay.server.filter;

import com.ctt.healthdisplay.config.ServerConfig;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v8.x · 异常伤害过滤器 G7b 的 per-(player, weapon) P95 训练样本注册表。
 *
 * <h2>语义</h2>
 * <p>每个玩家有一组按主手武器 ID 分桶的 {@link RollingWindow}：
 * <pre>
 *   Map&lt;UUID, Map&lt;weaponId, RollingWindow&gt;&gt;
 * </pre>
 *
 * <p>每次合法伤害事件按"入队时的主手武器 ID"路由到对应桶；查询 P95 时按"flush 时
 * 的主手武器 ID"读对应桶——切换武器时新武器桶从 0 样本开始累积，不会被旧武器
 * P95 污染。
 *
 * <h2>不入样的情况</h2>
 * <ul>
 *   <li>被任意 G_low / G2 / G3 / G4 / G7a / G7b 路由到 {@link com.ctt.healthdisplay.server.AttackerProbe.Layer#L9_FILTER}
 *       的事件——防"被过滤的大数把自身阈值拉高"自污染（详见 {@code 大额伤害过滤器.md} 规则 1）</li>
 *   <li>{@code wasLethalAtBuffer} 为 true 的事件（致死高光）——避免拉高 P95</li>
 *   <li>{@code damage &lt; lowDamageFloor} 的低伤事件——避免拉低 P95</li>
 *   <li>{@code victim.Defence &gt; defenceExclusionThreshold}（高护甲怪输出偏低）</li>
 *   <li>{@code attackerUuid == null}（归属失败）</li>
 *   <li>{@code weaponId == EMPTY}（无主手武器，分桶无意义）</li>
 * </ul>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@link #observe} —— buffer.flush 判定为合法事件后调</li>
 *   <li>{@link #p95} —— buffer.flush 判定 outlier 时调</li>
 *   <li>{@link #evict} —— 玩家 DISCONNECT hook 调，清掉所有武器桶</li>
 *   <li>{@link #clearAll} —— stats start / stop / clear 联动调</li>
 *   <li>{@link #snapshot} —— 1 Hz S2C 推送时 broadcaster 调，用于 L 面板渲染</li>
 * </ul>
 *
 * <h2>性能 / 内存</h2>
 * <p>单 RollingWindow ≈ 1 KB，单玩家 30 武器 ≈ 30 KB。4 玩家小队 ≈ 120 KB——可忽略。
 * <p>{@link #observe} / {@link #p95} 都是 O(log N + 1 HashMap.get) ≈ 50 ns/op。
 *
 * <h2>线程安全</h2>
 * <p>外层 {@link ConcurrentHashMap}；内层 {@link HashMap} 仅在 server tick 主线程访问。
 */
public final class PerPlayerWeaponP95Registry {

    private static final Map<UUID, Map<String, RollingWindow>> WINDOWS = new ConcurrentHashMap<>();

    private PerPlayerWeaponP95Registry() {}

    /**
     * 加入一个合法样本。{@code damage} 必须 &gt; 0。
     * <p>调用方需保证已通过入样规则（详见类文档"不入样的情况"）。
     */
    public static void observe(UUID playerUuid, String weaponId, int damage) {
        if (playerUuid == null || weaponId == null || weaponId.isEmpty() || damage <= 0) return;
        int cap = capacityCfg();
        Map<String, RollingWindow> byWeapon = WINDOWS.computeIfAbsent(
                playerUuid, k -> new HashMap<>());
        RollingWindow w = byWeapon.computeIfAbsent(weaponId, k -> new RollingWindow(cap));
        w.observe(damage);
    }

    /**
     * 查询当前玩家 + 当前主手武器的 P95 值。
     *
     * @return 当前样本数 &lt; {@code p95MinSamples} 时返回 -1；否则为 P95 值
     */
    public static int p95(UUID playerUuid, String weaponId) {
        if (playerUuid == null || weaponId == null || weaponId.isEmpty()) return -1;
        Map<String, RollingWindow> byWeapon = WINDOWS.get(playerUuid);
        if (byWeapon == null) return -1;
        RollingWindow w = byWeapon.get(weaponId);
        if (w == null) return -1;
        return w.p95(minSamplesCfg());
    }

    /** 当前样本数（L 面板用：显示 {@code (87/100)} 之类的进度）。 */
    public static int sampleCount(UUID playerUuid, String weaponId) {
        if (playerUuid == null || weaponId == null || weaponId.isEmpty()) return 0;
        Map<String, RollingWindow> byWeapon = WINDOWS.get(playerUuid);
        if (byWeapon == null) return 0;
        RollingWindow w = byWeapon.get(weaponId);
        return w == null ? 0 : w.size();
    }

    /** 玩家 DISCONNECT 调：清掉该玩家全部武器桶，释放内存。 */
    public static void evict(UUID playerUuid) {
        if (playerUuid == null) return;
        WINDOWS.remove(playerUuid);
    }

    /** stats start / stop / clear 联动：清空所有玩家所有武器桶。 */
    public static void clearAll() {
        WINDOWS.clear();
    }

    /**
     * 1 Hz S2C 推送数据源：每个玩家 + 每把武器的 (P95, sampleCount) 快照。
     * <p>返回的 Map 是不可变快照，调用方可安全持有 / 序列化。
     */
    public static Map<UUID, Map<String, WeaponP95Stat>> snapshot() {
        int min = minSamplesCfg();
        Map<UUID, Map<String, WeaponP95Stat>> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, RollingWindow>> e : WINDOWS.entrySet()) {
            Map<String, WeaponP95Stat> inner = new LinkedHashMap<>();
            for (Map.Entry<String, RollingWindow> we : e.getValue().entrySet()) {
                RollingWindow w = we.getValue();
                inner.put(we.getKey(), new WeaponP95Stat(w.p95(min), w.size(), w.capacity()));
            }
            out.put(e.getKey(), Collections.unmodifiableMap(inner));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 单玩家单武器的 P95 + 样本计数快照（network payload / L 面板渲染共用）。
     *
     * @param p95         P95 值；样本不足时为 -1
     * @param size        当前样本数
     * @param capacity    窗口容量（恒为 {@link ServerConfig#p95WindowSize}）
     */
    public record WeaponP95Stat(int p95, int size, int capacity) {}

    // =========================================================================
    //  config 兜底（便于上层 hot-reload 配置后立刻生效）
    // =========================================================================

    private static int capacityCfg() {
        int v = ServerConfig.INSTANCE.p95WindowSize;
        return v > 0 ? v : 100;
    }

    private static int minSamplesCfg() {
        int v = ServerConfig.INSTANCE.p95MinSamples;
        return v > 0 ? v : 20;
    }
}
