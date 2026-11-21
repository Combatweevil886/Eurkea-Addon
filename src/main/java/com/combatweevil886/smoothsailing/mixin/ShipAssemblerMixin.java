package com.combatweevil886.smoothsailing.mixin;

import com.combatweevil886.smoothsailing.core.AssemblyGuard;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.util.ShipAssembler;

/** Eureka's helm block search (flood fill from the helm). */
@Mixin(value = ShipAssembler.class, remap = false)
public abstract class ShipAssemblerMixin {

    @WrapOperation(method = "bfs", remap = false, at = @At(value = "INVOKE", remap = true,
        target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState smoothsailing$guardedGetBlockState(ServerLevel level, BlockPos pos, Operation<BlockState> original) {
        return AssemblyGuard.guardedGetBlockState(level, pos, original);
    }

    @ModifyExpressionValue(method = "bfs", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/eureka/EurekaConfig$Server;getMaxShipBlocks()I"))
    private int smoothsailing$capUnlimitedAssembly(int original) {
        return AssemblyGuard.effectiveMaxShipBlocks(original);
    }
}
