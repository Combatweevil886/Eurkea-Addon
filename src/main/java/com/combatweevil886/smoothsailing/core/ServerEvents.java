package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.command.SmoothSailingCommand;
import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.selftest.SelfTest;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

public final class ServerEvents {
    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        SmoothSailingCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ConfigSanitizer.run(server);
        if (SSConfig.startupReport) {
            CompatReport.log();
        }
        if (SelfTest.requested()) {
            SelfTest.start(server);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ShipPartRecount.reset();
        DismountFix.reset();
        ConfigSanitizer.resetSession();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        int interval = Math.max(1, SSConfig.sanitizerInterval);
        if (server.getTickCount() % interval == 0) {
            ConfigSanitizer.run(server);
        }
        ShipPartRecount.tick(server);
        SelfTest.tick(server);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        AssemblyGuard.clearPlayer(event.getEntity().getUUID());
    }
}
