package com.combatweevil886.smoothsailing.mixin;

import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.Stats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.EurekaProperties;
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity;

/**
 * Every heat-level change of an engine is a block-state change: light update, neighbour + shape updates, a forced
 * synchronous chunk re-render on clients ({@code flags = 11}) and - on a ship - a Valkyrien Skies mass/collision
 * update. With some Eureka (Tweaks) heat settings the level sits right on a boundary and flips every few ticks.
 * <p>
 * This limits how often the visible heat state may change and drops the update flags nobody needs. The engine's
 * actual heat/power logic is untouched; only the block-state mirror of it is rate limited, and it is reconciled at the
 * end of every tick once the cooldown has passed.
 */
@Mixin(value = EngineBlockEntity.class, remap = false)
public abstract class EngineBlockEntityMixin {
    @Unique
    private static final int SMOOTHSAILING$LIGHT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @Unique
    private long smoothsailing$lastStateChange = Long.MIN_VALUE / 4;

    @Shadow(remap = false)
    private int getHeatLevel() {
        throw new AssertionError();
    }

    @WrapOperation(method = "tick", remap = false, at = @At(value = "INVOKE", remap = true,
        target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean smoothsailing$throttleHeatState(Level level, BlockPos pos, BlockState state, int flags,
                                                   Operation<Boolean> original) {
        Stats.hook("engine.heatStateUpdate");
        if (!SSConfig.engineThrottle) {
            return original.call(level, pos, state, SSConfig.engineLightweight ? SMOOTHSAILING$LIGHT_FLAGS : flags);
        }
        long now = level.getGameTime();
        if (now - smoothsailing$lastStateChange < SSConfig.engineMinTicks) {
            Stats.event("engine.heatStateUpdateDeferred");
            return false; // reconciled in smoothsailing$reconcileHeatState once the cooldown is over
        }
        smoothsailing$lastStateChange = now;
        return original.call(level, pos, state, SSConfig.engineLightweight ? SMOOTHSAILING$LIGHT_FLAGS : flags);
    }

    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void smoothsailing$reconcileHeatState(CallbackInfo ci) {
        if (!SSConfig.engineThrottle) {
            return;
        }
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide || self.isRemoved()) {
            return;
        }
        long now = level.getGameTime();
        if (now - smoothsailing$lastStateChange < SSConfig.engineMinTicks) {
            return;
        }
        BlockState current = self.getBlockState();
        IntegerProperty heat = EurekaProperties.INSTANCE.getHEAT();
        if (!current.hasProperty(heat)) {
            return;
        }
        int wanted = Math.max(0, Math.min(4, getHeatLevel()));
        if (current.getValue(heat) != wanted) {
            smoothsailing$lastStateChange = now;
            Stats.event("engine.heatStateReconciled");
            level.setBlock(self.getBlockPos(), current.setValue(heat, wanted),
                SSConfig.engineLightweight ? SMOOTHSAILING$LIGHT_FLAGS : Block.UPDATE_ALL_IMMEDIATE);
        }
    }
}
