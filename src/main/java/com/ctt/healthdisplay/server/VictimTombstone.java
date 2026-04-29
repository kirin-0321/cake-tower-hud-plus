package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.config.ServerConfig;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v6.4.1 · 死亡扫描器（方案 C · candidate-driven 版本）。
 *
 * <h2>为什么改写（v6.4.0 → v6.4.1）</h2>
 * <p>v6.4.0 依赖 {@code world.iterateEntities()} + {@code prevSnap} diff 识别消失：
 * <ul>
 *   <li>Fabric 1.21.4 的 {@code ServerWorld.iterateEntities()} 会漏掉部分区块的实体，
 *       导致僵尸根本没进过 {@code prevSnap}，diff 永远不触发</li>
 *   <li>即使扫到，也要求怪物至少存活一整 tick 才能被首次登记——
 *       但 {@code /kill @e[scores={RedHearts=..0}]} 可能让怪物在同一 tick 内就消失</li>
 * </ul>
 *
 * <h2>新策略：候选驱动（candidate-driven）</h2>
 * <ol>
 *   <li>归属入口（{@link AttackerProbe#recordFromDamageShower}）每次写 {@link VictimLethalCandidate}
 *       时，victim 的 UUID 就进入了"近期吃过伤害"的候选池</li>
 *   <li>Tombstone 每 END_SERVER_TICK 遍历候选池，对每个 UUID
 *       用 {@link MinecraftServer#getWorlds()} + {@code world.getEntity(uuid)} 查</li>
 *   <li>查不到 / {@link Entity#isRemoved()} → 认定"真死亡"</li>
 *   <li>用 candidate 本身作为 killer 来源（最近一击覆盖写 = 最后一击）；
 *       若 candidate 的归属层未分类，再走 {@link VictimLastHitter}({@code AllDMG}) 兜底</li>
 * </ol>
 *
 * <h2>优点</h2>
 * <ul>
 *   <li>完全绕开 {@code iterateEntities()} 可能漏实体的问题</li>
 *   <li>无需"怪物先活一 tick"——candidate 在伤害同一 tick 即写入</li>
 *   <li>候选池只包含近期吃过归属伤害的 victim（通常 &lt; 20），性能影响可忽略</li>
 *   <li>长 TTL 的 candidate（30 s）覆盖免死救回、长战斗、延迟 kill 等场景</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * <p>只在 END_SERVER_TICK 主线程被调用；内部不持有长生命周期状态，
 * {@link VictimLethalCandidate#forEach} 内部已拷贝防并发修改。
 *
 * @see VictimLethalCandidate
 * @see VictimDamageContributors
 * @see PlayerKillStats
 */
public final class VictimTombstone {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-kill");

    private VictimTombstone() {}

    /** 诊断日志的间隔（tick）。每 20s 输出一次候选池状态，帮助确认扫描在工作。 */
    private static final long DIAG_INTERVAL_TICKS = 400;
    private static long lastDiagTick = 0;

    /**
     * 每 END_SERVER_TICK 调用。在 {@code AttackerProbe.gcTick} 之前，
     * 保证 candidate 不会被提前回收。
     */
    public static void tickEnd(MinecraftServer server) {
        if (server == null) return;
        long tick = DamageProbe.currentTick();

        // 先把所有"消失"的 victim 收集起来，避免遍历 candidate 时对其做 forget 操作。
        List<DiedVictim> dead = new ArrayList<>();
        final int[] aliveCount = {0};
        VictimLethalCandidate.forEach((uuid, entry) -> {
            if (uuid == null || entry == null) return;
            // 本帧 candidate 刚写入，不算（至少让实体过完本 tick 才判死）
            if (entry.age(tick) < 1) {
                aliveCount[0]++;
                return;
            }
            Entity victim = findEntity(server, uuid, entry.worldKey());
            if (victim == null || victim.isRemoved()) {
                dead.add(new DiedVictim(uuid, entry, captureIdentity(victim, uuid)));
            } else {
                aliveCount[0]++;
            }
        });

        // 结算
        for (DiedVictim d : dead) {
            settleKill(server, d, tick);
        }

        // 诊断：每 20s 输出一次 tombstone 状态（仅当候选池非空时）
        if (tick - lastDiagTick >= DIAG_INTERVAL_TICKS) {
            int total = aliveCount[0] + dead.size();
            if (total > 0 || VictimLethalCandidate.size() > 0) {
                LOGGER.info("[CTT Kill/diag] tick={} candidates={} alive={} died_this_scan={}",
                        tick, VictimLethalCandidate.size(), aliveCount[0], dead.size());
            }
            lastDiagTick = tick;
        }
    }

    /**
     * 按 UUID 找实体。
     *
     * <p>v8.0.0 性能：优先用 candidate 写入时记录的 {@code worldKey} 直接查单 world
     * （vanilla 的 worldless candidate / 跨维度传送的 victim 是少数边缘 case，落到
     * 全维度兜底）。CTT 地图常驻多 world（OW + 自定义维度），单 world 反查比
     * {@code for (ServerWorld w : server.getWorlds())} 全循环节省 N-1 次 hash 查询。
     */
    private static Entity findEntity(MinecraftServer server, UUID uuid, RegistryKey<World> worldKey) {
        if (worldKey != null) {
            ServerWorld w = server.getWorld(worldKey);
            if (w != null) {
                Entity e = w.getEntity(uuid);
                if (e != null) return e;
                // 命中 worldKey 但 entity 不在 → 真消失（或跨维度传送）。先回退兜底，
                // 因为跨维度传送很罕见但确实可能发生。
            }
        }
        for (ServerWorld w : server.getWorlds()) {
            Entity e = w.getEntity(uuid);
            if (e != null) return e;
        }
        return null;
    }

    /**
     * 取实体 name / typeId / boss 标志。若实体已被 kill（null），回退到 UUID 片段。
     * 注意：被 kill 的实体往往仍可读 type / tags，因此优先用实时信息。
     */
    private static Identity captureIdentity(Entity victim, UUID uuid) {
        if (victim != null) {
            String name = victim.getName().getString();
            String typeId = Registries.ENTITY_TYPE.getId(victim.getType()).toString();
            boolean boss = victim.getCommandTags().contains("Boss");
            return new Identity(name, typeId, boss);
        }
        // victim 找不到（已完全从世界清掉）：用 UUID 后 8 位作名字兜底
        String fallback = uuid.toString();
        return new Identity(fallback.substring(Math.max(0, fallback.length() - 8)),
                "<unknown>", false);
    }

    /**
     * 结算一次真死亡：确定 killer / assists，写 stats，可选广播。
     */
    private static void settleKill(MinecraftServer server, DiedVictim d, long tick) {
        VictimLethalCandidate.Entry cand = d.entry;
        UUID killerUuid = cand.attackerUuid();
        String killerLabel = cand.attackerLabel();
        AttackerProbe.Layer layer = cand.layer();

        // candidate 若归属失败（null）或落在未分类层：兜底查 VictimLastHitter(AllDMG) 近期硬层。
        if (killerUuid == null || !AttackerProbe.isAttributionClassified(layer)) {
            VictimLastHitter.Entry last = VictimLastHitter.lookup(
                    d.uuid, AttackerProbe.ALL_DMG_OBJECTIVE, tick);
            if (last != null && last.attackerUuid() != null) {
                killerUuid = last.attackerUuid();
                killerLabel = last.attackerLabel();
                layer = AttackerProbe.Layer.L8_LAST_HITTER; // 标记为"续归属兜底"（v6.5.2 由 L7 降为 L8）
            }
        }

        PlayerKillStats.VictimKind kind = d.identity.boss
                ? PlayerKillStats.VictimKind.BOSS
                : PlayerKillStats.VictimKind.MOB;

        boolean classified = killerUuid != null && AttackerProbe.isAttributionClassified(layer);

        List<VictimDamageContributors.ContribRow> contribs =
                VictimDamageContributors.getContributors(d.uuid);
        List<VictimDamageContributors.ContribRow> assists = new ArrayList<>();
        if (classified) {
            for (VictimDamageContributors.ContribRow c : contribs) {
                if (c.playerUuid().equals(killerUuid)) continue;
                assists.add(c);
            }
        }

        if (classified) {
            PlayerKillStats.recordKill(killerUuid, trimPlayerLabel(killerLabel),
                    null, layer, kind, tick);
            for (VictimDamageContributors.ContribRow a : assists) {
                PlayerKillStats.recordAssist(a.playerUuid(), a.playerName(), null, tick);
            }
        } else {
            PlayerKillStats.recordUnattributedKill(null, kind, tick);
        }

        // v8.x · 双路由：全局兜底（JSON 启用）→ 全服广播；否则仅发 per-player 订阅者。
        // 任一开启就构造 Text。击杀事件本身稀疏，构造成本可忽略。
        boolean killGlobal = ServerConfig.INSTANCE.broadcastKillsInChat;
        boolean killAnySub = com.ctt.healthdisplay.server.command.BroadcastSubscribers
                .hasAnySubscriber(com.ctt.healthdisplay.server.command.BroadcastSubscribers.Channel.KILL);
        if (killGlobal || killAnySub) {
            net.minecraft.text.Text msg = buildChatLine(d.identity, killerUuid, killerLabel, layer, kind, assists, classified);
            if (killGlobal) {
                server.getPlayerManager().broadcast(msg, false);
            } else {
                com.ctt.healthdisplay.server.command.BroadcastSubscribers.sendTo(
                        server, com.ctt.healthdisplay.server.command.BroadcastSubscribers.Channel.KILL, msg);
            }
        }

        LOGGER.info("[CTT Kill] victim={} ({}) killer={} layer={} kind={} assists={} contribs={} age={}t tick={}",
                d.identity.name, d.identity.typeId,
                classified ? trimPlayerLabel(killerLabel) : "<unattributed>",
                layer, kind, assists.size(), contribs.size(), cand.age(tick), tick);

        VictimLethalCandidate.forget(d.uuid);
        VictimDamageContributors.forget(d.uuid);
    }

    private static Text buildChatLine(Identity victim, UUID killerUuid, String killerLabel,
                                      AttackerProbe.Layer layer,
                                      PlayerKillStats.VictimKind kind,
                                      List<VictimDamageContributors.ContribRow> assists,
                                      boolean classified) {
        MutableText msg = Text.literal("[\u51fb\u6740] ").formatted(Formatting.GOLD); // 击杀
        if (classified && killerUuid != null) {
            msg.append(Text.literal(trimPlayerLabel(killerLabel)).formatted(Formatting.GREEN));
        } else {
            msg.append(Text.literal("???").formatted(Formatting.DARK_RED));
        }
        msg.append(Text.literal(" \u51fb\u6740\u4e86 ").formatted(Formatting.GRAY)); // 击杀了
        msg.append(Text.literal(victim.name == null ? "?" : victim.name).formatted(Formatting.WHITE));
        if (kind == PlayerKillStats.VictimKind.BOSS) {
            msg.append(Text.literal(" [Boss]").formatted(Formatting.DARK_PURPLE));
        }
        if (classified && !assists.isEmpty()) {
            msg.append(Text.literal("  \u52a9\u653b: ").formatted(Formatting.DARK_GRAY)); // 助攻
            int shown = 0;
            for (VictimDamageContributors.ContribRow c : assists) {
                if (shown >= 4) {
                    msg.append(Text.literal(String.format(" +%d", assists.size() - 4))
                            .formatted(Formatting.DARK_GRAY));
                    break;
                }
                if (shown > 0) msg.append(Text.literal(", ").formatted(Formatting.DARK_GRAY));
                msg.append(Text.literal(c.playerName()).formatted(Formatting.AQUA));
                shown++;
            }
        } else if (!classified) {
            msg.append(Text.literal("  \u672a\u5206\u7c7b").formatted(Formatting.DARK_RED)); // 未分类
        }
        msg.append(Text.literal("  " + layer.shortTag()).formatted(Formatting.DARK_AQUA));
        return msg;
    }

    private static String trimPlayerLabel(String label) {
        if (label == null) return "?";
        if (label.startsWith("Player(") && label.endsWith(")")) {
            return label.substring(7, label.length() - 1);
        }
        return label;
    }

    /** 死亡 victim 的中间收集结构（candidate loop 不能直接 forget）。 */
    private record DiedVictim(UUID uuid, VictimLethalCandidate.Entry entry, Identity identity) {}

    /** 用于广播 / 日志的 victim 基础信息。 */
    private static final class Identity {
        final String name;
        final String typeId;
        final boolean boss;
        Identity(String name, String typeId, boolean boss) {
            this.name = name;
            this.typeId = typeId;
            this.boss = boss;
        }
    }

    /** 诊断用。 */
    public static int candidatePoolSize() { return VictimLethalCandidate.size(); }
}
