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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.block.ShipHelmBlock;
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity;

@Mixin(value = ShipHelmBlock.class, remap = false)
public abstract class ShipHelmBlockMixin {

    /** Eureka casts the block entity without a null check; a missing one would throw on every right click. */
    // "use" in the dev environment, "m_6227_" in production (Eureka ships SRG-named overrides).
    @Inject(method = {"use", "m_6227_"}, at = @At("HEAD"), remap = false)
    private void smoothsailing$ensureHelmBlockEntity(BlockState state, Level level, BlockPos pos, Player player,
                                                    InteractionHand hand, BlockHitResult hit,
                                                    CallbackInfoReturnable<InteractionResult> cir) {
        Stats.hook("helm.use");
        HelmUtil.ensureBlockEntity(level, pos, state, ShipHelmBlockEntity.class);
    }
}
