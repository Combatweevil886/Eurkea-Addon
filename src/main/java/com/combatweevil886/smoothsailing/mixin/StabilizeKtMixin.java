package com.combatweevil886.smoothsailing.mixin;

import com.combatweevil886.smoothsailing.core.PhysicsGuard;
import com.combatweevil886.smoothsailing.core.Stats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.eureka.ship.StabilizeKt;

/** Eureka's self-levelling (top-level Kotlin function {@code stabilize}), physics thread. */
@Mixin(value = StabilizeKt.class, remap = false)
public abstract class StabilizeKtMixin {

    @WrapOperation(method = "stabilize", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/core/api/ships/PhysShip;applyInvariantTorque(Lorg/joml/Vector3dc;)V"))
    private static void smoothsailing$guardStabilizeTorque(PhysShip ship, Vector3dc torque, Operation<Void> original) {
        Stats.hook("physics.stabilizeTorque");
        if (PhysicsGuard.checkTorque(torque, "stabilize.applyInvariantTorque")) {
            original.call(ship, torque);
        }
    }

    @WrapOperation(method = "stabilize", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/core/api/ships/PhysShip;applyInvariantForce(Lorg/joml/Vector3dc;)V"))
    private static void smoothsailing$guardStabilizeForce(PhysShip ship, Vector3dc force, Operation<Void> original) {
        Stats.hook("physics.stabilizeForce");
        Vector3dc safe = PhysicsGuard.checkForce(ship, force, "stabilize.applyInvariantForce");
        if (safe != null) {
            original.call(ship, safe);
        }
    }
}
