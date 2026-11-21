package com.combatweevil886.smoothsailing.mixin.client;

import com.combatweevil886.smoothsailing.client.WheelModelFix;
import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.Stats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.block.ShipHelmBlock;
import org.valkyrienskies.eureka.blockentity.renderer.WheelModels;

@Mixin(value = WheelModels.class, remap = false)
public abstract class WheelModelsMixin {

    /** Eureka casts {@code blockState.block as ShipHelmBlock}; any other state crashes the render thread. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void smoothsailing$skipForeignState(PoseStack poseStack, BlockEntity blockEntity, MultiBufferSource buffer,
                                               int light, int overlay, CallbackInfo ci) {
        Stats.hook("client.wheelRender");
        if (SSConfig.rendererSafety && (blockEntity == null || !(blockEntity.getBlockState().getBlock() instanceof ShipHelmBlock))) {
            Stats.event("client.wheelRenderSkipped");
            ci.cancel();
        }
    }

    /** Use the model from the live model manager instead of the one cached at the very first resource load. */
    @WrapOperation(method = "render", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/eureka/blockentity/renderer/WheelModels$WheelModel;getModel()Lnet/minecraft/client/resources/model/BakedModel;"))
    private BakedModel smoothsailing$liveWheelModel(WheelModels.WheelModel wheel, Operation<BakedModel> original,
                                                   @Local(argsOnly = true) BlockEntity blockEntity) {
        if (SSConfig.wheelModelFix) {
            BakedModel live = WheelModelFix.lookup(blockEntity.getBlockState());
            if (live != null) {
                return live;
            }
        }
        return original.call(wheel);
    }
}
