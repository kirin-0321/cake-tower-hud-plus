package com.ctt.healthdisplay.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v6.5.2 · 攻击者归属探针（weapon-guarded 八层硬归属 + L9 三子层未分类）。
 *
 * <h2>归属优先级（全部带"武器类型守卫"）</h2>
 * <pre>
 *   ── 已分类（硬归属，进玩家账户 / grandTotal）─────────────
 *   L1  WEAPON_MATCH   唯一「持武器+近 10s 开火」玩家        ← 主手硬证据最强
 *   L2  STAT_TICK      本 tick vanilla damage_dealt stat    ← 70+ 近战武器
 *   L3  MARKER_NEAR    3m  marker/projectile + PlayerID    ← 贴身 marker
 *   L4  MARKER_FAR     40m marker/projectile + PlayerID    ← 远程标记点
 *   L5  STAT_WINDOW    近 5t damage_dealt 窗口              ← 近战延续 tick
 *   L6  FIRE_WINDOW    近 20t 右键 + 40m + Tier 打分        ← 远程右键法器
 *   L7  BOW_RELEASE    近 2s used:bow + victim 40m 内       ← 弓 / 弩 / 三叉戟
 *   L8  LAST_HITTER    victim×T 硬证据续归属 (20s TTL)      ← 召唤物 / DoT 续击
 *   ── 未分类（软桶，不进 grandTotal）──────────────────────
 *   L9-NONE   归属失败                                     ← 真未分类
 *   L9-FILTER 黑名单数值（怪物初始化 / 形态切换假伤害）       ← {@link com.ctt.healthdisplay.config.ModConfig#initHpJumpValues}
 *   L9-HEAL   绿色回血粒子（怪物 / 玩家回血事件）             ← background:-16515325
 * </pre>
 *
 * <h2>v6.5.2 改动要点</h2>
 * <ul>
 *   <li><b>删除 L8/L8b 召唤物归属</b>：{@code SUMMON_FALLBACK} / {@code SUMMON_SHARED}
 *       因误归属率高（40m 内唯一召唤物玩家不一定是真正的伤害源）整层删除。
 *       原 {@code scanSummonHolders} / {@code SummonHolder} / {@code Share} 已移除。</li>
 *   <li><b>L7b → L7 升位</b>：BOW_RELEASE 从软层升为硬归属（vanilla used:bow 是确定性信号）。</li>
 *   <li><b>L7 LAST_HITTER → L8 降位 + 改硬归属</b>：续归属仍是已分类层、伤害进玩家账户，
 *       但置后于 BOW_RELEASE。</li>
 *   <li><b>L9 拆三子层</b>：NONE / FILTER / HEAL 各自独立计数，UI 上"未归属"行细分展示。</li>
 *   <li><b>grandTotal 重定义</b>：仅 = sum(L1..L8)。L9 整体不进总伤害分母，
 *       玩家百分比 = self / grandTotal，与 L9 解耦。</li>
 * </ul>
 *
 * <h2>硬证据 vs 软层</h2>
 * <p>{@link #isHardLayer} 返回 true 的层才会写入 {@link VictimLastHitter}
 * 和 {@link PlayerRecentAttributionLog}。硬层 = L2/L3/L4/L5（物理 / stat 直接证据）。
 * 注意 {@link #isHardLayer} 与 {@link #isAttributionClassified} 是两个正交概念：
 * <ul>
 *   <li>isHardLayer  → 是否可作为续归属种子（避免错链）</li>
 *   <li>isAttributionClassified → 是否进玩家账户（伤害 / 击杀 / 助攻）</li>
 * </ul>
 */
public final class AttackerProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-attrib");

    public static final Set<String> TRACKED_OBJECTIVES = Set.of(
            "MeleeDMG",
            "BulletDMG",
            "ForceDMG",
            "FireDMG",
            "WaterDMG",
            "IceDMG",
            "DarkDMG",
            "LightDMG",
            "ElectricDMG"
    );

    public static boolean isTracked(String objectiveName) {
        return TRACKED_OBJECTIVES.contains(objectiveName);
    }

    /**
     * v6.5.2 · 归属层枚举。<br>
     * L1~L8 = 已分类（硬归属，进玩家账户）；<br>
     * L9_NONE / L9_FILTER / L9_HEAL = 未分类三子层（不进玩家账户、不进 grandTotal）。
     */
    public enum Layer {
        L1_WEAPON_MATCH("L1"),
        L2_STAT_TICK("L2"),
        L3_MARKER_NEAR("L3"),
        L4_MARKER_FAR("L4"),
        L5_STAT_WINDOW("L5"),
        L6_FIRE_WINDOW("L6"),
        /**
         * v6.3.7 · 弓/弩/三叉戟"刚释放完"的短暂窗口归属。<br>
         * v6.5.2 升位：从原 L7b 软层提升为 L7 硬归属（vanilla used:bow 是直接信号、置信度足够）。
         */
        L7_BOW_RELEASE("L7"),
        /**
         * v6.2.0 · victim × type 续归属缓存（20s TTL）。<br>
         * v6.5.2 降位：从原 L7 → L8，并改为硬归属（伤害进玩家账户）。<br>
         * <b>v6.5.9 修订：L8 实测中误命中过多（剧情 set 假伤害也会 carry 给最后命中玩家），
         * 伤害不再进玩家账户</b>，由 {@link com.ctt.healthdisplay.server.PlayerDamageStats#addCarry}
         * 累计到独立 L9-CARRY 桶。<br>
         * 注意：枚举本身仍属"已分类"层（{@link #isUnclassified()} 返回 false / 
         * {@link AttackerProbe#isAttributionClassified} 返回 true），
         * 击杀归属维度仍能用 L8 作为 killer 兜底
         * （见 {@link com.ctt.healthdisplay.server.VictimTombstone}）。
         */
        L8_LAST_HITTER("L8"),
        /** L9 子层 · 真正归属失败（兜底匹配全挂）。 */
        L9_NONE("L9-NONE"),
        /**
         * L9 子层 · 黑名单数值过滤（怪物初始化 / 形态切换的假伤害）。<br>
         * 触发：{@link com.ctt.healthdisplay.server.mixin.ScoreboardUpdateMixin}
         * 计算 RedHearts delta 时命中 {@link com.ctt.healthdisplay.config.ModConfig#initHpJumpValues}。
         */
        L9_FILTER("L9-FILT"),
        /**
         * L9 子层 · 绿色回血粒子。<br>
         * 触发：{@link DamageProbe#record} 检测 text_display 的 background=-16515325。
         */
        L9_HEAL("L9-HEAL");

        private final String shortTag;

        Layer(String shortTag) { this.shortTag = shortTag; }

        public String shortTag() { return shortTag; }

        /** v6.5.2 · 是否为 L9 任意子层（未分类大类）。 */
        public boolean isUnclassified() {
            return this == L9_NONE || this == L9_FILTER || this == L9_HEAL;
        }
    }

    /**
     * 运行时硬开关（保留作为兜底）。<br>
     * v6.5.3 起：实际 broadcast 检查 = {@code chatBroadcastEnabled && ModConfig.broadcastDamageInChat}。
     * 用户可在配置界面（伤害/击杀/承伤 三类聊天广播）切换 ModConfig 字段；
     * 该静态字段保持 true，未来若需加调试后门再写入。
     */
    public static volatile boolean chatBroadcastEnabled = true;
    private static final int MAX_LINES_PER_TICK = 3;
    private static final ConcurrentLinkedQueue<Text> pendingBroadcasts = new ConcurrentLinkedQueue<>();

    private static final AtomicLong eventCount = new AtomicLong();
    private static final AtomicLong[] layerCounts = new AtomicLong[Layer.values().length];
    static {
        for (int i = 0; i < layerCounts.length; i++) layerCounts[i] = new AtomicLong();
    }

    // ============ 参数（v6.2.0）============
    /** L1 认定"最近开过火算新鲜"的时间窗。 */
    private static final long L1_FIRE_FRESH_TICKS = 200; // 10 秒
    /** L5 近战延续窗口。 */
    private static final long L5_STAT_WINDOW_TICKS = 5;
    /** L6 右键回看窗口。 */
    private static final long L6_FIRE_WINDOW_TICKS = 20;
    /**
     * v6.3.7 · L7 弓释放回看窗口（原 L7b，v6.5.2 升位为 L7）。
     * <p>v6.3.8：从 60 tick(3s) 缩短为 40 tick(2s)——普通射距飞行 <1s，
     * 2s 足够覆盖中近距离；窗口越短越不容易把"2 秒前那一箭"误挂到当前伤害上。
     */
    public static final long L7_BOW_RELEASE_TICKS = 40;
    /** L1 / L6 / L8 的玩家-victim 距离上限。 */
    private static final double MAX_DISTANCE_M = 40.0;
    // ======================================

    /** v6.1.0 · DamageShower 兜底 objective 伪名。 */
    public static final String ALL_DMG_OBJECTIVE = "AllDMG";

    /** v6.1.0 · 本 tick 已被 *DMG 管线触发过归属的 victim 集合，避免 shower 兜底重复广播。 */
    private static final Map<Long, Set<UUID>> perTickAttributedVictims = new ConcurrentHashMap<>();
    private static final long ATTRIBUTED_TTL_TICKS = 5;

    private AttackerProbe() {}

    /** 硬证据层（有物理/stat 证据）。硬层成功才写入续归属缓存与类型日志。 */
    private static boolean isHardLayer(Layer layer) {
        return layer == Layer.L2_STAT_TICK
                || layer == Layer.L3_MARKER_NEAR
                || layer == Layer.L4_MARKER_FAR
                || layer == Layer.L5_STAT_WINDOW;
    }

    /**
     * v6.5.2 · 是否"已分类"层（能作为击杀 / 助攻 / contributors 归属凭据）。
     *
     * <p>判定规则：
     * <ul>
     *   <li>L1~L8（含 L7_BOW_RELEASE / L8_LAST_HITTER）→ 已分类</li>
     *   <li>L9_NONE / L9_FILTER / L9_HEAL → 未分类</li>
     * </ul>
     *
     * <p><b>v6.5.9 注意</b>：本方法只判"归属凭据是否成立"（用于击杀 / 助攻判定），
     * <b>不</b>等同于"伤害是否进玩家账户"。L8_LAST_HITTER 在 v6.5.9 起的"伤害"维度
     * 已剥离玩家账户（独立 L9-CARRY 桶），但击杀维度仍可由 L8 兜底归属——这两个维度
     * 由 {@link AttackerProbe#feedStats}（伤害侧）与
     * {@link com.ctt.healthdisplay.server.VictimTombstone}（击杀侧）独立决策，
     * 不要再用 isAttributionClassified 来判断"伤害是否进账户"。
     *
     * <p>供 {@link VictimDamageContributors} / {@link VictimTombstone} / {@link PlayerKillStats}
     * 统一决策"这次归属能否作为击杀 / 助攻凭据"。
     */
    public static boolean isAttributionClassified(Layer layer) {
        if (layer == null) return false;
        return !layer.isUnclassified();
    }

    // =========================================================================
    //  v6.3.0 · 累积到 PlayerDamageStats
    //  v6.5.2 · 拆分 L9 三子层（NONE / FILTER / HEAL）
    //  v6.5.9 · L8_LAST_HITTER carry 兜底从玩家账户剥离（独立 L9-CARRY 桶）。
    //           击杀归属维度不变（VictimTombstone 仍用 L8 作为 killer 兜底）。
    // =========================================================================
    private static void feedStats(Result r, int damage) {
        if (r == null) return;
        // L9 子层：直接走对应桶（不进玩家账户、不进 grandTotal）。
        if (r.layer.isUnclassified()) {
            PlayerDamageStats.addUnclassified(r.layer, damage);
            return;
        }
        // v6.5.9 · L8 carry 实测中误命中过多（剧情 set 假伤害也会 carry 给最后命中玩家），
        //          伤害不再进玩家账户，统一沉到 L9-CARRY 桶（保留诊断 globalLayerCounts[L8]）。
        if (r.layer == Layer.L8_LAST_HITTER) {
            PlayerDamageStats.addCarry(damage);
            return;
        }
        // L1~L7：已分类，伤害进玩家账户。
        if (r.attackerUuid != null) {
            String name = trimPlayerLabel(r.attackerLabel);
            PlayerDamageStats.add(r.attackerUuid, name, null, damage, r.layer);
        } else {
            // 罕见：层是 L1~L7 但 attackerUuid 为 null（如 L4 marker 反查不到玩家）。
            // 仍按未分类处理，避免数据丢失。
            PlayerDamageStats.addUnclassified(Layer.L9_NONE, damage);
        }
    }

    private static String trimPlayerLabel(String label) {
        if (label == null) return "?";
        if (label.startsWith("Player(") && label.endsWith(")")) {
            return label.substring(7, label.length() - 1);
        }
        return label;
    }

    // =========================================================================
    //  入口 1：*DMG 管线（MeleeDMG / BulletDMG / ... / 9 种 + 地图其他扩展）
    //
    //  v6.3.2 · 参数语义变更：{@code newValue} 现在由 Mixin 通过
    //  {@link ScoreDeltaTracker} 计算出来的"本次伤害增量 delta"，而非
    //  scoreboard 当前累计值。Mixin 保证只有 delta > 0 才调这里。
    //  本方法不再写入 PlayerDamageStats（那由 recordFromDamageShower 独占），
    //  只做归属 + 续归属缓存 + 聊天广播。
    // =========================================================================
    public static void record(MinecraftServer server, String objective,
                              ScoreHolder victimHolder, int newValue) {
        if (server == null) return;
        if (newValue <= 0) return;

        String holderName = victimHolder.getNameForScoreboard();
        UUID victimUuid;
        try {
            victimUuid = UUID.fromString(holderName);
        } catch (IllegalArgumentException e) {
            return;
        }

        Entity victim = null;
        ServerWorld victimWorld = null;
        for (ServerWorld w : server.getWorlds()) {
            Entity found = w.getEntity(victimUuid);
            if (found != null) {
                victim = found;
                victimWorld = w;
                break;
            }
        }
        if (victim == null || victimWorld == null) return;
        if (!victim.getCommandTags().contains("E")) return;

        long n = eventCount.incrementAndGet();
        long tick = DamageProbe.currentTick();
        Vec3d victimPos = victim.getPos();
        String worldKey = victimWorld.getRegistryKey().getValue().toString();

        perTickAttributedVictims
                .computeIfAbsent(tick, t -> ConcurrentHashMap.newKeySet())
                .add(victimUuid);

        String victimName = victim.getName().getString();
        String victimType = Registries.ENTITY_TYPE.getId(victim.getType()).toString();

        Result r = attribute(server, victimWorld, victim, victimUuid, objective, victimPos, worldKey, tick);

        layerCounts[r.layer.ordinal()].incrementAndGet();

        // v6.3.2 修复：*DMG 管线只做归属 + 缓存 + 日志，不再写 PlayerDamageStats。
        //   原因：scoreboard `*DMG` 会在 DoT / 同 tick 多写场景下触发多次，mixin 的 delta
        //   只能部分补偿（2000→20→0 会先出 +2000 的假事件）。最终用户"总伤害"数字以
        //   DamageShower（每伤害事件唯一的 text_display 粒子）为唯一 source of truth，
        //   通过 recordFromDamageShower() 累加。*DMG 只负责告诉我们"谁打的 + 什么类型"。

        // 写入 v6.2.0 续归属缓存（VictimLastHitter，所有类型）：只认硬层
        if (r.attackerUuid != null && isHardLayer(r.layer)) {
            VictimLastHitter.remember(victimUuid, objective, r.attackerUuid,
                    r.attackerLabel, tick, r.detail);
        }

        // v6.3.2 · 任何成功归属（含软层 L1/L6/L7/L8）都写入 AllDMG 专用 carry 缓存，
        //   供随后 0~5 tick 内的 DamageShower 兜底复用归属结果。
        //   * 为什么可以写软层？AllDMG key 只被 DamageShower 查询，不参与
        //     原 *DMG 类型自身的续归属链，所以不会形成 L1→remember→L7→remember 环路。
        //   * 为什么要写？没有这一步，DamageShower 对"L1 持枪开火"事件只能落到
        //     L7/L8 或 L9，面板数据虽然数值对，但归属粒度会降级。
        if (r.attackerUuid != null) {
            VictimLastHitter.remember(victimUuid, ALL_DMG_OBJECTIVE, r.attackerUuid,
                    r.attackerLabel, tick, r.layer.shortTag() + ":" + r.detail);
        }

        // 元素伤害专道（VictimDamageSourceCache，仅元素 + 硬层 + L6 / L7-bow 都可写）：
        //   - L1 不写：是软推断，碰到新元素武器不应污染 DoT carry
        //   - L8_LAST_HITTER 不写：它本身就在查这个缓存（避免自循环）
        //   - L9 子层不写：未分类不能作为续归属种子
        if (r.attackerUuid != null
                && r.layer != Layer.L1_WEAPON_MATCH
                && r.layer != Layer.L8_LAST_HITTER
                && !r.layer.isUnclassified()
                && VictimDamageSourceCache.isCarryable(objective)) {
            VictimDamageSourceCache.remember(victimUuid, objective, r.attackerUuid,
                    r.attackerLabel, tick, r.layer.shortTag());
        }

        // 只有硬层写 PlayerRecentAttributionLog（用于 L6 Tier 打分）
        if (r.attackerUuid != null && isHardLayer(r.layer)) {
            PlayerRecentAttributionLog.record(r.attackerUuid, objective, tick);
        }

        // v6.3.3 · 归属成功时写入 VictimTypeCache，
        // 让 0.5 s 内同 victim 的 DamageShower 入口聊天栏能复用具体类型 + 归属。
        if (r.attackerUuid != null) {
            VictimTypeCache.put(victimUuid, new VictimTypeCache.Snap(
                    tick, objective, r.attackerUuid, r.attackerLabel, r.layer, r.detail));
        }

        // v6.6.1 hotfix · 高频伤害事件日志降级为 DEBUG。INFO 阈值下 SLF4J 直接短路，
        // 避免 boss / 高频 DoT 怪每 tick 几十行日志拖垮 TPS。
        // 开发期需要看时改 logback 阈值为 DEBUG（或直接打开 broadcastDamageInChat 走聊天栏）。
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[CTT Attrib#{}] type={} victim={} ({}) delta={} layer={} attacker={} detail={}",
                    n, objective, victimName, victimType, newValue, r.layer, r.attackerLabel, r.detail);
        }

        // v6.3.3 · *DMG 入口不再向聊天广播。
        //   原因：即使 mixin 用了 delta 过滤，地图在同 tick 多次 set（先 35500 再 355）
        //   仍会让 delta 变成 +35500 这种假大值。为了让聊天栏只显示"实时单次伤害"，
        //   聊天广播完全交给 DamageShower 入口（每事件唯一 text_display，值 write-once）；
        //   具体伤害类型通过 VictimTypeCache 从这里带到那里。
    }

    // =========================================================================
    //  入口 2：DamageShower / RedHearts 路径（v6.1.0 / v6.6.0）
    //  v6.5.2 · 新增 forceLayer 参数：非 null 时跳过 attribute()，强制走 L9 子层。
    // =========================================================================

    /** 兼容旧入口：不强制 layer，走完整 attribute 流程。 */
    public static void recordFromDamageShower(MinecraftServer server, Entity victim,
                                              ServerWorld victimWorld, int damage, long tick) {
        recordFromDamageShower(server, victim, victimWorld, damage, tick, null, null);
    }

    /** v6.5.2 兼容入口：带 forceLayer 但不带 reason。 */
    public static void recordFromDamageShower(MinecraftServer server, Entity victim,
                                              ServerWorld victimWorld, int damage, long tick,
                                              Layer forceLayer) {
        recordFromDamageShower(server, victim, victimWorld, damage, tick, forceLayer, null);
    }

    /**
     * v6.5.2 · 主入口（带 forceLayer）；v6.7.4 · 新增 {@code filterReasonTag} 参数。
     *
     * @param forceLayer      非 null 时直接跳过归属逻辑，强制路由到该层（必须是 L9 子层）。
     *                        用于：
     *                        <ul>
     *                          <li>{@link com.ctt.healthdisplay.server.mixin.ScoreboardUpdateMixin}
     *                              RedHearts delta 命中黑名单 → {@link Layer#L9_FILTER}</li>
     *                          <li>{@link DamageProbe} 检测到绿色回血粒子 → {@link Layer#L9_HEAL}</li>
     *                        </ul>
     * @param filterReasonTag {@code forceLayer == L9_FILTER} 时由
     *                        {@link com.ctt.healthdisplay.server.filter.FilterReason#shortTag()} 传入
     *                        （如 {@code "low-noise"} / {@code "init-hp-jump"} / {@code "suspect-tag"} / ...）；
     *                        其它 layer 传 {@code null}。本字段会拼接进 detail 字符串，让聊天广播
     *                        与日志能直接看出哪条规则命中——v6.7.4 之前无此暴露，所有 L9-FILT 看起来
     *                        都一样导致诊断困难。
     */
    public static void recordFromDamageShower(MinecraftServer server, Entity victim,
                                              ServerWorld victimWorld, int damage, long tick,
                                              Layer forceLayer, String filterReasonTag) {
        if (server == null || victim == null || victimWorld == null) return;
        if (damage <= 0) return;
        if (!victim.getCommandTags().contains("E")) return;

        UUID victimUuid = victim.getUuid();
        long n = eventCount.incrementAndGet();
        Vec3d victimPos = victim.getPos();
        String worldKey = victimWorld.getRegistryKey().getValue().toString();

        String victimName = victim.getName().getString();
        String victimType = Registries.ENTITY_TYPE.getId(victim.getType()).toString();

        // 保留 perTickAttributedVictims 作诊断；去重已不需要（*DMG 入口不再广播）。
        perTickAttributedVictims
                .computeIfAbsent(tick, t -> ConcurrentHashMap.newKeySet())
                .add(victimUuid);

        Result r;
        String displayObjective;
        String suffix;
        if (forceLayer != null && forceLayer.isUnclassified()) {
            // v6.5.2 · 强制路由：黑名单 / 回血。不查归属，直接打成对应 L9 子层。
            // v6.5.8 · L9_FILTER 的 detail 由调用方决策（init-hp-jump vs suspect-victim），
            //        此处统一打 victim name 让聊天栏一眼看出"哪个怪触发了过滤"。
            String label = forceLayer == Layer.L9_FILTER ? "<filtered>"
                    : forceLayer == Layer.L9_HEAL ? "<heal>" : "<unattributed>";
            String detail;
            if (forceLayer == Layer.L9_FILTER) {
                if (filterReasonTag != null && !filterReasonTag.isEmpty()) {
                    detail = String.format("reason=%s value=%d victim=%s",
                            filterReasonTag, damage, victimName);
                } else {
                    detail = String.format("filtered value=%d victim=%s", damage, victimName);
                }
            } else if (forceLayer == Layer.L9_HEAL) {
                detail = "heal-particle";
            } else {
                detail = "forced";
            }
            r = new Result(forceLayer, null, label, detail);
            displayObjective = ALL_DMG_OBJECTIVE;
            suffix = forceLayer.shortTag();
        } else {
            // v6.3.3 · 优先从 VictimTypeCache 取"最近一次 *DMG 归属"作为显示依据：
            //   - 命中：聊天栏能显示具体类型（FireDMG 等）+ 精确归属（L1/L2 等）
            //   - 未命中：fallback 调 attribute(AllDMG)，走 L8/L9 兜底
            VictimTypeCache.Snap typeSnap = VictimTypeCache.get(victimUuid, tick);
            if (typeSnap != null && typeSnap.attackerUuid() != null) {
                r = new Result(typeSnap.layer(), typeSnap.attackerUuid(),
                        typeSnap.attackerLabel(), typeSnap.detail());
                displayObjective = typeSnap.objective();
                suffix = "typecache";
            } else {
                r = attribute(server, victimWorld, victim, victimUuid,
                        ALL_DMG_OBJECTIVE, victimPos, worldKey, tick);
                displayObjective = ALL_DMG_OBJECTIVE;
                suffix = "fallback";
            }
        }

        layerCounts[r.layer.ordinal()].incrementAndGet();

        // v6.3.0 · 按玩家累积到 PlayerDamageStats（唯一累加入口，数值就是 DamageShower 的真实 damage）
        feedStats(r, damage);

        // v6.4.0 · 击杀 / 助攻归属（阶段 ② 方案 C）：
        //   1) VictimLethalCandidate：登记"最近一次对该 victim 的归属结果"，
        //      tombstone 识别死亡时直接读出作为 killer；
        //      即使归属失败（r.attackerUuid == null）也写——让 tombstone 能识别"吃过伤害"。
        VictimLethalCandidate.remember(victimUuid, r.attackerUuid, r.attackerLabel,
                tick, r.layer, damage, displayObjective,
                victimWorld.getRegistryKey());
        //   2) VictimDamageContributors：累加本场对该 victim 的每玩家贡献（助攻数据源）。
        //      L7+/L8+/L9 未分类层不写（和 PlayerDamageStats 一致）。
        if (r.attackerUuid != null && isAttributionClassified(r.layer)) {
            VictimDamageContributors.addContribution(victimUuid, r.attackerUuid,
                    trimPlayerLabel(r.attackerLabel), damage, tick, true);
        }

        // AllDMG 专道仍保留：下次纯 vanilla 伤害若错过 typecache，可续归属。
        if (r.attackerUuid != null && isHardLayer(r.layer)) {
            VictimLastHitter.remember(victimUuid, ALL_DMG_OBJECTIVE, r.attackerUuid,
                    r.attackerLabel, tick, r.detail);
        }

        // v6.6.1 hotfix · 高频伤害事件日志降级为 DEBUG（同上）。
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[CTT Attrib#{}] type={} victim={} ({}) dmg={} layer={} attacker={} detail={} [{}]",
                    n, displayObjective, victimName, victimType, damage, r.layer, r.attackerLabel, r.detail, suffix);
        }

        // v8.x · 改为 per-player 订阅 + 全局兜底双路由：
        //   1) 全局 broadcastDamageInChat（编辑 JSON 启用）→ 给全服广播（旧行为，运维兜底）
        //   2) 任意玩家通过 /ctthd broadcast damage on 订阅 → 仅给订阅者发
        // 两条路径任一开启就构造 Text 入队，flushBroadcasts 内分别派发。
        // v8.x · broadcastDamageThreshold：单次 damage < 阈值时不入队（治疗负数也自动过滤）。
        //        阈值 0（默认）= 全部通过。
        if (chatBroadcastEnabled
                && damage >= com.ctt.healthdisplay.config.ServerConfig.INSTANCE.broadcastDamageThreshold
                && (com.ctt.healthdisplay.config.ServerConfig.INSTANCE.broadcastDamageInChat
                    || com.ctt.healthdisplay.server.command.BroadcastSubscribers
                        .hasAnySubscriber(com.ctt.healthdisplay.server.command.BroadcastSubscribers.Channel.DAMAGE))) {
            pendingBroadcasts.add(buildChatLine(n, displayObjective, victimName, damage, r));
        }
    }

    /**
     * 归属结果。v6.5.2 · 删除 L8b shares 字段（召唤物分摊层已删）。
     *
     * @param attackerUuid  attacker UUID；未归属为 null
     * @param attackerLabel 展示用标签（L9_NONE 为 "&lt;unattributed&gt;"，L9_FILTER / L9_HEAL 为对应特殊标签）
     */
    private record Result(Layer layer, UUID attackerUuid, String attackerLabel, String detail) {}

    // =========================================================================
    //  归属主逻辑（九层）
    // =========================================================================
    private static Result attribute(MinecraftServer server, ServerWorld world, Entity victim,
                                    UUID victimUuid, String objective,
                                    Vec3d victimPos, String worldKey, long tick) {
        boolean guardApplies = !ALL_DMG_OBJECTIVE.equals(objective); // AllDMG 不做守卫
        boolean carryable = VictimDamageSourceCache.isCarryable(objective);

        // -------- L1: WEAPON_MATCH --------
        // 条件：玩家主手/背包持有能造 objective 类型伤害的武器 + 10s 内开过火 + 40m 内。
        // 恰有 1 人匹配 → 直接归属；多人 → 取"最近开火"者。
        if (guardApplies) {
            List<L1Candidate> l1 = collectL1Candidates(server, objective, victimPos, worldKey, tick);
            if (!l1.isEmpty()) {
                // v8.0.0 性能：O(N log N) sort → O(N) 单次扫找极值。
                // 选择规则：最近开火优先；同 tick 则距离近。语义和原 sort 完全等价。
                L1Candidate winner = l1.get(0);
                for (int i = 1; i < l1.size(); i++) {
                    L1Candidate c = l1.get(i);
                    if (c.fireTick > winner.fireTick
                            || (c.fireTick == winner.fireTick && c.distance < winner.distance)) {
                        winner = c;
                    }
                }
                String detail = String.format("hand=%s fire-age=%dt d=%.1fm pool=%d",
                        winner.weaponKey, tick - winner.fireTick, winner.distance, l1.size());
                return new Result(Layer.L1_WEAPON_MATCH, winner.playerUuid,
                        "Player(" + winner.playerName + ")", detail);
            }
        }

        // -------- L2: STAT_TICK --------
        List<PlayerHitLog.PlayerHit> sameTick = PlayerHitLog.queryAtTick(tick);
        if (!sameTick.isEmpty()) {
            PlayerHitLog.PlayerHit winner = pickByWeaponGuard(server, sameTick, objective,
                    victimPos, worldKey, guardApplies);
            if (winner != null) {
                return mkResult(server, Layer.L2_STAT_TICK, winner.playerUuid(),
                        String.format("weapon=%s delta=%d", winner.weaponId(), winner.delta()));
            }
        }

        // -------- L3: MARKER_NEAR (3m) --------
        List<AttackerResolver.Candidate> mNear = AttackerResolver.scanMarkers(
                world, victim, AttackerResolver.MARKER_NEAR);
        if (!mNear.isEmpty()) {
            AttackerResolver.Candidate c = pickMarkerByWeaponGuard(world, mNear, objective, guardApplies);
            if (c != null) {
                return mkResultByPid(world, Layer.L3_MARKER_NEAR, c.playerId(),
                        describeList(mNear));
            }
        }

        // -------- L4: MARKER_FAR (40m) --------
        List<AttackerResolver.Candidate> mFar = AttackerResolver.scanMarkers(
                world, victim, AttackerResolver.MARKER_FAR);
        if (!mFar.isEmpty()) {
            AttackerResolver.Candidate c = pickMarkerByWeaponGuard(world, mFar, objective, guardApplies);
            if (c != null) {
                return mkResultByPid(world, Layer.L4_MARKER_FAR, c.playerId(),
                        describeList(mFar));
            }
        }

        // -------- L5: STAT_WINDOW (近 5t) --------
        List<PlayerHitLog.PlayerHit> window = PlayerHitLog.query(tick - L5_STAT_WINDOW_TICKS, tick - 1);
        if (!window.isEmpty()) {
            PlayerHitLog.PlayerHit winner = pickByWeaponGuard(server, window, objective,
                    victimPos, worldKey, guardApplies);
            if (winner != null) {
                return mkResult(server, Layer.L5_STAT_WINDOW, winner.playerUuid(),
                        String.format("weapon=%s delta=%d age=%dt",
                                winner.weaponId(), winner.delta(), tick - winner.tick()));
            }
        }

        // -------- L6: FIRE_WINDOW (右键 + Tier 打分) --------
        List<PlayerFireLog.FireCandidate> fires = PlayerFireLog.query(
                tick, L6_FIRE_WINDOW_TICKS, victimPos, worldKey, MAX_DISTANCE_M);
        if (!fires.isEmpty()) {
            PlayerFireLog.FireCandidate best = null;
            int bestTier = Integer.MAX_VALUE;
            double bestDist = Double.MAX_VALUE;
            StringBuilder diag = new StringBuilder();
            long from = tick - PlayerRecentAttributionLog.TTL_TICKS;

            for (PlayerFireLog.FireCandidate f : fires) {
                // 武器守卫
                if (guardApplies && !PlayerInventoryIndex.hasMatchingWeapon(f.playerUuid(), objective)) {
                    continue;
                }
                Set<String> recent = PlayerRecentAttributionLog.queryTypes(f.playerUuid(), from, tick);
                int tier;
                if (recent.isEmpty()) tier = 2;
                else if (recent.contains(objective)) tier = 1;
                else tier = 3;

                if (diag.length() > 0) diag.append(",");
                ServerPlayerEntity sp = server.getPlayerManager().getPlayer(f.playerUuid());
                String pn = (sp != null) ? sp.getName().getString() : "?";
                diag.append(String.format("%s/T%d/d%.1f", pn, tier, f.distance()));

                if (tier < bestTier || (tier == bestTier && f.distance() < bestDist)) {
                    bestTier = tier;
                    bestDist = f.distance();
                    best = f;
                }
            }

            if (best != null) {
                ServerPlayerEntity sp = server.getPlayerManager().getPlayer(best.playerUuid());
                String label = (sp != null) ? "Player(" + sp.getName().getString() + ")"
                        : "Unknown(uuid=" + best.playerUuid() + ")";
                String detail = String.format("item=%s d=%.1fm age=%dt tier=%d [%s]",
                        best.itemSig(), best.distance(), best.ticksAgo(), bestTier, diag);
                return new Result(Layer.L6_FIRE_WINDOW, best.playerUuid(), label, detail);
            }
        }

        // -------- L7: BOW_RELEASE_WINDOW（v6.5.2 升位为 L7 硬归属）--------
        // 弓/弩/三叉戟射击的单独信号源：vanilla used:bow stat（最近 2s）。
        // 不做武器守卫——玩家射完可能立刻切武器，重点是"这 2s 内射过一发"。
        // 也不限于 BulletDMG；箭矢落地后地图可能把伤害分解成多类型，
        // 这 2s 内出现的**任何**类型伤害都算这个弓手的。
        {
            List<PlayerFireLog.FireCandidate> bowReleases = PlayerFireLog.queryBowReleases(
                    tick, L7_BOW_RELEASE_TICKS, victimPos, worldKey, MAX_DISTANCE_M);
            if (!bowReleases.isEmpty()) {
                PlayerFireLog.FireCandidate winner = bowReleases.get(0); // 已按最近释放排序
                String detail = String.format("bow fire-age=%dt d=%.1fm pool=%d item=%s",
                        tick - winner.tick(), winner.distance(), bowReleases.size(),
                        winner.itemSig());
                ServerPlayerEntity sp = server.getPlayerManager().getPlayer(winner.playerUuid());
                String label = "Player(" + (sp != null ? sp.getName().getString() : "?") + ")";
                return new Result(Layer.L7_BOW_RELEASE, winner.playerUuid(), label, detail);
            }
        }

        // -------- L8: LAST_HITTER (续归属·v6.5.2 改硬归属) --------
        // 优先查 VictimLastHitter（所有类型，20s TTL）；元素伤害再查 VictimDamageSourceCache。
        // 守卫：续归属候选玩家现在仍需持有匹配武器，否则说明他已收刀走开 —— 不续。
        VictimLastHitter.Entry hit = VictimLastHitter.lookup(victimUuid, objective, tick);
        if (hit != null && (!guardApplies
                || PlayerInventoryIndex.hasMatchingWeapon(hit.attackerUuid(), objective))) {
            String detail = String.format("carry %s age=%dt src=%s",
                    hit.attackerLabel(), hit.age(tick), hit.weaponHint());
            return new Result(Layer.L8_LAST_HITTER, hit.attackerUuid(), hit.attackerLabel(), detail);
        }
        if (carryable) {
            VictimDamageSourceCache.Entry cached = VictimDamageSourceCache.lookup(victimUuid, objective, tick);
            if (cached != null && (!guardApplies
                    || PlayerInventoryIndex.hasMatchingWeapon(cached.attackerUuid(), objective))) {
                String detail = String.format("elem-carry %s age=%dt src=%s",
                        cached.attackerLabel(), cached.age(tick), cached.weaponHint());
                return new Result(Layer.L8_LAST_HITTER, cached.attackerUuid(), cached.attackerLabel(), detail);
            }
        }

        // v6.5.2 · 删除 L8 SUMMON_FALLBACK / L8b SUMMON_SHARED 召唤物兜底归属
        //   原因：召唤物持有者扫描误归属率高（玩家拿着召唤物站附近 != 那次伤害是他的召唤物造成）。
        //   保留 PlayerInventoryIndex.summonItemCountOf / WeaponDamageRegistry.allSummonKeys()
        //   作为 dead API 以便未来回滚。

        // -------- L9-NONE: 真未分类 --------
        return new Result(Layer.L9_NONE, null, "<unattributed>",
                String.format("hitTick=%d hitWin=%d fires=%d pMarker=%d/%d",
                        sameTick.size(), window.size(), fires.size(), mNear.size(), mFar.size()));
    }

    // =========================================================================
    //  辅助：L1 候选收集
    // =========================================================================
    private record L1Candidate(UUID playerUuid, String playerName, String weaponKey,
                               long fireTick, double distance) {}

    private static List<L1Candidate> collectL1Candidates(MinecraftServer server, String objective,
                                                         Vec3d victimPos, String worldKey, long tick) {
        List<L1Candidate> out = new ArrayList<>();
        if (!WeaponDamageRegistry.isLoaded()) return out;
        Set<String> weaponKeys = WeaponDamageRegistry.weaponsOfType(objective);
        Set<String> vanillaIds = WeaponDamageRegistry.vanillaItemsProducing(objective);
        // v6.3.4 · 只有两边都空才放弃——vanilla 武器（如弓）靠右边这张硬表支持。
        if (weaponKeys.isEmpty() && vanillaIds.isEmpty()) return out;

        double maxSq = MAX_DISTANCE_M * MAX_DISTANCE_M;
        long fireFrom = tick - L1_FIRE_FRESH_TICKS;

        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
            if (!worldKey.equals(sp.getWorld().getRegistryKey().getValue().toString())) continue;
            double dsq = sp.getPos().squaredDistanceTo(victimPos);
            if (dsq > maxSq) continue;

            UUID uuid = sp.getUuid();
            // 1. 必须持有匹配武器
            String matchedKey = findMatchingHeldKey(uuid, weaponKeys, objective);
            if (matchedKey == null) continue;

            // 2. 近 L1_FIRE_FRESH_TICKS 内必须开过火（右键 OR vanilla 左键 stat）
            long latestFire = latestFireOrHitTick(uuid, fireFrom, tick);
            if (latestFire < 0) continue;

            out.add(new L1Candidate(uuid, sp.getName().getString(), matchedKey,
                    latestFire, Math.sqrt(dsq)));
        }
        return out;
    }

    /**
     * 在给定候选 key 集合内，找玩家第一个持有的（主手优先；weapon kind 只看主手，
     * summon kind 看主手 + 背包）。没有返回 null。
     *
     * <p>v6.3.4 · 若自定义武器未命中，对 vanilla 弓/弩/三叉戟做兜底：
     * 主手的 vanilla item id 若能通过 {@link WeaponDamageRegistry#canVanillaProduce}
     * 则返回 {@code "vanilla:<itemName>"} 伪 key（仅用于日志 detail，不走 seed）。
     */
    private static String findMatchingHeldKey(UUID playerUuid, Set<String> candidateKeys, String damageType) {
        PlayerInventoryIndex.Snapshot snap = PlayerInventoryIndex.get(playerUuid);
        if (snap == PlayerInventoryIndex.Snapshot.EMPTY) return null;
        // 先主手（weapon kind 的唯一可攻击位置）
        for (String k : snap.mainHand()) {
            if (candidateKeys.contains(k)) return k;
        }
        // 再看背包里的 summon kind
        for (String k : snap.inventoryAny()) {
            if (!candidateKeys.contains(k)) continue;
            WeaponDamageRegistry.WeaponInfo info = WeaponDamageRegistry.getInfo(k);
            if (info != null && info.kind() == WeaponDamageRegistry.Kind.SUMMON) return k;
        }
        // v6.3.4 · vanilla 主手兜底（弓 / 弩 / 三叉戟）
        if (snap.mainHandItemId() != null
                && WeaponDamageRegistry.canVanillaProduce(snap.mainHandItemId(), damageType)) {
            String id = snap.mainHandItemId();
            int slash = id.indexOf(':');
            String short_ = slash >= 0 ? id.substring(slash + 1) : id;
            return "vanilla:" + short_;
        }
        return null;
    }

    /**
     * 玩家在 [fireFrom, tick] 内最近一次活跃时刻（右键开火 或 vanilla stat hit）。
     * 没有返回 -1。用于 L1「近 10s 开过火」认定。
     *
     * <p>v8.0.0 性能：原本 PlayerHitLog 走 {@code query(from,to)} 全表扫 + 过滤本玩家，
     * 是 O(在线玩家数 × 全部 hit) 的二阶开销。改用专门的 {@code latestTickOf(uuid,...)} API
     * （和 PlayerFireLog 同模式），降到 O(本玩家自己的 hit)，命中即提前 break。
     */
    private static long latestFireOrHitTick(UUID playerUuid, long fireFrom, long tick) {
        long latest = -1;
        // PlayerHitLog：近 10s vanilla stat（左键近战）
        Long hit = PlayerHitLog.latestTickOf(playerUuid, fireFrom, tick);
        if (hit != null) latest = hit;
        // PlayerFireLog：近 10s 右键（carrot_on_a_stick）
        // 距离过滤在 L1 候选筛选时已做（victim 40m 内）；这里只判"有没有开过火"。
        Long recent = PlayerFireLog.latestTickOf(playerUuid, fireFrom, tick);
        if (recent != null && recent > latest) latest = recent;
        return latest;
    }

    // =========================================================================
    //  辅助：*DMG 守卫选择器
    // =========================================================================
    /** 从 hits 列表中按武器守卫 + 最近距离挑选。没有合格候选返回 null。 */
    private static PlayerHitLog.PlayerHit pickByWeaponGuard(MinecraftServer server,
                                                            List<PlayerHitLog.PlayerHit> hits,
                                                            String objective,
                                                            Vec3d victimPos, String worldKey,
                                                            boolean guardApplies) {
        PlayerHitLog.PlayerHit best = null;
        double bestSq = Double.MAX_VALUE;
        for (PlayerHitLog.PlayerHit h : hits) {
            if (guardApplies && !PlayerInventoryIndex.hasMatchingWeapon(h.playerUuid(), objective)) continue;
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(h.playerUuid());
            if (sp == null) continue;
            if (!worldKey.equals(sp.getWorld().getRegistryKey().getValue().toString())) continue;
            double dsq = sp.getPos().squaredDistanceTo(victimPos);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = h;
            }
        }
        return best;
    }

    /**
     * 从 marker 候选列表按武器守卫挑选。守卫判定流程：
     * <ol>
     *   <li>把 PlayerID 反查到玩家实体</li>
     *   <li>检查该玩家是否持有匹配武器</li>
     *   <li>否则跳过</li>
     * </ol>
     * 返回第一个合格候选（按距离升序，因为传入列表本身就已排序）。
     */
    private static AttackerResolver.Candidate pickMarkerByWeaponGuard(ServerWorld world,
                                                                     List<AttackerResolver.Candidate> markers,
                                                                     String objective,
                                                                     boolean guardApplies) {
        for (AttackerResolver.Candidate c : markers) {
            if (!guardApplies) return c;
            PlayerEntity p = AttackerResolver.lookupPlayerByPlayerId(world, c.playerId());
            if (p == null) {
                // 反查失败（玩家可能已离线）——允许通过，因为 marker 本身就是硬证据
                return c;
            }
            if (PlayerInventoryIndex.hasMatchingWeapon(p.getUuid(), objective)) return c;
            // 否则继续看下一个 marker（可能是另一个玩家的 marker）
        }
        return null;
    }

    // =========================================================================
    //  辅助：结果打包 / 文本呈现
    // =========================================================================
    private static String describeList(List<AttackerResolver.Candidate> list) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (AttackerResolver.Candidate c : list) {
            if (shown > 0) sb.append(", ");
            if (shown >= 3) {
                sb.append("+").append(list.size() - 3);
                break;
            }
            sb.append(c.desc());
            shown++;
        }
        return sb.toString();
    }

    private static Result mkResult(MinecraftServer server, Layer layer, UUID attackerUuid, String detail) {
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(attackerUuid);
        String label = (sp != null) ? "Player(" + sp.getName().getString() + ")"
                : "Unknown(uuid=" + attackerUuid + ")";
        return new Result(layer, attackerUuid, label, detail);
    }

    private static Result mkResultByPid(ServerWorld world, Layer layer, int playerId, String detail) {
        PlayerEntity p = AttackerResolver.lookupPlayerByPlayerId(world, playerId);
        UUID uuid = (p != null) ? p.getUuid() : null;
        String label = (p != null) ? "Player(" + p.getName().getString() + ")"
                : String.format("Unknown(pid=%d)", playerId);
        return new Result(layer, uuid, label, detail);
    }

    /**
     * v6.5.3 · 聊天广播 · 统一格式：
     * <pre>[伤害] 玩家名称 伤害类型 级别 -数值 对象 其他</pre>
     * 颜色按字段保持各自语义：
     * <ul>
     *   <li>{@code [伤害]} 红色（与 {@code [承伤]} 同源，对仗）</li>
     *   <li>玩家名称：玩家归属绿色 / L9-NONE 深红 / L9-FILTER 深紫 / L9-HEAL 深绿 / 其它金色</li>
     *   <li>伤害类型（objective）：黄色</li>
     *   <li>级别（layer.shortTag）：默认青色，L9 子层用相应高亮色</li>
     *   <li>数值（-N）：红色</li>
     *   <li>对象（victim）：白色</li>
     *   <li>其他（detail）：深灰（仅当非空时追加）</li>
     * </ul>
     * <p>注：A#N 事件序号已不再写入聊天栏（仅日志保留），保持聊天观感简洁。
     */
    private static Text buildChatLine(long n, String objective, String victimName, int pre, Result r) {
        MutableText msg = Text.literal("[\u4f24\u5bb3] ").formatted(Formatting.RED); // 伤害

        Formatting attackerColor;
        if (r.layer == Layer.L8_LAST_HITTER) {
            // v6.5.9 · L8 carry 不进玩家账户，玩家名也用灰色（视觉提示"已剥离"）
            attackerColor = Formatting.GRAY;
        } else if (r.attackerLabel != null && r.attackerLabel.startsWith("Player(")) {
            attackerColor = Formatting.GREEN;
        } else if (r.layer == Layer.L9_FILTER) {
            attackerColor = Formatting.DARK_PURPLE;
        } else if (r.layer == Layer.L9_HEAL) {
            attackerColor = Formatting.DARK_GREEN;
        } else if (r.layer == Layer.L9_NONE) {
            attackerColor = Formatting.DARK_RED;
        } else {
            attackerColor = Formatting.GOLD;
        }
        msg.append(Text.literal(trimAttacker(r.attackerLabel) + " ").formatted(attackerColor));

        msg.append(Text.literal(objective + " ").formatted(Formatting.YELLOW));

        Formatting tagColor;
        if (r.layer == Layer.L9_FILTER) tagColor = Formatting.LIGHT_PURPLE;
        else if (r.layer == Layer.L9_HEAL) tagColor = Formatting.GREEN;
        else if (r.layer == Layer.L9_NONE) tagColor = Formatting.RED;
        else if (r.layer == Layer.L8_LAST_HITTER) tagColor = Formatting.GRAY; // v6.5.9 灰色
        else tagColor = Formatting.AQUA;
        msg.append(Text.literal(r.layer.shortTag() + " ").formatted(tagColor));

        msg.append(Text.literal("-" + pre + " ").formatted(Formatting.RED));

        msg.append(Text.literal(victimName).formatted(Formatting.WHITE));

        String detail = compactDetail(r.detail);
        if (!detail.isEmpty()) {
            msg.append(Text.literal(" " + detail).formatted(Formatting.DARK_GRAY));
        }
        return msg;
    }

    private static String trimAttacker(String label) {
        if (label == null) return "?";
        if (label.startsWith("Player(") && label.endsWith(")")) {
            return label.substring(7, label.length() - 1);
        }
        if (label.equals("<unattributed>")) return "?";
        if (label.equals("<filtered>")) return "[\u9ed1\u540d\u5355]"; // [黑名单]
        if (label.equals("<heal>")) return "[\u56de\u8840]";          // [回血]
        if (label.startsWith("Unknown(")) return "?" + label.substring(7);
        return label;
    }

    private static String compactDetail(String detail) {
        if (detail == null || detail.isEmpty()) return "";
        return detail.length() > 80 ? detail.substring(0, 77) + "..." : detail;
    }

    // =========================================================================
    //  诊断计数器
    // =========================================================================
    public static long getEventCount() { return eventCount.get(); }
    public static long getLayerCount(Layer l) { return layerCounts[l.ordinal()].get(); }

    /** v6.5.2 · 各层计数 getter（HUD / 诊断用）。 */
    public static long getL1Count() { return getLayerCount(Layer.L1_WEAPON_MATCH); }
    public static long getL2Count() { return getLayerCount(Layer.L2_STAT_TICK); }
    public static long getL3Count() { return getLayerCount(Layer.L3_MARKER_NEAR); }
    public static long getL4Count() { return getLayerCount(Layer.L4_MARKER_FAR); }
    public static long getL5Count() { return getLayerCount(Layer.L5_STAT_WINDOW); }
    public static long getL6Count() { return getLayerCount(Layer.L6_FIRE_WINDOW); }
    public static long getL7Count() { return getLayerCount(Layer.L7_BOW_RELEASE); }
    public static long getL8Count() { return getLayerCount(Layer.L8_LAST_HITTER); }
    public static long getL9NoneCount()   { return getLayerCount(Layer.L9_NONE); }
    public static long getL9FilterCount() { return getLayerCount(Layer.L9_FILTER); }
    public static long getL9HealCount()   { return getLayerCount(Layer.L9_HEAL); }
    /** L9 大类总和（NONE + FILTER + HEAL）。 */
    public static long getL9Count() {
        return getL9NoneCount() + getL9FilterCount() + getL9HealCount();
    }

    // =========================================================================
    //  End-of-tick GC
    // =========================================================================
    public static void gcTick(MinecraftServer server) {
        long tick = DamageProbe.currentTick();
        PlayerHitLog.gcTick(tick);
        PlayerFireLog.gcTick(tick);
        VictimDamageSourceCache.gcTick(tick);
        VictimLastHitter.gcTick(tick);
        PlayerRecentAttributionLog.gcTick(tick);
        ScoreDeltaTracker.gcTick(tick);
        VictimTypeCache.gcTick(tick);
        // v6.4.0 · 击杀归属链的缓存
        VictimLethalCandidate.gcTick(tick);
        VictimDamageContributors.gcTick(tick);

        long cutoff = tick - ATTRIBUTED_TTL_TICKS;
        Iterator<Map.Entry<Long, Set<UUID>>> it = perTickAttributedVictims.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey() < cutoff) it.remove();
        }

        flushBroadcasts(server);
    }

    private static void flushBroadcasts(MinecraftServer server) {
        if (server == null || pendingBroadcasts.isEmpty()) return;
        List<Text> snapshot = new ArrayList<>();
        Text t;
        while ((t = pendingBroadcasts.poll()) != null) snapshot.add(t);
        if (snapshot.isEmpty()) return;

        // v8.x · 双路由：global=true → 全服广播；否则仅发给 per-player 订阅者
        boolean global = com.ctt.healthdisplay.config.ServerConfig.INSTANCE.broadcastDamageInChat;

        int count = snapshot.size();
        int toSend = Math.min(count, MAX_LINES_PER_TICK);
        for (int i = 0; i < toSend; i++) {
            sendDamageLine(server, snapshot.get(i), global);
        }
        if (count > MAX_LINES_PER_TICK) {
            int omitted = count - MAX_LINES_PER_TICK;
            Text summary = Text.literal("  +" + omitted + " 条归属事件已省略（本 tick）")
                    .formatted(Formatting.DARK_GRAY, Formatting.ITALIC);
            sendDamageLine(server, summary, global);
        }
    }

    private static void sendDamageLine(MinecraftServer server, Text msg, boolean global) {
        if (global) {
            server.getPlayerManager().broadcast(msg, false);
        } else {
            com.ctt.healthdisplay.server.command.BroadcastSubscribers.sendTo(
                    server, com.ctt.healthdisplay.server.command.BroadcastSubscribers.Channel.DAMAGE, msg);
        }
    }
}
