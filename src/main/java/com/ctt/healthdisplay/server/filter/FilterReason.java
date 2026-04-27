package com.ctt.healthdisplay.server.filter;

/**
 * v6.7.0 · 异常伤害过滤器命中原因子标签。
 *
 * <p>每个值携带：
 * <ul>
 *   <li>{@link #shortTag()} —— 用于日志 / 聊天栏 {@code [L9-FILT:xxx]} 与 NBT 序列化（保持小写连字符）</li>
 *   <li>{@link #writesContributors()} —— 是否仍允许 {@link com.ctt.healthdisplay.server.VictimDamageContributors}
 *       记录该次贡献。规则参见 {@code DAMAGE_FILTER_DESIGN.md} §7.3：
 *       <ul>
 *         <li>{@link #LETHAL_MECHANISM} / {@link #OVERSIZE} / {@link #OUTLIER} → {@code true}：
 *             玩家确实有有效伤害行为，击杀维度仍可归属</li>
 *         <li>其它（{@code low-noise} / {@code init-hp-jump} / {@code suspect-victim} /
 *             {@code paused} / {@code session-boundary} / {@code suspect-tag} /
 *             {@code duplicate} / {@code mass-wipe}）→ {@code false}：噪声 / 伪伤害 /
 *             非玩家行为，不应进入击杀归属链路</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p><b>分阶段实装</b>：枚举一次性列全 11 项，但每条规则会在不同 P 阶段挂载到
 * {@link DamageFilterPipeline}：
 * <ul>
 *   <li>P1 (v6.7.0)：{@link #LOW_NOISE} / {@link #INIT_HP_JUMP} / {@link #SUSPECT_VICTIM}</li>
 *   <li>P2 (v6.7.1)：{@link #PAUSED} / {@link #SESSION_BOUNDARY} / {@link #SUSPECT_TAG} /
 *       {@link #OVERSIZE} / {@link #DUPLICATE}</li>
 *   <li>P3~P4 (v6.7.2~3)：{@link #LETHAL_MECHANISM} / {@link #OUTLIER}</li>
 *   <li>P5 (v6.7.4)：{@link #MASS_WIPE}</li>
 * </ul>
 */
public enum FilterReason {

    /** G_low · damage 低于 {@link com.ctt.healthdisplay.config.ServerConfig#lowDamageFloor}（默认 5）。DoT 噪声。 */
    LOW_NOISE("low-noise", false),

    /** G3 · 命中 {@link com.ctt.healthdisplay.config.ServerConfig#initHpJumpValues} 黑名单（怪物初始化 / 形态切换假伤害）。 */
    INIT_HP_JUMP("init-hp-jump", false),

    /** G4 · victim 显示名命中 {@link com.ctt.healthdisplay.config.ServerConfig#suspectVictims} 且超过阈值。 */
    SUSPECT_VICTIM("suspect-victim", false),

    /** G2 · {@code #PauseGame CTT > 0} 期间的伤害事件（地图机制暂停态）。 */
    PAUSED("paused", false),

    /** G2 · 关卡刚切换（GameID 跳变后 N 个 tick 内）的余波事件。 */
    SESSION_BOUNDARY("session-boundary", false),

    /** G2 · victim 带 {@code Coffin / Prop / NPC / TestDummy / Debug} 等非战斗 commandTag。 */
    SUSPECT_TAG("suspect-tag", false),

    /** G7a · damage 超过 {@code MaxHP × physicalCeilMultiplier}（默认 ×3）但未致死——大概率是机制刀。 */
    OVERSIZE("oversize", true),

    /** G7a / G7b · damage 超过物理地板或 P95×K 且**致死**——纯机制斩杀，伤害不计但击杀仍归玩家。 */
    LETHAL_MECHANISM("lethal-mechanism", true),

    /** G6 · 同 {@code (victim, damage, tick-1)} 重复出现的事件（伪伤害 mirror）。 */
    DUPLICATE("duplicate", false),

    /** G7b · damage 超过 {@code currentP95 × deathAnchorOutlierMultiplier} 且非致死。 */
    OUTLIER("outlier", true),

    /** G5 · 同 tick ≥ N 个 victim 各自 ≥ 95% MaxHP 的整批清场（{@code /kill @e} 等）。 */
    MASS_WIPE("mass-wipe", false);

    private final String shortTag;
    private final boolean writesContributors;

    FilterReason(String shortTag, boolean writesContributors) {
        this.shortTag = shortTag;
        this.writesContributors = writesContributors;
    }

    /** 短标签，用于 {@code [L9-FILT:xxx]} 日志与 NBT 字段。保持小写连字符稳定。 */
    public String shortTag() { return shortTag; }

    /**
     * 该 reason 命中后是否仍允许 {@link com.ctt.healthdisplay.server.VictimDamageContributors}
     * 记录贡献（影响击杀 / 助攻归属，不影响伤害账户）。
     */
    public boolean writesContributors() { return writesContributors; }
}
