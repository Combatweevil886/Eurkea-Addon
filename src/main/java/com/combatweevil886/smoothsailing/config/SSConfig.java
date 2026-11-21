package com.combatweevil886.smoothsailing.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Common config ({@code config/smoothsailing-common.toml}).
 * <p>
 * Every value is mirrored into a volatile static field by {@link #bake()} so the physics thread can read it without
 * touching the Forge config machinery. The defaults below are used until the config file is loaded.
 */
public final class SSConfig {
    public static final ForgeConfigSpec SPEC;

    // ---- physics -------------------------------------------------------------------------------------------------
    private static final ForgeConfigSpec.BooleanValue FORCE_GUARD;
    private static final ForgeConfigSpec.DoubleValue MAX_ACCELERATION;
    private static final ForgeConfigSpec.BooleanValue BUOYANCY_GUARD;
    private static final ForgeConfigSpec.DoubleValue MAX_BUOYANT_FACTOR;
    private static final ForgeConfigSpec.BooleanValue PHYSICS_EXCEPTION_SHIELD;
    private static final ForgeConfigSpec.BooleanValue MAIN_THREAD_MESSAGES;
    // ---- sanitizer -----------------------------------------------------------------------------------------------
    private static final ForgeConfigSpec.BooleanValue SANITIZER;
    private static final ForgeConfigSpec.IntValue SANITIZER_INTERVAL;
    private static final ForgeConfigSpec.BooleanValue SANITIZER_WARN_OPS;
    // ---- assembly ------------------------------------------------------------------------------------------------
    private static final ForgeConfigSpec.BooleanValue ASSEMBLY_NO_CHUNK_LOADING;
    private static final ForgeConfigSpec.BooleanValue ASSEMBLY_SKIP_FLUIDS;
    private static final ForgeConfigSpec.IntValue ASSEMBLY_UNLIMITED_CAP;
    private static final ForgeConfigSpec.IntValue ASSEMBLY_COOLDOWN;
    private static final ForgeConfigSpec.BooleanValue DISASSEMBLY_HEIGHT_GUARD;
    // ---- ship parts ----------------------------------------------------------------------------------------------
    private static final ForgeConfigSpec.BooleanValue FIX_FLOATER_DOUBLE_COUNT;
    private static final ForgeConfigSpec.BooleanValue CLAMP_PART_COUNTS;
    private static final ForgeConfigSpec.BooleanValue AUTO_RECOUNT;
    private static final ForgeConfigSpec.IntValue PERIODIC_RECOUNT_MINUTES;
    // ---- engines -------------------------------------------------------------------------------------------------
    private static final ForgeConfigSpec.BooleanValue ENGINE_THROTTLE;
    private static final ForgeConfigSpec.IntValue ENGINE_MIN_TICKS;
    private static final ForgeConfigSpec.BooleanValue ENGINE_LIGHTWEIGHT;
    // ---- helm ----------------------------------------------------------------------------------------------------
    private static final ForgeConfigSpec.BooleanValue DISMOUNT_FIX;
    private static final ForgeConfigSpec.DoubleValue DISMOUNT_MAX_DRIFT;
    private static final ForgeConfigSpec.BooleanValue STALE_SEAT_CLEANUP;
    private static final ForgeConfigSpec.BooleanValue HELM_MENU_VALIDATION;
    private static final ForgeConfigSpec.BooleanValue RECREATE_BLOCK_ENTITIES;
    // ---- client --------------------------------------------------------------------------------------------------
    private static final ForgeConfigSpec.BooleanValue WHEEL_MODEL_FIX;
    private static final ForgeConfigSpec.BooleanValue RENDERER_SAFETY;
    // ---- diagnostics ---------------------------------------------------------------------------------------------
    private static final ForgeConfigSpec.BooleanValue STARTUP_REPORT;

    // Mirrors (read these, never the ConfigValues, from hot paths)
    public static volatile boolean forceGuard = true;
    public static volatile double maxAcceleration = 1000.0;
    public static volatile boolean buoyancyGuard = true;
    public static volatile double maxBuoyantFactor = 0.0;
    public static volatile boolean physicsExceptionShield = true;
    public static volatile boolean mainThreadMessages = true;
    public static volatile boolean sanitizer = true;
    public static volatile int sanitizerInterval = 20;
    public static volatile boolean sanitizerWarnOps = true;
    public static volatile boolean assemblyNoChunkLoading = true;
    public static volatile boolean assemblySkipFluids = true;
    public static volatile int assemblyUnlimitedCap = 131072;
    public static volatile int assemblyCooldown = 20;
    public static volatile boolean disassemblyHeightGuard = true;
    public static volatile boolean fixFloaterDoubleCount = true;
    public static volatile boolean clampPartCounts = true;
    public static volatile boolean autoRecount = true;
    public static volatile int periodicRecountMinutes = 10;
    public static volatile boolean engineThrottle = true;
    public static volatile int engineMinTicks = 10;
    public static volatile boolean engineLightweight = true;
    public static volatile boolean dismountFix = true;
    public static volatile double dismountMaxDrift = 4.0;
    public static volatile boolean staleSeatCleanup = true;
    public static volatile boolean helmMenuValidation = true;
    public static volatile boolean recreateBlockEntities = true;
    public static volatile boolean wheelModelFix = true;
    public static volatile boolean rendererSafety = true;
    public static volatile boolean startupReport = true;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Guards around the forces Eureka applies on the Valkyrien Skies physics thread.").push("physics");
        FORCE_GUARD = b.comment("Drop NaN/infinite forces and torques from Eureka before they reach the physics engine.",
                "A single NaN force can fling a ship to infinity, which loads absurd chunks and crashes the server.")
            .define("forceGuard", true);
        MAX_ACCELERATION = b.comment("Largest acceleration (m/s^2) one Eureka force may cause; larger forces are scaled down.",
                "Normal Eureka flight stays far below 200. 0 disables the clamp (NaN filtering stays on).")
            .defineInRange("maxAcceleration", 1000.0, 0.0, 1.0E9);
        BUOYANCY_GUARD = b.comment("Keep the floater buoyancy factor finite and never negative (negative = ship is pulled underwater).")
            .define("buoyancyGuard", true);
        MAX_BUOYANT_FACTOR = b.comment("Upper limit for the floater buoyancy factor. 0 = no upper limit.")
            .defineInRange("maxBuoyantFactor", 0.0, 0.0, 1.0E6);
        PHYSICS_EXCEPTION_SHIELD = b.comment("Catch exceptions thrown by Eureka's per-ship physics tick so they cannot take the physics thread down.",
                "The failing ship skips that tick; the error is logged (rate limited).")
            .define("exceptionShield", true);
        MAIN_THREAD_MESSAGES = b.comment("Eureka sends the cruise on/off chat message from the physics thread. Move it to the server thread.")
            .define("mainThreadMessages", true);
        b.pop();

        b.comment("Watches EurekaConfig (including values written by Eureka Tweaks) and clamps values that would make",
            "the ship controller divide by zero or produce NaN, e.g. elevationSnappiness = 0 or linearMaxMass = 0.").push("sanitizer");
        SANITIZER = b.define("enabled", true);
        SANITIZER_INTERVAL = b.comment("How often (ticks) to re-check the values.").defineInRange("intervalTicks", 20, 1, 12000);
        SANITIZER_WARN_OPS = b.comment("Tell online operators in chat (once per value) when something was clamped.").define("warnOperators", true);
        b.pop();

        b.comment("Ship assembly (helm 'Assemble' button).").push("assembly");
        ASSEMBLY_NO_CHUNK_LOADING = b.comment("Never load or generate chunks while searching for ship blocks; unloaded chunks count as air.")
            .define("noChunkLoading", true);
        ASSEMBLY_SKIP_FLUIDS = b.comment("Never pull fluid blocks (modded fluids included) or bubble columns into a ship.",
                "Eureka only blacklists vanilla water and lava, so a ship touching e.g. acid or a modded oil lake floods the search.")
            .define("skipFluids", true);
        ASSEMBLY_UNLIMITED_CAP = b.comment("Eureka's maxShipBlocks = 0 means 'no limit', which lets one click freeze the server.",
                "When Eureka's limit is 0 this cap is used instead. 0 = really unlimited.")
            .defineInRange("unlimitedCap", 131072, 0, Integer.MAX_VALUE);
        ASSEMBLY_COOLDOWN = b.comment("Ticks a player must wait between assemble attempts (each attempt scans up to maxShipBlocks blocks). 0 = off.")
            .defineInRange("cooldownTicks", 20, 0, 1200);
        DISASSEMBLY_HEIGHT_GUARD = b.comment("Refuse to disassemble a ship that sticks out above or below the world's build height (those blocks would be deleted).")
            .define("disassemblyHeightGuard", true);
        b.pop();

        b.comment("Eureka keeps running counts of helms, balloons, floaters and anchors per ship. These options keep them honest.").push("parts");
        FIX_FLOATER_DOUBLE_COUNT = b.comment("Eureka 1.6.x applies a floater's redstone change twice (once directly, once via the block update).",
                "A powered floater then pushes the count negative and the ship is dragged underwater. This removes the duplicate.")
            .define("fixFloaterDoubleCount", true);
        CLAMP_PART_COUNTS = b.comment("Never let a part count go below zero.").define("clampCounts", true);
        AUTO_RECOUNT = b.comment("Recount the real parts on each ship shortly after it loads (fixes counts already broken in old saves).")
            .define("autoRecount", true);
        PERIODIC_RECOUNT_MINUTES = b.comment("Re-run the recount for active ships every N minutes. 0 = only once per ship per server start.")
            .defineInRange("periodicRecountMinutes", 10, 0, 1440);
        b.pop();

        b.comment("Ship engines.").push("engines");
        ENGINE_THROTTLE = b.comment("Limit how often an engine may change its heat block state (light level + ship mesh update).")
            .define("throttleStateUpdates", true);
        ENGINE_MIN_TICKS = b.comment("Minimum ticks between two heat block-state changes of the same engine.")
            .defineInRange("minTicksBetweenStateUpdates", 10, 0, 1200);
        ENGINE_LIGHTWEIGHT = b.comment("Send heat changes to clients without neighbour/shape updates and without forcing a synchronous chunk re-render.")
            .define("lightweightStateUpdates", true);
        b.pop();

        b.comment("Ship helms and seats.").push("helm");
        DISMOUNT_FIX = b.comment("Valkyrien Skies 2.4.11 sometimes teleports a player far away (or back to where they sat down) when",
                "they leave a helm (Eureka issue #500), and can deal fall damage for the height the ship travelled.",
                "When a player sneaks off a helm and lands far from the seat, put them back next to it and clear the bogus fall distance.")
            .define("dismountFix", true);
        DISMOUNT_MAX_DRIFT = b.comment("How far (blocks) from the seat a dismounted player may end up before the dismount fix moves them back.")
            .defineInRange("dismountMaxDrift", 4.0, 1.0, 64.0);
        STALE_SEAT_CLEANUP = b.comment("Forget the 'seated player' of a ship once they left the helm (Eureka keeps the reference forever).")
            .define("staleSeatCleanup", true);
        HELM_MENU_VALIDATION = b.comment("Close the helm menu when the helm is gone, so a stale menu can't assemble/disassemble remotely.")
            .define("menuValidation", true);
        RECREATE_BLOCK_ENTITIES = b.comment("Recreate a missing helm/engine block entity instead of crashing when the block is used or broken.")
            .define("recreateMissingBlockEntities", true);
        b.pop();

        b.comment("Client-only fixes (ignored on dedicated servers).").push("client");
        WHEEL_MODEL_FIX = b.comment("Eureka caches the helm wheel model from the first resource load and keeps the whole first model",
                "bakery alive forever. Look the model up from the live model manager instead (fixes stale wheels after F3+T and frees memory).")
            .define("wheelModelFix", true);
        RENDERER_SAFETY = b.comment("Skip a helm wheel render instead of crashing when the block entity is in an unexpected state",
                "(Create contraptions, schematic previews, carried blocks).")
            .define("rendererSafety", true);
        b.pop();

        b.push("diagnostics");
        STARTUP_REPORT = b.comment("Log a short compatibility report (versions, known issues, active protections) at server start.")
            .define("startupReport", true);
        b.pop();

        SPEC = b.build();
    }

    private SSConfig() {
    }

    /** Copies every value into its static mirror. Called on config load and reload. */
    public static void bake() {
        forceGuard = FORCE_GUARD.get();
        maxAcceleration = MAX_ACCELERATION.get();
        buoyancyGuard = BUOYANCY_GUARD.get();
        maxBuoyantFactor = MAX_BUOYANT_FACTOR.get();
        physicsExceptionShield = PHYSICS_EXCEPTION_SHIELD.get();
        mainThreadMessages = MAIN_THREAD_MESSAGES.get();
        sanitizer = SANITIZER.get();
        sanitizerInterval = SANITIZER_INTERVAL.get();
        sanitizerWarnOps = SANITIZER_WARN_OPS.get();
        assemblyNoChunkLoading = ASSEMBLY_NO_CHUNK_LOADING.get();
        assemblySkipFluids = ASSEMBLY_SKIP_FLUIDS.get();
        assemblyUnlimitedCap = ASSEMBLY_UNLIMITED_CAP.get();
        assemblyCooldown = ASSEMBLY_COOLDOWN.get();
        disassemblyHeightGuard = DISASSEMBLY_HEIGHT_GUARD.get();
        fixFloaterDoubleCount = FIX_FLOATER_DOUBLE_COUNT.get();
        clampPartCounts = CLAMP_PART_COUNTS.get();
        autoRecount = AUTO_RECOUNT.get();
        periodicRecountMinutes = PERIODIC_RECOUNT_MINUTES.get();
        engineThrottle = ENGINE_THROTTLE.get();
        engineMinTicks = ENGINE_MIN_TICKS.get();
        engineLightweight = ENGINE_LIGHTWEIGHT.get();
        dismountFix = DISMOUNT_FIX.get();
        dismountMaxDrift = DISMOUNT_MAX_DRIFT.get();
        staleSeatCleanup = STALE_SEAT_CLEANUP.get();
        helmMenuValidation = HELM_MENU_VALIDATION.get();
        recreateBlockEntities = RECREATE_BLOCK_ENTITIES.get();
        wheelModelFix = WHEEL_MODEL_FIX.get();
        rendererSafety = RENDERER_SAFETY.get();
        startupReport = STARTUP_REPORT.get();
    }
}
