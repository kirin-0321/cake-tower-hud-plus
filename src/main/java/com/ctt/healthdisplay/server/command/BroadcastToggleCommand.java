package com.ctt.healthdisplay.server.command;

import com.ctt.healthdisplay.config.ServerConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * v8.x · 服务端聊天广播 3 档的 per-player 订阅命令。
 *
 * <h2>语义</h2>
 * <p>命令 <b>仅修改执行者自己的订阅状态</b>，开/关广播只影响当前玩家。
 * 不再改写 {@link ServerConfig} 全局字段（那些字段保留作为运维兜底，
 * 通过编辑 JSON 启用时会给所有玩家广播）。
 *
 * <p>Per-player 订阅状态存于 {@link BroadcastSubscribers}（in-memory，断线 / 重启即清空）。
 *
 * <h2>命令清单（requires=true，任意权限玩家可用，控制台不能用）</h2>
 * <pre>
 *   /ctthd broadcast status                       查询 自己的订阅 + 全局兜底状态
 *   /ctthd broadcast damage on|off                自己订阅 / 退订 伤害广播
 *   /ctthd broadcast kill on|off                  自己订阅 / 退订 击杀广播
 *   /ctthd broadcast taken on|off                 自己订阅 / 退订 承伤广播
 *   /ctthd broadcast stage_report on|off          自己订阅 / 退订 每关战绩广播（v8.x 默认关）
 *   /ctthd broadcast all on|off                   自己一键四档
 *   /ctthd broadcast taken_threshold &lt;int&gt;        全局承伤阈值（仍是 ServerConfig 字段，会写盘）
 * </pre>
 *
 * <h2>反馈</h2>
 * <p>所有反馈仅发给执行者，不广播。
 */
