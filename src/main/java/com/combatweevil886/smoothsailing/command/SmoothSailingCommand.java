package com.combatweevil886.smoothsailing.command;

import com.combatweevil886.smoothsailing.core.CompatReport;
import com.combatweevil886.smoothsailing.core.ConfigSanitizer;
import com.combatweevil886.smoothsailing.core.ShipPartRecount;
import com.combatweevil886.smoothsailing.core.Stats;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /smoothsailing status} - versions, notes, what was prevented so far.<br>
 * {@code /smoothsailing recount} - recount the Eureka parts of the ship you sit on / look at / stand on.<br>
 * {@code /smoothsailing recount all} - every loaded ship.
 */
public final class SmoothSailingCommand {
    private SmoothSailingCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("smoothsailing")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
            .then(Commands.literal("recount")
                .executes(ctx -> recountNearest(ctx.getSource()))
                .then(Commands.literal("all").executes(ctx -> recountAll(ctx.getSource())))));
    }

    private static int status(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("Eureka: Smooth Sailing").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
        src.sendSuccess(() -> Component.literal("Eureka " + CompatReport.version("vs_eureka").orElse("?")
            + ", Valkyrien Skies " + CompatReport.version("valkyrienskies").orElse("?")
            + ", Eureka Tweaks " + CompatReport.version("eurekatweaks").orElse("not installed")), false);
        List<String> missing = CompatReport.missingMixins();
        src.sendSuccess(() -> Component.literal(missing.isEmpty() ? "All server hooks applied."
                : "Hooks not applied (yet): " + String.join(", ", missing))
            .withStyle(missing.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        for (String note : CompatReport.notes()) {
            src.sendSuccess(() -> Component.literal("- " + note).withStyle(ChatFormatting.GRAY), false);
        }
        Map<String, Long> events = Stats.events();
        if (events.isEmpty()) {
            src.sendSuccess(() -> Component.literal("Nothing needed fixing yet this session.").withStyle(ChatFormatting.GREEN), false);
        } else {
            src.sendSuccess(() -> Component.literal("Prevented / fixed this session:"), false);
            events.forEach((k, v) -> src.sendSuccess(() -> Component.literal("  " + k + ": " + v).withStyle(ChatFormatting.GRAY), false));
        }
        Map<String, String> corrections = ConfigSanitizer.corrections();
        if (!corrections.isEmpty()) {
            src.sendSuccess(() -> Component.literal("Eureka config values clamped:").withStyle(ChatFormatting.YELLOW), false);
            corrections.values().forEach(c -> src.sendSuccess(() -> Component.literal("  " + c).withStyle(ChatFormatting.GRAY), false));
        }
        src.sendSuccess(() -> Component.literal("Physics ticks guarded: " + Stats.PHYS_TICKS.get()
            + ", forces checked: " + Stats.FORCES_CHECKED.get()).withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int recountNearest(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        LoadedServerShip ship = findShip(player, level);
        if (ship == null) {
            src.sendFailure(Component.literal("No ship found: sit at its helm, look at it, or stand on it."));
            return 0;
        }
        return report(src, ship, ShipPartRecount.recount(level, ship, false)) ? 1 : 0;
    }

    private static int recountAll(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        List<LoadedServerShip> ships = new ArrayList<>();
        try {
            for (LoadedServerShip s : VSGameUtilsKt.getShipObjectWorld(server).getLoadedShips()) {
                ships.add(s);
            }
        } catch (Throwable t) {
            src.sendFailure(Component.literal("Could not list loaded ships from Valkyrien Skies: " + t));
            return 0;
        }
        int corrected = 0, checked = 0;
        for (LoadedServerShip ship : ships) {
            ServerLevel level = VSGameUtilsKt.getLevelFromDimensionId(server, ship.getChunkClaimDimension());
            if (level == null) {
                continue;
            }
            ShipPartRecount.Result r = ShipPartRecount.recount(level, ship, false);
            if (r.outcome() == ShipPartRecount.Outcome.NO_CONTROL) {
                continue;
            }
            checked++;
            if (r.outcome() == ShipPartRecount.Outcome.CORRECTED) {
                corrected++;
                report(src, ship, r);
            }
        }
        final int c = corrected, k = checked;
        src.sendSuccess(() -> Component.literal("Recounted " + k + " Eureka ship(s), corrected " + c + "."), true);
        return k;
    }

    private static boolean report(CommandSourceStack src, LoadedServerShip ship, ShipPartRecount.Result r) {
        String name = ship.getSlug() == null ? ("#" + ship.getId()) : ship.getSlug();
        switch (r.outcome()) {
            case NO_CONTROL -> {
                src.sendFailure(Component.literal(name + " has no Eureka controller (no helm, balloons, floaters or anchors)."));
                return false;
            }
            case INCOMPLETE -> {
                src.sendFailure(Component.literal(name + ": some of its chunks aren't loaded, try again closer to it."));
                return false;
            }
            case UNCHANGED -> src.sendSuccess(() -> Component.literal(name + ": counts already correct [" + r.before() + "]")
                .withStyle(ChatFormatting.GREEN), false);
            case CORRECTED -> {
                MutableComponent msg = Component.literal(name + ": [" + r.before() + "] -> [" + r.after() + "]");
                src.sendSuccess(() -> msg.withStyle(ChatFormatting.YELLOW), true);
            }
        }
        return true;
    }

    private static LoadedServerShip findShip(ServerPlayer player, ServerLevel level) {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof ShipMountingEntity) {
            LoadedServerShip s = VSGameUtilsKt.getLoadedShipManagingPos(level, vehicle.blockPosition());
            if (s != null) {
                return s;
            }
        }
        HitResult hit = player.pick(24.0, 1.0f, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            LoadedServerShip s = VSGameUtilsKt.getLoadedShipManagingPos(level, bhr.getBlockPos());
            if (s != null) {
                return s;
            }
        }
        AABB around = player.getBoundingBox().inflate(3.0);
        for (Ship ship : VSGameUtilsKt.getShipsIntersecting(level, around)) {
            Vector3dc inShip = ship.getTransform().getPositionInShip();
            LoadedServerShip s = VSGameUtilsKt.getLoadedShipManagingPos(level, BlockPos.containing(inShip.x(), inShip.y(), inShip.z()));
            if (s != null) {
                return s;
            }
        }
        return null;
    }
}
