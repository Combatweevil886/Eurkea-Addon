package com.combatweevil886.smoothsailing.mixin;

import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.Stats;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity;
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmScreenMenu;

/** Eureka's helm menu answers {@code stillValid = true} forever, even after the helm was broken. */
@Mixin(value = ShipHelmScreenMenu.class, remap = false)
public abstract class ShipHelmScreenMenuMixin {

    @Shadow(remap = false)
    @Final
    private ShipHelmBlockEntity blockEntity;

    @Inject(method = {"stillValid", "m_6875_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void smoothsailing$closeWhenHelmGone(Player player, CallbackInfoReturnable<Boolean> cir) {
        Stats.hook("helm.menuStillValid");
        if (SSConfig.helmMenuValidation && blockEntity != null && blockEntity.isRemoved()) {
            cir.setReturnValue(false);
        }
    }
}
