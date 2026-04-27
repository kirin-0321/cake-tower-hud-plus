package com.ctt.healthdisplay.server.filter;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v6.7.0 · 异常伤害过滤器的 per-reason 诊断报告（仅内存）。
 *
 * <p><b>不持久化</b>（{@code DAMAGE_FILTER_DESIGN.md} §8.3 / Q4 决议）：服务器 / session
 * 重启即清零；与玩家账户的 {@code unattributedFiltered} 汇总桶（仍持久化于
 * {@link com.ctt.healthdisplay.server.PlayerDamageStats}）相互独立。
 *
 * <p>用途：
 * <ul>
 *   <li>L 键面板"过滤器诊断"子页签每条 reason 的命中事件数 / 累计数值</li>
 *   <li>{@code /ctt-stats filter-diag} 命令打印当前过滤命中分布（待 P6）</li>
 *   <li>调参时验证某条规则是否过严 / 过松（事件数为 0 即可能配置不当）</li>
 * </ul>
 *
 * <p>线程安全：每个 reason 一对 {@link AtomicLong}，{@link #observe} 可在任意线程调用。
 */
public final class FilterDiagReport {

    private static final EnumMap<FilterReason, AtomicLong> EVENT_COUNTS = new EnumMap<>(FilterReason.class);
    private static final EnumMap<FilterReason, AtomicLong> DAMAGE_SUMS = new EnumMap<>(FilterReason.class);

    static {
        for (FilterReason r : FilterReason.values()) {
            EVENT_COUNTS.put(r, new AtomicLong());
            DAMAGE_SUMS.put(r, new AtomicLong());
        }
    }

    private FilterDiagReport() {}

    /**
     * 记录一次过滤命中。
     *
     * @param reason 命中原因（不能为 null）
     * @param damage 该次伤害值（用于 damageSum 累计；负值会被截到 0 防御）
     */
    public static void observe(FilterReason reason, int damage) {
        if (reason == null) return;
        EVENT_COUNTS.get(reason).incrementAndGet();
        if (damage > 0) DAMAGE_SUMS.get(reason).addAndGet(damage);
    }

    /** 查询某 reason 的命中事件数。 */
    public static long getEventCount(FilterReason reason) {
        return reason == null ? 0L : EVENT_COUNTS.get(reason).get();
    }

    /** 查询某 reason 的累计 damage 总和。 */
    public static long getDamageSum(FilterReason reason) {
        return reason == null ? 0L : DAMAGE_SUMS.get(reason).get();
    }

    /** 全部 reason 命中事件数之和。 */
    public static long totalEvents() {
        long sum = 0;
        for (AtomicLong a : EVENT_COUNTS.values()) sum += a.get();
        return sum;
    }

    /** 全部 reason 累计 damage 之和。 */
    public static long totalDamage() {
        long sum = 0;
        for (AtomicLong a : DAMAGE_SUMS.values()) sum += a.get();
        return sum;
    }

    /**
     * 一次性快照（按 reason 排序），供 L 面板 / chat 命令格式化使用。
     * 返回的 EnumMap 为不可变拷贝，调用方修改不影响内部状态。
     */
    public static Map<FilterReason, Snapshot> snapshot() {
        EnumMap<FilterReason, Snapshot> out = new EnumMap<>(FilterReason.class);
        for (FilterReason r : FilterReason.values()) {
            out.put(r, new Snapshot(EVENT_COUNTS.get(r).get(), DAMAGE_SUMS.get(r).get()));
        }
        return out;
    }

    /** session / 服务器重启时调用。 */
    public static void reset() {
        for (FilterReason r : FilterReason.values()) {
            EVENT_COUNTS.get(r).set(0);
            DAMAGE_SUMS.get(r).set(0);
        }
    }

    /** 单条 reason 的快照（事件数 + damage 总和），供 UI 格式化。 */
    public record Snapshot(long eventCount, long damageSum) {}
}
