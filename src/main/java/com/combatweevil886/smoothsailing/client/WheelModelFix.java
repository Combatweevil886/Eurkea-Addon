package com.combatweevil886.smoothsailing.client;

import com.combatweevil886.smoothsailing.SmoothSailing;
import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.Stats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.valkyrienskies.eureka.block.IWoodType;
import org.valkyrienskies.eureka.block.ShipHelmBlock;
import org.valkyrienskies.eureka.blockentity.renderer.WheelModels;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eureka's {@code EurekaModForgeClient} hands {@code WheelModels} a lambda that captures the
 * {@code ModelEvent.BakingCompleted} event of the <i>first</i> resource load and never replaces it. That keeps the
 * first {@code ModelBakery} - every baked model of the whole pack - reachable after each resource reload, and the
 * lazily cached wheel model keeps pointing at the old texture atlas.
 * <p>
 * Here the wheel model is resolved from the live {@code ModelManager} at render time, and after every bake the getter
 * is replaced with one that holds no reference to the event.
 */
public final class WheelModelFix {
    private static final Map<String, ResourceLocation> LOCATIONS = new ConcurrentHashMap<>();

    private WheelModelFix() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(EventPriority.LOWEST, WheelModelFix::onBakingCompleted);
    }

    private static ResourceLocation locationFor(IWoodType wood) {
        String name = wood.getSerializedName().toLowerCase(java.util.Locale.ROOT);
        return LOCATIONS.computeIfAbsent(name, n -> new ResourceLocation("vs_eureka", "block/" + n + "_ship_helm_wheel"));
    }

    /** @return the current wheel model for this helm state, or {@code null} to fall back to Eureka's own lookup. */
    public static BakedModel lookup(BlockState state) {
        try {
            if (!(state.getBlock() instanceof ShipHelmBlock helm)) {
                return null;
            }
            Stats.hook("client.wheelModelLookup");
            return Minecraft.getInstance().getModelManager().getModel(locationFor(helm.getWoodType()));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void onBakingCompleted(ModelEvent.BakingCompleted event) {
        if (!SSConfig.wheelModelFix) {
            return;
        }
        try {
            // Replace Eureka's event-capturing getter; ours only touches the live model manager.
            WheelModels.INSTANCE.setModelGetter(wood -> Minecraft.getInstance().getModelManager().getModel(locationFor(wood)));
        } catch (Throwable t) {
            SmoothSailing.LOGGER.warn("[Smooth Sailing] Could not replace Eureka's helm wheel model getter", t);
        }
    }
}
