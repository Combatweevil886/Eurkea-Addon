package com.combatweevil886.smoothsailing.mixin;

import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * <ul>
 *     <li>Skips a mixin whose target class is missing (a different Eureka version) instead of erroring.</li>
 *     <li>Lets pack makers switch single mixins off in {@code config/smoothsailing-mixins.properties}.</li>
 *     <li>Records which mixins were applied in a system property (shared across class loaders) for
 *     {@code /smoothsailing status} and the CI self-test.</li>
 * </ul>
 * Must not reference other classes of this mod: it is loaded before the game classes are.
 */
public class SmoothSailingMixinPlugin implements IMixinConfigPlugin {
    public static final String APPLIED_PROPERTY = "smoothsailing.mixins.applied";
    private static final Logger LOGGER = LogManager.getLogger("SmoothSailing/Mixins");
    private static final Set<String> APPLIED = new TreeSet<>();
    private final Properties toggles = new Properties();

    @Override
    public void onLoad(String mixinPackage) {
        try {
            Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap")
                .getMethod("init").invoke(null);
        } catch (Throwable ignored) {
            // MixinExtras is bundled by Valkyrien Skies and initialises itself; this is only a safety net.
        }
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve("smoothsailing-mixins.properties");
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    toggles.load(in);
                }
            } else {
                Properties defaults = new Properties();
                for (String m : new String[]{"EngineBlockEntityMixin", "EngineBlockMixin", "EurekaShipControlMixin",
                    "FloaterBlockMixin", "ShipAssemblerMixin", "ShipHelmBlockEntityMixin", "ShipHelmBlockMixin",
                    "ShipHelmScreenMenuMixin", "StabilizeKtMixin", "client.ShipHelmBlockEntityRendererMixin",
                    "client.WheelModelsMixin"}) {
                    defaults.setProperty(m, "true");
                }
                Files.createDirectories(file.getParent());
                try (OutputStream out = Files.newOutputStream(file)) {
                    defaults.store(out, "Eureka: Smooth Sailing - set a mixin to false to disable it (restart required).\n"
                        + "Prefer the options in smoothsailing-common.toml; this file is an emergency switch for conflicts.");
                }
                toggles.putAll(defaults);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read smoothsailing-mixins.properties, all mixins enabled", e);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String shortName = mixinClassName.substring(mixinClassName.indexOf(".mixin.") + ".mixin.".length());
        if ("false".equalsIgnoreCase(toggles.getProperty(shortName, "true").trim())) {
            LOGGER.info("Mixin {} disabled by smoothsailing-mixins.properties", shortName);
            return false;
        }
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(targetClassName.replace('.', '/'));
        } catch (Throwable t) {
            LOGGER.warn("Skipping {}: target {} is not present (different Eureka version?)", shortName, targetClassName);
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        synchronized (APPLIED) {
            APPLIED.add(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
            System.setProperty(APPLIED_PROPERTY, String.join(",", APPLIED));
        }
    }
}
