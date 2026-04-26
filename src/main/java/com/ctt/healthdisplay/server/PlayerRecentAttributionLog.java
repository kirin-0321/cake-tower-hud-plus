package com.ctt.healthdisplay.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.0.9 · 每个玩家最近被"硬证据"归属过的 `*DMG` 类型日志。
 *
 * <h2>为什么只记"硬证据"</h2>
 * <p>只有 L1（本 tick vanilla damage_dealt）/ L2a（3m marker）/ L2b（30m marker/projectile）
 * 才是 100% 可靠的归属 —— 它们有物理证据（marker / stat 写入）支撑。
 * L3 / L4 / L4b / L4c 都是软证据（推测 / 兜底 / carry），如果也写入就会造成
 * "错归属 → 被日志记录 → 下次更坚定地错归属"的自增强循环。
 *
 * <h2>用途：L4 的 Tier 打分破除歧义</h2>
 * <p>典型场景：玩家 A 用 AK47 持续射击（频繁 L2a 归属 {@code BulletDMG}），
 * 同时玩家 B 用火法器 丢一发（写入 {@code FireDMG} 但 marker 早死、vanilla stat 没触发 → 落到 L4）。
 * L4 的 RightClick 候选里两人都在（A 连点、B 单发），按距离选可能错归属到 A。
 *
 * <p>但查 {@code PlayerRecentAttributionLog}：
 * <ul>
 *   <li>A 最近 5t 归属过 {@code BulletDMG}（与当前 {@code FireDMG} 不符）→ Tier 3</li>
 *   <li>B 最近 5t 无归属 → Tier 2（中性）</li>
 * </ul>
 * 即使 A 距离更近，也选 B。
 *
 * <h2>TTL</h2>
 * <p>默认 5 tick（0.25 秒）—— 显著短于 L4 RightClick 窗口（20 tick）。
 * 持续攻击的玩家永远保有类型印记；切换武器后 5 tick 即清空。
 */
public final class PlayerRecentAttributionLog {

    private PlayerRecentAttributionLog() {}

    public record Event(String objective, long tick) {}

    private static final Map<UUID, Deque<Event>> perPlayer = new ConcurrentHashMap<>();

    /** 类型印记存留时长（tick）。选 5 = 0.25 秒，短于 L4 RightClick 窗口 20 tick。 */
    public static final long TTL_TICKS = 5;

    /** 由 {@link AttackerProbe#record} 在硬证据归属成功时调用。 */
    public static void record(UUID playerUuid, String objective, long tick) {
        if (playerUuid == null || objective == null) return;
        perPlayer.computeIfAbsent(playerUuid, k -> new ArrayDeque<>())
                .addLast(new Event(objective, tick));
    }

    /**
     * 查某玩家在 [from, to] 区间（闭区间）内被归属过的所有 `*DMG` 类型集合。
     * 线程安全：外层 ConcurrentHashMap + 对 Deque 短暂同步访问。
     */
    public static Set<String> queryTypes(UUID playerUuid, long fromTick, long toTick) {
        Deque<Event> d = perPlayer.get(playerUuid);
        if (d == null) return Set.of();
        Set<String> out = new HashSet<>();
        synchronized (d) {
            for (Event e : d) {
                if (e.tick() >= fromTick && e.tick() <= toTick) out.add(e.objective());
            }
        }
        return out;
    }

    /** End-of-tick 清理过期条目。 */
    public static void gcTick(long currentTick) {
        Iterator<Map.Entry<UUID, Deque<Event>>> it = perPlayer.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Deque<Event>> entry = it.next();
            Deque<Event> d = entry.getValue();
            synchronized (d) {
                while (!d.isEmpty() && d.peekFirst().tick() < currentTick - TTL_TICKS) {
                    d.pollFirst();
                }
                if (d.isEmpty()) it.remove();
            }
        }
    }

    public static int size() {
        int n = 0;
        for (Deque<Event> d : perPlayer.values()) {
            synchronized (d) { n += d.size(); }
        }
        return n;
    }
}
