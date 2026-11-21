package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.config.SSConfig;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.PhysShip;

/**
 * Validation for everything Eureka hands to the physics engine. Runs on the VS physics thread: no allocation on the
 * happy path, no Minecraft calls, no locks.
 */
public final class PhysicsGuard {
    private PhysicsGuard() {
    }

    public static boolean isFinite(Vector3dc v) {
        return v != null && Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }

    /**
     * @return the force to apply (possibly scaled down), or {@code null} if it must be dropped.
     */
    public static Vector3dc checkForce(PhysShip ship, Vector3dc force, String where) {
        Stats.FORCES_CHECKED.incrementAndGet();
        if (!SSConfig.forceGuard || force == null) {
            return force;
        }
        if (!isFinite(force)) {
            Stats.event("physics.nonFiniteForceDropped");
            RateLimitedLog.warn("nan-force-" + where,
                "[Smooth Sailing] Dropped a non-finite Eureka force at {} ({}). Usually a Eureka/Eureka Tweaks value is 0 or extreme.",
                where, force);
            return null;
        }
        double maxAccel = SSConfig.maxAcceleration;
        if (maxAccel > 0.0) {
            double mass = ship.getMass();
            if (Double.isFinite(mass) && mass > 0.0) {
                double limit = mass * maxAccel;
                double lenSq = force.lengthSquared();
                if (lenSq > limit * limit) {
                    Stats.event("physics.forceClamped");
                    RateLimitedLog.warn("clamp-force-" + where,
                        "[Smooth Sailing] Clamped a runaway Eureka force at {} ({} N on {} kg).", where, Math.sqrt(lenSq), mass);
                    return new Vector3d(force).mul(limit / Math.sqrt(lenSq));
                }
            }
        }
        return force;
    }

    /** @return {@code true} if the torque may be applied. */
    public static boolean checkTorque(Vector3dc torque, String where) {
        Stats.FORCES_CHECKED.incrementAndGet();
        if (!SSConfig.forceGuard || torque == null) {
            return true;
        }
        if (!isFinite(torque)) {
            Stats.event("physics.nonFiniteTorqueDropped");
            RateLimitedLog.warn("nan-torque-" + where,
                "[Smooth Sailing] Dropped a non-finite Eureka torque at {} ({}).", where, torque);
            return false;
        }
        return true;
    }

    /** @return the buoyant factor to apply, or {@code NaN} to skip the call. */
    public static double checkBuoyantFactor(double factor) {
        if (!SSConfig.buoyancyGuard) {
            return factor;
        }
        if (!Double.isFinite(factor)) {
            Stats.event("physics.nonFiniteBuoyancyDropped");
            RateLimitedLog.warn("nan-buoyancy", "[Smooth Sailing] Dropped a non-finite floater buoyancy factor ({}).", factor);
            return Double.NaN;
        }
        if (factor < 0.0) {
            Stats.event("physics.negativeBuoyancyClamped");
            RateLimitedLog.warn("neg-buoyancy",
                "[Smooth Sailing] Floater buoyancy factor was negative ({}); clamped to 0. The ship's floater count is probably wrong - try /smoothsailing recount.",
                factor);
            return 0.0;
        }
        double max = SSConfig.maxBuoyantFactor;
        if (max > 0.0 && factor > max) {
            Stats.event("physics.buoyancyCapped");
            return max;
        }
        return factor;
    }

    public static void onPhysTickException(Throwable t) {
        Stats.event("physics.tickExceptionCaught");
        RateLimitedLog.error("phys-tick-" + t.getClass().getName(),
            "[Smooth Sailing] Eureka's ship controller threw on the physics thread; the ship skipped this tick instead of crashing physics.", t);
    }
}
