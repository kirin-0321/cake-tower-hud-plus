package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.network.TeamHeartsPayload;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * v8.4.0 · 客户端 "全队四色心" 缓存（服务端权威）。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>接 {@link TeamHeartsPayload}，把 {@code List<Entry>} 转成 {@code name → Entry} 只读 map；</li>
 *   <li>提供 {@link #isFresh()}：最近一次推送不超过 {@value #FRESH_WINDOW_MS} ms 视为新鲜；</li>
 *   <li>提供 {@link #lookup(String)}：{@link com.ctt.healthdisplay.health.HealthData#parseTeamBar}
 *       拼 {@link com.ctt.healthdisplay.health.HealthData.TeammateData} 时按名字查 4 色心 + maxHp + lives；</li>
 *   <li>{@link #reset()} 给 DISCONNECT 钩子清缓存，避免切服后看到上一局残留四色心。</li>
 * </ul>
 *
 * <h2>服务端未装 mod 的兼容路径</h2>
 * <p>从未收到 payload → {@code lastPushMs = 0} → {@code isFresh() = false} →
 * {@code HealthData.parseTeamBar} 退到 v8.3.x 老路径：bossbar 文本正则 + 本地 scoreboard
 * 读 Lives，{@code TeammateData.soulHearts/blackHearts/blueHearts} 全 0，三个队友渲染
 * 入口检测到 layered 数据缺失 → 退到旧的 OVERFLOW_COLORS 单色多槽条。
 *
 * <h2>失鲜窗口为何选 5 秒</h2>
 * <p>{@link com.ctt.healthdisplay.server.TeamHeartsBroadcaster} 默认 4 tick (200 ms)
 * 推一次，差量吞噬下静态场景可能 3-4 s 静默。5 s 给静默场景留余量，超过 5 s 没动静
 * 大概率服务端没装 mod 或下线了 —— 切到 fallback 单色条对玩家是无感切换（数字 HP 没变）。
 */
public final class ClientTeamHeartsCache {

    /** 新鲜窗口：5 s 没收到 payload → 视作服务端失联，回落本地路径。 */
    private static final long FRESH_WINDOW_MS = 5_000L;

    private static volatile long lastPushMs = 0L;
    private static volatile Map<String, TeamHeartsPayload.Entry> snapshot = Collections.emptyMap();

    private ClientTeamHeartsCache() {}

    public static void onPayload(TeamHeartsPayload payload) {
        if (payload == null) return;
        var entries = payload.entries();
        Map<String, TeamHeartsPayload.Entry> map = new HashMap<>(Math.max(8, entries.size() * 2));
        for (TeamHeartsPayload.Entry e : entries) {
            if (e == null || e.name() == null || e.name().isEmpty()) continue;
            map.put(e.name(), e);
        }
        snapshot = Collections.unmodifiableMap(map);
        lastPushMs = System.currentTimeMillis();
    }

    public static boolean isFresh() {
        long last = lastPushMs;
        if (last == 0L) return false;
        return (System.currentTimeMillis() - last) < FRESH_WINDOW_MS;
    }

    /** 按玩家名查 entry，cache 失鲜或名字不存在时返回 {@code null}。 */
    public static TeamHeartsPayload.Entry lookup(String name) {
        if (name == null || name.isEmpty()) return null;
        if (!isFresh()) return null;
        return snapshot.get(name);
    }

    /** DISCONNECT / reset 时清缓存。 */
    public static void reset() {
        snapshot = Collections.emptyMap();
        lastPushMs = 0L;
    }
}
