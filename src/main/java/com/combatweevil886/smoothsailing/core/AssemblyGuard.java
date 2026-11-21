package com.combatweevil886.smoothsailing.core;

import com.combatweevil886.smoothsailing.SmoothSailing;
import com.combatweevil886.smoothsailing.config.SSConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes Eureka's helm block search safe:
 * <ul>
 *     <li>never loads/generates a chunk (Eureka's BFS calls {@code getBlockState} on whatever it touches);</li>
 *     <li>never follows fluid blocks (Eureka only blacklists vanilla water/lava);</li>
 *     <li>keeps a hard upper bound when Eureka's own limit is disabled;</li>
 *     <li>rate-limits assemble clicks.</li>
 * </ul>
 * The BFS runs on the server thread only, so a plain static context is enough.
 */
public final class AssemblyGuard {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Map<UUID, Long> LAST_ATTEMPT = new ConcurrentHashMap<>();

    private static Context current;

    public static final class Context {
        public int unloadedPositions;
        public int fluidPositions;
        public int scanned;
    }

    private AssemblyGuard() {
    }

    public static Context begin() {
        Context ctx = new Context();
        current = ctx;
        return ctx;
    }

    public static void end() {
        current = null;
    }

    /** Replacement for {@code level.getBlockState(pos)} inside Eureka's BFS. */
    public static BlockState guardedGetBlockState(ServerLevel level, BlockPos pos, Operation<BlockState> original) {
        Stats.hook("assembly.bfsGetBlockState");
        Context ctx = current;
        if (ctx != null) {
            ctx.scanned++;
        }
        if (level.isOutsideBuildHeight(pos)) {
            return AIR;
        }
        if (SSConfig.assemblyNoChunkLoading && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            if (ctx != null) {
                ctx.unloadedPositions++;
            }
            Stats.event("assembly.unloadedChunkSkipped");
            return AIR;
        }
        BlockState state = original.call(level, pos);
        if (SSConfig.assemblySkipFluids) {
            Block block = state.getBlock();
            if (block instanceof LiquidBlock || block instanceof BubbleColumnBlock) {
                if (ctx != null) {
                    ctx.fluidPositions++;
                }
                return AIR;
            }
        }
        return state;
    }

    /** Eureka treats maxShipBlocks = 0 as unlimited; substitute a sane cap. */
    public static int effectiveMaxShipBlocks(int eurekaValue) {
        Stats.hook("assembly.maxShipBlocks");
        if (eurekaValue > 0) {
            return eurekaValue;
        }
        int cap = SSConfig.assemblyUnlimitedCap;
        return cap > 0 ? cap : eurekaValue;
    }

    /** @return {@code true} if the player must wait before trying again. */
    public static boolean onCooldown(Player player, long gameTime) {
        int cd = SSConfig.assemblyCooldown;
        if (cd <= 0 || player == null) {
            return false;
        }
        Long last = LAST_ATTEMPT.get(player.getUUID());
        if (last != null && gameTime - last < cd && gameTime >= last) {
            return true;
        }
        LAST_ATTEMPT.put(player.getUUID(), gameTime);
        return false;
    }

    public static void report(Player player, Context ctx, ServerShip result) {
        if (ctx == null) {
            return;
        }
        if (ctx.unloadedPositions > 0 || ctx.fluidPositions > 0) {
            SmoothSailing.LOGGER.info("[Smooth Sailing] Assembly by {}: scanned {} positions, skipped {} in unloaded chunks and {} fluid blocks.",
                player == null ? "?" : player.getName().getString(), ctx.scanned, ctx.unloadedPositions, ctx.fluidPositions);
        }
        if (player != null && result != null && ctx.unloadedPositions > 0) {
            player.displayClientMessage(Component.literal(
                "Smooth Sailing: part of the structure reaches into unloaded chunks and was left behind.")
                .withStyle(ChatFormatting.YELLOW), false);
        }
    }

    /**
     * @return a reason string if the ship must not be disassembled right now, otherwise {@code null}.
     */
    public static String disassemblyBlockedReason(ServerLevel level, LoadedServerShip ship) {
        if (!SSConfig.disassemblyHeightGuard || ship == null) {
            return null;
        }
        AABBdc box = ship.getWorldAABB();
        if (box == null) {
            return null;
        }
        double minY = box.minY() - 1.0;
        double maxY = box.maxY() + 1.0;
        if (minY < level.getMinBuildHeight()) {
            return "the ship reaches below the bottom of the world (y " + level.getMinBuildHeight() + ")";
        }
        if (maxY > level.getMaxBuildHeight()) {
            return "the ship reaches above the build limit (y " + level.getMaxBuildHeight() + ")";
        }
        return null;
    }

    public static void clearPlayer(UUID id) {
        LAST_ATTEMPT.remove(id);
    }
}
