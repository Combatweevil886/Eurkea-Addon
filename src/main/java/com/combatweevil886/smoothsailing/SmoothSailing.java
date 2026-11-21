package com.combatweevil886.smoothsailing;

import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.DismountFix;
import com.combatweevil886.smoothsailing.core.ServerEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(SmoothSailing.MOD_ID)
public final class SmoothSailing {
    public static final String MOD_ID = "smoothsailing";
    public static final Logger LOGGER = LogManager.getLogger("SmoothSailing");

    public SmoothSailing() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SSConfig.SPEC);
        modBus.addListener(this::onConfig);
        modBus.addListener(this::onConfigReload);

        MinecraftForge.EVENT_BUS.register(ServerEvents.class);
        MinecraftForge.EVENT_BUS.register(DismountFix.class);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            com.combatweevil886.smoothsailing.client.WheelModelFix.register(modBus));
    }

    private void onConfig(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SSConfig.SPEC) {
            SSConfig.bake();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SSConfig.SPEC) {
            SSConfig.bake();
        }
    }
}
