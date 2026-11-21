package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.SmoothSailing;
import com.combatweevil886.smoothsailing.config.SSConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Valkyrien Skies 2.4.11 regression (Eureka issue #500): leaving a helm seat can put the player far away - sideways,
 * or back where they first sat down - and can deal fall damage for the height the ship travelled.
 * <p>
 * Only voluntary dismounts (sneaking off the seat) are handled, so commands, portals, waystones etc. are never undone.
 * For a few ticks afterwards the player's position is compared with the seat's current world position (recomputed from
 * the live ship transform, so a moving ship is fine). If they drifted further than walking could explain, they are put
 * back next to the seat. Their fall distance is cleared during the window.
 */
public final class DismountFix {
    private static final int WINDOW_TICKS = 10;
    private static final double WALK_ALLOWANCE_PER_TICK = 0.7;

    private record Pending(ServerLevel level, long shipId, Vec3 seatShipPos, long startTick) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private DismountFix() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMount(EntityMountEvent event) {
        if (!SSConfig.dismountFix || !event.isDismounting() || event.isCanceled()) {
            return;
        }
        if (!(event.getEntityMounting() instanceof ServerPlayer player)) {
            return;
        }
        Entity vehicle = event.getEntityBeingMounted();
        if (!(vehicle instanceof ShipMountingEntity) || !(vehicle.level() instanceof ServerLevel level)) {
            return;
        }
        if (!player.isShiftKeyDown()) {
            return; // not a voluntary dismount
        }
        LoadedServerShip ship = VSGameUtilsKt.getLoadedShipManagingPos(level, vehicle.blockPosition());
        if (ship == null) {
            return;
        }
        Stats.hook("dismount.recorded");
        PENDING.put(player.getUUID(), new Pending(level, ship.getId(), vehicle.position(), level.getGameTime()));
    }

    @SubscribeEvent
    public static void onTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            PENDING.remove(p.getUUID());
        }
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty() || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        Pending p = PENDING.get(player.getUUID());
        if (p == null) {
            return;
        }
        long age = p.level.getGameTime() - p.startTick;
        if (age > WINDOW_TICKS || player.level() != p.level || player.isPassenger() || player.isRemoved()) {
            PENDING.remove(player.getUUID());
            return;
        }
        player.resetFallDistance();

        LoadedServerShip ship = VSGameUtilsKt.getLoadedShipManagingPos(p.level,
            net.minecraft.core.BlockPos.containing(p.seatShipPos));
        if (ship == null || ship.getId() != p.shipId) {
            PENDING.remove(player.getUUID());
            return;
        }
        Vector3d seatWorld = ship.getShipToWorld().transformPosition(
            new Vector3d(p.seatShipPos.x, p.seatShipPos.y + 0.1, p.seatShipPos.z));
        if (!PhysicsGuard.isFinite(seatWorld)) {
            return;
        }
        double allowed = SSConfig.dismountMaxDrift + age * WALK_ALLOWANCE_PER_TICK;
        double dx = player.getX() - seatWorld.x, dy = player.getY() - seatWorld.y, dz = player.getZ() - seatWorld.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > allowed * allowed) {
            Stats.event("dismount.teleportCorrected");
            SmoothSailing.LOGGER.info("[Smooth Sailing] {} left a helm and ended up {} blocks from the seat; moved back to {}, {}, {}.",
                player.getName().getString(), String.format("%.1f", Math.sqrt(distSq)), String.format("%.1f", seatWorld.x),
                String.format("%.1f", seatWorld.y), String.format("%.1f", seatWorld.z));
            player.teleportTo(p.level, seatWorld.x, seatWorld.y, seatWorld.z, player.getYRot(), player.getXRot());
            player.resetFallDistance();
            PENDING.remove(player.getUUID());
        }
    }

    public static void reset() {
        PENDING.clear();
    }
}
