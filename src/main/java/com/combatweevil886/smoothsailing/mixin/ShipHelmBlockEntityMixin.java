package com.combatweevil886.smoothsailing.mixin;

import com.combatweevil886.smoothsailing.core.AssemblyGuard;
import com.combatweevil886.smoothsailing.core.HelmUtil;
import com.combatweevil886.smoothsailing.core.ShipPartRecount;
import com.combatweevil886.smoothsailing.core.Stats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import kotlin.jvm.functions.Function1;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity;
import org.valkyrienskies.eureka.ship.EurekaShipControl;
import org.valkyrienskies.eureka.util.ShipAssembler;

@Mixin(value = ShipHelmBlockEntity.class, remap = false)
public abstract class ShipHelmBlockEntityMixin {

    @Shadow(remap = false)
    private LoadedServerShip getShip() {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private EurekaShipControl getControl() {
        throw new AssertionError();
    }

    // ---------------------------------------------------------------- per-tick housekeeping (server only)

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void smoothsailing$afterTick(CallbackInfo ci) {
        Stats.hook("helm.tick");
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Spread the work: each helm does its checks once a second, on its own tick offset.
        long phase = Math.floorMod(level.getGameTime() + self.getBlockPos().asLong(), 20L);
        if (phase != 0L) {
            return;
        }
        LoadedServerShip ship = getShip();
        if (ship == null) {
            return;
        }
        EurekaShipControl control = getControl();
        if (control == null) {
            return;
        }
        HelmUtil.cleanupSeatedPlayer(level, ship, control);
        ShipPartRecount.noteShip(level, ship);
    }

    // ---------------------------------------------------------------- assembly

    @Inject(method = "assemble", at = @At("HEAD"), cancellable = true, remap = false)
    private void smoothsailing$assembleCooldown(Player player, CallbackInfo ci) {
        Stats.hook("helm.assemble");
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level != null && AssemblyGuard.onCooldown(player, level.getGameTime())) {
            Stats.event("assembly.cooldownBlocked");
            player.displayClientMessage(Component.literal("Smooth Sailing: wait a moment before assembling again.")
                .withStyle(ChatFormatting.YELLOW), true);
            ci.cancel();
        }
    }

    @WrapOperation(method = "assemble", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/eureka/util/ShipAssembler;collectBlocks(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lkotlin/jvm/functions/Function1;)Lorg/valkyrienskies/core/api/ships/ServerShip;"))
    private ServerShip smoothsailing$trackAssembly(ShipAssembler assembler, ServerLevel level, BlockPos center,
                                                   Function1<? super BlockState, Boolean> predicate,
                                                   Operation<ServerShip> original,
                                                   @Local(argsOnly = true) Player player) {
        AssemblyGuard.Context ctx = AssemblyGuard.begin();
        try {
            ServerShip result = original.call(assembler, level, center, predicate);
            AssemblyGuard.report(player, ctx, result);
            return result;
        } finally {
            AssemblyGuard.end();
        }
    }

    // ---------------------------------------------------------------- disassembly

    @WrapOperation(method = "disassemble", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/eureka/util/ShipAssembler;unfillShip(Lnet/minecraft/server/level/ServerLevel;Lorg/valkyrienskies/core/api/ships/ServerShip;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V"))
    private void smoothsailing$guardDisassembly(ShipAssembler assembler, ServerLevel level, ServerShip ship,
                                               BlockPos shipCenter, BlockPos worldCenter, Operation<Void> original) {
        Stats.hook("helm.disassemble");
        if (ship instanceof LoadedServerShip loaded) {
            String reason = AssemblyGuard.disassemblyBlockedReason(level, loaded);
            if (reason != null) {
                Stats.event("disassembly.refusedOutOfBounds");
                EurekaShipControl control = getControl();
                if (control != null) {
                    control.setDisassembling(false);
                    control.setAligning(false);
                }
                HelmUtil.messageNearShip(level, loaded, control, Component.literal(
                    "Smooth Sailing: can't disassemble here - " + reason + ". Those blocks would be deleted. Move the ship and try again.")
                    .withStyle(ChatFormatting.RED));
                return;
            }
        }
        original.call(assembler, level, ship, shipCenter, worldCenter);
    }
}
