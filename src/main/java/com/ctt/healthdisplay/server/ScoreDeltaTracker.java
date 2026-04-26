package com.ctt.healthdisplay.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6.3.2 · (holder × objective) 最近 score 值缓存。
 *
 * <h2>问题背景</h2>
 * <p>{@link com.ctt.healthdisplay.server.mixin.ScoreboardUpdateMixin} 在 scoreboard 写入后
 * 拿到的 {@code score.getScore()} 是<b>当前累计值</b>，不是"本次增量"。
 * 对于 DoT / 同 tick 多次 set / add 的场景，会让下游把累积值误当成一次新伤害，
 * 典型症状：火焰长矛 burningSpear 每 tick 让 {@code FireDMG} 从 20 涨到 2000，
 * 聊天栏和面板都会出现 {@code FireDMG -2000} 的"巨额"假事件。
 *
 * <h2>解法</h2>
 * <p>本类维护 {@code (holder,objective) → (lastValue, lastTick)} 缓存。
 * Mixin 每次拿到 newValue 时查 old，返回 delta = newValue - oldValue：
 * <ul>
 *   <li>delta &gt; 0 —— 真的增加了伤害（新伤害事件）</li>
 *   <li>delta == 0 —— 重复写入同值（忽略）</li>
 *   <li>delta &lt; 0 —— reset 到 0 / 回收（忽略）</li>
 * </ul>
 *
 * <h2>GC</h2>
 * <p>entry 超过 {@link #TTL_TICKS}（60s）未更新 → 清除，避免 mob 大量轮换后内存爆炸。
 * victim 被 kill 时地图一般会 reset *DMG 到 0，cache 也会停留在 0，等 TTL 到期后移除。
 */
public final class ScoreDeltaTracker {

    public static final long TTL_TICKS = 1200; // 60s

    private record Snap(int value, long tick) {}

    private static final Map<String, Snap> cache = new ConcurrentHashMap<>();

    private ScoreDeltaTracker() {}

    /**
     * 记录 (holder, objective) 的新值，返回 delta = newValue - oldValue。
     * 首次出现时 oldValue = 0，delta = newValue。
     */
    public static int observeDelta(String holder, String objective, int newValue) {
        if (holder == null || objective == null) return newValue;
        String key = holder + '\u001f' + objective;
        long now = DamageProbe.currentTick();
        Snap prev = cache.put(key, new Snap(newValue, now));
        int old = prev == null ? 0 : prev.value;
        return newValue - old;
    }

    /** 主动清除某 holder 的所有条目（entity 被移除时调用，可选）。 */
    public static void forget(String holder) {
        if (holder == null) return;
        String prefix = holder + '\u001f';
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public static void gcTick(long now) {
        cache.entrySet().removeIf(e -> now - e.getValue().tick() > TTL_TICKS);
    }

    public static int size() { return cache.size(); }
}