public final class BroadcastToggleCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("ctt-stats-cmd");

    private static final SimpleCommandExceptionType ONLY_PLAYERS = new SimpleCommandExceptionType(
            Text.literal("[CTT] /ctthd broadcast 仅玩家可用（per-player 订阅，控制台无意义）"));

    private BroadcastToggleCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("ctthd")
                .requires(src -> true);

        LiteralArgumentBuilder<ServerCommandSource> broadcast = CommandManager.literal("broadcast")
                .requires(src -> true)
                .executes(BroadcastToggleCommand::runStatus);

        broadcast.then(CommandManager.literal("status")
                .requires(src -> true)
                .executes(BroadcastToggleCommand::runStatus));

        broadcast.then(buildToggle("damage",       BroadcastSubscribers.Channel.DAMAGE));
        broadcast.then(buildToggle("kill",         BroadcastSubscribers.Channel.KILL));
        broadcast.then(buildToggle("taken",        BroadcastSubscribers.Channel.TAKEN));
        broadcast.then(buildToggle("stage_report", BroadcastSubscribers.Channel.STAGE_REPORT));

        broadcast.then(CommandManager.literal("all")
                .requires(src -> true)
                .then(CommandManager.literal("on").executes(ctx -> setAll(ctx, true)))
                .then(CommandManager.literal("off").executes(ctx -> setAll(ctx, false))));

        broadcast.then(CommandManager.literal("taken_threshold")
                .requires(src -> true)
                .then(CommandManager.argument("value", IntegerArgumentType.integer(0))
                        .executes(BroadcastToggleCommand::runSetTakenThreshold)));

        broadcast.then(CommandManager.literal("damage_threshold")
                .requires(src -> true)
                .then(CommandManager.argument("value", IntegerArgumentType.integer(0))
                        .executes(BroadcastToggleCommand::runSetDamageThreshold)));

        root.then(broadcast);
        root.executes(BroadcastToggleCommand::runStatus);
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildToggle(
            String name, BroadcastSubscribers.Channel ch) {
        return CommandManager.literal(name)
                .requires(src -> true)
                .executes(ctx -> {
                    UUID uuid = playerUuid(ctx);
                    boolean cur = BroadcastSubscribers.isSubscribed(ch, uuid);
                    feedbackBool(ctx, name, cur);
                    return cur ? 1 : 0;
                })
                .then(CommandManager.literal("on").executes(ctx -> {
                    UUID uuid = playerUuid(ctx);
                    BroadcastSubscribers.subscribe(ch, uuid);
                    feedbackBoolChanged(ctx, name, true);
                    return 1;
                }))
                .then(CommandManager.literal("off").executes(ctx -> {
                    UUID uuid = playerUuid(ctx);
                    BroadcastSubscribers.unsubscribe(ch, uuid);
                    feedbackBoolChanged(ctx, name, false);
                    return 0;
                }));
    }

    private static int setAll(CommandContext<ServerCommandSource> ctx, boolean on) throws CommandSyntaxException {
        UUID uuid = playerUuid(ctx);
        for (BroadcastSubscribers.Channel ch : BroadcastSubscribers.Channel.values()) {
            if (on) BroadcastSubscribers.subscribe(ch, uuid);
            else    BroadcastSubscribers.unsubscribe(ch, uuid);
        }
        Text msg = Text.literal("[CTT] 你的四档广播订阅（damage/kill/taken/stage_report）已统一切到 ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
        ctx.getSource().sendFeedback(() -> msg, false);
        return on ? 1 : 0;
    }

    private static int runSetTakenThreshold(CommandContext<ServerCommandSource> ctx) {
        int v = IntegerArgumentType.getInteger(ctx, "value");
        ServerConfig.INSTANCE.broadcastTakenThreshold = v;
        saveServerConfig();
        Text msg = Text.literal("[CTT] 承伤广播阈值（全局）-> " + v
                + (v == 0 ? "（全部显示）" : "（仅显示 ≥ " + v + "）")).formatted(Formatting.GRAY);
        ctx.getSource().sendFeedback(() -> msg, false);
        return v;
    }

    private static int runSetDamageThreshold(CommandContext<ServerCommandSource> ctx) {
        int v = IntegerArgumentType.getInteger(ctx, "value");
        ServerConfig.INSTANCE.broadcastDamageThreshold = v;
        saveServerConfig();
        Text msg = Text.literal("[CTT] 伤害广播阈值（全局）-> " + v
                + (v == 0 ? "（全部显示）" : "（仅显示 ≥ " + v + "）")).formatted(Formatting.GRAY);
        ctx.getSource().sendFeedback(() -> msg, false);
        return v;
    }

    private static void saveServerConfig() {
        try { ServerConfig.INSTANCE.save(); }
        catch (Throwable t) { LOGGER.warn("[CTT cmd] save server config failed: {}", t.toString()); }
    }

    private static int runStatus(CommandContext<ServerCommandSource> ctx) {
        ServerConfig c = ServerConfig.INSTANCE;
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = src.getPlayer();
        UUID uuid = player == null ? null : player.getUuid();

        Text head = Text.literal("[CTT] 广播订阅状态:").formatted(Formatting.GRAY);
        src.sendFeedback(() -> head, false);

        if (uuid != null) {
            sendStatusLine(src, "你订阅 damage      ", BroadcastSubscribers.isSubscribed(BroadcastSubscribers.Channel.DAMAGE,       uuid));
            sendStatusLine(src, "你订阅 kill        ", BroadcastSubscribers.isSubscribed(BroadcastSubscribers.Channel.KILL,         uuid));
            sendStatusLine(src, "你订阅 taken       ", BroadcastSubscribers.isSubscribed(BroadcastSubscribers.Channel.TAKEN,        uuid));
            sendStatusLine(src, "你订阅 stage_report", BroadcastSubscribers.isSubscribed(BroadcastSubscribers.Channel.STAGE_REPORT, uuid));
        } else {
            Text note = Text.literal("  （控制台执行：仅显示全局兜底状态）").formatted(Formatting.DARK_GRAY);
            src.sendFeedback(() -> note, false);
        }

        Text divider = Text.literal("  ----- 全局兜底（编辑 JSON 启用，对所有玩家广播）-----").formatted(Formatting.DARK_GRAY);
        src.sendFeedback(() -> divider, false);
        sendStatusLine(src, "global damage      ", c.broadcastDamageInChat);
        sendStatusLine(src, "global kill        ", c.broadcastKillsInChat);
        sendStatusLine(src, "global taken       ", c.broadcastTakenInChat);
        sendStatusLine(src, "global stage_report", c.broadcastStageReportInChat);
        Text tDamage = Text.literal("  damage_threshold = " + c.broadcastDamageThreshold
                + (c.broadcastDamageThreshold == 0 ? "（全部）" : "")).formatted(Formatting.DARK_GRAY);
        src.sendFeedback(() -> tDamage, false);
        Text tTaken = Text.literal("  taken_threshold  = " + c.broadcastTakenThreshold
                + (c.broadcastTakenThreshold == 0 ? "（全部）" : "")).formatted(Formatting.DARK_GRAY);
        src.sendFeedback(() -> tTaken, false);

        Text help = Text.literal("用法: /ctthd broadcast <damage|kill|taken|stage_report|all> <on|off>  （仅你自己看到）")
                .formatted(Formatting.DARK_GRAY);
        src.sendFeedback(() -> help, false);
        Text help2 = Text.literal("阈值: /ctthd broadcast <damage_threshold|taken_threshold> <int>  （全局，写盘）")
                .formatted(Formatting.DARK_GRAY);
        src.sendFeedback(() -> help2, false);
        return 1;
    }

    private static void sendStatusLine(ServerCommandSource src, String name, boolean on) {
        Text line = Text.literal("  " + name + " = ")
                .formatted(Formatting.DARK_GRAY)
                .append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
        src.sendFeedback(() -> line, false);
    }

    private static void feedbackBool(CommandContext<ServerCommandSource> ctx, String name, boolean on) {
        Text msg = Text.literal("[CTT] 你的 broadcast " + name + " = ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
        ctx.getSource().sendFeedback(() -> msg, false);
    }

    private static void feedbackBoolChanged(CommandContext<ServerCommandSource> ctx, String name, boolean on) {
        Text msg = Text.literal("[CTT] 你的 broadcast " + name + " -> ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
        ctx.getSource().sendFeedback(() -> msg, false);
    }

    /** 命令必须由玩家执行；控制台执行抛 ONLY_PLAYERS 异常。 */
    private static UUID playerUuid(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) throw ONLY_PLAYERS.create();
        return p.getUuid();
    }
}
