package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.SmoothSailing;
import com.combatweevil886.smoothsailing.config.SSConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.eureka.ship.EurekaShipControl;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

public final class HelmUtil {
    private HelmUtil() {
    }

    /**
     * Eureka hard-casts {@code level.getBlockEntity(pos)} in its helm/engine {@code use} and {@code playerWillDestroy}.
     * If the block entity went missing (bad relocation, chunk corruption, world edits) that throws every time the block
     * is touched. Put a fresh one in place instead.
     */
    public static void ensureBlockEntity(Level level, BlockPos pos, BlockState state, Class<? extends BlockEntity> expected) {
        if (!SSConfig.recreateBlockEntities || level.isClientSide) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (expected.isInstance(be)) {
            return;
        }
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return;
        }
        BlockEntity fresh = entityBlock.newBlockEntity(pos, state);
        if (fresh == null) {
            return;
        }
        if (be != null) {
            level.removeBlockEntity(pos);
        }
        level.setBlockEntity(fresh);
        Stats.event("blockEntity.recreated");
        SmoothSailing.LOGGER.warn("[Smooth Sailing] {} at {} had {} block entity; created a new one so it no longer crashes when used.",
            state.getBlock(), pos, be == null ? "no" : "a wrong (" + be.getClass().getSimpleName() + ")");
    }

    /**
     * Eureka stores the last player that sat at a helm and never clears it, keeping a disconnected player object (and
     * its connection) alive and sending cruise messages to whoever sat there last, even on another ship.
     */
    public static void cleanupSeatedPlayer(ServerLevel level, LoadedServerShip ship, EurekaShipControl control) {
        if (!SSConfig.staleSeatCleanup) {
            return;
        }
        Player p = control.getSeatedPlayer();
        if (p == null) {
            return;
        }
        boolean stale;
        if (p.isRemoved() || (p instanceof ServerPlayer sp && sp.hasDisconnected())) {
            stale = true;
        } else {
            Entity vehicle = p.getVehicle();
            if (!(vehicle instanceof ShipMountingEntity) || vehicle.level() != level) {
                stale = true;
            } else {
                LoadedServerShip seatShip = VSGameUtilsKt.getLoadedShipManagingPos(level, vehicle.blockPosition());
                stale = seatShip == null || seatShip.getId() != ship.getId();
            }
        }
        if (stale) {
            control.setSeatedPlayer(null);
            Stats.event("helm.staleSeatedPlayerCleared");
        }
    }

    /** Tells players near (or seated on) a ship why something was refused. */
    public static void messageNearShip(ServerLevel level, LoadedServerShip ship, EurekaShipControl control, Component msg) {
        Player seated = control == null ? null : control.getSeatedPlayer();
        if (seated != null && !seated.isRemoved()) {
            seated.displayClientMessage(msg, false);
            return;
        }
        Vector3dc c = ship.getTransform().getPositionInWorld();
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(c.x(), c.y(), c.z()) < 64 * 64) {
                p.displayClientMessage(msg, false);
            }
        }
    }
}
