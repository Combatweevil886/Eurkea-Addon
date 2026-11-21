package com.combatweevil886.smoothsailing.mixin;

import com.combatweevil886.smoothsailing.core.HelmUtil;
import com.combatweevil886.smoothsailing.core.Stats;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.block.EngineBlock;
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity;

/** Same hard-cast problem as the helm, in both {@code use} and {@code playerWillDestroy}. */
@Mixin(value = EngineBlock.class, remap = false)
public abstract class EngineBlockMixin {

    @Inject(method = {"use", "m_6227_"}, at = @At("HEAD"), remap = false)
    private void smoothsailing$ensureEngineOnUse(BlockState state, Level level, BlockPos pos, Player player,
                                                InteractionHand hand, BlockHitResult hit,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        Stats.hook("engine.use");
        HelmUtil.ensureBlockEntity(level, pos, state, EngineBlockEntity.class);
    }

    @Inject(method = {"playerWillDestroy", "m_5707_"}, at = @At("HEAD"), remap = false)
    private void smoothsailing$ensureEngineOnBreak(Level level, BlockPos pos, BlockState state, Player player,
                                                  CallbackInfo ci) {
        Stats.hook("engine.playerWillDestroy");
        HelmUtil.ensureBlockEntity(level, pos, state, EngineBlockEntity.class);
    }
}
