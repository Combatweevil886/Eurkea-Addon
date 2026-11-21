package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.SmoothSailing;
import com.combatweevil886.smoothsailing.config.SSConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.valkyrienskies.eureka.EurekaConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Keeps {@link EurekaConfig#SERVER} inside the range where Eureka's ship controller math stays finite.
 * <p>
 * Eureka itself does not validate its config, and Eureka Tweaks exposes in-game sliders that start at 0 for several
 * values that are used as divisors (e.g. {@code elevationSnappiness}, {@code speedMassScale}, {@code linearMaxMass},
 * {@code maxSpeedFromEngines}). Any of those at 0 makes every Eureka ship produce NaN forces.
 */
public final class ConfigSanitizer {
    private static final List<Rule> RULES = new ArrayList<>();
    /** name -> description of the last correction, for /smoothsailing status. */
    private static final Map<String, String> CORRECTIONS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Set<String> WARNED_OPS = ConcurrentHashMap.newKeySet();

    private record Rule(String name, DoubleSupplier getter, DoubleConsumer setter, double min, double max, double fallback,
                        String why) {
    }

    static {
        EurekaConfig.Server s = EurekaConfig.SERVER;
        // Divisors / values that make the controller NaN at 0
        d("elevationSnappiness", s::getElevationSnappiness, s::setElevationSnappiness, 0.01, 1.0E6, 1.0, "divides gravity; 0 gives NaN lift");
        d("linearMaxMass", s::getLinearMaxMass, s::setLinearMaxMass, 1.0, 1.0E12, 10000.0, "divisor in thrust smoothing; 0 gives NaN thrust");
        d("linearBaseMass", s::getLinearBaseMass, s::setLinearBaseMass, 1.0, 1.0E12, 50.0, "must be positive (Eureka docs); 0 gives NaN thrust");
        d("speedMassScale", s::getSpeedMassScale, s::setSpeedMassScale, 0.001, 1.0E6, 1.0, "engine boost is divided by mass*scale");
        d("maxSpeedFromEngines", s::getMaxSpeedFromEngines, s::setMaxSpeedFromEngines, 0.01, 1.0E6, 12.0, "divisor in speed smoothing");
        d("maxReverseSpeedFromEngines", s::getMaxReverseSpeedFromEngines, s::setMaxReverseSpeedFromEngines, 0.01, 1.0E6, 5.0, "divisor in speed smoothing");
        d("maxSizeForTurnSpeedPenalty", s::getMaxSizeForTurnSpeedPenalty, s::setMaxSizeForTurnSpeedPenalty, 0.5, 1.0E6, 16.0, "below 0.5 Kotlin's coerceIn throws on the physics thread");
        d("antiVelocityMassRelevance", s::getAntiVelocityMassRelevance, s::setAntiVelocityMassRelevance, 0.0, 10.0, 0.8, "above 10 the brake accelerates the ship");
        d("popSideBalloonChance", s::getPopSideBalloonChance, s::setPopSideBalloonChance, 0.0, 1.0, 0.3, "probability");
        // Non-negative physical quantities
        d("massPerBalloon", s::getMassPerBalloon, s::setMassPerBalloon, 0.0, 1.0E12, 5000.0, "negative lift");
        d("floaterBuoyantLift", s::getFloaterBuoyantLift, s::setFloaterBuoyantLift, 0.0, 1.0E12, 2000.0, "negative buoyancy");
        d("maxFloaterBuoyantFactor", s::getMaxFloaterBuoyantFactor, s::setMaxFloaterBuoyantFactor, 0.0, 1.0E6, 0.1, "negative buoyancy");
        d("ballastWeight", s::getBallastWeight, s::setBallastWeight, 0.0, 1.0E12, 10000.0, "negative block mass breaks physics");
        d("ballastNoWeight", s::getBallastNoWeight, s::setBallastNoWeight, 0.0, 1.0E12, 1000.0, "negative block mass breaks physics");
        d("turnSpeed", s::getTurnSpeed, s::setTurnSpeed, 0.0, 1.0E6, 3.0, "non-negative");
        d("turnAcceleration", s::getTurnAcceleration, s::setTurnAcceleration, 0.0, 1.0E6, 10.0, "non-negative");
        d("stabilizationTorqueConstant", s::getStabilizationTorqueConstant, s::setStabilizationTorqueConstant, 0.0, 1.0E6, 15.0, "non-negative");
        d("linearStabilizeMaxAntiVelocity", s::getLinearStabilizeMaxAntiVelocity, s::setLinearStabilizeMaxAntiVelocity, 0.0, 1.0E6, 1.0, "non-negative");
        d("scaledInstability", s::getScaledInstability, s::setScaledInstability, 0.0, 1.0E12, 70.0, "non-negative");
        d("unscaledInstability", s::getUnscaledInstability, s::setUnscaledInstability, 0.0, 1.0E12, 0.1, "non-negative");
        d("linearMassScaling", s::getLinearMassScaling, s::setLinearMassScaling, 0.0, 1.0E6, 0.0002, "non-negative");
        d("balloonElevationMaxSpeed", s::getBalloonElevationMaxSpeed, s::setBalloonElevationMaxSpeed, 0.0, 1.0E6, 5.5, "non-negative");
        // Only need to be finite
        d("engineBoost", s::getEngineBoost, s::setEngineBoost, -1.0E12, 1.0E12, 0.2, "finite");
        d("engineBoostOffset", s::getEngineBoostOffset, s::setEngineBoostOffset, -1.0E12, 1.0E12, 2.5, "finite");
        d("engineBoostExponentialPower", s::getEngineBoostExponentialPower, s::setEngineBoostExponentialPower, -1.0E12, 1.0E12, 0.000001, "finite");
        d("stabilizationSpeed", s::getStabilizationSpeed, s::setStabilizationSpeed, -1.0E12, 1.0E12, 10.0, "finite");
        d("baseImpulseElevationRate", s::getBaseImpulseElevationRate, s::setBaseImpulseElevationRate, -1.0E12, 1.0E12, 2.0, "finite");
        d("baseImpulseDescendRate", s::getBaseImpulseDescendRate, s::setBaseImpulseDescendRate, -1.0E12, 1.0E12, 4.0, "finite");
        d("linearBaseSpeed", s::getLinearBaseSpeed, s::setLinearBaseSpeed, -1.0E12, 1.0E12, 3.0, "finite");
        d("passiveBalloonMinHeight", s::getPassiveBalloonMinHeight, s::setPassiveBalloonMinHeight, -1.0E9, 1.0E9, 64.0, "finite");
        d("passiveBalloonMaxHeight", s::getPassiveBalloonMaxHeight, s::setPassiveBalloonMaxHeight, -1.0E9, 1.0E9, 400.0, "finite");
        // Floats
        f("enginePowerLinear", s::getEnginePowerLinear, s::setEnginePowerLinear, 0.0, 1.0E15, 500000.0, "non-negative");
        f("enginePowerLinearMin", s::getEnginePowerLinearMin, s::setEnginePowerLinearMin, 0.0, 1.0E15, 10000.0, "non-negative");
        f("enginePowerAngular", s::getEnginePowerAngular, s::setEnginePowerAngular, -1.0E9, 1.0E9, 1.0, "finite");
        f("enginePowerAngularMin", s::getEnginePowerAngularMin, s::setEnginePowerAngularMin, -1.0E9, 1.0E9, 0.0, "finite");
        f("engineHeatLoss", s::getEngineHeatLoss, s::setEngineHeatLoss, 0.0, 100.0, 0.01, "non-negative");
        f("engineHeatGain", s::getEngineHeatGain, s::setEngineHeatGain, 0.0, 100.0, 0.03, "non-negative");
        f("engineHeatChangeExponent", s::getEngineHeatChangeExponent, s::setEngineHeatChangeExponent, -1.0E6, 1.0E6, 0.1, "finite");
        f("engineFuelMultiplier", s::getEngineFuelMultiplier, s::setEngineFuelMultiplier, 0.0, 1.0E6, 2.0, "non-negative");
        // Ints
        i("maxBalloonsPerEngine", s::getMaxBalloonsPerEngine, s::setMaxBalloonsPerEngine, 0, Integer.MAX_VALUE, 0);
        i("engineMinCapacity", s::getEngineMinCapacity, s::setEngineMinCapacity, 0, Integer.MAX_VALUE, 2000);
        i("maxShipBlocks", s::getMaxShipBlocks, s::setMaxShipBlocks, 0, Integer.MAX_VALUE, 32768);
        i("disassemblingTimeUntilForced", s::getDisassemblingTimeUntilForced, s::setDisassemblingTimeUntilForced, 0, Integer.MAX_VALUE, 1000);
    }

    private ConfigSanitizer() {
    }

    private static void d(String name, DoubleSupplier g, DoubleConsumer st, double min, double max, double fallback, String why) {
        RULES.add(new Rule(name, g, st, min, max, fallback, why));
    }

    @FunctionalInterface
    private interface FloatGetter {
        float get();
    }

    @FunctionalInterface
    private interface FloatSetter {
        void set(float v);
    }

    @FunctionalInterface
    private interface IntGetter {
        int get();
    }

    @FunctionalInterface
    private interface IntSetter {
        void set(int v);
    }

    private static void f(String name, FloatGetter g, FloatSetter st, double min, double max, double fallback, String why) {
        RULES.add(new Rule(name, g::get, v -> st.set((float) v), min, max, fallback, why));
    }

    private static void i(String name, IntGetter g, IntSetter st, int min, int max, int fallback) {
        RULES.add(new Rule(name, g::get, v -> st.set((int) v), min, max, fallback, "integer range"));
    }

    /**
     * Checks every rule and fixes what is out of range.
     *
     * @return number of values corrected in this pass
     */
    public static int run(MinecraftServer server) {
        if (!SSConfig.sanitizer) {
            return 0;
        }
        int fixed = 0;
        for (Rule r : RULES) {
            double v;
            try {
                v = r.getter.getAsDouble();
            } catch (Throwable t) {
                continue; // field missing in some other Eureka version - ignore
            }
            double target = v;
            if (!Double.isFinite(v)) {
                target = r.fallback;
            } else if (v < r.min) {
                target = r.min;
            } else if (v > r.max) {
                target = r.max;
            }
            if (target != v) {
                apply(server, r, v, target);
                fixed++;
            }
        }
        fixed += crossChecks(server);
        return fixed;
    }

    private static int crossChecks(MinecraftServer server) {
        int fixed = 0;
        EurekaConfig.Server s = EurekaConfig.SERVER;
        try {
            // passive balloons divide by (max - min)
            double min = s.getPassiveBalloonMinHeight();
            double max = s.getPassiveBalloonMaxHeight();
            if (max <= min) {
                double target = min + 1.0;
                note(server, "passiveBalloonMaxHeight", max, target, "must be above passiveBalloonMinHeight (divisor)");
                s.setPassiveBalloonMaxHeight(target);
                fixed++;
            }
            // balloon power share divides by enginePowerLinear when maxBalloonsPerEngine is on
            if (s.getMaxBalloonsPerEngine() > 0 && s.getEnginePowerLinear() < 1.0f) {
                note(server, "enginePowerLinear", s.getEnginePowerLinear(), 1.0, "divisor while maxBalloonsPerEngine > 0");
                s.setEnginePowerLinear(1.0f);
                fixed++;
            }
        } catch (Throwable ignored) {
        }
        return fixed;
    }

    private static void apply(MinecraftServer server, Rule r, double from, double to) {
        r.setter.accept(to);
        note(server, r.name, from, to, r.why);
    }

    private static void note(MinecraftServer server, String name, double from, double to, String why) {
        Stats.event("sanitizer.valueClamped");
        String text = name + ": " + from + " -> " + to + " (" + why + ")";
        String prev = CORRECTIONS.put(name, text);
        if (!text.equals(prev)) {
            SmoothSailing.LOGGER.warn("[Smooth Sailing] EurekaConfig.{} was {} - {}; clamped to {}. (If Eureka Tweaks set this, move its slider off the limit.)",
                name, from, why, to);
        }
        if (server != null && SSConfig.sanitizerWarnOps && WARNED_OPS.add(name)) {
            Component msg = Component.literal("[Smooth Sailing] Eureka setting '" + name + "' was " + from
                + " which breaks ship physics (" + why + "). Using " + to + " instead.").withStyle(ChatFormatting.YELLOW);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.hasPermissions(2)) {
                    p.sendSystemMessage(msg);
                }
            }
        }
    }

    public static Map<String, String> corrections() {
        synchronized (CORRECTIONS) {
            return new LinkedHashMap<>(CORRECTIONS);
        }
    }

    public static void resetSession() {
        WARNED_OPS.clear();
        CORRECTIONS.clear();
    }
}
