package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.network.MobHealthPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * v8.3.0 · M7 · 服务端 → 客户端"怪物血量表"推送器。
 *
 * <h2>循环</h2>
 * <ul>
 *   <li>在 {@code END_SERVER_TICK} 上注册 {@link #tickPushIfDue}；每 {@value #PUSH_INTERVAL_TICKS}
 *       tick (4 Hz) 触发一次遍历，非 CTT 地图 (无 RedHearts / MaxHP objective) 直接跳过。</li>
 *   <li>per-player：以玩家位置为中心 {@value #SCAN_RADIUS} 格半径做 {@link ServerWorld#getOtherEntities}
 *       粗筛，读 {@code RedHearts} / {@code MaxHP} scoreboard，都为 0 的"无 CTT 数据 mob"
 *       (原版怪、漏打 scoreboard 的自定义实体等) 直接剔除。</li>
 *   <li>按距离平方升序排序，取前 {@value #MAX_ENTRIES_PER_PLAYER} 条；最近那一只标
 *       {@code targetted=true}，其余 false — 客户端 NEAREST 档和 ▶ 箭头就能锁到正确目标，
 *       不再受 vanilla bossbar 被 Boss champion 抢占所害。</li>
 *   <li>差量：与 {@link #LAST_SENT} 里上次推给该玩家的列表做值比较，完全相同时不重复发包；
 *       视野里没 CTT mob 时也只在"上次非空"时发一次空包，后续保持静默。</li>
 * </ul>
 *
 * <h2>性能</h2>
 * <p>每 tick ~10 玩家 × 96³ 格 box 内 ~30 LivingEntity × 2 次 scoreboard lookup，
 * 内部都是 {@link java.util.HashMap} O(1) 查询；实测 &lt; 0.5 ms 的挂钟开销。
 * 差量+空包吞噬让稳态下 0 mob 场景几乎 0 网络流量。
 *
 * <h2>服务端未装 mod 的回落</h2>
 * <p>本类只在服务端 entrypoint {@link CttStatsServer} 里注册；客户端如果连到
 * 纯 vanilla 服务端（或纯 datapack、未装该 mod）就收不到任何 {@link MobHealthPayload}，
 * {@link com.ctt.healthdisplay.client.ClientMobHealthCache#isFresh()} 始终 false，
 * 渲染路径自动回落到客户端 bossbar 解析链路 —— 老行为完全保留。
 */
public final class MobHealthBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-mob-health");

    /** 推送间隔 (tick)。4 Hz = 250 ms，对头顶 HP 条"够实时 + 不抖"是经验拍板值。 */
    private static final int PUSH_INTERVAL_TICKS = 5;

    /** 扫描半径 (格)。略大于 vanilla 默认实体同步距离 (32-48)，覆盖典型头顶条渲染范围。 */
    private static final double SCAN_RADIUS = 48.0;

    /** 单次 payload 最大 entry 数。32 条覆盖实战人群 mob 场景，超过截断最远的。 */
    private static final int MAX_ENTRIES_PER_PLAYER = 32;

    /** per-player 上次推送列表，用于差量判定。玩家下线时由 {@link #onPlayerDisconnect} 清理。 */
    private static final Map<UUID, List<MobHealthPayload.Entry>> LAST_SENT = new HashMap<>();

    private static int tickCounter = 0;

    private MobHealthBroadcaster() {}

    /** {@code END_SERVER_TICK} 钩子。到 {@value #PUSH_INTERVAL_TICKS} tick 就跑一次。 */
    public static void tickPushIfDue(MinecraftServer server) {
        if (++tickCounter < PUSH_INTERVAL_TICKS) return;
        tickCounter = 0;
        if (server == null) return;

        Scoreboard sb = server.getScoreboard();
        boolean hasRedHearts = ScoreboardReader.hasObjective(sb, "RedHearts");
        boolean hasMaxHp     = ScoreboardReader.hasObjective(sb, "MaxHP");
        // 非 CTT 地图：没有 RedHearts / MaxHP 任何一家 → 没数据可推，直接跳过。
        // 客户端 5 s 没收到包会自动回落老路径，纯 vanilla / 别的地图世界都不受影响。
        if (!hasRedHearts && !hasMaxHp) {
            if (!LAST_SENT.isEmpty()) LAST_SENT.clear();
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
                processPlayer(player, sb);
            } catch (Throwable t) {
                LOGGER.warn("[CTT MobHealth] process {} failed: {}",
                        player.getName().getString(), t.toString());
            }
        }

        // 兜底清理：DISCONNECT 钩子漏触发时的保底（例如服务器 reload / 直接杀进程）
        if (!LAST_SENT.isEmpty()) {
            Set<UUID> online = new HashSet<>();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                online.add(p.getUuid());
            }
            LAST_SENT.keySet().removeIf(uuid -> !online.contains(uuid));
        }
    }

    private static void processPlayer(ServerPlayerEntity player, Scoreboard sb) {
        ServerWorld world = player.getServerWorld();
        if (world == null) return;

        Vec3d pos = player.getPos();
        double boxSize = SCAN_RADIUS * 2;
        Box box = Box.of(pos, boxSize, boxSize, boxSize);

        List<Candidate> candidates = new ArrayList<>();
        for (Entity e : world.getOtherEntities(null, box, ent ->
                ent instanceof LivingEntity
                        && !(ent instanceof PlayerEntity)
                        && !(ent instanceof ArmorStandEntity)
                        && ent.isAlive()
                        && !ent.isRemoved())) {
            int hp = ScoreboardReader.readOrZero(sb, "RedHearts", e);
            int maxHp = ScoreboardReader.readOrZero(sb, "MaxHP", e);
            // 数据包没管控的实体（原版僵尸、自定义但未打 RedHearts 的 mob）完全跳过，
            // 避免头顶出现 0/0 的畸形条；客户端 vanilla 名牌继续负责显示。
            if (hp <= 0 && maxHp <= 0) continue;
            if (maxHp <= 0) maxHp = hp;  // 只写了 RedHearts 的自定义 mob：用当前值做 maxHp 兜底
            double dist = e.getPos().squaredDistanceTo(pos);
            candidates.add(new Candidate(e, hp, maxHp, dist));
        }

        if (candidates.isEmpty()) {
            maybeSendEmpty(player);
            return;
        }

        candidates.sort(Comparator.comparingDouble(Candidate::dist));
        if (candidates.size() > MAX_ENTRIES_PER_PLAYER) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_ENTRIES_PER_PLAYER));
        }

        UUID nearestUuid = candidates.get(0).entity.getUuid();

        List<MobHealthPayload.Entry> entries = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            Text display = c.entity.getDisplayName();
            String nameStr = display != null
                    ? display.getString()
                    : c.entity.getName().getString();
            int argb = 0;
            if (display != null && display.getStyle() != null) {
                TextColor tc = display.getStyle().getColor();
                if (tc != null) argb = tc.getRgb() | 0xFF000000;
            }
            boolean tgt = c.entity.getUuid().equals(nearestUuid);
            entries.add(new MobHealthPayload.Entry(
                    c.entity.getUuid(),
                    nameStr,
                    "",     // v8.3.0 初版不传 suffix（冠军徽章等）；后续迭代扩展
                    argb,
                    c.hp, c.maxHp,
                    tgt
            ));
        }

        // 差量：上次 snapshot 和这次 entry-by-entry 完全相同 → 不重发。
        // HashMap 里存 Entry record 列表，record 自带结构相等，但 list 比较我们自己做
        // 一次避免 rebuild 一个"值相同但内存位不同"的 list 触发误推。
        List<MobHealthPayload.Entry> prev = LAST_SENT.get(player.getUuid());
        if (entriesEqual(prev, entries)) return;
        LAST_SENT.put(player.getUuid(), entries);

        MobHealthPayload payload = new MobHealthPayload(MobHealthPayload.CURRENT_VERSION, entries);
        try {
            ServerPlayNetworking.send(player, payload);
        } catch (Throwable t) {
            LOGGER.warn("[CTT MobHealth] send to {} failed: {}",
                    player.getName().getString(), t.toString());
        }
    }

    /**
     * 玩家视野里当前没有 CTT mob。如果上一次推的也是空 / 从未推过，就保持静默，
     * 避免进 lobby / 过场动画时每 250 ms 空包闪烁。
     */
    private static void maybeSendEmpty(ServerPlayerEntity player) {
        List<MobHealthPayload.Entry> prev = LAST_SENT.get(player.getUuid());
        if (prev == null || prev.isEmpty()) return;
        LAST_SENT.put(player.getUuid(), Collections.emptyList());
        try {
            ServerPlayNetworking.send(player,
                    new MobHealthPayload(MobHealthPayload.CURRENT_VERSION, Collections.emptyList()));
        } catch (Throwable ignored) {
            // 空包失败无副作用 — 下次 4 Hz 还会重试
        }
    }

    private static boolean entriesEqual(List<MobHealthPayload.Entry> a,
                                        List<MobHealthPayload.Entry> b) {
        if (a == null) return b == null || b.isEmpty();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            MobHealthPayload.Entry x = a.get(i), y = b.get(i);
            if (!x.uuid().equals(y.uuid())) return false;
            if (x.hp() != y.hp() || x.maxHp() != y.maxHp()) return false;
            if (x.targetted() != y.targetted()) return false;
            if (x.nameColor() != y.nameColor()) return false;
            if (!Objects.equals(x.name(), y.name())) return false;
            if (!Objects.equals(x.suffix(), y.suffix())) return false;
        }
        return true;
    }

    /** 客户端 DISCONNECT 事件的服务端侧钩子，防止 LAST_SENT 泄漏占用同 UUID 重连后残留。 */
    public static void onPlayerDisconnect(UUID uuid) {
        LAST_SENT.remove(uuid);
    }

    private record Candidate(Entity entity, int hp, int maxHp, double dist) {}
}
