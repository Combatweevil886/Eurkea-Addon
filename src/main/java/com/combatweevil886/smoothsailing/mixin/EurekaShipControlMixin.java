package com.combatweevil886.smoothsailing.mixin;

import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.PhysicsGuard;
import com.combatweevil886.smoothsailing.core.Stats;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.eureka.ship.EurekaShipControl;

/**
 * Eureka's per-ship controller. {@code physTick} runs on the Valkyrien Skies physics thread.
 */
@Mixin(value = EurekaShipControl.class, remap = false)
public abstract class EurekaShipControlMixin {

    // ---------------------------------------------------------------- exception shield

    @WrapMethod(method = "physTick", remap = false)
    private void smoothsailing$shieldPhysTick(PhysShip physShip, PhysLevel physLevel, Operation<Void> original) {
        Stats.PHYS_TICKS.incrementAndGet();
        if (!SSConfig.physicsExceptionShield) {
            original.call(physShip, physLevel);
            return;
        }
        try {
            original.call(physShip, physLevel);
        } catch (VirtualMachineError e) {
            if (!(e instanceof StackOverflowError)) {
                throw e; // out of memory etc. - nothing sensible to do here
            }
            PhysicsGuard.onPhysTickException(e);
        } catch (Throwable t) {
            PhysicsGuard.onPhysTickException(t);
        }
    }

    // ---------------------------------------------------------------- force / torque guards

    /** {@code physShip.applyWorldForce(force)} - Kotlin compiles the default-argument call to a static bridge. */
    @WrapOperation(method = {"physTick", "applyPlayerControl"}, remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/core/api/ships/PhysShip;applyWorldForce$default(Lorg/valkyrienskies/core/api/ships/PhysShip;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;ILjava/lang/Object;)V"))
    private void smoothsailing$guardWorldForce(PhysShip ship, Vector3dc force, Vector3dc pos, int mask, Object marker,
                                              Operation<Void> original) {
        Stats.hook("physics.applyWorldForce");
        Vector3dc safe = PhysicsGuard.checkForce(ship, force, "EurekaShipControl.applyWorldForce");
        if (safe == null || (pos != null && !PhysicsGuard.isFinite(pos))) {
            return;
        }
        original.call(ship, safe, pos, mask, marker);
    }

    @WrapOperation(method = {"physTick", "applyPlayerControl"}, remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/core/api/ships/PhysShip;applyWorldTorque(Lorg/joml/Vector3dc;)V"))
    private void smoothsailing$guardWorldTorque(PhysShip ship, Vector3dc torque, Operation<Void> original) {
        Stats.hook("physics.applyWorldTorque");
        if (PhysicsGuard.checkTorque(torque, "EurekaShipControl.applyWorldTorque")) {
            original.call(ship, torque);
        }
    }

    @WrapOperation(method = "physTick", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lorg/valkyrienskies/core/api/ships/PhysShip;setBuoyantFactor(D)V"))
    private void smoothsailing$guardBuoyancy(PhysShip ship, double factor, Operation<Void> original) {
        Stats.hook("physics.setBuoyantFactor");
        double safe = PhysicsGuard.checkBuoyantFactor(factor);
        if (!Double.isNaN(safe)) {
            original.call(ship, safe);
        }
    }

    /**
     * With {@code maxBalloonsPerEngine > 0} and no balloons on the ship, Eureka computes {@code 0 * min(1, 0/0)} = NaN
     * balloon force. Treat any non-finite result as "no lift".
     */
    @Inject(method = "getBalloonForce", at = @At("RETURN"), cancellable = true, remap = false)
    private void smoothsailing$finiteBalloonForce(CallbackInfoReturnable<Double> cir) {
        Stats.hook("physics.balloonForce");
        double v = cir.getReturnValueD();
        if (!Double.isFinite(v)) {
            Stats.event("physics.nonFiniteBalloonForceZeroed");
            cir.setReturnValue(0.0);
        }
    }

    // ---------------------------------------------------------------- cruise message off the physics thread

    @WrapOperation(method = "showCruiseStatus", remap = false, at = @At(value = "INVOKE", remap = true,
        target = "Lnet/minecraft/world/entity/player/Player;displayClientMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void smoothsailing$cruiseMessageOnServerThread(Player player, Component message, boolean actionBar,
                                                          Operation<Void> original) {
        Stats.hook("helm.cruiseMessage");
        if (SSConfig.mainThreadMessages && player instanceof ServerPlayer sp) {
            MinecraftServer server = sp.getServer();
            if (server != null && !server.isSameThread()) {
                server.execute(() -> {
                    if (!sp.hasDisconnected()) {
                        sp.displayClientMessage(message, actionBar);
                    }
                });
                return;
            }
        }
        original.call(player, message, actionBar);
    }

    // ---------------------------------------------------------------- part counts never negative

    @ModifyVariable(method = "setBalloons", at = @At("HEAD"), argsOnly = true, remap = false)
    private int smoothsailing$clampBalloons(int v) {
        return smoothsailing$clamp(v, "balloons");
    }

    @ModifyVariable(method = "setFloaters", at = @At("HEAD"), argsOnly = true, remap = false)
    private int smoothsailing$clampFloaters(int v) {
        return smoothsailing$clamp(v, "floaters");
    }

    @ModifyVariable(method = "setHelms", at = @At("HEAD"), argsOnly = true, remap = false)
    private int smoothsailing$clampHelms(int v) {
        return smoothsailing$clamp(v, "helms");
    }

    @ModifyVariable(method = "setAnchors", at = @At("HEAD"), argsOnly = true, remap = false)
    private int smoothsailing$clampAnchors(int v) {
        return smoothsailing$clamp(v, "anchors");
    }

    @ModifyVariable(method = "setAnchorsActive", at = @At("HEAD"), argsOnly = true, remap = false)
    private int smoothsailing$clampAnchorsActive(int v) {
        return smoothsailing$clamp(v, "anchorsActive");
    }

    private static int smoothsailing$clamp(int v, String what) {
        Stats.hook("parts.setter");
        if (v < 0 && SSConfig.clampPartCounts) {
            Stats.event("parts.negativeCountClamped." + what);
            return 0;
        }
        return v;
    }
}
