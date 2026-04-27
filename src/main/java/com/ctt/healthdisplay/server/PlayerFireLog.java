package com.ctt.healthdisplay.server;

import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * v6.0.5 · vanilla {@code minecraft.used:minecraft.carrot_on_a_stick} 使用型 objective 收集器。
 *
 * <h2>信号来源</h2>
 * <p>地图注册了 {@code RightClick} objective：
 * <pre>
 *   scoreboard objectives add RightClick minecraft.used:minecraft.carrot_on_a_stick
 * </pre>
 * 每当持有 carrot-on-a-stick（所有自定义武器 / 法器 / 道具的物理容器）的玩家右键一次，
 * vanilla 会自动给该玩家这条 objective += 1。
 *
 * <h2>为什么需要它</h2>
 * <p>地图里大量远程武器（AK47 / Nut Laser / Pumpkin Carver Knife / Jelly Dash 等）的触发链：
 * <ol>
 *   <li>玩家右键 → carrot 使用 +1（{@code RightClick} tick）</li>
 *   <li>datapack 通过 {@code execute as @e[scores={RightClick=1..}]} 启动武器函数</li>
 *   <li>武器函数通过 marker/projectile/光束命中目标，自己写入 {@code *DMG} 到 victim</li>
 * </ol>
 * <p>整个过程中 vanilla {@link net.minecraft.entity.LivingEntity#damage} 不会触发 →
 * {@link PlayerHitLog}（damage_dealt stat）无事件。唯一能把"是谁开的枪"沉淀下来的就是这条右键信号。
 *
 * <h2>v6.7.8 · Q 丢弃信号</h2>
 * <p>地图里还有一类武器/法器并不是右键触发，而是按 <b>Q（丢弃键）</b> 把 carrot_on_a_stick
 * "扔出去"瞬间触发——典型表现是数据包用
 * {@code execute as @a[scores={DropCarrot=1..}]} 启动武器函数，并立刻把丢出的物品 clear
 * 再 give 回主手。对应 vanilla criterion 是
 * {@code minecraft.dropped:minecraft.carrot_on_a_stick}：
 * 玩家每丢出一根 carrot_on_a_stick 时该 stat += 1。
 *
 * <p>没有这条信号，丢弃式武器的伤害事件会全部掉到 L9-NONE（或被 L8 续归属误派给"刚好打过这个 victim"
 * 的另一名玩家）。本类把 dropped 信号同样写入 {@link FireKind#CARROT} 通道——对归属层而言，
 * "右键开火"和"丢弃开火"语义一致：都是"该玩家在过去 N tick 内主动触发了一次自定义武器"，
 * L1 / L6 等无需区分。
 *
 * <h2>用途（AttackerProbe L4）</h2>
 * <p>当 {@code *DMG} 写入 victim、L1/L2/L3 都查不到攻击者时，回看最近 20 tick 内
 * 开过火的玩家，按"距离 victim 由近到远"加权，取最高分。
 * 注意：只是"可能"（多个玩家同时开火会竞争）。
 */
public final class PlayerFireLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stats-fire");

    /** {@link ScoreboardCriterion#getName()} 对 carrot-on-a-stick 使用 objective 的返回值。 */
    public static final String USED_CARROT_CRITERION = "minecraft.used:minecraft.carrot_on_a_stick";
    /**
     * v6.7.8 · carrot-on-a-stick "被丢弃"信号 (按 Q 触发的武器)。
     * 地图常见用法：
     * <pre>
     *   scoreboard objectives add DropCarrot minecraft.dropped:minecraft.carrot_on_a_stick
     *   execute as @a[scores={DropCarrot=1..}] run function ...weapon trigger...
     * </pre>
     * 对归属算法而言与 {@link #USED_CARROT_CRITERION} 同义，统一走 {@link FireKind#CARROT}。
     */
    public static final String DROPPED_CARROT_CRITERION = "minecraft.dropped:minecraft.carrot_on_a_stick";
    /** v6.3.7 · 弓释放：玩家每射出一箭就 +1（需要地图注册了 {@code minecraft.used:minecraft.bow} 类 objective）。 */
    public static final String USED_BOW_CRITERION = "minecraft.used:minecraft.bow";
    public static final String USED_CROSSBOW_CRITERION = "minecraft.used:minecraft.crossbow";
    public static final String USED_TRIDENT_CRITERION = "minecraft.used:minecraft.trident";

    /** 条目保留窗口。 */
    private static final long TTL_TICKS = 400; // 20 秒

    /**
     * v6.3.7 · 事件类型。
     * <ul>
     *   <li>{@link #CARROT} — carrot_on_a_stick 右键（=自定义武器触发）</li>
     *   <li>{@link #BOW}    — vanilla 弓 / 弩 / 三叉戟真正发射出弹体（不是拉弓那一瞬间）</li>
     * </ul>
     */
    public enum FireKind { CARROT, BOW }

    /** 每个玩家的近况。 */
    private static final Map<UUID, Deque<FireEvent>> perPlayer = new ConcurrentHashMap<>();

    /**
     * 去重缓存：objective + holder → 上次看到的累计值。
     * vanilla stat 总是累加，只有 newValue > last 才算一次新事件。
     */
    private static final Map<String, Integer> lastValueCache = new ConcurrentHashMap<>();

    /**
     * 单次开火事件。
     *
     * @param tick        当前服务器 tick
     * @param pos         玩家开火时的位置（用于 L4 距离加权）
     * @param itemSig     简短的物品签名（主手 item id，记录用）
     * @param worldKey    维度 ID 字符串（跨维度不匹配）
     * @param kind        事件类型（v6.3.7 新增；CARROT = 右键自定义武器，BOW = vanilla 弹体发射）
     */
    public record FireEvent(long tick, Vec3d pos, String itemSig, String worldKey, FireKind kind) {}

    private PlayerFireLog() {}

    /**
     * 判断 objective 是否是 carrot "右键使用 / Q 丢弃"任意一类触发 stat。
     *
     * <p>v6.7.8 起：除原来的 {@link #USED_CARROT_CRITERION}，额外接受
     * {@link #DROPPED_CARROT_CRITERION}——支持地图里"按 Q 丢弃 carrot_on_a_stick"触发的武器，
     * 防止这类武器伤害落入 L9-NONE。两者都由 {@link #record} 写成 {@link FireKind#CARROT}
     * 事件，归属层 (L1 / L6) 不区分。
     */
    public static boolean isRightClickStat(ScoreboardObjective objective) {
        String n = objective.getCriterion().getName();
        return USED_CARROT_CRITERION.equals(n) || DROPPED_CARROT_CRITERION.equals(n);
    }

    /** v6.3.7 · 判断 objective 是否是弓/弩/三叉戟发射 stat。 */
    public static boolean isBowReleaseStat(ScoreboardObjective objective) {
        String n = objective.getCriterion().getName();
        return USED_BOW_CRITERION.equals(n)
                || USED_CROSSBOW_CRITERION.equals(n)
                || USED_TRIDENT_CRITERION.equals(n);
    }

    /** carrot 路径（原 v6.0.5 语义）。 */
    public static void record(MinecraftServer server, ScoreHolder holder,
                              ScoreboardObjective objective, int newValue, long currentTick) {
        recordInternal(server, holder, objective, newValue, currentTick, FireKind.CARROT);
    }

    /** v6.3.7 · 弓/弩/三叉戟发射路径。 */
    public static void recordBow(MinecraftServer server, ScoreHolder holder,
                                 ScoreboardObjective objective, int newValue, long currentTick) {
        recordInternal(server, holder, objective, newValue, currentTick, FireKind.BOW);
    }

    private static void recordInternal(MinecraftServer server, ScoreHolder holder,
                                       ScoreboardObjective objective, int newValue,
                                       long currentTick, FireKind kind) {
        if (server == null) return;

        String holderName = holder.getNameForScoreboard();
        String cacheKey = objective.getName() + "\0" + holderName;

        Integer last = lastValueCache.put(cacheKey, newValue);
        int delta = (last == null) ? newValue : (newValue - last);
        if (delta <= 0) return;

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(holderName);
        if (player == null) return;

        UUID uuid = player.getUuid();
        Vec3d pos = player.getPos();
        String worldKey = player.getWorld().getRegistryKey().getValue().toString();
        String itemSig = resolveItemSig(player);

        Deque<FireEvent> deque = perPlayer.computeIfAbsent(uuid, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new FireEvent(currentTick, pos, itemSig, worldKey, kind));

        while (!deque.isEmpty() && deque.peekFirst().tick() < currentTick - TTL_TICKS) {
            deque.pollFirst();
        }
    }

    /**
     * 获取主手物品简短描述。只记录给人看，归属算法不依赖此值。
     */
    private static String resolveItemSig(ServerPlayerEntity player) {
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) return "empty";
        return net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
    }

    /**
     * 查询：过去 [currentTick - withinTicks, currentTick] 内开过火的玩家，按距离 victim 升序。
     *
     * @param currentTick  当前 tick
     * @param withinTicks  回看窗口（建议 20 tick ~ 1 秒）
     * @param victimPos    victim 位置
     * @param worldKey     victim 所在维度，必须匹配
     * @param maxDist      只返回距离 victim 不超过此米数的候选；超过视为不可能
     * @return 按距离升序（最有可能 → 最不可能）
     */
    public static List<FireCandidate> query(long currentTick, long withinTicks,
                                            Vec3d victimPos, String worldKey, double maxDist) {
        long from = currentTick - withinTicks;
        List<FireCandidate> out = new ArrayList<>();
        double maxSq = maxDist * maxDist;

        for (Map.Entry<UUID, Deque<FireEvent>> e : perPlayer.entrySet()) {
            // 取该玩家窗口内最新一次开火
            FireEvent latest = null;
            for (var it = e.getValue().descendingIterator(); it.hasNext(); ) {
                FireEvent ev = it.next();
                if (ev.tick() < from) break;
                if (!worldKey.equals(ev.worldKey())) continue;
                latest = ev;
                break;
            }
            if (latest == null) continue;

            double dsq = latest.pos().squaredDistanceTo(victimPos);
            if (dsq > maxSq) continue;

            out.add(new FireCandidate(e.getKey(), latest.tick(), latest.pos(), latest.itemSig(),
                    Math.sqrt(dsq), currentTick - latest.tick()));
        }
        out.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        return out;
    }

    /**
     * 查询结果条目。distance 越小越可信；ticksAgo 越小越可信。
     */
    public record FireCandidate(UUID playerUuid, long tick, Vec3d firePos, String itemSig,
                                double distance, long ticksAgo) {}

    /**
     * v6.3.7 · L7b 专用：只看 {@link FireKind#BOW} 事件。
     * 排序规则：<b>最近释放优先</b>（同 tick 再比距离）——弓箭飞行延迟决定"刚射的那位"最可能是真凶。
     *
     * @param withinTicks 推荐 60 tick（3 s）
     * @param maxDist     距离上限（米）
     */
    public static List<FireCandidate> queryBowReleases(long currentTick, long withinTicks,
                                                       Vec3d victimPos, String worldKey, double maxDist) {
        long from = currentTick - withinTicks;
        List<FireCandidate> out = new ArrayList<>();
        double maxSq = maxDist * maxDist;

        for (Map.Entry<UUID, Deque<FireEvent>> e : perPlayer.entrySet()) {
            FireEvent latest = null;
            for (var it = e.getValue().descendingIterator(); it.hasNext(); ) {
                FireEvent ev = it.next();
                if (ev.tick() < from) break;
                if (ev.kind() != FireKind.BOW) continue;
                if (!worldKey.equals(ev.worldKey())) continue;
                latest = ev;
                break;
            }
            if (latest == null) continue;

            double dsq = latest.pos().squaredDistanceTo(victimPos);
            if (dsq > maxSq) continue;

            out.add(new FireCandidate(e.getKey(), latest.tick(), latest.pos(), latest.itemSig(),
                    Math.sqrt(dsq), currentTick - latest.tick()));
        }
        // 最近释放优先；同 tick 则距离近者
        out.sort((a, b) -> {
            int cmp = Long.compare(b.tick(), a.tick());
            return cmp != 0 ? cmp : Double.compare(a.distance(), b.distance());
        });
        return out;
    }

    /**
     * v6.2.0 · 不带距离/世界过滤的"某玩家近窗是否开过火"判定。
     * 用于 AttackerProbe L1 层"近 10s 开过火"守卫。
     *
     * @return 在 [fromTick, toTick] 内该玩家最新一次开火的 tick，无则 null。
     */
    public static Long latestTickOf(java.util.UUID playerUuid, long fromTick, long toTick) {
        Deque<FireEvent> d = perPlayer.get(playerUuid);
        if (d == null || d.isEmpty()) return null;
        for (var it = d.descendingIterator(); it.hasNext(); ) {
            FireEvent ev = it.next();
            if (ev.tick() > toTick) continue;
            if (ev.tick() < fromTick) break;
            return ev.tick();
        }
        return null;
    }

    public static void gcTick(long currentTick) {
        perPlayer.entrySet().removeIf(e -> {
            Deque<FireEvent> d = e.getValue();
            while (!d.isEmpty() && d.peekFirst().tick() < currentTick - TTL_TICKS) {
                d.pollFirst();
            }
            return d.isEmpty();
        });
    }

    public static int totalEvents() {
        int n = 0;
        for (Deque<FireEvent> d : perPlayer.values()) n += d.size();
        return n;
    }
}
