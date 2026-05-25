package com.ctt.healthdisplay.mixin;

import com.ctt.healthdisplay.CttHealthDisplay;
import com.ctt.healthdisplay.client.HudRenderGate;
import com.ctt.healthdisplay.hud.TeammateWorldRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("CTT-WorldMixin");

    @Unique
    private static boolean logged = false;

    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;

    @Inject(
            method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("TAIL"),
            require = 0
    )
    private void ctt_afterRenderEntity(Entity entity, double cameraX, double cameraY, double cameraZ,
                                       float tickDelta, MatrixStack matrices,
                                       VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        if (!logged) {
            logged = true;
            LOGGER.info("WorldRendererMixin applied successfully!");
        }
        if (HudRenderGate.shouldSuppressModHud(CttHealthDisplay.statsData)) return;

        TeammateWorldRenderer.renderHealthBar(entity, cameraX, cameraY, cameraZ,
                tickDelta, matrices, vertexConsumers, entityRenderDispatcher);
        TeammateWorldRenderer.renderMobHealthBar(entity, cameraX, cameraY, cameraZ,
                tickDelta, matrices, vertexConsumers, entityRenderDispatcher);
    }
}
