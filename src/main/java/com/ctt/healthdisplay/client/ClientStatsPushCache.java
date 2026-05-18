package com.ctt.healthdisplay.client;

/**
 * v8.4.0 · 客户端 "服务端属性 push 新鲜度" 追踪器（轻量）。
 *
 * <h2>职责</h2>
 * <p>{@link com.ctt.healthdisplay.network.PlayerStatsPayload} 收到后由 receiver 直接灌进
 * {@link com.ctt.healthdisplay.health.StatsData#applyServerSnapshot}，<b>不</b>在本类里
 * 二次缓存数据。本类只承担"判断服务端最近是否推过 = 客户端是否应跳过
 * 自发 {@code /trigger ViewStats}"的开关角色。
 *
 * <h2>判定语义</h2>
 * <ul>
 *   <li>{@link #recordPush()}：每次收到 payload 都打一次时间戳；</li>
 *   <li>{@link #isFresh()}：最近 {@value #FRESH_WINDOW_MS} ms 内有过 push → fresh；</li>
 *   <li>{@link #reset()}：DISCONNECT / 切服时清零，避免切到无 mod 服务端后误认为还 fresh。</li>
 * </ul>
 *
 * <h2>fresh 窗口为何选 10 秒</h2>
 * <p>{@link com.ctt.healthdisplay.server.PlayerStatsPushBroadcaster} 默认推送间隔 20 tick (1 s)；
 * 服务端差量逻辑可能在静态场景持续静默几秒不重发（属性面板不变）。
 * 10 s 给"服务端在 5-9 s 静默"留出余量，超过 10 s 没动静大概率服务端没装 mod 或下线了。
 * {@link com.ctt.healthdisplay.CttHealthDisplay} 的 auto refresh 路径据此决定：fresh →
 * 跳过 {@code /trigger ViewStats}；非 fresh → 退到 v8.3.x 老路径自发命令。
 */
public final class ClientStatsPushCache {

    /** 新鲜窗口：10 s 没收到 push → 视作服务端没装 mod / 下线，切回客户端 chat 路径。 */
    private static final long FRESH_WINDOW_MS = 10_000L;

    private static volatile long lastPushMs = 0L;

    private ClientStatsPushCache() {}

    /** receiver 入口：每次收到 {@code PlayerStatsPayload} 调一次。 */
    public static void recordPush() {
        lastPushMs = System.currentTimeMillis();
    }

    /** 最近 10 s 内有过 push → fresh。 */
    public static boolean isFresh() {
        long last = lastPushMs;
        if (last == 0L) return false;
        return (System.currentTimeMillis() - last) < FRESH_WINDOW_MS;
    }

    /** DISCONNECT / 切服时清零。 */
    public static void reset() {
        lastPushMs = 0L;
    }
}
