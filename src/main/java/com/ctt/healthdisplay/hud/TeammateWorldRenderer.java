package com.ctt.healthdisplay.hud;

import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.health.HealthData;
import com.ctt.healthdisplay.health.MobHealthData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Renders teammate health bars above player entities in 3D world space.
 * Called from WorldRendererMixin after each entity is rendered,
 * following the same pattern as Neat (VazkiiMods).
 */
public class TeammateWorldRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CTT-WorldHP");
    private static long lastDebugTime = 0;

    private static final int[] OVERFLOW_COLORS = {
        0xE84040, // 1x red
        0xFF8800, // 2x orange
        0xFFDD00, // 3x yellow
        0x44CC44, // 4x green
        0x4488EE, // 5x blue
        0xAA44DD  // 6x+ purple
    };

    public static void renderHealthBar(Entity entity, double cameraX, double cameraY, double cameraZ,
                                       float tickDelta, MatrixStack matrices,
                                       VertexConsumerProvider vertexConsumers,
                                       EntityRenderDispatcher dispatcher) {
        if (!(entity instanceof PlayerEntity player)) return;
        if (!ModConfig.INSTANCE.showTeammateHeadHP) return;
        if (player == MinecraftClient.getInstance().player) return;

        String name = player.getName().getString();
        Map<String, HealthData.TeammateData> map = HealthData.getTeammateMap();
        HealthData.TeammateData data = findTeammate(name, map);

        long now = System.currentTimeMillis();
        if (now - lastDebugTime > 3000) {
            lastDebugTime = now;
            LOGGER.info("[DEBUG] entity='{}', teammate={}, mapKeys={}", name, data != null, map.keySet());
        }

        if (data == null) return;

        double x = MathHelper.lerp(tickDelta, entity.prevX, entity.getX()) - cameraX;
        double y = MathHelper.lerp(tickDelta, entity.prevY, entity.getY()) - cameraY
                + entity.getHeight() + 0.5;
        double z = MathHelper.lerp(tickDelta, entity.prevZ, entity.getZ()) - cameraZ;

        final int light = 0xF000F0;
        final float globalScale = 0.025F;

        matrices.push();
        matrices.translate(x, y, z);
        matrices.multiply(dispatcher.getRotation());
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
        matrices.scale(-globalScale, -globalScale, globalScale);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;

        float halfW = ModConfig.INSTANCE.teammateBarHalfWidth;
        float barTop = 10;
        float barH = 5;
        float barW = halfW * 2;

        VertexConsumer bg = vertexConsumers.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
        bg.vertex(matrix, -halfW - 1, barTop - 1, 0.01f).color(0, 0, 0, 80).light(light);
        bg.vertex(matrix, -halfW - 1, barTop + barH + 1, 0.01f).color(0, 0, 0, 80).light(light);
        bg.vertex(matrix, halfW + 1, barTop + barH + 1, 0.01f).color(0, 0, 0, 80).light(light);
        bg.vertex(matrix, halfW + 1, barTop - 1, 0.01f).color(0, 0, 0, 80).light(light);

        if (data.hp > 0 && data.maxHP > 0) {
            int topIdx = (data.hp - 1) / data.maxHP;
            int hpInTop = data.hp - topIdx * data.maxHP;
            float topFill = Math.min(barW, (float) hpInTop / data.maxHP * barW);

            if (topIdx > 0 && topFill < barW) {
                int bgColorIdx = Math.min(topIdx - 1, OVERFLOW_COLORS.length - 1);
                drawBarQuad(vertexConsumers, matrix, -halfW + topFill, -halfW + barW,
                        barTop, barH, OVERFLOW_COLORS[bgColorIdx], 220, light, 0.02f);
            }

            int topColorIdx = Math.min(topIdx, OVERFLOW_COLORS.length - 1);
            drawBarQuad(vertexConsumers, matrix, -halfW, -halfW + topFill,
                    barTop, barH, OVERFLOW_COLORS[topColorIdx], 220, light, 0.025f);
        }

        String hpText = data.maxHP > 0 ? data.hp + "/" + data.maxHP : String.valueOf(data.hp);
        matrices.push();
        matrices.scale(0.75f, 0.75f, 0.75f);
        Matrix4f smallMatrix = matrices.peek().getPositionMatrix();
        float scaledTextX = -textRenderer.getWidth(hpText) / 2.0f;
        float scaledTextY = (barTop + (barH - 9 * 0.75f) / 2.0f) / 0.75f;
        textRenderer.draw(hpText, scaledTextX, scaledTextY, 0xFFFFFFFF, false, smallMatrix,
                vertexConsumers, TextRenderer.TextLayerType.SEE_THROUGH, 0, light);
        matrices.pop();

        matrices.pop();
    }

    public static void renderMobHealthBar(Entity entity, double cameraX, double cameraY, double cameraZ,
                                            float tickDelta, MatrixStack matrices,
                                            VertexConsumerProvider vertexConsumers,
                                            EntityRenderDispatcher dispatcher) {
        if (!(entity instanceof LivingEntity living)) return;
        if (entity instanceof PlayerEntity) return;
        if (entity instanceof ArmorStandEntity) return;
        if (!ModConfig.INSTANCE.isMobHeadHPEnabled()) return;
        if (!living.isAlive()) return;

        MobHealthData data = HealthData.getMobHealthMap().get(entity.getUuid());
        if (data == null) return;
        // NEAREST 档：只画 bossbar 锁定的那只（targetted=true），其他同名 mob 不渲染 CTT 条，
        // 交由 vanilla 名牌/原始标签继续显示（mixin 层也会放行非 targetted 的同名实体）。
        if (ModConfig.INSTANCE.isMobHeadHPNearestOnly() && !data.targetted) return;

        int pct = data.getPercent();

        double x = MathHelper.lerp(tickDelta, entity.prevX, entity.getX()) - cameraX;
        double y = MathHelper.lerp(tickDelta, entity.prevY, entity.getY()) - cameraY
                + entity.getHeight() + 0.5;
        double z = MathHelper.lerp(tickDelta, entity.prevZ, entity.getZ()) - cameraZ;

        final int light = 0xF000F0;
        final float globalScale = 0.025F;

        matrices.push();
        matrices.translate(x, y, z);
        matrices.multiply(dispatcher.getRotation());
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
        matrices.scale(-globalScale, -globalScale, globalScale);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;

        float halfW = ModConfig.INSTANCE.mobBarHalfWidth;
        float barH = 6;
        float barTop = 2;

        // v8.3.6 · MobHealthData 升级到 Text nameText 后，名字按 OrderedText 画，
        // 颜色由 Text 自带 Style 携带（白色 fallback），不再读 data.nameColor，
        // 客户端中文 / 英文 lang 自动渲染不同字面量，translate 键过线后由 vanilla
        // textRenderer 用本地 lang 文件还原。
        Text nameText = data.nameText != null ? data.nameText : Text.empty();
        OrderedText nameOrdered = nameText.asOrderedText();
        Text suffixText = data.suffixText != null ? data.suffixText : Text.empty();
        OrderedText suffixOrdered = suffixText.asOrderedText();
        float suffixW = textRenderer.getWidth(suffixOrdered);

        // v5.1.10：完全对齐 MC 原版玩家名牌（EntityRenderer.renderLabelIfPresent）——
        //   1) textLayer = SEE_THROUGH，保持"穿墙仍可见"；
        //   2) backgroundColor 传 accessibility opacity × 255 << 24，画一块随设置开合的半透明黑底衬字；
        //   3) shadow=false，阴影改由背景板承担（跟原版玩家名牌一致，白字在黑底上干净锐利）。
        // Targetted 的可见度差异保留在条身（fillAlpha=255）+ 黄色 ▶ 前缀上，不额外动文字样式。
        boolean targetted = data.targetted;
        int bgAlpha = 100;
        int fillAlpha = targetted ? 255 : 200;
        TextRenderer.TextLayerType textLayer = TextRenderer.TextLayerType.SEE_THROUGH;
        int textBgColor = (int) (MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25f) * 255.0f) << 24;

        float headerY = barTop - 12;
        if (targetted) {
            String arrow = "\u25B6 ";
            textRenderer.draw(arrow, -halfW, headerY, 0xFFFFFF55, false, matrix,
                    vertexConsumers, textLayer, textBgColor, light);
            float arrowW = textRenderer.getWidth(arrow);
            textRenderer.draw(nameOrdered, -halfW + arrowW, headerY, 0xFFFFFFFF, false, matrix,
                    vertexConsumers, textLayer, textBgColor, light);
        } else {
            textRenderer.draw(nameOrdered, -halfW, headerY, 0xFFFFFFFF, false, matrix,
                    vertexConsumers, textLayer, textBgColor, light);
        }
        if (suffixW > 0) {
            textRenderer.draw(suffixOrdered, halfW - suffixW, headerY, 0xFFFFFFFF, false, matrix,
                    vertexConsumers, textLayer, textBgColor, light);
        }

        float barW = halfW * 2;

        VertexConsumer bg = vertexConsumers.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
        bg.vertex(matrix, -halfW - 1, barTop - 1, 0.01f).color(0, 0, 0, bgAlpha).light(light);
        bg.vertex(matrix, -halfW - 1, barTop + barH + 1, 0.01f).color(0, 0, 0, bgAlpha).light(light);
        bg.vertex(matrix, halfW + 1, barTop + barH + 1, 0.01f).color(0, 0, 0, bgAlpha).light(light);
        bg.vertex(matrix, halfW + 1, barTop - 1, 0.01f).color(0, 0, 0, bgAlpha).light(light);

        // v5.3.2：怪物 HP > MaxHP 时接入队友相同的栈式溢出多色显示。
        //   · 原生档 (hp <= maxHP)：维持原来 getMobBarColor(pct) 红/橙/黄/绿 渐变，外观不变；
        //   · 溢出档 (hp > maxHP)：按 OVERFLOW_COLORS 6 档栈式绘制 —— 顶层从左填 topFill，下层底色铺右侧剩余。
        //   · topIdx = (hp-1)/maxHP 为第几层（0 基），溢出时恒 >= 1；hpInTop = 顶层剩余 HP。
        if (data.hp > 0 && data.maxHP > 0 && data.hp > data.maxHP) {
            int topIdx = (data.hp - 1) / data.maxHP;
            int hpInTop = data.hp - topIdx * data.maxHP;
            float topFill = Math.min(barW, (float) hpInTop / data.maxHP * barW);

            if (topFill < barW) {
                int bgColorIdx = Math.min(topIdx - 1, OVERFLOW_COLORS.length - 1);
                drawBarQuad(vertexConsumers, matrix, -halfW + topFill, -halfW + barW,
                        barTop, barH, OVERFLOW_COLORS[bgColorIdx], fillAlpha, light, 0.02f);
            }

            int topColorIdx = Math.min(topIdx, OVERFLOW_COLORS.length - 1);
            drawBarQuad(vertexConsumers, matrix, -halfW, -halfW + topFill,
                    barTop, barH, OVERFLOW_COLORS[topColorIdx], fillAlpha, light, 0.025f);
        } else {
            float fillW = barW * pct / 100.0f;
            if (fillW > 0) {
                int color = getMobBarColor(pct);
                int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
                VertexConsumer fill = vertexConsumers.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
                fill.vertex(matrix, -halfW, barTop, 0.02f).color(r, g, b, fillAlpha).light(light);
                fill.vertex(matrix, -halfW, barTop + barH, 0.02f).color(r, g, b, fillAlpha).light(light);
                fill.vertex(matrix, -halfW + fillW, barTop + barH, 0.02f).color(r, g, b, fillAlpha).light(light);
                fill.vertex(matrix, -halfW + fillW, barTop, 0.02f).color(r, g, b, fillAlpha).light(light);
            }
        }

        String hpText = data.hp + "/" + data.maxHP;
        matrices.push();
        matrices.scale(0.75f, 0.75f, 0.75f);
        Matrix4f smallMatrix = matrices.peek().getPositionMatrix();
        float scaledTextX = -textRenderer.getWidth(hpText) / 2.0f;
        float scaledTextY = (barTop + (barH - 9 * 0.75f) / 2.0f) / 0.75f;
        textRenderer.draw(hpText, scaledTextX, scaledTextY, 0xFFFFFFFF, false, smallMatrix,
                vertexConsumers, textLayer, textBgColor, light);
        matrices.pop();

        matrices.pop();
    }

    private static void drawBarQuad(VertexConsumerProvider vcp, Matrix4f matrix,
                                     float left, float right, float top, float h,
                                     int color, int alpha, int light, float zOff) {
        if (right <= left) return;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
        vc.vertex(matrix, left, top, zOff).color(r, g, b, alpha).light(light);
        vc.vertex(matrix, left, top + h, zOff).color(r, g, b, alpha).light(light);
        vc.vertex(matrix, right, top + h, zOff).color(r, g, b, alpha).light(light);
        vc.vertex(matrix, right, top, zOff).color(r, g, b, alpha).light(light);
    }

    private static int getMobBarColor(int pct) {
        if (pct <= 25) return 0xCC2222;
        if (pct <= 50) return 0xDD6622;
        if (pct <= 75) return 0xDDAA22;
        return 0x22CC44;
    }

    private static HealthData.TeammateData findTeammate(String name, Map<String, HealthData.TeammateData> map) {
        if (map.isEmpty() || name.isEmpty()) return null;

        HealthData.TeammateData data = map.get(name);
        if (data != null) return data;

        for (Map.Entry<String, HealthData.TeammateData> entry : map.entrySet()) {
            if (name.contains(entry.getKey()) || entry.getKey().contains(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static int getHpTextColor(int pct) {
        if (pct <= 25) return 0xFFFF6666;
        if (pct <= 50) return 0xFFFFDD44;
        return 0xFFFFFFFF;
    }

    private static int getBarColor(int pct) {
        if (pct <= 25) return 0xAA1515;
        if (pct <= 50) return 0xCC6620;
        if (pct <= 75) return 0xD83030;
        return 0xE84040;
    }
}
