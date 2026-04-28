package com.ctt.healthdisplay;

import com.ctt.healthdisplay.client.ClientDamageProbe;
import com.ctt.healthdisplay.client.ClientStageProbe;
import com.ctt.healthdisplay.client.ClientStatsCache;
import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.health.HealthData;
import com.ctt.healthdisplay.health.MobHealthData;
import com.ctt.healthdisplay.health.StatsData;
import com.ctt.healthdisplay.hud.ClientStageLocation;
import com.ctt.healthdisplay.hud.DamagePanelRenderer;
import com.ctt.healthdisplay.hud.DamagePanelScreen;
import com.ctt.healthdisplay.hud.HealthBarRenderer;
import com.ctt.healthdisplay.hud.StageNameRegistry;
import com.ctt.healthdisplay.hud.StatsRenderer;
import com.ctt.healthdisplay.hud.StatsTableScreen;
import com.ctt.healthdisplay.network.StagePayload;
import com.ctt.healthdisplay.network.StatsSnapshotPayload;
import com.ctt.healthdisplay.server.DamageProbe;
import net.minecraft.text.Style;
import net.minecraft.util.Formatting;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CttHealthDisplay implements ClientModInitializer {

    public static final String MOD_ID = "ctt-health-display";
    public static boolean hudEnabled = true;
    public static boolean hideBossBars = false;

    private static final HealthData healthData = new HealthData();
    public static final StatsData statsData = new StatsData();
    private static KeyBinding toggleHudKey;
    private static KeyBinding toggleStatsKey;
    private static KeyBinding toggleBossBarsKey;
    private static KeyBinding toggleDmgSessionKey;
    // v6.6.0 · M4 · 统计表格面板键。设计文档原拍板用 K，但 K 已绑 toggleBossBarsKey；
    // 这里临时用 N，玩家在控制设置里可重绑。
    private static KeyBinding toggleStatsTableKey;
    private static int autoStatsTimer = 0;
    private static long tickCounter = 0;
    private static long lastRefreshTick = 0;
    private static int prevAllHearts = -1;
    private static final long MIN_REFRESH_INTERVAL_TICKS = 20;
    // 瞬间治疗触发属性刷新的最小增量：只对"喝治疗药水"这种一次性回血 ≥ 25 的场景敏感，
    // 避免 buff 回血、自然再生这种慢速 tick 积分也刷属性栏。
    private static final int BIG_HEAL_THRESHOLD = 25;
    // 仅在 mob 离开视野/死亡后，再等多少 tick 才从血条记录里清理。
    // 给区块加载抖动一个缓冲，避免"同名怪物之一短暂不可见 → 记录丢失 → 回到视野时用错误 bar.hp 重新初始化"的错位 bug。
    private static final long MOB_ENTRY_STALE_TICKS = 100; // 5s
    private static long lastPartyBossbarToggleTick = -99999;
    private static final long PARTY_BOSSBAR_TOGGLE_COOLDOWN_TICKS = 60; // 3s：两次自动 toggle 最少间隔（v5.1.13）
    // 熔断：癫狂等特殊效果会让 team bar 持续不可解析，避免把 toggle 累积发到被服务端踢出。
    // 连续尝试 MAX_FAILED 次后依然没检测到 team bar → 进入 LONG 冷却，期间完全不发命令。
    // 一旦 hasTeamBossBar 再次为 true，计数立刻归零，熔断解除。
    private static int partyBossbarFailedToggles = 0;
    private static long partyBossbarFailsafeUntilTick = -1;
    private static final int MAX_FAILED_PARTY_BOSSBAR_TOGGLES = 5;
    private static final long PARTY_BOSSBAR_LONG_COOLDOWN_TICKS = 12000; // 10 分钟

    @Override
    public void onInitializeClient() {
        ModConfig.load();
        // v6.5.7 · 关卡 ID → 本地化名称硬编码表（来自 scripts/gen_stage_name_map.py 生成）。
        StageNameRegistry.load();
        healthData.setStatsData(statsData);

        // v6.5.6 · 接收服务端推送的关卡位置 payload，缓存到 ClientStageLocation 供 HUD 读。
        // payload type 必须双端都注册（server side 在 CttStatsServer.onInitialize），不然会握手失败。
        ClientPlayNetworking.registerGlobalReceiver(StagePayload.ID, (payload, ctx) -> {
            ClientStageLocation.onPayload(payload);
        });

        // v6.6.5 · M6 · 接收 stats 全量快照 payload。集成服务器场景下 ClientStatsCache
        // 的 isIntegrated() 直读路径会优先生效（payload 也会收到但被忽略），
        // 此分支主要服务于专用服务器 / LAN 远程客户端。
        // 客户端侧也注册一次 PayloadType（与 CttStatsServer 服务端注册对称）：
        // 集成服务器单 JVM 下 server entrypoint 已注册过，重复注册会抛异常 →
        // 只在客户端独立 JVM（dedicated client）需要这条；为安全 try-catch。
        try {
            PayloadTypeRegistry.playS2C().register(StatsSnapshotPayload.ID, StatsSnapshotPayload.CODEC);
        } catch (IllegalArgumentException dup) {
            // 集成 server / LAN host 场景：CttStatsServer.onInitialize 已注册过，忽略
        }
        ClientPlayNetworking.registerGlobalReceiver(StatsSnapshotPayload.ID, (payload, ctx) -> {
            ClientStatsCache.update(payload);
        });

        // 切服 / 断线时清缓存，避免悬挂上一局数据。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientStageLocation.reset();
            ClientStatsCache.reset();
            ClientDamageProbe.INSTANCE.resetForDisconnect();
            com.ctt.healthdisplay.client.ClientStageDetector.onDisconnect();
        });

        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ctt-health-display.toggle_hud",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.ctt-health-display"
        ));

        toggleStatsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ctt-health-display.toggle_stats",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.ctt-health-display"
        ));

        toggleBossBarsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ctt-health-display.toggle_bossbars",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.ctt-health-display"
        ));

        // 默认未绑定（GLFW_KEY_UNKNOWN = -1）：v6.5.30 起伤害分配面板列入"实验性 / 隐藏"
        // 功能，新装用户不再默认占用 L 键，避免与原版未来按键变动冲突；老用户的
        // options.txt 里若已绑定 L，会保持原状（vanilla 持久化的是用户设置的 key）。
        toggleDmgSessionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ctt-health-display.toggle_dmg_session",
                InputUtil.Type.KEYSYM,
                InputUtil.UNKNOWN_KEY.getCode(),
                "category.ctt-health-display"
        ));

        toggleStatsTableKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ctt-health-display.toggle_stats_table",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                "category.ctt-health-display"
        ));

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            boolean wasReady = statsData.hasData();
            boolean hide = statsData.processMessage(message, overlay);
            return !hide;
        });

        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            if (hudEnabled) {
                HealthBarRenderer.render(context, tickCounter, healthData, statsData);
            }
            StatsRenderer.render(context, statsData);
            // v6.3.1 · 伤害分配面板：HUD 只读绘制。
            //   - 专属 DamagePanelScreen 自己会画，不要重复。
            //   - 其他 Screen（聊天 T / 背包 E / 暂停 ESC 等）打开时继续画，
            //     鼠标坐标从 mc.mouse 取，绘出按钮 hover 效果。点击由 ScreenMouseEvents 接管。
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.currentScreen instanceof DamagePanelScreen) return;

            double mouseX = -1, mouseY = -1;
            if (mc.currentScreen != null && mc.getWindow() != null) {
                int ww = mc.getWindow().getWidth();
                int wh = mc.getWindow().getHeight();
                if (ww > 0 && wh > 0) {
                    mouseX = mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) ww;
                    mouseY = mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) wh;
                }
            }
            // v6.6.7 · 拖拽 tick：聊天栏 / 背包 / 暂停等 Screen 下持续追鼠标更新面板坐标。
            // DamagePanelScreen 本身在自己的 render 里调 tickDrag，HudRenderCallback 这里跳过它。
            if (DamagePanelRenderer.isDragging() && mc.currentScreen != null
                    && !(mc.currentScreen instanceof DamagePanelScreen)) {
                DamagePanelRenderer.tickDrag(mc, mouseX, mouseY);
            }
            DamagePanelRenderer.drawHud(context, mc, mouseX, mouseY);
        });

        // v6.3.1 · 任意 Screen（聊天 T / 背包 E / 暂停 ESC ...）打开时，
        //   注册鼠标点击 hook：点中 HUD 面板按钮即执行按钮动作并阻止 Screen 接收。
        //   DamagePanelScreen 本身由自己处理，跳过。
        // v6.6.7 · 同时支持拖拽：点中标题栏 (非按钮区) 开始拖；松开时结束。
        ScreenEvents.AFTER_INIT.register((mc, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof DamagePanelScreen) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> {
                if (button != 0) return true;
                if (!ModConfig.INSTANCE.damagePanelHudVisible) return true;
                int idx = DamagePanelRenderer.hitTestButton(mx, my, mc);
                if (idx >= 0) {
                    DamagePanelRenderer.handleButton(idx, mc);
                    return false; // 拦截，不让聊天栏 / 背包等消费这次点击
                }
                if (DamagePanelRenderer.hitTestTitleBarDraggable(mx, my, mc)) {
                    DamagePanelRenderer.beginDrag(mc, mx, my);
                    return false; // 拦截，避免 vanilla Screen 误处理
                }
                return true;
            });
            ScreenMouseEvents.allowMouseRelease(screen).register((s, mx, my, button) -> {
                if (button == 0 && DamagePanelRenderer.isDragging()) {
                    DamagePanelRenderer.endDrag();
                    return false;
                }
                return true;
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickCounter++;

            // Keep HealthData fresh even when HUD is off, so we can auto-toggle party bossbar.
            if (client.player != null && client.world != null) {
                healthData.update();
                maybeAutoTogglePartyBossbar(client);
            }

            // v7.0.0 · P0 客户端探针：每 tick 扫 DamageShower 粒子 + 累加三段聚合数字。
            // 设计依据：CLIENT_SIDE_STATS_PROPOSAL.md §X "P0 客户端探针骨架"。
            ClientDamageProbe.INSTANCE.onClientTick(client);

            // v7.0.10 · 客户端关卡位置兜底探测：服务端没装 mod 时，1Hz 读 client scoreboard
            // 推断当前 STAGE_*，写入 ClientStageLocation.fromClient。装着服务端 mod 时
            // ClientStageLocation.current() 仍优先返回 fromServer，本探测结果只在兜底路径上被读。
            ClientStageProbe.tick(client);

            // ALL / NEAREST 两档都要维持 mobHealthMap：NEAREST 档只是渲染时过滤非 targetted 的条，
            // 数据本身仍需更新，这样切换档位时立刻就能显示正确血量。
            if (ModConfig.INSTANCE.isMobHeadHPEnabled() && client.world != null) {
                updateMobTracking(client);
            }

            if (ModConfig.INSTANCE.showTeammateHeadHP && client.world != null) {
                var scoreboard = client.world.getScoreboard();
                if (scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.BELOW_NAME) != null) {
                    scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.BELOW_NAME, null);
                }
            }
            if (toggleHudKey.wasPressed()) {
                hudEnabled = !hudEnabled;
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("CTT Health Display: " + (hudEnabled ? "ON" : "OFF")),
                            true
                    );
                }
            }
            if (toggleStatsKey.wasPressed()) {
                ModConfig cfg = ModConfig.INSTANCE;
                cfg.statsVisibility = (cfg.statsVisibility + 1) % 3;
                cfg.save();
                String[] labels = {"\u5e38\u663e", "\u80cc\u5305\u663e\u793a", "\u9690\u85cf"};
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("CTT \u5c5e\u6027\u680f: " + labels[cfg.statsVisibility]),
                            true
                    );
                }
            }
            if (toggleBossBarsKey.wasPressed()) {
                ModConfig cfg = ModConfig.INSTANCE;
                boolean allHidden = cfg.hidePersonalBar && cfg.hideTeamBar && cfg.hideMobBars;
                cfg.hidePersonalBar = !allHidden;
                cfg.hideTeamBar = !allHidden;
                cfg.hideMobBars = !allHidden;
                cfg.save();
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("CTT Boss Bars: " + (!allHidden ? "\u9690\u85cf" : "\u663e\u793a")),
                            true
                    );
                }
            }
            if (toggleDmgSessionKey.wasPressed()) {
                handleDmgPanelToggle(client);
            }
            if (toggleStatsTableKey.wasPressed()) {
                handleStatsTableToggle(client);
            }
            // v6.3.0 · actionbar 只在 session 活跃且面板 Screen 未打开时显示（避免遮挡）
            if (DamageProbe.isSessionActive() && !(client.currentScreen instanceof DamagePanelScreen)) {
                renderDmgSessionActionbar(client);
            }
            ModConfig config = ModConfig.INSTANCE;
            if (config.autoRefreshStats
                    && !statsData.isCapturing()
                    && client.player != null && client.getNetworkHandler() != null) {

                int currentAllHearts = healthData.allHearts;
                int heartsDelta = (prevAllHearts > 0) ? (currentAllHearts - prevAllHearts) : 0;
                boolean hpDropped = prevAllHearts > 0 && heartsDelta < 0;
                boolean bigHeal = prevAllHearts > 0 && heartsDelta >= BIG_HEAL_THRESHOLD;
                prevAllHearts = currentAllHearts;

                boolean canRefresh = (tickCounter - lastRefreshTick) >= MIN_REFRESH_INTERVAL_TICKS;

                if ((hpDropped || bigHeal) && canRefresh) {
                    triggerViewStats(client);
                } else {
                    autoStatsTimer++;
                    int intervalTicks = config.autoRefreshIntervalSeconds * 20;
                    if (autoStatsTimer >= intervalTicks) {
                        triggerViewStats(client);
                    }
                }
            } else {
                autoStatsTimer = 0;
            }
        });
    }

    // ========================================================================
    //  v6.6.0 hotfix · L 键 = 显示 / 隐藏 HUD 上的伤害分配面板。
    //
    //  历史：v6.3.0 L 键打开 DamagePanelScreen 交互菜单（▶/⊘/⏸/详情/切片 按钮）。
    //  v6.6.0 hotfix：服务器启动后默认 live=true，T1F1 自动 clear，玩家不需要手动
    //  start/stop/clear，菜单上的按钮失去日常用途；L 键改为更直觉的"显示/隐藏面板"。
    //
    //  保留：DamagePanelScreen 类未删除——若以后要拖拽面板位置仍可由别处入口（ConfigScreen
    //  或 Shift+L）打开。其他屏幕（聊天/背包）打开时点 HUD 上按钮的 hover/click 路径
    //  仍走 ScreenMouseEvents，按钮功能未失。
    // ========================================================================

    private static void handleDmgPanelToggle(net.minecraft.client.MinecraftClient client) {
        ModConfig cfg = ModConfig.INSTANCE;
        cfg.damagePanelHudVisible = !cfg.damagePanelHudVisible;
        cfg.save();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("CTT \u4f24\u5bb3\u5206\u914d\u9762\u677f: "
                            + (cfg.damagePanelHudVisible ? "\u663e\u793a" : "\u9690\u85cf")),
                    true
            );
        }
    }

    /** v6.6.0 · M4 · N 键 = 打开 / 关闭统计表格面板（设计 §6）。 */
    private static void handleStatsTableToggle(net.minecraft.client.MinecraftClient client) {
        if (StatsTableScreen.isOpen(client)) {
            client.setScreen(null);
        } else {
            client.setScreen(new StatsTableScreen());
        }
    }

    private static void renderDmgSessionActionbar(net.minecraft.client.MinecraftClient client) {
        if (client.player == null) return;
        long total = DamageProbe.getLiveTotal();
        int events = DamageProbe.getLiveEvents();
        double durSec = (System.currentTimeMillis() - DamageProbe.getSessionStartMs()) / 1000.0;
        String text = String.format("\u25c6 \u7edf\u8ba1\u4e2d: %d \u4f24\u5bb3 / %d \u547d\u4e2d / %.1fs",
                total, events, durSec);
        client.player.sendMessage(
                Text.literal(text).setStyle(Style.EMPTY.withColor(Formatting.GOLD)),
                true // actionbar
        );
    }

    private static void updateMobTracking(net.minecraft.client.MinecraftClient client) {
        List<HealthData.MobBossBarEntry> bars = HealthData.getActiveMobBars();
        Map<UUID, MobHealthData> map = HealthData.getMobHealthMap();
        net.minecraft.util.math.Vec3d playerPos = client.player.getPos();

        // 每个 bar 对应一只"当前服务端选中的同名 mob"。我们用距离最近近似（对齐服务端 sort=nearest）找到它，
        // 只对这只 mob 写入 bar.hp/maxHP；其它同名 mob 若 map 里已有记录则保持冻结血量，map 里没有则不入库。
        // 关键：**永远不要用 bar.hp 批量初始化同名实体**，那会导致"打残 A → 切打 B → scan 用 B 的 bar 把 A 错误刷新成满血"的 bug。
        for (HealthData.MobBossBarEntry bar : bars) {
            String mobName = bar.name;

            Entity closest = null;
            double closestDist = Double.MAX_VALUE;
            for (Entity e : client.world.getEntities()) {
                if (e instanceof LivingEntity living
                        && !(e instanceof PlayerEntity)
                        && !(e instanceof ArmorStandEntity)
                        && living.isAlive()
                        && matchesName(e, mobName)) {
                    double dist = e.getPos().squaredDistanceTo(playerPos);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = e;
                    }
                }
            }
            if (closest == null) continue;
            UUID closestUUID = closest.getUuid();

            MobHealthData data = map.get(closestUUID);
            if (data == null) {
                data = new MobHealthData(mobName, bar.suffixText, bar.hp, bar.maxHP, tickCounter);
                map.put(closestUUID, data);
            } else {
                data.hp = bar.hp;
                data.maxHP = bar.maxHP;
                data.suffixText = bar.suffixText;
            }
            data.targetted = true;
            data.lastUpdateTick = tickCounter;
            net.minecraft.text.TextColor tc = closest.getDisplayName().getStyle().getColor();
            data.nameColor = tc != null ? (tc.getRgb() | 0xFF000000) : 0xFFFFFFFF;

            for (Map.Entry<UUID, MobHealthData> entry : map.entrySet()) {
                MobHealthData d = entry.getValue();
                if (!d.name.equals(mobName)) continue;
                if (!entry.getKey().equals(closestUUID)) {
                    d.targetted = false;
                }
            }
        }

        // 清理：仅当实体查不到且记录已陈旧（> MOB_ENTRY_STALE_TICKS 未被更新）才删，避免区块抖动丢数据。
        // 明确死亡 / 被移除的实体立即清理。
        if (tickCounter % 20 == 0 && client.world != null) {
            map.entrySet().removeIf(entry -> {
                Entity e = findEntityByUUID(client, entry.getKey());
                if (e != null && e.isAlive() && !e.isRemoved()) return false;
                if (e != null) return true; // 存在但死了 / 标记移除：立刻清
                return (tickCounter - entry.getValue().lastUpdateTick) > MOB_ENTRY_STALE_TICKS;
            });
        }
    }

    private static Entity findEntityByUUID(net.minecraft.client.MinecraftClient client, UUID uuid) {
        if (client.world == null) return null;
        for (Entity e : client.world.getEntities()) {
            if (e.getUuid().equals(uuid)) return e;
        }
        return null;
    }

    private static boolean matchesName(Entity entity, String bossBarName) {
        String entityName = entity.getName().getString().trim();
        String barName = bossBarName.trim();
        if (entityName.equalsIgnoreCase(barName)) return true;
        return entityName.contains(barName) || barName.contains(entityName);
    }

    private static void triggerViewStats(net.minecraft.client.MinecraftClient client) {
        if (client.getNetworkHandler() == null) return;
        autoStatsTimer = 0;
        lastRefreshTick = tickCounter;
        statsData.markAutoTriggered();
        client.getNetworkHandler().sendCommand("trigger ViewStats");
    }

    private static void maybeAutoTogglePartyBossbar(net.minecraft.client.MinecraftClient client) {
        if (client.getNetworkHandler() == null) return;

        // team bar 已经出现：目的达成，熔断计数归零。
        if (healthData.hasTeamBossBar) {
            partyBossbarFailedToggles = 0;
            partyBossbarFailsafeUntilTick = -1;
            return;
        }

        if (!healthData.hasPersonalBossBar) return;

        // 熔断长冷却期间：完全不发命令，避免癫狂等特殊效果导致命令被服务端 rate limit 踢出。
        if (tickCounter < partyBossbarFailsafeUntilTick) return;

        // 普通冷却：最少每 3 秒一次。
        if ((tickCounter - lastPartyBossbarToggleTick) < PARTY_BOSSBAR_TOGGLE_COOLDOWN_TICKS) return;

        lastPartyBossbarToggleTick = tickCounter;
        partyBossbarFailedToggles++;
        client.getNetworkHandler().sendCommand("trigger TogglePartyBossbar");

        // 连续尝试达到阈值仍未出现 team bar：进入长冷却（大概率玩家身上挂了癫狂之类状态，
        // 继续 toggle 也不会奏效，且会触发服务端 rate limit）。
        if (partyBossbarFailedToggles >= MAX_FAILED_PARTY_BOSSBAR_TOGGLES) {
            partyBossbarFailsafeUntilTick = tickCounter + PARTY_BOSSBAR_LONG_COOLDOWN_TICKS;
            partyBossbarFailedToggles = 0;
        }
    }
}
