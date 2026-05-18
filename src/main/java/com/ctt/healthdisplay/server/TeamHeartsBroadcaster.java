package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.config.ServerConfig;
import com.ctt.healthdisplay.network.TeamHeartsPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * v8.4.0 · 服务端 → 全在线玩家 "全队四色心" 推送器。
 *
 * <h2>循环</h2>
 * <ul>
 *   <li>{@code END_SERVER_TICK} 钩子，每 {@link ServerConfig#serverPushTeamHeartsIntervalTicks}
 *       tick 触发一次（默认 4 tick = 5 Hz），扫所有在线玩家的
 *       {@code RedHearts / SoulHearts / BlackHearts / BlueHearts / MaxHP / Lives} 6 个 objective，
 *       拼一份 {@link TeamHeartsPayload}，全量广播给每个在线玩家。</li>
 *   <li>非 CTT 地图（无 RedHearts objective）整轮跳过。</li>
 *   <li>差量：上次构造的 entries 字节级一致就不重发，避免静态场景每秒 ~24 包冗余流量。</li>
 * </ul>
 *
 * <h2>设计</h2>
 * <p>区别于 {@link MobHealthBroadcaster} 的 per-player 视野裁剪（每人收到的是不同子集），
 * 本类是<b>全局广播</b>——所有在线玩家收到同一份 entries（因为客户端要查"队友 X 当前心数"，
 * 与位置无关）。因此差量判定只需要一份全局 {@link #lastSnapshot}，不是 per-player Map。
 *
 * <h2>性能（4 人队 5 Hz）</h2>
 * <ul>
 *   <li>每 tick：4 × 6 = 24 次 {@code ScoreboardReader.readOrZero} (~0.24 µs)</li>
 *   <li>每 5 Hz：1.2 µs/s 服务端开销，可忽略</li>
 *   <li>每秒 5 × 4 = 20 包，每包 ~120 字节 = 2.4 KB/s 总流量</li>
 *   <li>稳态（玩家心数无变动）差量吞噬：0 流量</li>
 * </ul>
 *
 * <h2>无 mod 服务端的兜底</h2>
 * <p>客户端 {@link com.ctt.healthdisplay.client.ClientTeamHeartsCache#isFresh()} 持续 false →
 * {@link com.ctt.healthdisplay.health.HealthData#parseTeamBar} 走 v8.3.x 老路径（bossbar 队伍
 * 文本正则 + 本地 scoreboard 读 Lives），队友头顶仍是单色 OVERFLOW_COLORS 多槽条。
 */
public final class TeamHeartsBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-team-hearts");

    /** 全局上次广播的 entries snapshot；非 per-player —— 所有客户端收的是同一份。 */
    private static volatile List<TeamHeartsPayload.Entry> lastSnapshot = Collections.emptyList();

    private static int tickCounter = 0;

    /**
     * 上次实际发包的挂钟时间戳。差量命中后即便不发包也保持此值不动，
     * {@link #tickPushIfDue} 检测到距上次发包 ≥ {@value #KEEPALIVE_MS} 就强制重发上次
     * snapshot —— 客户端 {@link com.ctt.healthdisplay.client.ClientTeamHeartsCache#isFresh}
     * 的 5 s 失鲜窗口需要持续刷新，否则数据稳定不变时客户端会因为 5 s 没收到包翻 stale，
     * 队友头顶 4 色心条退回 v8.3.x 单色多槽（用户反馈 2026-05-18）。
     */
    private static long lastSendMs = 0L;

    /**
     * 数据无变化时的 keep-alive 强制重发周期（ms）。
     *
     * <p>取 3 s = 客户端 {@code FRESH_WINDOW_MS=5_000} 的 60%，留 2 s 抖动余量
     * （TCP 重传 + 网络延迟最差也 ≤ 2 s）。
     *
     * <p>稳态成本：4 人队场景下从"差量恒静默"变成"每 3 s 重发一次 ~120 字节"
     * = 40 B/s 流量代价，可忽略；换得"队友 4 色心 HUD 永不闪回单色"的视觉稳定性。
     */
    private static final long KEEPALIVE_MS = 3_000L;

    private TeamHeartsBroadcaster() {}

    /** {@code END_SERVER_TICK} 钩子。 */
    public static void tickPushIfDue(MinecraftServer server) {
        if (server == null) return;
        ServerConfig cfg = ServerConfig.INSTANCE;
        if (!cfg.serverPushTeamHeartsEnabled) {
            if (!lastSnapshot.isEmpty()) lastSnapshot = Collections.emptyList();
            return;
        }
        int interval = Math.max(1, cfg.serverPushTeamHeartsIntervalTicks);
        if (++tickCounter < interval) return;
        tickCounter = 0;

        Scoreboard sb = server.getScoreboard();
        // 非 CTT 地图：没 RedHearts objective → 跳过
        if (!ScoreboardReader.hasObjective(sb, "RedHearts")) {
            if (!lastSnapshot.isEmpty()) {
                lastSnapshot = Collections.emptyList();
                // 广播一次空包，让客户端 cache 失鲜回落，避免老队员下线后头顶残留四色心
                broadcastEmpty(server);
            }
            return;
        }

        List<ServerPlayerEntity> online = server.getPlayerManager().getPlayerList();
        List<TeamHeartsPayload.Entry> entries = new ArrayList<>(online.size());
        for (ServerPlayerEntity p : online) {
            try {
                int red   = ScoreboardReader.readOrZero(sb, "RedHearts",   p);
                int soul  = ScoreboardReader.readOrZero(sb, "SoulHearts",  p);
                int black = ScoreboardReader.readOrZero(sb, "BlackHearts", p);
                int blue  = ScoreboardReader.readOrZero(sb, "BlueHearts",  p);
                int maxHp = ScoreboardReader.readOrZero(sb, "MaxHP",       p);
                int lives = ScoreboardReader.readOrZero(sb, "Lives",       p);
                // 全 0 玩家（未参与本局 / 还在 lobby）也带上，否则切局时客户端 cache 不会
                // 失鲜 → 老数据残留。带上让客户端能看到他确实有 entry 但全 0。
                entries.add(new TeamHeartsPayload.Entry(
                        p.getName().getString(),
                        red, soul, black, blue, maxHp, lives));
            } catch (Throwable t) {
                LOGGER.warn("[CTT TeamHearts] read {} failed: {}",
                        p.getName().getString(), t.toString());
            }
        }

        // 差量 + keep-alive：完全一致且距上次发包未超 KEEPALIVE_MS 就跳过。
        // 超过 KEEPALIVE_MS 还没发包 → 即使数据不变也强制重发，刷新客户端的 fresh 窗口，
        // 避免稳态下 5 s 后客户端 cache 失鲜让队友 4 色心 HUD 退回单色（v8.4.1 fix）。
        boolean unchanged = entriesEqual(lastSnapshot, entries);
        long nowMs = System.currentTimeMillis();
        boolean keepaliveOverdue = (nowMs - lastSendMs) >= KEEPALIVE_MS;
        if (unchanged && !keepaliveOverdue) return;
        lastSnapshot = Collections.unmodifiableList(entries);
        lastSendMs = nowMs;

        TeamHeartsPayload payload = new TeamHeartsPayload(
                TeamHeartsPayload.CURRENT_VERSION, lastSnapshot);
        for (ServerPlayerEntity p : online) {
            try {
                ServerPlayNetworking.send(p, payload);
            } catch (Throwable t) {
                LOGGER.warn("[CTT TeamHearts] send to {} failed: {}",
                        p.getName().getString(), t.toString());
            }
        }
    }

    /** 玩家 JOIN 时立即推一次当前 snapshot，避免新加入者等 4 tick 才看到队友四色心。 */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        if (player == null) return;
        ServerConfig cfg = ServerConfig.INSTANCE;
        if (!cfg.serverPushTeamHeartsEnabled) return;
        if (lastSnapshot.isEmpty()) return;
        try {
            ServerPlayNetworking.send(player, new TeamHeartsPayload(
                    TeamHeartsPayload.CURRENT_VERSION, lastSnapshot));
        } catch (Throwable t) {
            LOGGER.warn("[CTT TeamHearts] initial push to {} failed: {}",
                    player.getName().getString(), t.toString());
        }
    }

    /** 没装 mod 的地图切到 CTT 地图、或 RedHearts objective 消失后清缓存广播一次空包。 */
    private static void broadcastEmpty(MinecraftServer server) {
        TeamHeartsPayload empty = new TeamHeartsPayload(
                TeamHeartsPayload.CURRENT_VERSION, Collections.emptyList());
        // 让 keep-alive 计时器也归零，避免下一轮 tickPushIfDue 误以为该重发空包
        lastSendMs = System.currentTimeMillis();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            try {
                ServerPlayNetworking.send(p, empty);
            } catch (Throwable ignored) {
                // 空包失败无副作用 — 下次 tick 还会重试
            }
        }
    }

    private static boolean entriesEqual(List<TeamHeartsPayload.Entry> a,
                                         List<TeamHeartsPayload.Entry> b) {
        if (a == null) return b == null || b.isEmpty();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            TeamHeartsPayload.Entry x = a.get(i), y = b.get(i);
            if (!Objects.equals(x.name(), y.name())) return false;
            if (x.redHearts()   != y.redHearts())   return false;
            if (x.soulHearts()  != y.soulHearts())  return false;
            if (x.blackHearts() != y.blackHearts()) return false;
            if (x.blueHearts()  != y.blueHearts())  return false;
            if (x.maxHp()       != y.maxHp())       return false;
            if (x.lives()       != y.lives())       return false;
        }
        return true;
    }
}
