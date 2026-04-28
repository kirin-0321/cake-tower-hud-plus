package com.ctt.healthdisplay.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.0.7 · 攻击者身份反查（marker / player 拆分版）。
 *
 * <h2>为什么拆 marker 扫和 player 扫</h2>
 * <p>v6.0.5 把"3m/30m 内任何 PlayerID 实体"合在一层扫，在 FireDMG 这种多玩家同场景里
 * 变成"距离最近玩家胜"的近似算法，归属会随玩家走位飘移 ——
 * 日志证据见 FEATURES.md v6.0.7 节。
 *
 * <p>根本观察：**marker / projectile 只由攻击行为衍生**，它带 PlayerID 就意味着
 * 这个 PlayerID 的玩家是真实攻击者。而玩家本人身上的 PlayerID 只代表"这是 X"，
 * 并不代表"X 正在攻击"。所以两类候选必须分别打分：
 * <ul>
 *   <li>{@code scanMarkers} → 结果直接采信</li>
 *   <li>{@code scanPlayers} → 作为兜底，调用方结合其它信号（L1/L4 时间窗）决定</li>
 * </ul>
 *
 * <h2>扫描半径</h2>
 * <ul>
 *   <li>marker 近场 3m / 远场 30m（AK47 marker 生成在命中点附近 ≤2m；Nut Laser marker 可能在射线中段更远）</li>
 *   <li>player 仅 3m（贴脸兜底，超出此距离不可信）</li>
 * </ul>
 */
public final class AttackerResolver {

    private AttackerResolver() {}

    public static final double MARKER_NEAR = 3.0;
    /** v6.2.0: 30m → 40m。召唤物 / 远程法器标记点漂移半径普遍偏大。 */
    public static final double MARKER_FAR  = 40.0;
    /**
     * v6.2.0: 保留 API，但 AttackerProbe 的归属栈已移除 L4b 调用（scanPlayers
     * 现在是 no-call）。如需用到"贴脸兜底"可再启用，但会重新引入路人误判风险。
     */
    public static final double PLAYER_NEAR = 3.0;

    public enum Source { PLAYER, MARKER, PROJECTILE, OTHER }

    /**
     * @param entity   候选实体
     * @param playerId PlayerID 值
     * @param distance 与 victim 欧氏距离
     * @param source   类型分类
     * @param tagsDesc 主要 tag / 类型，用于日志
     */
    public record Candidate(Entity entity, int playerId, double distance, Source source,
                            String tagsDesc) {
        /** 简洁日志格式：{@code MARKER/AK47ShootAI/pid=-1234/d=1.81m}。 */
        public String desc() {
            return String.format("%s/%s/pid=%d/d=%.2fm", source, tagsDesc, playerId, distance);
        }
    }

    /**
     * 扫 victim 周围 radius 范围内的 marker / projectile / armor_stand 类候选
     * —— 不含玩家实体。
     *
     * <p>返回列表按距离升序；空列表表示未找到。
     *
     * <p><b>v8.0.0 性能：per-tick 缓存</b>。同 tick 内同 (victim, radius) 的扫描结果
     * 共享同一 list（CTT boss 一击多伤害类型，PhysicalDMG/FireDMG/ImpactDMG 同 tick 反复触发
     * 同一 victim 的归属链 → 节省 N-1 次 box scan）。
     *
     * <p>缓存安全前提：<i>同 tick 内 victim 周围的候选实体集合不变</i>。这是合理假设：
     * <ul>
     *   <li>vanilla 实体移动在 tick 开头集中跑，attribute chain 是 mid-tick</li>
     *   <li>mcfunction 同 tick 内新 summon 的 DamageShower/E 标记已被 scan 的 predicate 过滤</li>
     *   <li>同 tick 内 PlayerID score 的写入不会改变扫到的实体集合，只可能改其 pid 值
     *       —— 而 pid 值是在 cache miss 时一次性读取，cache hit 时不再重新读</li>
     * </ul>
     *
     * <p><b>调用约定：返回的 list 必须只读</b>（缓存共享同一 ArrayList 引用）。
     */
    public static List<Candidate> scanMarkers(ServerWorld world, Entity victim, double radius) {
        long tick = DamageProbe.currentTick();
        invalidateCacheIfStale(tick);
        CacheKey key = new CacheKey(victim.getUuid(), radius);
        List<Candidate> cached = MARKER_CACHE.get(key);
        if (cached != null) return cached;

        List<Candidate> result = scan(world, victim, victim.getPos(), radius,
                /*includePlayers=*/false, /*includeNonPlayers=*/true);
        MARKER_CACHE.put(key, result);
        return result;
    }

