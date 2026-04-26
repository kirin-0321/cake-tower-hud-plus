package com.ctt.healthdisplay.server;

import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * v6.0.5 · vanilla {@code minecraft.custom:minecraft.damage_dealt} 统计型 objective 收集器。
 *
 * <h2>信号来源</h2>
 * <p>地图在 {@code scoreboards_part_2.mcfunction} 注册了 <b>70+ 种武器特定的 vanilla stat objective</b>，
 * criterion 全部是 {@code minecraft.custom:minecraft.damage_dealt}：
 * <pre>
 *   scoreboard objectives add SwansLustDMG minecraft.custom:minecraft.damage_dealt
 *   scoreboard objectives add PumpkinCarverKnifeDMG minecraft.custom:minecraft.damage_dealt
 *   scoreboard objectives add JellySwordDMG minecraft.custom:minecraft.damage_dealt
 *   ...
 * </pre>
 *
 * <p>vanilla 行为：每当玩家通过 {@link net.minecraft.entity.LivingEntity#damage} 成功对实体造成伤害，
 * {@code PlayerStatHandler.increment(...)} 会给该玩家的**所有** {@code damage_dealt} 类 objective 全部 +=damage×10。
 *
 * <h2>为什么能用</h2>
 * <ul>
 *   <li>holder 永远是攻击者玩家（vanilla stat 天然属性）</li>
 *   <li>objective 名字 == 武器名 → 直接告诉我们"用的哪把武器"</li>
 *   <li>与地图自定义 {@code *DMG} scoreboard 在同 tick / 紧邻 tick 发生 → pair matching 简单</li>
 * </ul>
 *
 * <h2>已知覆盖</h2>
 * <p>约 70+ 种近战武器（剑 / 斧 / 匕首 / 拳套等左键击打类）100% 覆盖。<br>
 * 不覆盖 carrot-on-a-stick 右键激活的远程法器 / 枪械（它们绕过 vanilla damage，走 {@link PlayerFireLog}）。
 */
public final class PlayerHitLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stats-hit");

    /** {@link ScoreboardCriterion#getName()} 对 {@code damage_dealt} 类 objective 返回的串。 */
    public static final String DAMAGE_DEALT_CRITERION = "minecraft.custom:minecraft.damage_dealt";

    /** 日志条目保留窗口。超过就可以被 tick GC 清掉。 */
    private static final long TTL_TICKS = 400; // 20 秒

    /** 每个玩家保留的 hit 事件（双端队列，尾部是最新）。 */
    private static final Map<UUID, Deque<HitEvent>> perPlayer = new ConcurrentHashMap<>();

    /**
     * 用于增量检测：同 objective + 同 holder 的 (lastValue) 缓存。
     * vanilla stat 的更新方式是"累加"，Mixin 在 RETURN 拿到 newValue，
     * 需要减去上次值得到 delta 才能判断"这次是否真的发生了写入"。
     */
    private static final Map<String, Integer> lastValueCache = new ConcurrentHashMap<>();

    /**
     * 单条 hit 事件。
     *
     * @param tick       服务器 tick 计数（由 DamageProbe 维护）
     * @param weaponId   objective 名字，例如 {@code "SwansLustDMG"}
     * @param delta      本次 +分值（通常 = 伤害值 × 10）
     */
    public record HitEvent(long tick, String weaponId, int delta) {}

    private PlayerHitLog() {}

    /** 判断一个 objective 是不是我们关心的 damage_dealt 类 stat。 */
    public static boolean isDamageDealtStat(ScoreboardObjective objective) {
        return DAMAGE_DEALT_CRITERION.equals(objective.getCriterion().getName());
    }

    /**
     * Mixin 入口。调用前已过滤：objective 是 damage_dealt stat 类型。
     *
     * @param server    MinecraftServer（用来反查 holder 是哪个在线玩家）
     * @param holder    stat holder —— 对玩家 stat 来说 name 是玩家用户名
     * @param objective objective 本身（这里只读 name）
     * @param newValue  Mixin 拦截到的 score 当前值
     * @param currentTick 当前 tick 计数
     */
    public static void record(MinecraftServer server, ScoreHolder holder,
                              ScoreboardObjective objective, int newValue, long currentTick) {
        if (server == null) return;

        String objName = objective.getName();
        String holderName = holder.getNameForScoreboard();
        String cacheKey = objName + "\0" + holderName;

        Integer last = lastValueCache.put(cacheKey, newValue);
        int delta = (last == null) ? newValue : (newValue - last);
        if (delta <= 0) return; // 未变化 / 回退（vanilla stat 只会累加，不应 <0，保险起见过滤）

        // 反查玩家 UUID（stat holder 是玩家名）
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(holderName);
        if (player == null) return; // 名字不对应在线玩家，忽略（可能是离线时写入的）

        UUID uuid = player.getUuid();
        Deque<HitEvent> deque = perPlayer.computeIfAbsent(uuid, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new HitEvent(currentTick, objName, delta));

        // 就地 GC：丢掉队首过老的
        while (!deque.isEmpty() && deque.peekFirst().tick() < currentTick - TTL_TICKS) {
            deque.pollFirst();
        }
    }

    /**
     * 查询：本 tick 有哪些玩家触发了 damage_dealt stat 写入。
     * 用于 L1（同 tick vanilla hit 匹配）。
     *
     * @return 可能多人（联机齐挥）；按"最新事件时间戳"排序
     */
    public static List<PlayerHit> queryAtTick(long tick) {
        return query(tick, tick);
    }

    /**
     * 查询时间窗口内的 hits。用于 L3（近战 DoT 延续期归属）。
     *
     * @param fromTick 含
     * @param toTick   含
     */
    public static List<PlayerHit> query(long fromTick, long toTick) {
        List<PlayerHit> out = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Deque<HitEvent>> e : perPlayer.entrySet()) {
            for (HitEvent ev : e.getValue()) {
                if (ev.tick() >= fromTick && ev.tick() <= toTick) {
                    out.add(new PlayerHit(e.getKey(), ev.tick(), ev.weaponId(), ev.delta()));
                }
            }
        }
        // 按 tick 降序（更新的优先）
        out.sort((a, b) -> Long.compare(b.tick(), a.tick()));
        return out;
    }

    /** 查询结果条目 —— 扁平化方便调用方消费。 */
    public record PlayerHit(UUID playerUuid, long tick, String weaponId, int delta) {}

    /** 每 tick 末批量 GC，防止离线玩家的数据堆积。 */
    public static void gcTick(long currentTick) {
        perPlayer.entrySet().removeIf(e -> {
            Deque<HitEvent> d = e.getValue();
            while (!d.isEmpty() && d.peekFirst().tick() < currentTick - TTL_TICKS) {
                d.pollFirst();
            }
            return d.isEmpty();
        });
    }

    /** 供诊断日志使用。 */
    public static int totalEvents() {
        int n = 0;
        for (Deque<HitEvent> d : perPlayer.values()) n += d.size();
        return n;
    }
}
