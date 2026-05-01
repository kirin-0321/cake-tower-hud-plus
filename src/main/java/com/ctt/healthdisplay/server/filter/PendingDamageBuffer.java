package com.ctt.healthdisplay.server.filter;

import com.ctt.healthdisplay.config.ServerConfig;
import com.ctt.healthdisplay.server.AttackerProbe;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v8.x · 异常伤害过滤器 G7b 的延迟分发缓冲。
 *
 * <h2>语义</h2>
 * <p>{@link com.ctt.healthdisplay.server.filter.DamageFilterPipeline#applyFilters} 跑完
 * 同步规则栈后，**未命中**的事件不再立刻调 {@code AttackerProbe.recordFromDamageShower}
 * 进归属，而是构造 {@link Entry} 入队等 N tick（{@link ServerConfig#bufferDelayTicks}，默认 2）。
 *
 * <p>等 N tick 的目的：
 * <ul>
 *   <li>让"同 tick 较晚到达的 RedHearts 致死信号"有机会更新 {@code wasLethalAtBuffer}
 *       —— 实际现版用法是入队时已读完 RedHearts，{@code wasLethalAtBuffer} 在 enqueue
 *       时一次性快照（详见 {@code DAMAGE_FILTER_DESIGN_V2.md} §5）</li>
 *   <li>让上层（{@link com.ctt.healthdisplay.server.DamageProbe#flushTick}）能在
 *       {@code scanDeaths → buffer.flush} 顺序里先消费完本 tick 的死亡 contributors</li>
 * </ul>
 *
 * <h2>flush 阶段的判定流程</h2>
 * <p>{@link #flush} 把所有 {@code enqueueTick + delay ≤ currentTick} 的 entry 按 FIFO 顺序
 * 派发给 {@link FlushHandler}。handler 由 {@link com.ctt.healthdisplay.server.DamageProbe} 注入，
 * 内部实现"三指标 AND outlier 判定 + lethal/outlier 路由 + 写入 contributors / 更新 P95 / DPS"。
 *
 * <h2>溢出策略</h2>
 * <p>{@link ServerConfig#bufferMaxSize} 默认 4096——CTT 高强度战斗 ≤ 200 events/s × 2 tick
 * delay ≈ 20 entries 同时驻留，4096 给 5 倍冗余。溢出时丢最老 entry，{@link #overflowDropped}
 * 计数 +1（L 面板暴露给运维查看）。
 *
 * <h2>线程安全</h2>
 * <p>{@code DEQUE} 是 {@link ArrayDeque} + 显式 lock：MC 服务端 tick 单线程驱动，但
 * mixin 注入路径历史上偶有跨线程，简单 synchronized 包装无副作用。
 */
public final class PendingDamageBuffer {

    private static final Deque<Entry> DEQUE = new ArrayDeque<>();
    private static final AtomicLong OVERFLOW_DROPPED = new AtomicLong(0);

    private PendingDamageBuffer() {}

    /**
     * 入队一个待延迟分发的伤害事件。
     *
     * @param victim              受害者实体（必须非 null）。buffer delay ≤ 2 tick 内 entity
     *                            不会被 GC，可直接持有引用——flush 时再判 isRemoved。
     * @param victimWorld         受害者所在世界（用于 flush 阶段 attacker 解析 fallback）。
     * @param damage              本次受击伤害（必须 &gt; 0）。
     * @param enqueueTick         入队时的 server tick。
     * @param attackerUuid        入队时已通过 {@link com.ctt.healthdisplay.server.AttackerProbe}
     *                            纯查询路径解析出的攻击者；解析失败传 null。
     * @param attackerLabel       攻击者展示标签（"Player(xxx)"）；解析失败传 null。
     * @param attackerLayer       归属层级（{@link AttackerProbe.Layer#L1_WEAPON_MATCH} 等）；
     *                            解析失败传 {@link AttackerProbe.Layer#L9_NONE}。
     *                            v8.x · 用 enum 而非 shortTag——后续 capped 补偿需要原始 Layer
     *                            调 {@link com.ctt.healthdisplay.server.PlayerDamageStats#add}
     *                            把 lethal-mechanism 击杀的 {@code min(damage, MaxHP)} 计入玩家账户。
     * @param weaponId            入队时刻攻击者主手武器 ID
     *                            （详见 {@link WeaponIdResolver#resolveCurrent}）；
     *                            解析失败 / 空手时为 {@link WeaponIdResolver#EMPTY}。
     * @param wasLethalAtBuffer   入队时刻 victim 是否已致死（RedHearts ≤ 0 或 isRemoved）。
     */
    public static void enqueue(Entity victim, ServerWorld victimWorld, int damage,
                               long enqueueTick, UUID attackerUuid, String attackerLabel,
                               AttackerProbe.Layer attackerLayer, String weaponId,
                               boolean wasLethalAtBuffer) {
        if (victim == null || victimWorld == null || damage <= 0) return;

        synchronized (DEQUE) {
            int cap = capacityCfg();
            while (DEQUE.size() >= cap) {
                DEQUE.pollFirst();
                OVERFLOW_DROPPED.incrementAndGet();
            }
            DEQUE.addLast(new Entry(
                    victim, victimWorld, damage, enqueueTick,
                    attackerUuid, attackerLabel,
                    attackerLayer == null ? AttackerProbe.Layer.L9_NONE : attackerLayer,
                    weaponId == null ? WeaponIdResolver.EMPTY : weaponId,
                    wasLethalAtBuffer));
        }
    }

    /**
     * 排干所有 {@code enqueueTick + delay ≤ currentTick} 的 entry 给 {@code handler} 处理。
     * <p>FIFO 顺序，单次调用会把所有到期 entry 一次性 drain；未到期的留在队头等下一 tick。
     *
     * @param currentTick 当前 server tick
     * @param handler     非 null；buffer 不在意 handler 内部抛异常——handler 自行 try-catch
     */
    public static void flush(long currentTick, FlushHandler handler) {
        if (handler == null) return;
        int delay = Math.max(0, ServerConfig.INSTANCE.bufferDelayTicks);
        long readyBefore = currentTick - delay;

        // 一次性复制到本地数组释放锁，handler 可能调用其它 server 方法（避免锁嵌套）
        Entry[] ready;
        synchronized (DEQUE) {
            int n = 0;
            for (Entry e : DEQUE) {
                if (e.enqueueTick > readyBefore) break;
                n++;
            }
            if (n == 0) return;
            ready = new Entry[n];
            for (int i = 0; i < n; i++) ready[i] = DEQUE.pollFirst();
        }

        for (Entry e : ready) {
            try {
                handler.handle(e, currentTick);
            } catch (Throwable t) {
                // 个别 entry 处理失败不应阻塞后续——记日志由 handler 侧决定，buffer 仅吃异常
            }
        }
    }

    /** 当前队列长度（L 面板用）。 */
    public static int size() {
        synchronized (DEQUE) {
            return DEQUE.size();
        }
    }

    /** 累计因溢出被丢弃的 entry 数（L 面板用）。 */
    public static long overflowDropped() { return OVERFLOW_DROPPED.get(); }

    /** start / stop / clear 联动：清空队列并归零计数。 */
    public static void clearAll() {
        synchronized (DEQUE) {
            DEQUE.clear();
        }
        OVERFLOW_DROPPED.set(0);
    }

    private static int capacityCfg() {
        int v = ServerConfig.INSTANCE.bufferMaxSize;
        return v >= 16 ? v : 4096;
    }

    // =========================================================================
    //  Entry / FlushHandler
    // =========================================================================

    /**
     * 单个待延迟分发的伤害事件。所有需要在 flush 时使用的上下文都在 enqueue 时一并捕获，
     * 避免 flush 时再访问外部状态（victim 已死 / worldKey 已变 / inventory 已切等）。
     */
    public record Entry(
            Entity victim,
            ServerWorld victimWorld,
            int damage,
            long enqueueTick,
            UUID attackerUuid,
            String attackerLabel,
            AttackerProbe.Layer attackerLayer,
            String weaponId,
            boolean wasLethalAtBuffer) {}

    /**
     * flush 阶段的 entry 处理回调。{@link com.ctt.healthdisplay.server.DamageProbe} 注入实现：
     * 内部跑三指标 AND，按 outlier / lethal-mechanism / pass 路由，最终调
     * {@code AttackerProbe.recordFromDamageShower} 与 {@code VictimDamageContributors.add}。
     */
    @FunctionalInterface
    public interface FlushHandler {
        void handle(Entry entry, long currentTick);
    }
}
