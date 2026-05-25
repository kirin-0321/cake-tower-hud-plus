package com.ctt.healthdisplay.client;

import com.ctt.healthdisplay.config.ModConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;

/**
 * 客户端本地命令 {@code /ctthd reload}：热加载 {@code config/ctt-health-display.json}。
 *
 * <p>不向服务端发包；用于手改 {@link ModConfig#hideHudOnBerserk} 等未进 ModMenu 的字段后即时生效。
 */
public final class ClientConfigReloadCommand {

    private ClientConfigReloadCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(ClientConfigReloadCommand::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                         CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("ctthd")
                .then(ClientCommandManager.literal("reload")
                        .executes(ctx -> {
                            boolean hadFile = ModConfig.reload();
                            FabricClientCommandSource src = ctx.getSource();
                            src.sendFeedback(Text.translatable(
                                    "ctt-health-display.command.reload.success",
                                    Text.translatable(ModConfig.INSTANCE.hideHudOnBerserk
                                            ? "ctt-health-display.config.value.on"
                                            : "ctt-health-display.config.value.off")));
                            if (!hadFile) {
                                src.sendFeedback(Text.translatable(
                                        "ctt-health-display.command.reload.no_file"));
                            }
                            return 1;
                        })));
    }
}
