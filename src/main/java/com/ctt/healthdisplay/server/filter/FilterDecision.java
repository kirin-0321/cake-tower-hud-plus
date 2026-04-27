package com.ctt.healthdisplay.server.filter;

/**
 * v6.7.0 · {@link DamageFilterPipeline#applyFilters} 的判定结果（不可变）。
 *
 * <p>{@link #filtered} 为 true 时该事件被路由到 {@link com.ctt.healthdisplay.server.AttackerProbe.Layer#L9_FILTER}，
 * 不进玩家账户、不进 {@code grandTotal} / {@code sessionTotal}；contributors 是否记录由
 * {@link FilterReason#writesContributors()} 决定（详见 {@code DAMAGE_FILTER_DESIGN.md} §7.3）。
 *
 * <p>{@link #filtered} 为 false 时调用方继续走 {@link com.ctt.healthdisplay.server.AttackerProbe#recordFromDamageShower}
 * 的正常归属流程。
 */
public record FilterDecision(boolean filtered, FilterReason reason) {

    private static final FilterDecision PASS = new FilterDecision(false, null);

    /** 单例：未命中任何过滤规则，正常归属。 */
    public static FilterDecision pass() { return PASS; }

    /**
     * 命中过滤规则。
     *
     * @param reason 过滤原因（不能为 null；调用方已确保）
     */
    public static FilterDecision filter(FilterReason reason) {
        if (reason == null) throw new IllegalArgumentException("FilterReason cannot be null");
        return new FilterDecision(true, reason);
    }

    /** 便捷判断：当前决策是否要求 contributors 仍写入。仅 {@link #filtered} 为 true 时有意义。 */
    public boolean writesContributors() {
        return filtered && reason != null && reason.writesContributors();
    }
}
