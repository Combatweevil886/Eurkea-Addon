package com.combatweevil886.smoothsailing.mixin;

import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.Stats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.eureka.block.FloaterBlock;
import org.valkyrienskies.eureka.ship.EurekaShipControl;

import java.util.function.Consumer;

/**
 * {@code FloaterBlock.neighborChanged} adds {@code (oldPower - newPower)} to the ship's floater count and then calls
 * {@code level.setBlock(pos, state.setValue(POWER, newPower))}. That state change runs {@code onRemove(old)} and
 * {@code onPlace(new)}, which apply exactly the same delta again. Powering a floater therefore subtracts twice and the
 * count goes negative, which turns buoyancy negative and drags the ship under water.
 * <p>
 * The direct adjustment is the duplicate, so it is skipped; onRemove/onPlace stay the single source of truth.
 */
@Mixin(value = FloaterBlock.class, remap = false)
public abstract class FloaterBlockMixin {

    @WrapOperation(method = {"neighborChanged", "m_6861_"}, remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/eureka/ship/EurekaShipControl$Companion;deferUntilLoaded(Lorg/valkyrienskies/core/api/ships/ServerShip;Ljava/util/function/Consumer;)V"))
    private void smoothsailing$dropDuplicateFloaterDelta(EurekaShipControl.Companion companion, ServerShip ship,
                                                        Consumer<EurekaShipControl> action, Operation<Void> original) {
        Stats.hook("floater.neighborChanged");
        if (SSConfig.fixFloaterDoubleCount) {
            Stats.event("parts.floaterDoubleCountPrevented");
            return;
        }
        original.call(companion, ship, action);
    }
}
