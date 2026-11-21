package com.combatweevil886.smoothsailing.mixin.client;

import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.RateLimitedLog;
import com.combatweevil886.smoothsailing.core.Stats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.eureka.block.ShipHelmBlock;
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity;
import org.valkyrienskies.eureka.blockentity.renderer.ShipHelmBlockEntityRenderer;

@Mixin(value = ShipHelmBlockEntityRenderer.class, remap = false)
public abstract class ShipHelmBlockEntityRendererMixin {
    /** The renderer reads HORIZONTAL_FACING unconditionally; a foreign state would throw while rendering the world. */
    @Inject(method = "render(Lorg/valkyrienskies/eureka/blockentity/ShipHelmBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void smoothsailing$guardState(ShipHelmBlockEntity blockEntity, float partialTicks, PoseStack poseStack,
                                         MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        Stats.hook("client.helmRender");
        if (!SSConfig.rendererSafety) {
            return;
        }
        BlockState state = blockEntity.getBlockState();
        if (!(state.getBlock() instanceof ShipHelmBlock) || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Stats.event("client.helmRenderSkipped");
            ci.cancel();
        }
    }

    /** Ship lookup for the wheel spin; schematic/contraption preview levels can throw here. */
    @WrapOperation(method = "render(Lorg/valkyrienskies/eureka/blockentity/ShipHelmBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/mod/common/VSGameUtilsKt;getShipManagingPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lorg/valkyrienskies/core/api/ships/Ship;"))
    private Ship smoothsailing$safeShipLookup(Level level, BlockPos pos, Operation<Ship> original) {
        if (!SSConfig.rendererSafety) {
            return original.call(level, pos);
        }
        try {
            return original.call(level, pos);
        } catch (Throwable t) {
            Stats.event("client.helmShipLookupFailed");
            RateLimitedLog.error("helm-render-lookup", "[Smooth Sailing] Ship lookup failed while rendering a helm wheel (level "
                + (level == null ? "null" : level.getClass().getName()) + "); rendering it as not on a ship.", t);
            return null;
        }
    }
}
