package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.config.ServerConfig;
import com.ctt.healthdisplay.network.PlayerStatsPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * v8.4.0 · 服务端 → 每个玩家 "属性面板" 推送器。
 *
 * <h2>循环</h2>
 * <ul>
 *   <li>在 {@code END_SERVER_TICK} 上注册 {@link #tickPushIfDue}；
 *       每 {@link ServerConfig#serverPushStatsIntervalTicks} tick 触发一次。
 *       默认 20 tick (1 Hz)，与 v8.3.x 客户端默认 {@code autoRefreshIntervalSeconds=30}
 *       相比 fresher 30 倍，但服务端走的是直接读 scoreboard 拼 Text 路径，
 *       完全不占用 chat 通道 & 不触发 datapack {@code function view_stats.mcfunction}
 *       (那条路径每次执行 0.5~1.5 ms / 玩家)。</li>
 *   <li>非 CTT 地图（无 {@code RedHearts} objective）整轮跳过。</li>
 *   <li>per-player 调 {@link ViewStatsBuilder#build}，与 {@link #LAST_SENT} 上次结果比较 ——
 *       完全一致就不重复发包，避免静态属性面板每秒多余 24+ KB 流量。</li>
 * </ul>
 *
 * <h2>无 mod 服务端的兜底</h2>
 * <p>本类只在服务端 entrypoint {@link CttStatsServer} 注册；纯 vanilla 服务端不会推
 * {@link PlayerStatsPayload}，客户端 {@link com.ctt.healthdisplay.client.ClientStatsPushCache#isFresh()}
 * 持续 false，{@link com.ctt.healthdisplay.CttHealthDisplay} 自动 fallback 到 v8.3.x 老路径
 * —— 客户端自发 {@code /trigger ViewStats}，老行为完全保留。
 *
 * <h2>性能</h2>
 * <p>4 人队 × 1 Hz × {@link ViewStatsBuilder#build} (~25 µs/玩家) ≈ 0.1 ms/s 服务端开销，
 * 实测可推到 5 Hz 仍 &lt; 1 ms/s，无需关心 TPS。
 */
public final class PlayerStatsPushBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-player-stats");

    /** per-player 上次推过的 snapshot，用于差量判定。{@link #onPlayerDisconnect} 清理。 */
    private static final Map<UUID, Snapshot> LAST_SENT = new HashMap<>();

    private static int tickCounter = 0;

    private PlayerStatsPushBroadcaster() {}

    /** {@code END_SERVER_TICK} 钩子。到 {@code serverPushStatsIntervalTicks} 触发一次。 */
    public static void tickPushIfDue(MinecraftServer server) {
        if (server == null) return;
        ServerConfig cfg = ServerConfig.INSTANCE;
        if (!cfg.serverPushStatsEnabled) {
            if (!LAST_SENT.isEmpty()) LAST_SENT.clear();
            return;
        }
        int interval = Math.max(1, cfg.serverPushStatsIntervalTicks);
        if (++tickCounter < interval) return;
        tickCounter = 0;

        // 非 CTT 地图：没 RedHearts objective → 跳过（datapack 没维护数据，构造没意义）。
        if (!ScoreboardReader.hasObjective(server.getScoreboard(), "RedHearts")) {
            if (!LAST_SENT.isEmpty()) LAST_SENT.clear();
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                processPlayer(player);
            } catch (Throwable t) {
                LOGGER.warn("[CTT PlayerStats] process {} failed: {}",
                        player.getName().getString(), t.toString());
            }
        }

        // 兜底清理：DISCONNECT 钩子漏触发时的保底
        if (!LAST_SENT.isEmpty()) {
            Set<UUID> online = new HashSet<>();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                online.add(p.getUuid());
            }
            LAST_SENT.keySet().removeIf(uuid -> !online.contains(uuid));
        }
    }

    private static void processPlayer(ServerPlayerEntity player) {
        PlayerStatsPayload payload = ViewStatsBuilder.build(player);
        Snapshot now = Snapshot.of(payload);
        Snapshot prev = LAST_SENT.get(player.getUuid());
        if (prev != null && prev.equalsSnapshot(now)) return;
        LAST_SENT.put(player.getUuid(), now);
        try {
            ServerPlayNetworking.send(player, payload);
        } catch (Throwable t) {
            LOGGER.warn("[CTT PlayerStats] send to {} failed: {}",
                    player.getName().getString(), t.toString());
        }
    }

    /**
     * 玩家 JOIN 时强制推一次完整 payload，让客户端不必等到下一个 tick 才有 fresh 数据。
     * 单独入口（不走 tickPushIfDue 的 throttle / 差量）。
     */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        if (player == null) return;
        ServerConfig cfg = ServerConfig.INSTANCE;
        if (!cfg.serverPushStatsEnabled) return;
        try {
            PlayerStatsPayload payload = ViewStatsBuilder.build(player);
            LAST_SENT.put(player.getUuid(), Snapshot.of(payload));
            ServerPlayNetworking.send(player, payload);
        } catch (Throwable t) {
            LOGGER.warn("[CTT PlayerStats] initial push to {} failed: {}",
                    player.getName().getString(), t.toString());
        }
    }

    /** 客户端 DISCONNECT 服务端侧钩子，防止 LAST_SENT 泄漏占用同 UUID 重连后残留。 */
    public static void onPlayerDisconnect(UUID uuid) {
        LAST_SENT.remove(uuid);
    }

    /**
     * 差量判定专用 snapshot：4 心 + lines。lines 用 {@link Text#getString} 字面量做比对，
     * 与 {@link MobHealthBroadcaster#textEquivalent} 同款逻辑——同 tick 同玩家同分数构造出的
     * MutableText 引用不同但字面量相同，按字面量比对避免每 tick 误推 ~3 KB 包。
     */
    private record Snapshot(int red, int soul, int black, int blue, List<String> lineStrings) {

        static Snapshot of(PlayerStatsPayload p) {
            List<Text> lines = p.lines();
            List<String> strs = new java.util.ArrayList<>(lines.size());
            for (Text t : lines) strs.add(t == null ? "" : t.getString());
            return new Snapshot(p.redHearts(), p.soulHearts(), p.blackHearts(), p.blueHearts(),
                    java.util.Collections.unmodifiableList(strs));
        }

        boolean equalsSnapshot(Snapshot other) {
            if (other == null) return false;
            return red == other.red && soul == other.soul
                    && black == other.black && blue == other.blue
                    && Objects.equals(lineStrings, other.lineStrings);
        }
    }
}
