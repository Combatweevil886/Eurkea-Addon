package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.SmoothSailing;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Startup summary: what is installed, which known problems apply, which protections are live. */
public final class CompatReport {
    private static final String[] EXPECTED_MIXINS = {
        "EngineBlockEntityMixin", "EngineBlockMixin", "EurekaShipControlMixin", "FloaterBlockMixin",
        "ShipAssemblerMixin", "ShipHelmBlockEntityMixin", "ShipHelmBlockMixin", "ShipHelmScreenMenuMixin",
        "StabilizeKtMixin"
    };

    private CompatReport() {
    }

    public static Optional<String> version(String modId) {
        return ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString());
    }

    public static String appliedMixins() {
        return System.getProperty("smoothsailing.mixins.applied", ""); // set by SmoothSailingMixinPlugin
    }

    /** Server-side mixins that were expected but are not applied (targets get loaded lazily, so call after start). */
    public static List<String> missingMixins() {
        String applied = "," + appliedMixins() + ",";
        List<String> missing = new ArrayList<>();
        for (String m : EXPECTED_MIXINS) {
            if (!applied.contains("," + m + ",")) {
                missing.add(m);
            }
        }
        return missing;
    }

    public static List<String> notes() {
        List<String> notes = new ArrayList<>();
        String vs = version("valkyrienskies").orElse("?");
        String eureka = version("vs_eureka").orElse("?");
        if (vs.startsWith("2.4.11")) {
            notes.add("Valkyrien Skies 2.4.11 has the helm-dismount teleport bug (Eureka #500) - dismount fix "
                + (com.combatweevil886.smoothsailing.config.SSConfig.dismountFix ? "ON" : "OFF (config)") + ".");
        }
        if (!eureka.startsWith("1.6.")) {
            notes.add("Eureka " + eureka + " is outside the tested 1.6.x line; incompatible hooks are skipped automatically.");
        }
        if (ModList.get().isLoaded("eurekatweaks")) {
            notes.add("Eureka Tweaks detected: its sliders reach 0 for divisor values (elevationSnappiness, speedMassScale, "
                + "linearMaxMass, maxSpeedFromEngines). The config sanitizer keeps them safe.");
        }
        if (ModList.get().isLoaded("entityculling")) {
            notes.add("Entity Culling detected: if helm wheels or seats on ships flicker/vanish, add "
                + "'valkyrienskies:ship_mounting_entity' to tickCullingWhitelist and 'vs_eureka:ship_helm' to blockEntityWhitelist.");
        }
        return notes;
    }

    public static void log() {
        SmoothSailing.LOGGER.info("[Smooth Sailing] Eureka {} / Valkyrien Skies {} / Eureka Tweaks {}",
            version("vs_eureka").orElse("missing"), version("valkyrienskies").orElse("missing"),
            version("eurekatweaks").orElse("not installed"));
        String applied = appliedMixins();
        SmoothSailing.LOGGER.info("[Smooth Sailing] Applied hooks so far: {}", applied.isEmpty() ? "(none yet - Eureka classes load lazily)" : applied);
        for (String n : notes()) {
            SmoothSailing.LOGGER.info("[Smooth Sailing] Note: {}", n);
        }
    }
}