    // -------- per-tick scan cache（仅 scanMarkers 使用） --------
    private record CacheKey(UUID victimUuid, double radius) {}
    private static final Map<CacheKey, List<Candidate>> MARKER_CACHE = new ConcurrentHashMap<>();
    private static volatile long cacheTick = -1L;

    private static void invalidateCacheIfStale(long tick) {
        if (tick != cacheTick) {
            MARKER_CACHE.clear();
            cacheTick = tick;
        }
    }

    /**
     * 扫 victim 周围 radius 范围内的玩家实体（仅玩家，不扫 marker）。
     * 调用方应当视情况决定是否采信（通常作为 L4b 贴脸兜底，或多信号印证）。
     */
    public static List<Candidate> scanPlayers(ServerWorld world, Entity victim, double radius) {
        return scan(world, victim, victim.getPos(), radius, /*includePlayers=*/true, /*includeNonPlayers=*/false);
    }

    private static List<Candidate> scan(ServerWorld world, Entity victim, Vec3d anchor,
                                        double radius, boolean includePlayers, boolean includeNonPlayers) {
        Scoreboard sb = world.getScoreboard();
        ScoreboardObjective pidObj = sb.getNullableObjective("PlayerID");
        if (pidObj == null) return List.of();

        double boxSize = radius * 2;
        Box box = Box.of(anchor, boxSize, boxSize, boxSize);

        List<Candidate> out = new ArrayList<>();
        List<Entity> near = world.getOtherEntities(victim, box, c ->
                !(c instanceof DisplayEntity.TextDisplayEntity)
                && !c.getCommandTags().contains("DamageShower")
                && !c.getCommandTags().contains("E")
        );

        for (Entity e : near) {
            boolean isPlayer = e instanceof PlayerEntity;
            if (isPlayer && !includePlayers) continue;
            if (!isPlayer && !includeNonPlayers) continue;

            double d = Math.sqrt(e.squaredDistanceTo(anchor));
            if (d > radius) continue;

            ReadableScoreboardScore score = sb.getScore(e, pidObj);
            if (score == null) continue;
            int pid = score.getScore();
            if (pid == 0) continue;

            Source src;
            String tagsDesc;
            if (isPlayer) {
                src = Source.PLAYER;
                tagsDesc = e.getName().getString();
            } else if (isProjectile(e)) {
                src = Source.PROJECTILE;
                tagsDesc = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            } else if (!e.getCommandTags().isEmpty()) {
                src = Source.MARKER;
                tagsDesc = primaryTag(e.getCommandTags());
            } else {
                src = Source.OTHER;
                tagsDesc = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            }

            out.add(new Candidate(e, pid, d, src, tagsDesc));
        }

        out.sort(Comparator.comparingDouble(Candidate::distance));
        return out;
    }

    /**
     * 尽力把 PlayerID 反查到在线玩家。地图约定 PlayerID = UUID[0]（UUID 前 32 位）。
     */
    public static PlayerEntity lookupPlayerByPlayerId(ServerWorld world, int playerId) {
        for (PlayerEntity p : world.getPlayers()) {
            int pid = (int) (p.getUuid().getMostSignificantBits() >> 32);
            if (pid == playerId) return p;
        }
        return null;
    }

    // markerTag 识别优先级：攻击相关 tag 优先于通用 tag
    private static final java.util.Set<String> PRIMARY_TAG_PRIORITY = java.util.Set.of(
            "AK47ShootAI", "PistolShoot", "PistolShootHit",
            "SlingshotHit", "SlingshotChestplate",
            "ShockArrow2", "ShockArrowHit",
            "BoomerangHit", "BoomerangFlowerAI", "S4_03_BoomerangFlowerAI",
            "AmethystDomainAI", "EvokerFangHit",
            "NSLaserAI1", "NSLaserAI", "NSLaserHit", "NSLaserCharge",
            "PumpkinCarverKnifeHit", "PumpkinCarverKnifeTarget",
            "SwansLustTarget"
    );

    private static String primaryTag(java.util.Set<String> tags) {
        for (String t : tags) {
            if (PRIMARY_TAG_PRIORITY.contains(t)) return t;
        }
        for (String t : tags) {
            if (t.contains("Hit") || t.contains("Shoot") || t.contains("AI")) return t;
        }
        return tags.iterator().next();
    }

    private static boolean isProjectile(Entity e) {
        String path = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        return path.equals("arrow") || path.equals("spectral_arrow")
                || path.equals("snowball") || path.equals("egg")
                || path.equals("trident") || path.equals("fireball")
                || path.equals("small_fireball") || path.equals("dragon_fireball")
                || path.equals("wind_charge") || path.equals("breeze_wind_charge");
    }
}
