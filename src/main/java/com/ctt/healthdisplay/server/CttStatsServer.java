package com.ctt.healthdisplay.server;

import com.ctt.healthdisplay.network.StagePayload;
import com.ctt.healthdisplay.network.StatsSnapshotPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stat.Stat;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端入口（integrated server / dedicated server 都会加载）。
 *
 * <ul>
 *   <li>v6.0.1：挂 {@code END_SERVER_TICK} 驱动 {@link DamageProbe#flushTick}</li>
 *   <li>v6.0.4：缓存 MinecraftServer 引用供 Mixin 反查 world</li>
 *   <li>v6.0.5：挂 {@code END_SERVER_TICK} 清理 {@link PlayerHitLog} / {@link PlayerFireLog}</li>
 *   <li>v6.2.0：启动时加载 {@link WeaponDamageRegistry}；每 tick 刷新 {@link PlayerInventoryIndex}</li>
 * </ul>
 */
public class CttStatsServer implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stats-server");

    /** 当前正在运行的服务器实例。Mixin 回调通过 {@link #getServer()} 取 world。 */
    private static volatile MinecraftServer currentServer;

    public static MinecraftServer getServer() {
        return currentServer;
    }

    @Override
    public void onInitialize() {
        LOGGER.info("[CTT Stats v6.2.0] server entrypoint loaded. Attribution stack: "
                + "L1 weapon+fire | L2 stat tick | L3/L4 marker 3m/40m | L5 stat window | "
                + "L6 fire window | L7 last-hitter carry | L8 summon fallback | L9 none.");

        // v6.6.4 · M5 · 服务端独立 config（聊天广播开关 + 大额过滤黑名单 + RedHearts 数据源）。
        // 文件不存在时会先尝试从老的 ctt-health-display.json 迁移老 key，再写一份新 JSON。
        // 走 "main" entrypoint 在集成 + 专用两边都会跑，保证：
        //   - 专用服务器读自己的 server config
        //   - 集成单机 ConfigScreen 也能即时读写同一份 INSTANCE
        com.ctt.healthdisplay.config.ServerConfig.load();
        LOGGER.info("[CTT Stats] server config loaded (broadcastDamage={}, broadcastKills={}, broadcastTaken={}, useRedHearts={})",
                com.ctt.healthdisplay.config.ServerConfig.INSTANCE.broadcastDamageInChat,
                com.ctt.healthdisplay.config.ServerConfig.INSTANCE.broadcastKillsInChat,
                com.ctt.healthdisplay.config.ServerConfig.INSTANCE.broadcastTakenInChat,
                com.ctt.healthdisplay.config.ServerConfig.INSTANCE.useRedHeartsTally);

        // v8.x · 注册 /ctthd broadcast ... 运行时开关命令。requires=true，
        // 任意权限玩家都能用，方便服务器现场临时开广播诊断。
        com.ctt.healthdisplay.server.command.BroadcastToggleCommand.register();

        // 加载武器-伤害注册表（失败只打日志，不阻断服务端启动）
        WeaponDamageRegistry.load();
        LOGGER.info("[CTT Stats] weapon registry loaded: {} weapons", WeaponDamageRegistry.weaponCount());

        // v6.6.0 hotfix · 关卡名表也在服务端 load 一次（idempotent）：
        // StageReportBroadcaster 拼"T1F1 · 紫晶迷宫"标题时不依赖客户端 mod 入口先跑。
        // 注意：StageNameRegistry 内部 currentLangCode() 会调 MinecraftClient，但仅在
        // 不带 preferLang 的重载里走那条路径；服务端只调 (kind, id, "zh_cn") 入口，
        // 故该类型即便在 dedicated server 上加载也只触及 lookup 数据查询。
        com.ctt.healthdisplay.hud.StageNameRegistry.load();

        // v6.5.6 · 注册 S2C 关卡位置 payload。集成服务器和专用服务器都走这条路径，
        // 与客户端 CttHealthDisplay.onInitializeClient 里的注册必须双方都执行才能完成
        // 协商；fabric-networking-api 的 registerS2C 会保证两端 ID 一致。
        PayloadTypeRegistry.playS2C().register(StagePayload.ID, StagePayload.CODEC);

        // v6.6.5 · M6 · 注册 stats 全量快照 S2C payload。集成服务器场景下客户端
        // 仍优先走 ClientStatsCache 的 isIntegrated() 直读分支（零开销零行为变化），
        // 这条链路主要给专用服务器 / LAN 远程客户端用，但集成服务器也会推送
        // —— 让 host 端的客户端代码与远程客户端走同一条数据通路，便于回归测试。
        PayloadTypeRegistry.playS2C().register(StatsSnapshotPayload.ID, StatsSnapshotPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            LOGGER.info("[CTT Stats] server reference cached.");
            // v6.3.7 · autostart vanilla used:bow / used:crossbow / used:trident objective，
            // 地图可能没注册这些 stat；不注册的话 scoreboard 就不会 +1，L7b 收不到信号。
            ensureBowObjectives(server);
            // v6.6.0 hotfix · 服务器启动 → 默认开始采集（不再依赖 L 键 ▶ 按钮）。
            // 三家 stats 的 live=true 由 PlayerDamageStats.start() 串联开启；
            // 立刻 setFrozen(true) 让 ⏱ 计时器停在 0（无玩家或都在大厅时不空跑）；
            // 玩家进战斗关 / 休息室时 StageBoundaryDispatcher 会自动解冻；
            // 进 T1F1 时 dispatcher 会自动 clear 实现"开新局清零"语义。
            PlayerDamageStats.start();
            PlayerDamageStats.setFrozen(true);
            // v6.6.1 · M2 · 持久化层：先 start() 重置完，再尝试从 NBT 还原。
            // 如果存档存在就把 live/frozen/数据全部覆盖回上次的状态；
            // 文件不存在 / 损坏 → 维持 start() 后的"全 0 + frozen"。
            StatsPersistenceManager.load(server);
            LOGGER.info("[CTT Stats] auto-started session at server start (live=true, frozen=true; waiting for first stage entry).");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // v6.6.1 · M2 · 收尾写盘：避免崩服务器 / 玩家拔电丢最近一段数据。
            try { StatsPersistenceManager.onServerStopping(server); }
            catch (Throwable t) { LOGGER.warn("[CTT Stats] persistence STOP write failed: {}", t.toString()); }
            currentServer = null;
        });

        // v6.5.6 · 玩家加入 / 断线时维护 StageProbeServer 缓存，确保首屏立即推送。
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            StageProbeServer.onJoin(handler.getPlayer());
            // v6.6.5 · M6 · 玩家加入立即推一次 stats 快照（首屏 baseline，
            // 不用等下一个 1Hz 心跳；HUD / K 表起步即有数据）。
            StatsSnapshotBroadcaster.pushTo(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            java.util.UUID uuid = handler.getPlayer().getUuid();
            StageProbeServer.onDisconnect(uuid);
            // v8.x · 玩家断线时清理 per-player 广播订阅，避免泄漏到下次同 UUID 重连后还残留旧订阅
            com.ctt.healthdisplay.server.command.BroadcastSubscribers.onPlayerDisconnect(uuid);
        });

        ServerTickEvents.END_SERVER_TICK.register(DamageProbe::flushTick);
        ServerTickEvents.END_SERVER_TICK.register(PlayerInventoryIndex::tickRefresh);
        // v6.5.0 · 玩家承伤扫描：读地图 DamageTook scoreboard。放在 gcTick 之前无严格要求，
        // 但保持和其它 probe 的集中注册风格。
        ServerTickEvents.END_SERVER_TICK.register(PlayerTakenProbe::tickEnd);
        // v6.4.0 · 击杀扫描必须在 AttackerProbe.gcTick 之前，否则 VictimLethalCandidate 会被提前回收。
        ServerTickEvents.END_SERVER_TICK.register(VictimTombstone::tickEnd);
        ServerTickEvents.END_SERVER_TICK.register(AttackerProbe::gcTick);
        // v6.5.6 · 关卡位置探测：每 tick 给每个玩家算 + diff 推送。
        // 注意 StageProbeServer.tick 内部会调 StageBoundaryDispatcher.updateFromPayload，
        // 因此 dispatcher 不需要单独注册 tick 钩子。
        ServerTickEvents.END_SERVER_TICK.register(StageProbeServer::tick);

        // v6.6.5 · M6 · 1 Hz 推送 stats 全量快照。每 20 tick 由 broadcaster 内部计数器
        // 触发一次广播。集成服务器和专用服务器都注册（集成场景下 client 端的 mirror 走
        // 直读路径会忽略收到的 payload，不影响）。
        ServerTickEvents.END_SERVER_TICK.register(StatsSnapshotBroadcaster::tickPushIfDue);

        // v6.6.1 · M2 · NBT 持久化节流写：每 60s 检查一次是否到点。
        ServerTickEvents.END_SERVER_TICK.register(StatsPersistenceManager::onTickEnd);

        // v6.6.0 · M1 · 关卡边界派发器外部 listener。M2 起接入持久化：
        // 关结束立即写盘、session 切换归档 + reset + 写盘。
        StageBoundaryDispatcher.onStageEnter(key ->
                LOGGER.info("[CTT BD/external] stage ENTER  -> {}", key));
        StageBoundaryDispatcher.onStageExit(key -> {
            LOGGER.info("[CTT BD/external] stage EXIT   -> {}", key);
            try { StatsPersistenceManager.onStageExit(key); }
            catch (Throwable t) { LOGGER.warn("[CTT Stats] persistence EXIT write failed: {}", t.toString()); }
        });
        StageBoundaryDispatcher.onSessionChange(() -> {
            LOGGER.info("[CTT BD/external] session CHANGE (M2 archive)");
            try { StatsPersistenceManager.onSessionChange(); }
            catch (Throwable t) { LOGGER.warn("[CTT Stats] persistence ARCHIVE failed: {}", t.toString()); }
        });
    }

    /**
     * v6.3.7 · 确保 bow / crossbow / trident 的 used stat objective 存在。
     * 如果已由地图 datapack 注册则跳过（名字撞上只影响 displayName，不影响功能）。
     */
    private static void ensureBowObjectives(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        ensureObjective(sb, "ctt.bow.used",      Stats.USED.getOrCreateStat(Items.BOW));
        ensureObjective(sb, "ctt.crossbow.used", Stats.USED.getOrCreateStat(Items.CROSSBOW));
        ensureObjective(sb, "ctt.trident.used",  Stats.USED.getOrCreateStat(Items.TRIDENT));
    }

    private static void ensureObjective(Scoreboard sb, String name, Stat<Item> stat) {
        if (sb.getNullableObjective(name) != null) {
            LOGGER.info("[CTT Stats] objective {} already exists, reusing", name);
            return;
        }
        try {
            ScoreboardCriterion criterion = (ScoreboardCriterion) stat;
            sb.addObjective(name, criterion, Text.literal(name),
                    ScoreboardCriterion.RenderType.INTEGER, false, null);
            LOGGER.info("[CTT Stats] registered objective {} ({})", name, criterion.getName());
        } catch (Throwable t) {
            LOGGER.warn("[CTT Stats] failed to register objective {}: {}", name, t.toString());
        }
    }
}
