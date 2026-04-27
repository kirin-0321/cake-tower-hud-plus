package com.ctt.healthdisplay.server.filter;

import com.ctt.healthdisplay.config.ServerConfig;
import com.ctt.healthdisplay.server.PlayerInventoryIndex;
import com.ctt.healthdisplay.server.StageBoundaryDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.7.0 · 异常伤害过滤器统一入口（决策树短路）。
 *
 * <p>调用方（{@link com.ctt.healthdisplay.server.DamageProbe#recordFromRedHearts} 与
 * DamageShower 路径）在每次记录一条 damage 事件时调用 {@link #applyFilters(Entity, int, long)}，
 * 按"最便宜先判 → 命中即短路"的顺序串接所有过滤规则。任何一条命中就立刻返回该
 * {@link FilterDecision}，不会再过后续规则。
 *
 * <h3>P1 (v6.7.0~v6.7.3) 实装规则</h3>
 * <p>决策树顺序（与 {@code DAMAGE_FILTER_DESIGN.md} §3 对齐）：
 * <ol>
 *   <li>{@link FilterReason#LOW_NOISE} —— damage 低于地板（默认 5）+ Defence 豁免（v6.7.0~v6.7.1）</li>
 *   <li>{@link FilterReason#INIT_HP_JUMP} —— 命中黑名单数值（v6.5.2 收编）</li>
 *   <li>{@link FilterReason#SUSPECT_VICTIM} —— 可疑怪物 + 大额阈值（v6.5.8 收编）</li>
 *   <li>{@link FilterReason#PAUSED} / {@link FilterReason#SESSION_BOUNDARY} /
 *       {@link FilterReason#SUSPECT_TAG} —— v6.7.3 G2 状态机边界守卫</li>
 *   <li>{@link FilterReason#LETHAL_MECHANISM} / {@link FilterReason#OVERSIZE} ——
 *       v6.7.2 G7a 物理地板：{@code damage > victim.MaxHP × 3}</li>
 *   <li>{@link FilterReason#DUPLICATE} —— v6.7.3 G6 重放守卫：
 *       上一 tick 已记录过同样 (victim, damage)</li>
 * </ol>
 *
 * <h3>后续阶段挂载点</h3>
 * <p>P3~P4 引入 PendingDamageBuffer（异步）后，G6 重放守卫的 LRU 缓存会被 buffer 自身替换；
 * G7b outlier 子规则（{@code damage &gt; P95 × 50}）也将在 buffer.flush 阶段挂入。</p>
 *
 * <p>命中后 {@link FilterDiagReport#observe(FilterReason, int)} 自动记录用于诊断；
 * 调用方负责把 {@link FilterDecision} 翻译成 {@link com.ctt.healthdisplay.server.AttackerProbe.Layer#L9_FILTER}
 * 的 forceLayer 路由。
 *
 * <p>{@link ServerConfig#filterEnabled} 为 false 时所有规则旁路（dev mode 调试用），
 * 直接返回 {@link FilterDecision#pass()}。
 */
public final class DamageFilterPipeline {

    private DamageFilterPipeline() {}

    /**
     * 串接全部启用的过滤规则。
     *
     * @param victim 受害者实体（已通过非 null 校验）
     * @param damage 本次 damage（已通过 &gt; 0 校验）
     * @param tick   当前 tick；保留给 P2 的 session-boundary / G6 duplicate 使用
     * @return 命中即返回对应 {@link FilterDecision}（已自动登记到 {@link FilterDiagReport}）；
     *         全部未命中返回 {@link FilterDecision#pass()}
     */
    public static FilterDecision applyFilters(Entity victim, int damage, long tick) {
        ServerConfig cfg = ServerConfig.INSTANCE;
        if (!cfg.filterEnabled) return FilterDecision.pass();
        if (victim == null || damage <= 0) return FilterDecision.pass();

        // === G_low · 低伤害噪声地板 (P1) ===
        // 高频 1~4 点 DoT / 反伤碎片：常态足以拉低 P95 训练样本，硬性切除。
        // v6.7.1 · Defence 豁免：石头僵尸 / 远古卫士这类高护甲怪被普通武器打后减伤后正常输出
        //   就是 1~4 点（合法物理伤害），不是噪声——豁免之，让伤害正常进玩家账户。
        //   依据 DAMAGE_FILTER_DESIGN.md §5.1：Defence > 50 不入 P95 训练，但伤害仍计入账户。
        // v6.7.6 · 武器白名单豁免：BOSS（"大炮" 等）Defence=0 救不上，CTT 高频低伤武器
        //   （nutStickLaser / ak47 等）每 tick 1~4 点连击会被一刀切。改判：附近若有玩家手持
        //   白名单武器，则视为合法连击，跳过 G_low。
        if (damage < cfg.lowDamageFloor
                && readVictimDefence(victim) <= cfg.defenceExclusionThreshold
                && !hasNearbyPlayerWithWhitelistedWeapon(victim,
                        cfg.lowNoiseWeaponWhitelist, cfg.lowNoiseWhitelistRadius)) {
            return record(FilterReason.LOW_NOISE, damage);
        }

        // === G3 · 固定值黑名单 (v6.5.2 收编) ===
        // 怪物初始化 / 形态切换 set RedHearts 造成的负 delta 假伤害。
        // 仅当 ServerConfig.filterInitHpJumps 为 true 时启用。
        if (isInitHpJump(damage)) {
            return record(FilterReason.INIT_HP_JUMP, damage);
        }

        // === G4 · 可疑 victim + 大额阈值 (v6.5.8 收编) ===
        // 幽匿骷髅 / 幽匿僵尸等剧情怪 set 假伤害（浮动值，不能用固定黑名单）。
        if (isSuspectVictim(victim, damage)) {
            return record(FilterReason.SUSPECT_VICTIM, damage);
        }

        // === G2 · 状态机边界守卫 (v6.7.3) ===
        // 三个子条件任一命中即过滤；设计依据：DAMAGE_FILTER_DESIGN.md §4.5
        //   1) #PauseGame CTT > 0  → paused        （地图暂停态，所有 score 变动无意义）
        //   2) GameID 跳变 < N tick → session-boundary（局结束 / 新局清场期）
        //   3) victim 含 Coffin/Prop/NPC/TestDummy/Debug tag → suspect-tag
        if (cfg.filterStateBoundary) {
            if (readPauseGame(victim) > 0) {
                return record(FilterReason.PAUSED, damage);
            }
            if (cfg.sessionBoundaryGuardTicks > 0) {
                long lastChange = StageBoundaryDispatcher.lastGameIdChangeTick();
                // lastChange = Long.MIN_VALUE 时 (tick - lastChange) 溢出会变成负数，
                // 不会满足 < guardTicks（因为 guardTicks > 0），自然跳过——无需特判。
                if (lastChange > Long.MIN_VALUE
                        && tick - lastChange < cfg.sessionBoundaryGuardTicks) {
                    return record(FilterReason.SESSION_BOUNDARY, damage);
                }
            }
            if (hasSuspectTag(victim, cfg.suspectVictimTags)) {
                return record(FilterReason.SUSPECT_TAG, damage);
            }
        }

        // === G7a · 物理地板 (v6.7.2) ===
        // damage > victim.MaxHP × physicalCeilMultiplier → 超出任何合理设计的"伪值"
        // 致死（RedHearts ≤ 0 或 isRemoved） → lethal-mechanism
        // 非致死                                 → oversize
        // 设计依据：DAMAGE_FILTER_DESIGN.md §4.6
        // P1 阶段 contributors 写入逻辑保持不变（同 init-hp-jump 不写）；
        //        P3~P4 上 PendingDamageBuffer 后再统一改为"lethal-mechanism 仍写 contributors"
        if (cfg.filterPhysicalCeil && cfg.physicalCeilMultiplier > 0) {
            int maxHp = readVictimMaxHp(victim);
            if (maxHp > 0) {
                long threshold = (long) maxHp * (long) cfg.physicalCeilMultiplier;
                if ((long) damage > threshold) {
                    boolean lethal = victim.isRemoved() || readVictimRedHearts(victim) <= 0;
                    return record(lethal ? FilterReason.LETHAL_MECHANISM : FilterReason.OVERSIZE, damage);
                }
            }
        }

        // === G6 · 重放守卫 (v6.7.3) ===
        // 本次 (victim UUID, damage) 与上一 tick 完全相同 → duplicate（命中率 < 0.1% sanity check）
        // P1 简化版：用本类内部 LRU 缓存替代未来的 PendingDamageBuffer。
        // 缓存策略：仅"通过所有过滤规则"的事件进入比对池——与文档语义一致
        //         （buffer 只接收未被 G_low/G2/G3/G4/G7a/G6 短路的事件）。
        if (cfg.filterDuplicateReplay) {
            UUID uuid = victim.getUuid();
            if (uuid != null) {
                LastEvent last = LAST_EVENT_CACHE.get(uuid);
                if (last != null && last.damage() == damage && last.tick() == tick - 1L) {
                    // 滚动重放：把"幽灵"刷新到本 tick，让连续 N tick 的同值重放都能被持续命中
                    LAST_EVENT_CACHE.put(uuid, new LastEvent(damage, tick));
                    return record(FilterReason.DUPLICATE, damage);
                }
            }
        }

        // === pass: 写入 G6 比对缓存 ===
        // 仅"通过所有过滤"的事件作为下一 tick G6 比对的基线。
        if (cfg.filterDuplicateReplay) {
            UUID uuid = victim.getUuid();
            if (uuid != null) {
                LAST_EVENT_CACHE.put(uuid, new LastEvent(damage, tick));
                if (LAST_EVENT_CACHE.size() > LAST_EVENT_CACHE_CAP) {
                    sweepStaleLastEvents(tick);
                }
            }
        }

        return FilterDecision.pass();
    }

    /** 命中：登记诊断报告并构造 {@link FilterDecision}。 */
    private static FilterDecision record(FilterReason reason, int damage) {
        FilterDiagReport.observe(reason, damage);
        return FilterDecision.filter(reason);
    }

    // =========================================================================
    //  规则实现
    //  收编自 DamageProbe.isInitHpJump / isSuspectVictim（保持完全兼容的判定语义）。
    //  保留 public 静态方法允许外部代码（测试 / 诊断命令）单独调用。
    // =========================================================================

    /**
     * G3 · 是否命中"固定值黑名单"（怪物初始化 / 形态切换的负 delta）。
     *
     * <p>判定语义与原 {@code DamageProbe.isInitHpJump} 完全一致；后者保留为
     * {@code @Deprecated} 转发，外部调用者可平滑迁移。
     */
    public static boolean isInitHpJump(int damage) {
        ServerConfig cfg = ServerConfig.INSTANCE;
        if (!cfg.filterInitHpJumps) return false;
        int[] vals = cfg.initHpJumpValues;
        if (vals == null || vals.length == 0) return false;
        for (int v : vals) {
            if (v == damage) return true;
        }
        return false;
    }

    /**
     * G4 · 是否命中"可疑 victim + 大额阈值"组合。
     *
     * <p>判定语义与原 {@code DamageProbe.isSuspectVictim} 完全一致。
     */
    public static boolean isSuspectVictim(Entity victim, int damage) {
        ServerConfig cfg = ServerConfig.INSTANCE;
        if (!cfg.filterSuspectVictims) return false;
        if (damage < cfg.suspectVictimDamageThreshold) return false;
        String[] keywords = cfg.suspectVictims;
        if (keywords == null || keywords.length == 0) return false;
        if (victim == null) return false;
        String name = victim.getName().getString();
        if (name == null || name.isEmpty()) return false;
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty() && name.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 读 victim 的 {@code Defence} 计分板值（地图自定义的"物理护甲"——拼写为 Defence 不是 Defense，
     * 详见 {@code MAP_DATAPACK_REFERENCE.md}）。
     *
     * <p>地图 datapack 未加载或 victim 未设值时返回 0（保持原有行为，不触发任何 Defence 豁免）。
     *
     * @param victim 受害者实体；{@code null} 时返回 0
     * @return Defence 计分板值；不存在 / 失败均返回 0
     */
    public static int readVictimDefence(Entity victim) {
        return readVictimScoreboard(victim, "Defence");
    }

    /**
     * 读 victim 的 {@code MaxHP} 计分板值（地图自定义的"实体满血"——CTT 怪物各形态独立维护）。
     *
     * <p>地图无 {@code MaxHP} 计分板或 victim 未注册时返回 0；调用方据此判定是否启用 G7a
     * 物理地板（{@code maxHp > 0} 才进 G7a 判定，否则直接放行）。
     *
     * @param victim 受害者实体；{@code null} 时返回 0
     * @return MaxHP 计分板值；不存在 / 失败均返回 0
     */
    public static int readVictimMaxHp(Entity victim) {
        return readVictimScoreboard(victim, "MaxHP");
    }

    /**
     * 读 victim 的 {@code RedHearts} 计分板值（地图自定义的"当前红心血量"）。
     *
     * <p>{@link com.ctt.healthdisplay.server.mixin.ScoreboardUpdateMixin} 用 {@code @At("RETURN")}
     * 注入——意味着 {@link com.ctt.healthdisplay.server.DamageProbe#recordFromRedHearts} 调到本方法时，
     * RedHearts 计分板已经更新为新值（即扣血后的当前值）。读出 ≤ 0 即视为本次受击致死。
     *
     * @param victim 受害者实体；{@code null} 时返回 0
     * @return RedHearts 计分板值；不存在 / 失败均返回 0
     */
    public static int readVictimRedHearts(Entity victim) {
        return readVictimScoreboard(victim, "RedHearts");
    }

    private static int readVictimScoreboard(Entity victim, String objName) {
        if (victim == null) return 0;
        World world = victim.getWorld();
        if (world == null) return 0;
        Scoreboard sb = world.getScoreboard();
        if (sb == null) return 0;
        ScoreboardObjective obj = sb.getNullableObjective(objName);
        if (obj == null) return 0;
        ReadableScoreboardScore score = sb.getScore(victim, obj);
        return score != null ? score.getScore() : 0;
    }

    /**
     * 读 {@code #PauseGame CTT} 计分板值（地图自定义的"游戏暂停标志"——见 {@code MAP_DATAPACK_REFERENCE.md}）。
     *
     * <p>{@code #PauseGame} 是 virtual scoreboard holder（不是 entity），用 {@link ScoreHolder#fromName(String)}
     * 构造。借 victim 的 world.scoreboard 拿（CTT 服务端只有一个 scoreboard，holder 仅作 key 不需要 victim 本身）。
     *
     * @param anyEntity 用于拿到 {@link Scoreboard} 的任意 entity；{@code null} 时返回 0
     * @return {@code #PauseGame CTT} 的当前值；scoreboard / objective 不存在或读取失败时返回 0
     */
    public static int readPauseGame(Entity anyEntity) {
        if (anyEntity == null) return 0;
        World world = anyEntity.getWorld();
        if (world == null) return 0;
        Scoreboard sb = world.getScoreboard();
        if (sb == null) return 0;
        ScoreboardObjective obj = sb.getNullableObjective("CTT");
        if (obj == null) return 0;
        ReadableScoreboardScore score = sb.getScore(ScoreHolder.fromName("#PauseGame"), obj);
        return score != null ? score.getScore() : 0;
    }

    /**
     * G2 · victim 是否含 {@link ServerConfig#suspectVictimTags} 中的任一 commandTag。
     *
     * <p>vanilla {@link Entity#getCommandTags()} 是 {@link Set}{@code <String>}——服务端任何 datapack
     * 通过 {@code /tag @e add Coffin} 等命令加上的标签都在里面。客户端 entity 的 commandTags
     * 是空集（不跨网络同步），但本方法仅在 server pipeline 调用，不影响。
     */
    public static boolean hasSuspectTag(Entity victim, String[] suspectTags) {
        if (victim == null || suspectTags == null || suspectTags.length == 0) return false;
        Set<String> tags = victim.getCommandTags();
        if (tags == null || tags.isEmpty()) return false;
        for (String tag : suspectTags) {
            if (tag != null && !tag.isEmpty() && tags.contains(tag)) return true;
        }
        return false;
    }

    /**
     * G_low 武器白名单（v6.7.6）：检查 victim 周围 {@code radius} 米内是否存在任一玩家——
     * 其主手 custom_data key 或 vanilla item id 与 {@code whitelist} 任一元素发生 contains 子串匹配。
     *
     * <p>用途：BOSS 战常见的"玩家拿激光打 1 点"场景下，为合法 G_low 候选事件提供豁免。
     * 不是精确归属（攻击者识别的精确版本在 P3~P4 的 PendingDamageBuffer 上线后才能实装）——
     * 但对 G_low 这一规则的语义（"高频低伤噪声"）来说"宁过不漏"足够：玩家在场拿白名单
     * 武器时，就算这 1 点伤害是地图机制打的也不影响，账户不会被严重污染。
     *
     * <p>性能：遍历在线玩家（典型 ≤ 4），同世界 + 距离过滤后调 {@link PlayerInventoryIndex#get}
     * 拿快照。整体常数级开销，命中即短路。
     *
     * @param victim    受害者实体；{@code null} 直接返回 false（让 G_low 正常触发）
     * @param whitelist 白名单 pattern 数组；{@code null} / 空数组 / 全空字符串均返回 false
     * @param radius    检查半径（米）；{@code <= 0} 返回 false（等价于功能关闭）
     * @return 命中任一玩家任一主手 key 时 true
     */
    public static boolean hasNearbyPlayerWithWhitelistedWeapon(Entity victim,
                                                                String[] whitelist,
                                                                double radius) {
        if (victim == null) return false;
        if (whitelist == null || whitelist.length == 0) return false;
        if (radius <= 0.0) return false;
        World world = victim.getWorld();
        if (!(world instanceof ServerWorld sw)) return false;
        MinecraftServer server = sw.getServer();
        if (server == null) return false;

        String victimWorldKey = sw.getRegistryKey().getValue().toString();
        Vec3d victimPos = victim.getPos();
        double radSq = radius * radius;

        for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
            if (sp == null) continue;
            if (!victimWorldKey.equals(sp.getWorld().getRegistryKey().getValue().toString())) continue;
            if (sp.getPos().squaredDistanceTo(victimPos) > radSq) continue;

            PlayerInventoryIndex.Snapshot snap = PlayerInventoryIndex.get(sp.getUuid());
            if (snap == PlayerInventoryIndex.Snapshot.EMPTY) continue;

            // CTT custom_data key 池
            for (String mainKey : snap.mainHand()) {
                if (mainKey == null || mainKey.isEmpty()) continue;
                for (String pattern : whitelist) {
                    if (pattern == null || pattern.isEmpty()) continue;
                    if (mainKey.contains(pattern)) return true;
                }
            }
            // vanilla item id 兜底（如 minecraft:bow / crossbow）
            String vanillaId = snap.mainHandItemId();
            if (vanillaId != null && !vanillaId.isEmpty()) {
                for (String pattern : whitelist) {
                    if (pattern == null || pattern.isEmpty()) continue;
                    if (vanillaId.contains(pattern)) return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    //  G6 · 重放守卫缓存（P1 简化版，PendingDamageBuffer 上线后将替换为 buffer 内查询）
    // =========================================================================

    /** {@link #LAST_EVENT_CACHE} 的容量上限——超过后触发 {@link #sweepStaleLastEvents} 清理过期条目。 */
    private static final int LAST_EVENT_CACHE_CAP = 256;

    /** sweep 时认为"过期"的 tick 距离阈值——比当前 tick 早 STALE 个 tick 以上的条目会被丢弃。 */
    private static final long LAST_EVENT_STALE_TICKS = 20L;

    private record LastEvent(int damage, long tick) {}

    /**
     * G6 比对池：每个 victim UUID 最近一次"通过所有过滤"的事件的 (damage, tick)。
     * 本 tick 的事件若与上一 tick 的同 victim 同 damage 命中，即视为重放。
     *
     * <p>使用 {@link ConcurrentHashMap}：MC 服务端单 tick 主线程驱动，但 mixin 注入路径
     * 历史上偶有 client/server scoreboard 共用 (虽然 ScoreboardUpdateMixin 已 instanceof 过滤)，
     * 加锁防御无伤大雅且热路径极轻（HashMap 内部分段锁）。
     */
    private static final Map<UUID, LastEvent> LAST_EVENT_CACHE = new ConcurrentHashMap<>();

    /**
     * 清理 {@link #LAST_EVENT_CACHE} 中早于 {@code currentTick - LAST_EVENT_STALE_TICKS} 的条目。
     *
     * <p>仅在 cache size 超过 {@link #LAST_EVENT_CACHE_CAP} 时触发，平均成本 O(N) 但发生频率
     * 远低于每 tick——20 tick 窗口下，正常游戏每 tick 几十条事件，sweep 几乎不被触发。
     */
    private static void sweepStaleLastEvents(long currentTick) {
        long staleBefore = currentTick - LAST_EVENT_STALE_TICKS;
        LAST_EVENT_CACHE.entrySet().removeIf(e -> e.getValue().tick() < staleBefore);
    }
}
