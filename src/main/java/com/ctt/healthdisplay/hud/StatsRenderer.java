package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.health.StatsData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.text.Text;

import java.util.List;

public class StatsRenderer {

    private static final int LINE_HEIGHT = 10;
    private static final int TITLE_COLOR = 0xFFFFAA00;

    public static void render(DrawContext context, StatsData data) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int vis = ModConfig.INSTANCE.statsVisibility;
        if (vis == 2) return;
        if (vis == 1 && !(client.currentScreen instanceof InventoryScreen)) return;
        if (vis == 0 && client.options.hudHidden) return;

        TextRenderer tr = client.textRenderer;
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        ModConfig config = ModConfig.INSTANCE;
        int x = (int) (config.statsX * screenW);
        int y = (int) (config.statsY * screenH);

        if (!data.hasData()) {
            Text hint = data.isGameNotStarted()
                    ? Text.translatable("ctt-health-display.stats.hint.game_not_started")
                    : Text.translatable("ctt-health-display.stats.hint.use_view_stats");
            context.drawTextWithShadow(tr, hint, x, y, 0xFF888888);
            return;
        }

        List<Text> lines = data.getLines();
        if (lines.isEmpty()) return;

        int columns = Math.max(1, Math.min(2, config.statsColumns));

        if (columns == 1) {
            int lineY = y;
            for (Text line : lines) {
                context.drawTextWithShadow(tr, line, x, lineY, 0xFFFFFFFF);
                lineY += LINE_HEIGHT;
            }
        } else {
            int colWidth = 0;
            for (Text line : lines) {
                colWidth = Math.max(colWidth, tr.getWidth(line));
            }
            colWidth += 6;

            int rows = (lines.size() + 1) / 2;
            for (int i = 0; i < lines.size(); i++) {
                int col = i / rows;
                int row = i % rows;
                context.drawTextWithShadow(tr, lines.get(i), x + col * colWidth, y + row * LINE_HEIGHT, 0xFFFFFFFF);
            }
        }
    }
}
