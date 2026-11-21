package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.SmoothSailing;
import com.combatweevil886.smoothsailing.config.SSConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.primitives.AABBic;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.eureka.block.AnchorBlock;
import org.valkyrienskies.eureka.block.BalloonBlock;
import org.valkyrienskies.eureka.block.FloaterBlock;
import org.valkyrienskies.eureka.block.ShipHelmBlock;
import org.valkyrienskies.eureka.ship.EurekaShipControl;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Eureka never re-derives its per-ship part counts; it only adds/subtracts on block place/remove. Any missed or
 * doubled update (the floater redstone bug, mods moving blocks oddly, crashes mid-update) stays in the save forever and
 * shows up as ships that sink, float away, or won't steer. This counts the real blocks and corrects the numbers.
 */
public final class ShipPartRecount {
    private static final int DELAY_TICKS = 40;
    private static final int SHIPS_PER_TICK = 2;

    private static final Predicate<BlockState> IS_PART = s -> {
        Block b = s.getBlock();
        return b instanceof ShipHelmBlock || b instanceof BalloonBlock || b instanceof FloaterBlock || b instanceof AnchorBlock;
    };

    private record Pending(WeakReference<ServerLevel> level, WeakReference<LoadedServerShip> ship, long dueTick) {
    }

    private static final Map<Long, Pending> PENDING = new HashMap<>();
    private static final Deque<Long> ORDER = new ArrayDeque<>();
    private static final Set<Long> DONE = new HashSet<>();
    private static long lastPeriodicReset;

    public record Counts(int helms, int balloons, int floaters, int anchors, int anchorsActive) {
        @Override
        public String toString() {
            return "helms=" + helms + " balloons=" + balloons + " floaters=" + floaters + " anchors=" + anchors + " activeAnchors=" + anchorsActive;
        }
    }

    public enum Outcome { UNCHANGED, CORRECTED, INCOMPLETE, NO_CONTROL }

    public record Result(Outcome outcome, Counts before, Counts after) {
    }

    private ShipPartRecount() {
    }

    /** Called from the helm block entity tick (server thread). */
    public static void noteShip(ServerLevel level, LoadedServerShip ship) {
        if (!SSConfig.autoRecount || ship == null) {
            return;
        }
        long id = ship.getId();
        if (DONE.contains(id) || PENDING.containsKey(id)) {
            return;
        }
        PENDING.put(id, new Pending(new WeakReference<>(level), new WeakReference<>(ship), level.getGameTime() + DELAY_TICKS));
        ORDER.add(id);
    }

    /** Server tick end. */
    public static void tick(MinecraftServer server) {
        int minutes = SSConfig.periodicRecountMinutes;
        long now = server.getTickCount();
        if (minutes > 0 && now - lastPeriodicReset >= minutes * 1200L) {
            lastPeriodicReset = now;
            DONE.clear();
        }
        int budget = SHIPS_PER_TICK;
        int guard = ORDER.size();
        while (budget > 0 && guard-- > 0 && !ORDER.isEmpty()) {
            Long id = ORDER.poll();
            Pending p = PENDING.get(id);
            if (p == null) {
                continue;
            }
            ServerLevel level = p.level.get();
            LoadedServerShip ship = p.ship.get();
            if (level == null || ship == null) {
                PENDING.remove(id);
                continue;
            }
            if (level.getGameTime() < p.dueTick) {
                ORDER.add(id); // not yet
                continue;
            }
            PENDING.remove(id);
            budget--;
            try {
                Result r = recount(level, ship, false);
                if (r.outcome != Outcome.INCOMPLETE) {
                    DONE.add(id);
                }
            } catch (Throwable t) {
                DONE.add(id);
                RateLimitedLog.error("recount-" + id, "[Smooth Sailing] Ship part recount failed for ship " + id, t);
            }
        }
    }

    public static void reset() {
        PENDING.clear();
        ORDER.clear();
        DONE.clear();
        lastPeriodicReset = 0;
    }

    public static Counts countsOf(EurekaShipControl c) {
        return new Counts(c.getHelms(), c.getBalloons(), c.getFloaters(), c.getAnchors(), c.getAnchorsActive());
    }

    /**
     * Counts the Eureka parts physically present on the ship and writes them into its {@link EurekaShipControl}.
     *
     * @param dryRun only report, don't write
     */
    public static Result recount(ServerLevel level, LoadedServerShip ship, boolean dryRun) {
        Stats.hook("recount.run");
        EurekaShipControl control = ship.getAttachment(EurekaShipControl.class);
        if (control == null) {
            return new Result(Outcome.NO_CONTROL, null, null);
        }
        Counts before = countsOf(control);
        AABBic box = ship.getShipAABB();
        if (box == null) {
            return new Result(Outcome.INCOMPLETE, before, null);
        }
        int minX = box.minX() - 1, minY = box.minY() - 1, minZ = box.minZ() - 1;
        int maxX = box.maxX() + 1, maxY = box.maxY() + 1, maxZ = box.maxZ() + 1;
        minY = Math.max(minY, level.getMinBuildHeight());
        maxY = Math.min(maxY, level.getMaxBuildHeight() - 1);

        int helms = 0, balloons = 0, floaters = 0, anchors = 0, active = 0;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    return new Result(Outcome.INCOMPLETE, before, null);
                }
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    int idx = level.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= chunk.getSectionsCount()) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(idx);
                    if (section.hasOnlyAir() || !section.maybeHas(IS_PART)) {
                        continue;
                    }
                    int x0 = Math.max(minX, cx << 4) & 15, x1 = Math.min(maxX, (cx << 4) + 15) & 15;
                    int y0 = Math.max(minY, sy << 4) & 15, y1 = Math.min(maxY, (sy << 4) + 15) & 15;
                    int z0 = Math.max(minZ, cz << 4) & 15, z1 = Math.min(maxZ, (cz << 4) + 15) & 15;
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                BlockState st = section.getBlockState(x, y, z);
                                Block b = st.getBlock();
                                if (b instanceof BalloonBlock) {
                                    balloons++;
                                } else if (b instanceof ShipHelmBlock) {
                                    helms++;
                                } else if (b instanceof FloaterBlock) {
                                    int power = st.hasProperty(BlockStateProperties.POWER) ? st.getValue(BlockStateProperties.POWER) : 0;
                                    floaters += 15 - power;
                                } else if (b instanceof AnchorBlock) {
                                    anchors++;
                                    if (st.hasProperty(BlockStateProperties.POWERED) && st.getValue(BlockStateProperties.POWERED)) {
                                        active++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Counts after = new Counts(helms, balloons, floaters, anchors, active);
        if (after.equals(before)) {
            return new Result(Outcome.UNCHANGED, before, after);
        }
        if (!dryRun) {
            // Eureka's setters drop the attachment as soon as all four counts are <= 0. Raise first, then lower, so
            // the attachment is only dropped if the ship really has no Eureka parts left.
            control.setHelms(Math.max(before.helms(), helms));
            control.setBalloons(Math.max(before.balloons(), balloons));
            control.setFloaters(Math.max(before.floaters(), floaters));
            control.setAnchors(Math.max(before.anchors(), anchors));
            control.setAnchorsActive(active);
            control.setHelms(helms);
            control.setBalloons(balloons);
            control.setFloaters(floaters);
            control.setAnchors(anchors);
            Stats.event("recount.shipCorrected");
            SmoothSailing.LOGGER.info("[Smooth Sailing] Corrected Eureka part counts of ship {} ({}): [{}] -> [{}]",
                ship.getId(), ship.getSlug(), before, after);
        }
        return new Result(Outcome.CORRECTED, before, after);
    }
}
