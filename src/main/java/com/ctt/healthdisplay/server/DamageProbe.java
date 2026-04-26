package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.config.ServerConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v6.0.1 ·「最终伤害」数据采集器。
 *
 * <h2>数据来源</h2>
 * <p><b>默认（v6.6.0）</b>：实体 {@code RedHearts} 计分板下降 = 实扣红血量（与地图扣血
 * 栈一致），无粒子数量上限。可通过 {@code ServerConfig.useRedHeartsTally=false} 切回
 * 旧线。
 * <p><b>历史路径</b>：地图数据包 {@code cake_team_tower:misc/damage.mcfunction} line
 * 1021~1028 的 <b>DamageShower 粒子机制</b>。地图为每个受伤实体召唤
 * {@code text_display}（tag=DamageShower），其 {@code DamageShower} 分值为
 * 受伤者 {@code Damage}（防具/Buff 后的最终量），有 {@code limit=10} 同 tick
 * 上限。
 *
 * <p>这个值就是用户定义的「最终对怪物造成的血量减少量」：
 * <ul>
 *   <li>不受怪物回血 / HealDMG 污染（那是独立管线）</li>
 *   <li>不被后续 Damage 扣除 BlueHearts/BlackHearts/SoulHearts 干扰</li>
 *   <li>每个事件独立，不会因为同 tick 多次写入被覆盖（write-once 语义）</li>
 * </ul>
 *
 * <h2>采集路径</h2>
 * <ol>
 *   <li>{@link com.ctt.healthdisplay.server.mixin.ScoreboardUpdateMixin}
 *       拦截 {@code Scoreboard.updateScore}，当 objective=="DamageShower" 且
 *       value>=1 时调用 {@link #record}</li>
 *   <li>记录进 lock-free 队列 {@code pending}（Mixin 可能在任意线程调用，保险起见用并发队列）</li>
 *   <li>每 tick 末 {@link #flushTick(MinecraftServer, long)} 批量处理：
 *       通过 text_display 的 UUID 查实体，取位置作为受害者锚点，
 *       在 1.5 格内找最近的非-DamageShower {@code CTTAll} 实体作为受害者</li>
 * </ol>
 *
 * <h2>v6.0.1 的范围</h2>
 * <p>仅打印日志。攻击者归属、PersistentState、客户端同步、击杀判定全部留到后续版本。
 * 日志格式被设计为方便用户在休息室打假人时和地图自带统计做人肉校验。
 *
 * <h2>已知限制</h2>
 * <ul>
 *   <li>旧路径：AoE 同 tick 目标 &gt; 10 时地图只生成最多 10 个粒子
 *       —— <b>RedHearts 主源开启后</b>由计分板补齐</li>
 *   <li>服务器卡顿 {@code #ServerLag CT != 0} 时地图不生成粒子 —— 本地单机测试不会触发</li>
 * </ul>
 */
public final class DamageProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stats");

    /**
     * v6.5.1 · 回血（HealDMG）DamageShower 粒子的 background 色值。
     *
     * <p>地图 {@code damage.mcfunction} line 57-60 summon 治疗粒子时硬编码 {@code background:-16515325}
     * （ARGB = 0xFF04A003，深绿）。玩家受伤粒子是 {@code -65536}（红色，line 1027 data merge 设置），
     * 怪物受伤粒子没 data merge → 默认半透明黑 {@code 0x40000000}。
     *
     * <p>地图把治疗数值也通过 {@code DamageShower} scoreboard 输出（line 62），和伤害粒子共用
     * 同一条 Mixin 钩子。v6.5.1 通过 {@code return} 拦截，v6.5.2 改为路由到
     * {@link AttackerProbe.Layer#L9_HEAL}（保留聊天栏可见性 + 计入 L9 桶诊断用）。
     */
    private static final int HEAL_PARTICLE_BACKGROUND = -16515325;

    /**
     * 由 Mixin 调用的入口。Mixin 在服务端主线程调用（Scoreboard 操作走服务器主线程），
     * 但保险起见仍用并发队列。
     */
    private static final ConcurrentLinkedQueue<RawEvent> pending = new ConcurrentLinkedQueue<>();

    /** v6.5.1 · 诊断：已识别的回血粒子事件数（v6.5.2 起改为路由到 L9_HEAL，不再 return）。 */
    private static final AtomicLong healFilteredCount = new AtomicLong();
    public static long getHealFilteredCount() { return healFilteredCount.get(); }

    /** v6.5.2 · 诊断：已识别的"初始化血量跳变"黑名单事件数（路由到 L9_FILTER）。 */
    private static final AtomicLong initHpJumpFilteredCount = new AtomicLong();
    public static long getInitHpJumpFilteredCount() { return initHpJumpFilteredCount.get(); }

    /**
     * v6.5.2 · 是否为"怪物初始化 / 形态切换"造成的假伤害值。
     *
     * <p>地图 {@code enemies/*.mcfunction} 给某些怪物 {@code set @s RedHearts <N>}
     * 初始化或形态切换时，{@link ScoreDeltaTracker} 计算到的 delta 是负的，会被
     * 误判为玩家造成的"瞬间巨额伤害"。命中 {@link ServerConfig#initHpJumpValues}
     * 列表的 damage 值会被强制路由到 {@link AttackerProbe.Layer#L9_FILTER}。
     *
     * @see ServerConfig#filterInitHpJumps
     * @see ServerConfig#initHpJumpValues
     */
    public static boolean isInitHpJump(int damage) {
        if (!ServerConfig.INSTANCE.filterInitHpJumps) return false;
        int[] vals = ServerConfig.INSTANCE.initHpJumpValues;
        if (vals == null || vals.length == 0) return false;
        for (int v : vals) {
            if (v == damage) return true;
        }
        return false;
    }

    /**
     * v6.5.8 · 是否为"特定怪物 + 单次大额伤害"组合的伪伤害。
     *
     * <p>区别于 {@link #isInitHpJump(int)}（只能拦固定值），本规则按
     * <ul>
     *   <li>victim 显示名子串匹配（{@link ServerConfig#suspectVictims}）</li>
     *   <li>本次伤害 ≥ 阈值（{@link ServerConfig#suspectVictimDamageThreshold}）</li>
     * </ul>
     * 双条件命中时认定为剧情 set 假伤害（如幽匿骷髅 / 幽匿僵尸状态切换时
     * RedHearts 被脚本 set 到低值产生的负 delta 970 / 920 / 856 等浮动值）。
     *
     * @param victim 受害者实体（不能为 null；调用方已确保）
     * @param damage 本次记录的伤害值（>0）
     * @return 命中 → true（应该路由到 L9_FILTER）
     */
    public static boolean isSuspectVictim(Entity victim, int damage) {
        ServerConfig cfg = ServerConfig.INSTANCE;
        if (!cfg.filterSuspectVictims) return false;
        if (damage < cfg.suspectVictimDamageThreshold) return false;
        String[] keywords = cfg.suspectVictims;
        if (keywords == null || keywords.length == 0) return false;
        String name = victim.getName().getString();
        if (name == null || name.isEmpty()) return false;
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty() && name.contains(kw)) return true;
        }
        return false;
    }

    private static long tickCounter = 0;

    /** 服务端 tick 基准。v6.0.5 起 AttackerProbe / PlayerHitLog / PlayerFireLog 共用此计数。 */
    public static long currentTick() { return tickCounter; }

    // =========================================================================
    //  v6.0.2 · 区间统计会话（由客户端 L 键驱动）
    //
    //  单机 integrated server：客户端和服务端共享同一个 JVM / classloader，
    //  所以客户端线程（ClientTickEvents）可以直接调用 startSession/stopSession，
    //  服务端线程（END_SERVER_TICK 里的 flushTick）直接写 sessionTotal。
    //  跨 JVM（专用服务器）场景留给 v6.0.3 的 CustomPayload 同步。
    //
    //  筛选：只统计 victim 带 "E" tag（地图定义的敌人标签）的伤害 ——
    //  自动排除 PvP、自残、假人以外的干扰，符合"玩家打怪总输出"的语义。
    // =========================================================================
    private static volatile boolean sessionActive = false;
    private static final AtomicLong sessionTotal = new AtomicLong();
    private static final AtomicInteger sessionEvents = new AtomicInteger();
    private static final AtomicInteger sessionMaxHit = new AtomicInteger();
    private static volatile long sessionStartMs = 0;
    private static volatile long sessionStartTick = 0;

    public static void startSession() {
        sessionTotal.set(0);
        sessionEvents.set(0);
        sessionMaxHit.set(0);
        sessionStartMs = System.currentTimeMillis();
        sessionStartTick = tickCounter;
        sessionActive = true;
        LOGGER.info("[CTT Stats] === session START (tick={}) ===", sessionStartTick);
    }

    public static SessionSummary stopSession() {
        sessionActive = false;
        long total = sessionTotal.get();
        int events = sessionEvents.get();
        int maxHit = sessionMaxHit.get();
        long durationMs = System.currentTimeMillis() - sessionStartMs;
        long tickSpan = tickCounter - sessionStartTick;
        LOGGER.info("[CTT Stats] === session STOP : total={} events={} maxHit={} durMs={} tickSpan={} ===",
                total, events, maxHit, durationMs, tickSpan);
        return new SessionSummary(total, events, maxHit, durationMs, tickSpan);
    }

    public static boolean isSessionActive() { return sessionActive; }
    public static long getLiveTotal()        { return sessionTotal.get(); }
    public static int  getLiveEvents()       { return sessionEvents.get(); }
    public static int  getLiveMaxHit()       { return sessionMaxHit.get(); }
    public static long getSessionStartMs()   { return sessionStartMs; }

    public record SessionSummary(long total, int events, int maxHit, long durationMs, long tickSpan) {}

    private DamageProbe() {}

    /**
     * Mixin 拦截到 {@code DamageShower} 被写入时调用。
     * 若 {@code useRedHeartsTally} 为真则整条跳过（会走 {@link #recordFromRedHearts}）。
     *
     * <p>v6.5.2 · 治疗粒子（绿色 background）不再 return，而是登记 {@code healPending}
     * 标志，让 {@link #resolveAndLog} 改走 {@link AttackerProbe.Layer#L9_HEAL} 路径，
     * 仍计入 L9 桶 + 聊天栏可见。
     */
    public static void record(ScoreHolder holder, int value) {
        if (ServerConfig.INSTANCE.useRedHeartsTally) return;

        if (value <= 0) return; // unless score @s DamageShower matches 0.. 意味着未初始化值会是 null/0，过滤
        // getNameForScoreboard() 对实体 holder 返回 UUID 字符串；对玩家返回用户名。
        // DamageShower 实体是 text_display，holder 名一定是 UUID 字符串。
        String name = holder.getNameForScoreboard();
        UUID uuid;
        try {
            uuid = UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return; // 非 UUID holder，不是 text_display 实体，跳过
        }

        // v6.5.2 · 检测回血粒子（绿色 background）。是 → 标 isHeal 并路由到 L9_HEAL 桶，
        //   不污染 sessionTotal（false positive），但仍然进 PlayerDamageStats.L9_HEAL 桶 + 广播。
        MinecraftServer server = CttStatsServer.getServer();
        boolean isHeal = (server != null) && isHealParticle(server, uuid);
        if (isHeal) {
            healFilteredCount.incrementAndGet();
        }

        // v6.0.3 修复：session 累加**在这里**完成，不等 flushTick 解析受害者。
        // v6.5.2 · 回血事件不进 sessionTotal（它本质是 false positive 不是真伤害）。
        if (sessionActive && !isHeal) {
            sessionTotal.addAndGet(value);
            sessionEvents.incrementAndGet();
            int cur;
            do {
                cur = sessionMaxHit.get();
                if (value <= cur) break;
            } while (!sessionMaxHit.compareAndSet(cur, value));
        }

        pending.add(new RawEvent(tickCounter, uuid, value, isHeal));
    }

    /**
     * 计分板 {@code RedHearts} 下降时由 Mixin 直调；降幅即最终伤害，与会话/归属链一致。
     *
     * <p>v6.5.2 · 黑名单数值（{@link ServerConfig#initHpJumpValues}，默认 1000/10000/100000）
     * 强制路由到 {@link AttackerProbe.Layer#L9_FILTER}，不进玩家账户、不进 grandTotal、
     * 不进 sessionTotal——它们本质是怪物初始化 / 形态切换造成的负 delta 假伤害，
     * 不是任何玩家的真实输出。
     */
    public static void recordFromRedHearts(MinecraftServer server, ScoreHolder holder, int damage,
                                            long tick) {
        if (server == null || damage <= 0) return;
        String name = holder.getNameForScoreboard();
        UUID victimUuid;
        try {
            victimUuid = UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return;
        }
        Entity victim = null;
        ServerWorld foundWorld = null;
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(victimUuid);
            if (e != null) {
                victim = e;
                foundWorld = w;
                break;
            }
        }
        if (victim == null) return;

        // v6.5.2 · 固定值黑名单（1000/10000/100000）→ L9_FILTER。
        // v6.5.8 · 特定 victim + 大额阈值（如幽匿骷髅/幽匿僵尸 ≥800）→ L9_FILTER。
        boolean blacklisted = isInitHpJump(damage);
        boolean suspectMatched = !blacklisted && isSuspectVictim(victim, damage);
        boolean filtered = blacklisted || suspectMatched;
        AttackerProbe.Layer forceLayer = filtered ? AttackerProbe.Layer.L9_FILTER : null;
        if (filtered) {
            initHpJumpFilteredCount.incrementAndGet();
        }

        if (sessionActive && !filtered) {
            sessionTotal.addAndGet(damage);
            sessionEvents.incrementAndGet();
            int cur;
            do {
                cur = sessionMaxHit.get();
                if (damage <= cur) break;
            } while (!sessionMaxHit.compareAndSet(cur, damage));
        }

        String vName = victim.getName().getString();
        String vType = Registries.ENTITY_TYPE.getId(victim.getType()).toString();
        String reason = blacklisted ? " [L9-FILTER:init-hp-jump]"
                : suspectMatched     ? " [L9-FILTER:suspect-victim]"
                : "";
        // v6.6.1 hotfix · 高频伤害事件日志降级为 DEBUG。INFO 阈值下 SLF4J 直接短路，
        // 避免 boss / 高频 DoT 怪每 tick 几十行 RedHearts 日志拖垮 TPS。
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[CTT Stats] (RedHearts) tick={} dmg={} holder={} type={} uuid={}{}",
                    tick, damage, vName, vType, victim.getUuidAsString(), reason);
        }

        if (victim.getCommandTags().contains("E")) {
            AttackerProbe.recordFromDamageShower(server, victim, foundWorld, damage, tick, forceLayer);
        }
    }

    /**
     * v6.5.1 · 判断 UUID 对应的 text_display 粒子是否为回血粒子（绿色背景）。
     *
     * <p>地图 {@code damage.mcfunction} summon 治疗粒子时在同一条命令里就写死了
     * {@code background:-16515325}，因此粒子一出生 background 字段就是终值，
     * 查询无 race condition。
     */
    private static boolean isHealParticle(MinecraftServer server, UUID uuid) {
        for (ServerWorld world : server.getWorlds()) {
            Entity e = world.getEntity(uuid);
            if (e == null) continue;
            if (e instanceof DisplayEntity.TextDisplayEntity td) {
                return td.getBackground() == HEAL_PARTICLE_BACKGROUND;
            }
            return false; // 找到了但不是 text_display（理论上不会），稳妥起见不过滤
        }
        return false; // 实体不存在（可能已被 kill）：不过滤，按原有行为进 pending
    }

    /** 每 tick 末调用，批量消费队列，找受害者并打日志。 */
    public static void flushTick(MinecraftServer server) {
        tickCounter++;
        if (pending.isEmpty()) return;

        List<RawEvent> batch = new ArrayList<>();
        RawEvent e;
        while ((e = pending.poll()) != null) batch.add(e);

        for (RawEvent ev : batch) {
            resolveAndLog(server, ev);
        }
    }

    private static void resolveAndLog(MinecraftServer server, RawEvent ev) {
        // 遍历所有世界找 text_display by UUID
        Entity textDisplay = null;
        ServerWorld foundWorld = null;
        for (ServerWorld world : server.getWorlds()) {
            Entity found = world.getEntity(ev.textDisplayUuid);
            if (found != null) {
                textDisplay = found;
                foundWorld = world;
                break;
            }
        }

        if (textDisplay == null || foundWorld == null) {
            // text_display 可能已被 kill；记录一条但不带受害者信息
            // v6.6.1 hotfix · 降级 DEBUG（高频路径，避免拖 TPS）。
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[CTT Stats] tick={} dmg={} victim=<unresolved:text_display gone>", ev.tick, ev.damage);
            }
            return;
        }

        // 在粒子位置 1.5 格半径内找最近的 CTTAll 实体（地图 line 1028 用 distance=..1.5 定位受害者）
        Vec3d pos = textDisplay.getPos();
        Box searchBox = Box.of(pos, 3.0, 3.0, 3.0); // 1.5 半径
        List<Entity> candidates = foundWorld.getOtherEntities(textDisplay, searchBox, c ->
                !(c instanceof DisplayEntity.TextDisplayEntity)
                && !c.getCommandTags().contains("DamageShower")
                && c.getCommandTags().contains("CTTAll")
        );

        Entity victim = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity c : candidates) {
            double d = c.squaredDistanceTo(pos);
            if (d > 2.25) continue;
            if (d < bestDistSq) {
                bestDistSq = d;
                victim = c;
            }
        }

        String victimDesc;
        if (victim != null) {
            String nameText = victim.getName().getString();
            String typeId = Registries.ENTITY_TYPE.getId(victim.getType()).toString();
            victimDesc = String.format("%s (type=%s, uuid=%s)", nameText, typeId, victim.getUuidAsString());
        } else {
            victimDesc = String.format("<unresolved: no CTTAll within 1.5m of particle @ %.2f/%.2f/%.2f>",
                    pos.x, pos.y, pos.z);
        }

        // 注：session 累加已在 record() 里完成（v6.0.3 修复），这里只管日志。
        // v6.6.1 hotfix · 降级 DEBUG（高频路径，避免拖 TPS）。
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[CTT Stats] tick={} dmg={} victim={}", ev.tick, ev.damage, victimDesc);
        }

        // v6.1.0：成功解析 victim 且带 E tag，调用 AttackerProbe 兜底归属。
        // AttackerProbe 内部会查 perTickAttributedVictims 去重：
        //   若本 tick 已被 *DMG 归属过 → 跳过（FireDMG/MeleeDMG 等优先）
        //   否则走 attribute() 找攻击者 → 以 "AllDMG" 为 objective 广播
        // 这条路径是纯 vanilla 左键武器（无元素词条）唯一能被归属的入口。
        // v6.5.2 · 回血粒子在 record() 已标记 isHeal，强制走 L9_HEAL 路径。
        if (victim != null
                && victim.getCommandTags().contains("E")
                && !ServerConfig.INSTANCE.useRedHeartsTally) {
            AttackerProbe.Layer forceLayer = ev.isHeal ? AttackerProbe.Layer.L9_HEAL : null;
            AttackerProbe.recordFromDamageShower(server, victim, foundWorld, ev.damage, ev.tick, forceLayer);
        }
    }

    /**
     * 原始事件（Mixin 线程到 tick flush 之间的传输体）。
     *
     * @param isHeal v6.5.2 · 该粒子是否为绿色回血粒子（路由到 L9_HEAL）
     */
    private record RawEvent(long tick, UUID textDisplayUuid, int damage, boolean isHeal) {}
}
