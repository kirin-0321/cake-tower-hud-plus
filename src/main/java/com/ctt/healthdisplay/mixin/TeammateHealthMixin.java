package com.ctt.healthdisplay.mixin;

import com.ctt.healthdisplay.config.ModConfig;
import com.ctt.healthdisplay.health.HealthData;
import com.ctt.healthdisplay.health.MobHealthData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(EntityRenderer.class)
public abstract class TeammateHealthMixin<S extends EntityRenderState> {

    private static final Logger LOGGER = LoggerFactory.getLogger("CTT-HeadHP");
    private static long lastDebugTime = 0;

    @Shadow
    protected EntityRenderDispatcher dispatcher;

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void ctt_onLabel(EntityRenderState state, Text text, MatrixStack matrices,
                             VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        String label = text.getString().trim();
        String entityName = state.displayName != null ? state.displayName.getString().trim() : "";
        Vec3d pos = state.nameLabelPos;

        long now = System.currentTimeMillis();
        if (now - lastDebugTime > 2000) {
            lastDebugTime = now;
            LOGGER.info("[DEBUG] renderLabelIfPresent: label='{}', displayName='{}', nameLabelPos={}, mobMapSize={}",
                    label, entityName, pos != null ? "set" : "null", HealthData.getMobHealthMap().size());
        }

        // 三档 mob 头顶血量：
        //   OFF       - 不碰 vanilla 标签，整个 if 块跳过；
        //   ALL       - 找到匹配的 MobHealthData 就 cancel 原 vanilla 标签（CTT 会自己画）；
        //   NEAREST   - 只对 targetted（bossbar 锁定的那只）cancel；其他同名 mob 保留 vanilla 名字，
        //               避免场上全是名字裸块。
        if (ModConfig.INSTANCE.isMobHeadHPEnabled()) {
            String matchName = !entityName.isEmpty() ? entityName : label;
            if (!matchName.isEmpty()) {
                MobHealthData matched = findMobWithHealth(matchName, text);
                if (matched != null) {
                    boolean shouldCancel = !ModConfig.INSTANCE.isMobHeadHPNearestOnly() || matched.targetted;
                    if (shouldCancel) {
                        ci.cancel();
                        return;
                    }
                }
            }
        }

        if (!ModConfig.INSTANCE.showTeammateHeadHP) return;

        if (label.matches("\\d+\\s*[❤♥\u2764\u2665].*") && !entityName.isEmpty()) {
            if (findTeammate(entityName) != null) {
                ci.cancel();
                return;
            }
        }

        if (pos == null || entityName.isEmpty()) return;

        HealthData.TeammateData data = findTeammate(entityName);
        if (data == null) {
            data = findTeammate(label);
        }
        if (data == null) return;

        int pct = data.getPercent();

        matrices.push();
        matrices.translate((float) pos.x, (float) pos.y + 0.3f, (float) pos.z);
        matrices.multiply(this.dispatcher.getRotation());
        matrices.scale(-0.025F, -0.025F, 0.025F);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;

        String hpText = data.maxHP > 0 ? data.hp + "/" + data.maxHP : String.valueOf(data.hp);
        float textX = -textRenderer.getWidth(hpText) / 2.0f;
        textRenderer.draw(hpText, textX, -20, getHpTextColor(pct), false, matrix,
                vertexConsumers, TextRenderer.TextLayerType.SEE_THROUGH, 0, light);

        float halfW = 20;
        float barTop = -12;
        float barH = 3;
        float fillW = halfW * 2 * pct / 100.0f;

        VertexConsumer bg = vertexConsumers.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
        bg.vertex(matrix, -halfW - 1, barTop + barH + 1, 0.01f).color(0, 0, 0, 100).light(light);
        bg.vertex(matrix, halfW + 1, barTop + barH + 1, 0.01f).color(0, 0, 0, 100).light(light);
        bg.vertex(matrix, halfW + 1, barTop - 1, 0.01f).color(0, 0, 0, 100).light(light);
        bg.vertex(matrix, -halfW - 1, barTop - 1, 0.01f).color(0, 0, 0, 100).light(light);

        if (fillW > 0) {
            int color = getBarColor(pct);
            int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
            VertexConsumer fill = vertexConsumers.getBuffer(RenderLayer.getTextBackgroundSeeThrough());
            fill.vertex(matrix, -halfW, barTop + barH, 0.02f).color(r, g, b, 200).light(light);
            fill.vertex(matrix, -halfW + fillW, barTop + barH, 0.02f).color(r, g, b, 200).light(light);
            fill.vertex(matrix, -halfW + fillW, barTop, 0.02f).color(r, g, b, 200).light(light);
            fill.vertex(matrix, -halfW, barTop, 0.02f).color(r, g, b, 200).light(light);
        }

        matrices.pop();
    }

    private static MobHealthData findMobWithHealth(String displayName, Text labelText) {
        Map<java.util.UUID, MobHealthData> mobMap = HealthData.getMobHealthMap();
        if (mobMap.isEmpty()) return null;

        for (MobHealthData mob : mobMap.values()) {
            String mobName = mob.name();
            if (mobName.equalsIgnoreCase(displayName) || displayName.contains(mobName) || mobName.contains(displayName)) {
                return mob;
            }
        }
        return null;
    }

    private static HealthData.TeammateData findTeammate(String displayName) {
        Map<String, HealthData.TeammateData> map = HealthData.getTeammateMap();
        if (map.isEmpty() || displayName.isEmpty()) return null;

        HealthData.TeammateData data = map.get(displayName);
        if (data != null) return data;

        for (Map.Entry<String, HealthData.TeammateData> entry : map.entrySet()) {
            if (displayName.contains(entry.getKey()) || entry.getKey().contains(displayName)) {
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
