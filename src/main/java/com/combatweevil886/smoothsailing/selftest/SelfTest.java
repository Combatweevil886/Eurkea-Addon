package com.combatweevil886.smoothsailing.selftest;

import com.combatweevil886.smoothsailing.SmoothSailing;
import com.combatweevil886.smoothsailing.config.SSConfig;
import com.combatweevil886.smoothsailing.core.AssemblyGuard;
import com.combatweevil886.smoothsailing.core.CompatReport;
import com.combatweevil886.smoothsailing.core.ConfigSanitizer;
import com.combatweevil886.smoothsailing.core.PhysicsGuard;
import com.combatweevil886.smoothsailing.core.ShipPartRecount;
import com.combatweevil886.smoothsailing.core.Stats;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBic;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.block.FloaterBlock;
import org.valkyrienskies.eureka.block.ShipHelmBlock;
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity;
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity;
import org.valkyrienskies.eureka.block.EngineBlock;
import org.valkyrienskies.eureka.ship.EurekaShipControl;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * End-to-end test on a real dedicated server, used by CI ({@code -Dsmoothsailing.selftest=true}).
 * <p>
 * Builds a small Eureka ship, assembles it through the real helm code, then exercises every protection that can be
 * reached without a human client, asserting both the outcome and that each mixin handler actually ran. A fake
 * player is joined through an embedded network channel so Valkyrien Skies loads and ticks the ship like it would for
 * a real player. Prints {@code SMOOTHSAILING-SELFTEST RESULT: PASS|FAIL} and stops the server.
 */
public final class SelfTest {
    private static final String TAG = "[SelfTest] ";
    private static final UUID BOT_ID = UUID.fromString("5a5a5a5a-0000-4000-8000-00000000cafe");

    private record Step(String name, int timeoutTicks, boolean critical, BooleanSupplier body) {
    }

    private static boolean running;
    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> RESULTS = new ArrayList<>();
    private static int failures;
    private static int stepIndex;
    private static int stepTicks;
    private static int globalTicks;

    private static MinecraftServer server;
    private static ServerLevel level;
    private static ServerPlayer bot;
    private static EmbeddedChannel botChannel;
    private static BlockPos base;
    private static BlockPos helmWorldPos;
    private static LoadedServerShip ship;
    private static BlockPos helmShipPos;
    private static BlockPos floaterShipPos;
    private static BlockPos engineShipPos;
    private static Vec3 seatShipPos;
    private static long waitUntil;
    private static long marker;
    private static double savedValue;

    private SelfTest() {
    }

    public static boolean requested() {
        return Boolean.getBoolean("smoothsailing.selftest");
    }

    // ------------------------------------------------------------------------------------------------ harness

    private static void log(String msg) {
        SmoothSailing.LOGGER.info(TAG + msg);
    }

    private static boolean check(String name, boolean ok, String detail) {
        String line = (ok ? "PASS  " : "FAIL  ") + name + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")");
        RESULTS.add(line);
        log(line);
        if (!ok) {
            failures++;
        }
        return ok;
    }

    private static void skip(String name, String why) {
        String line = "SKIP  " + name + "  (" + why + ")";
        RESULTS.add(line);
        log(line);
    }

    private static boolean waitTicks(int ticks) {
        if (waitUntil == 0) {
            waitUntil = level.getGameTime() + ticks;
        }
        if (level.getGameTime() >= waitUntil) {
            waitUntil = 0;
            return true;
        }
        return false;
    }

    public static void start(MinecraftServer srv) {
        if (running) {
            return;
        }
        running = true;
        server = srv;
        level = srv.overworld();
        log("starting; Eureka " + CompatReport.version("vs_eureka").orElse("?") + ", VS " + CompatReport.version("valkyrienskies").orElse("?"));
        buildSteps();
    }

    public static void tick(MinecraftServer srv) {
        if (!running) {
            return;
        }
        globalTicks++;
        if (botChannel != null) {
            botChannel.releaseOutbound();
            botChannel.runPendingTasks();
        }
        if (bot != null && !bot.isRemoved()) {
            try {
                bot.doTick(); // the embedded connection is not ticked by the server, so tick the player ourselves
            } catch (Throwable t) {
                log("bot tick threw: " + t);
            }
        }
        if (globalTicks > 20 * 60 * 4) {
            check("selftest finished within 4 minutes", false, "aborted at step " + stepIndex);
            finish();
            return;
        }
        if (stepIndex >= STEPS.size()) {
            finish();
            return;
        }
        Step step = STEPS.get(stepIndex);
        stepTicks++;
        boolean done;
        try {
            done = step.body.getAsBoolean();
        } catch (Throwable t) {
            SmoothSailing.LOGGER.error(TAG + "step '" + step.name + "' threw", t);
            check(step.name, false, "exception: " + t);
            done = true;
            if (step.critical) {
                stepIndex = STEPS.size();
                return;
            }
        }
        if (!done && stepTicks > step.timeoutTicks) {
            check(step.name, false, "timed out after " + step.timeoutTicks + " ticks");
            done = true;
            if (step.critical) {
                stepIndex = STEPS.size();
                return;
            }
        }
        if (done) {
            stepIndex++;
            stepTicks = 0;
            waitUntil = 0;
        }
    }

    private static void finish() {
        running = false;
        StringBuilder sb = new StringBuilder();
        sb.append("Eureka: Smooth Sailing self-test\n");
        sb.append("Eureka ").append(CompatReport.version("vs_eureka").orElse("?"))
            .append(" / Valkyrien Skies ").append(CompatReport.version("valkyrienskies").orElse("?")).append('\n');
        sb.append("Applied mixins: ").append(CompatReport.appliedMixins()).append("\n\n");
        RESULTS.forEach(r -> sb.append(r).append('\n'));
        sb.append("\nHooks:\n");
        Stats.hooks().forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
        sb.append("Events:\n");
        Stats.events().forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
        String verdict = failures == 0 ? "PASS" : "FAIL";
        sb.append("\nRESULT: ").append(verdict).append(" (").append(failures).append(" failure(s))\n");
        try {
            Files.writeString(Path.of("smoothsailing-selftest.txt"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log("could not write report: " + e);
        }
        for (String line : sb.toString().split("\n")) {
            SmoothSailing.LOGGER.info(TAG + line);
        }
        SmoothSailing.LOGGER.info("SMOOTHSAILING-SELFTEST RESULT: {}", verdict);
        try {
            if (bot != null && bot.connection != null) {
                bot.connection.disconnect(net.minecraft.network.chat.Component.literal("self-test done"));
            }
        } catch (Throwable ignored) {
        }
        server.halt(false);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Block eureka(String id) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("vs_eureka", id));
        if (b == null || b == Blocks.AIR) {
            throw new IllegalStateException("missing block vs_eureka:" + id);
        }
        return b;
    }

    private static void set(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    private static EurekaShipControl control() {
        return ship == null ? null : ship.getAttachment(EurekaShipControl.class);
    }

    private static BlockPos findOnShip(Class<? extends Block> type) {
        AABBic box = ship.getShipAABB();
        if (box == null) {
            return null;
        }
        for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
            for (int y = box.minY() - 1; y <= box.maxY() + 1; y++) {
                for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (type.isInstance(level.getBlockState(p).getBlock())) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private static LoadedServerShip findShipNear(BlockPos worldPos) {
        for (Ship s : VSGameUtilsKt.getShipsIntersecting(level, new AABB(worldPos).inflate(4.0))) {
            Vector3dc inShip = s.getTransform().getPositionInShip();
            LoadedServerShip loaded = VSGameUtilsKt.getLoadedShipManagingPos(level,
                BlockPos.containing(inShip.x(), inShip.y(), inShip.z()));
            if (loaded != null) {
                return loaded;
            }
        }
        return null;
    }

    private static Vector3d seatWorld() {
        return ship.getShipToWorld().transformPosition(new Vector3d(seatShipPos.x, seatShipPos.y, seatShipPos.z));
    }

    private static BlockHitResult hitOn(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static void buildSteps() {
        STEPS.clear();

        STEPS.add(new Step("setup: load area and join test player", 200, true, () -> {
            BlockPos spawn = level.getSharedSpawnPos();
            base = new BlockPos(spawn.getX(), 120, spawn.getZ());
            int cx = base.getX() >> 4, cz = base.getZ() >> 4;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.setChunkForced(cx + dx, cz + dz, true);
                    level.getChunk(cx + dx, cz + dz);
                }
            }
            GameProfile profile = new GameProfile(BOT_ID, "SS_SelfTest");
            bot = new ServerPlayer(server, level, profile);
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            botChannel = new EmbeddedChannel(connection);
            connection.setProtocol(ConnectionProtocol.PLAY);
            server.getPlayerList().placeNewPlayer(connection, bot);
            bot.setGameMode(GameType.CREATIVE);
            bot.getAbilities().invulnerable = true;
            bot.getAbilities().mayfly = true;
            bot.getAbilities().flying = true;
            bot.onUpdateAbilities();
            bot.teleportTo(level, base.getX() + 0.5, base.getY() + 3, base.getZ() + 6.5, 180f, 20f);
            return check("test player joined", server.getPlayerList().getPlayer(BOT_ID) != null, bot.getName().getString());
        }));

        STEPS.add(new Step("build test ship", 40, true, () -> {
            if (!waitTicks(20)) {
                return false; // let the join settle
            }
            BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    set(base.offset(x, 0, z), planks);
                }
            }
            helmWorldPos = base.offset(0, 1, 0);
            set(helmWorldPos, eureka("oak_ship_helm").defaultBlockState());
            set(base.offset(2, 1, 0), eureka("engine").defaultBlockState());
            set(base.offset(-2, 1, 0), eureka("floater").defaultBlockState());
            set(base.offset(0, 1, 2), eureka("anchor").defaultBlockState());
            for (int x = -2; x <= 2; x++) {
                set(base.offset(x, 1, -2), eureka("white_balloon").defaultBlockState());
            }
            if (level.getBlockEntity(base.offset(2, 1, 0)) instanceof EngineBlockEntity engine) {
                engine.setItem(0, new ItemStack(Items.COAL, 16));
            }
            return check("structure placed", level.getBlockEntity(helmWorldPos) instanceof ShipHelmBlockEntity, "helm at " + helmWorldPos);
        }));

        STEPS.add(new Step("assemble through the helm", 20, true, () -> {
            long before = Stats.hookCount("assembly.bfsGetBlockState");
            ShipHelmBlockEntity helm = (ShipHelmBlockEntity) level.getBlockEntity(helmWorldPos);
            helm.assemble(bot);
            check("hook: assembly BFS guarded getBlockState", Stats.hookCount("assembly.bfsGetBlockState") > before,
                "calls " + (Stats.hookCount("assembly.bfsGetBlockState") - before));
            check("hook: assembly max-ship-blocks cap", Stats.hookCount("assembly.maxShipBlocks") > 0, null);
            check("hook: helm assemble", Stats.hookCount("helm.assemble") > 0, null);
            check("assembly cooldown blocks an immediate second click", AssemblyGuard.onCooldown(bot, level.getGameTime()), null);
            return true;
        }));

        STEPS.add(new Step("ship is created and loaded", 400, true, () -> {
            if (ship == null) {
                ship = findShipNear(helmWorldPos);
            }
            EurekaShipControl c = control();
            if (ship == null || c == null || c.getHelms() < 1) {
                return false;
            }
            helmShipPos = findOnShip(ShipHelmBlock.class);
            floaterShipPos = findOnShip(FloaterBlock.class);
            engineShipPos = findOnShip(EngineBlock.class);
            check("ship loaded with Eureka controller", true, "ship " + ship.getId() + " / " + ship.getSlug());
            ShipPartRecount.Counts counts = ShipPartRecount.countsOf(c);
            check("initial part counts", counts.helms() == 1 && counts.balloons() == 5 && counts.floaters() == 15
                && counts.anchors() == 1 && counts.anchorsActive() == 0, counts.toString());
            check("helm, floater and engine found on ship", helmShipPos != null && floaterShipPos != null && engineShipPos != null,
                helmShipPos + " " + floaterShipPos + " " + engineShipPos);
            return true;
        }));

        STEPS.add(new Step("physics runs and is guarded", 400, false, () -> {
            if (!waitTicks(100)) {
                return false;
            }
            check("hook: physTick wrapped", Stats.PHYS_TICKS.get() > 0, "ticks " + Stats.PHYS_TICKS.get());
            check("hook: applyWorldForce guarded", Stats.hookCount("physics.applyWorldForce") > 0, null);
            check("hook: setBuoyantFactor guarded", Stats.hookCount("physics.setBuoyantFactor") > 0, null);
            check("hook: balloon force guarded", Stats.hookCount("physics.balloonForce") > 0, null);
            check("hook: stabilize torque guarded", Stats.hookCount("physics.stabilizeTorque") > 0, null);
            check("hook: stabilize force guarded", Stats.hookCount("physics.stabilizeForce") > 0, null);
            check("hook: helm tick housekeeping", Stats.hookCount("helm.tick") > 0, null);
            Vector3dc pos = ship.getTransform().getPositionInWorld();
            check("ship position is finite", PhysicsGuard.isFinite(pos), String.valueOf(pos));
            return true;
        }));

        STEPS.add(new Step("floater redstone is counted once", 100, false, () -> {
            EurekaShipControl c = control();
            if (floaterShipPos == null || c == null) {
                skip("floater redstone is counted once", "no floater/controller");
                return true;
            }
            if (marker == 0) {
                set(floaterShipPos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                marker = 1;
                return false;
            }
            if (marker == 1) {
                if (!waitTicks(2)) {
                    return false;
                }
                check("powered floater count (expect 0, Eureka alone gives -15)", c.getFloaters() == 0, "floaters=" + c.getFloaters());
                check("hook: floater duplicate delta dropped", Stats.hookCount("floater.neighborChanged") > 0, null);
                set(floaterShipPos.above(), Blocks.AIR.defaultBlockState());
                marker = 2;
                return false;
            }
            if (!waitTicks(2)) {
                return false;
            }
            check("unpowered floater count back to 15", c.getFloaters() == 15, "floaters=" + c.getFloaters());
            marker = 0;
            return true;
        }));

        STEPS.add(new Step("negative counts clamp and recount repairs", 20, false, () -> {
            EurekaShipControl c = control();
            c.setBalloons(-7);
            check("negative balloon count clamped to 0", c.getBalloons() == 0, "balloons=" + c.getBalloons());
            ShipPartRecount.Result r = ShipPartRecount.recount(level, ship, false);
            check("recount restores 5 balloons", r.outcome() == ShipPartRecount.Outcome.CORRECTED && c.getBalloons() == 5,
                r.outcome() + " " + r.after());
            ShipPartRecount.Result again = ShipPartRecount.recount(level, ship, false);
            check("second recount finds nothing to fix", again.outcome() == ShipPartRecount.Outcome.UNCHANGED, String.valueOf(again.outcome()));
            return true;
        }));

        STEPS.add(new Step("config sanitizer clamps divisor values", 20, false, () -> {
            EurekaConfig.Server s = EurekaConfig.SERVER;
            double snap = s.getElevationSnappiness();
            double turn = s.getMaxSizeForTurnSpeedPenalty();
            s.setElevationSnappiness(0.0);
            s.setMaxSizeForTurnSpeedPenalty(0.1);
            ConfigSanitizer.run(server);
            check("elevationSnappiness 0 -> >= 0.01", s.getElevationSnappiness() >= 0.01, "now " + s.getElevationSnappiness());
            check("maxSizeForTurnSpeedPenalty 0.1 -> 0.5", s.getMaxSizeForTurnSpeedPenalty() >= 0.5, "now " + s.getMaxSizeForTurnSpeedPenalty());
            s.setElevationSnappiness(snap);
            s.setMaxSizeForTurnSpeedPenalty(turn);
            return true;
        }));

        STEPS.add(new Step("NaN force from a zero divisor is dropped on the physics thread", 200, false, () -> {
            EurekaConfig.Server s = EurekaConfig.SERVER;
            if (marker == 0) {
                SSConfig.sanitizer = false; // let the bad value through on purpose
                savedValue = s.getElevationSnappiness();
                s.setElevationSnappiness(0.0);
                marker = Stats.eventCount("physics.nonFiniteForceDropped") + 1;
                return false;
            }
            if (!waitTicks(40)) {
                return false;
            }
            long dropped = Stats.eventCount("physics.nonFiniteForceDropped") - (marker - 1);
            s.setElevationSnappiness(savedValue);
            SSConfig.sanitizer = true;
            marker = 0;
            check("NaN lift forces dropped", dropped > 0, dropped + " dropped");
            check("ship still finite after NaN config", PhysicsGuard.isFinite(ship.getTransform().getPositionInWorld()),
                String.valueOf(ship.getTransform().getPositionInWorld()));
            return true;
        }));

        STEPS.add(new Step("engine heat state updates are hooked", 600, false, () -> {
            if (Stats.hookCount("engine.heatStateUpdate") > 0 || Stats.eventCount("engine.heatStateReconciled") > 0) {
                check("hook: engine heat state update", true, null);
                return true;
            }
            return false;
        }));

        STEPS.add(new Step("helm use, seat and dismount fix", 200, false, () -> {
            if (helmShipPos == null) {
                skip("helm use, seat and dismount fix", "helm not found on ship");
                return true;
            }
            if (marker == 0) {
                BlockState helmState = level.getBlockState(helmShipPos);
                helmState.use(level, bot, InteractionHand.MAIN_HAND, hitOn(helmShipPos));
                check("hook: helm use guard", Stats.hookCount("helm.use") > 0, null);
                check("test player seated at helm", bot.getVehicle() instanceof ShipMountingEntity,
                    String.valueOf(bot.getVehicle()));
                if (!(bot.getVehicle() instanceof ShipMountingEntity)) {
                    return true;
                }
                seatShipPos = bot.getVehicle().position();
                marker = 1;
                return false;
            }
            if (marker == 1) {
                if (!waitTicks(10)) {
                    return false;
                }
                bot.setShiftKeyDown(true);
                bot.stopRiding();
                bot.setShiftKeyDown(false);
                check("hook: voluntary dismount recorded", Stats.hookCount("dismount.recorded") > 0, null);
                // Simulate the VS 2.4.11 bug: the player lands 60 blocks away.
                bot.teleportTo(level, bot.getX() + 60, bot.getY() + 5, bot.getZ() + 60, bot.getYRot(), bot.getXRot());
                marker = 2;
                return false;
            }
            if (!waitTicks(3)) {
                return false;
            }
            Vector3d seat = seatWorld();
            double dist = Math.sqrt(bot.distanceToSqr(seat.x, seat.y, seat.z));
            check("dismount fix returned the player to the seat", dist < SSConfig.dismountMaxDrift + 2.0
                && Stats.eventCount("dismount.teleportCorrected") > 0, String.format("%.1f blocks away", dist));
            marker = 0;
            return true;
        }));

        STEPS.add(new Step("helm menu validation and engine guards", 60, false, () -> {
            if (marker == 0) {
                if (level.getBlockEntity(helmShipPos) instanceof ShipHelmBlockEntity helm) {
                    bot.openMenu(helm);
                }
                marker = 1;
                return false;
            }
            if (!waitTicks(5)) {
                return false;
            }
            check("hook: helm menu stillValid", Stats.hookCount("helm.menuStillValid") > 0, null);
            bot.closeContainer();
            if (engineShipPos != null) {
                level.removeBlockEntity(engineShipPos);
                BlockState engineState = level.getBlockState(engineShipPos);
                engineState.use(level, bot, InteractionHand.MAIN_HAND, hitOn(engineShipPos));
                bot.closeContainer();
                check("missing engine block entity recreated instead of crashing",
                    level.getBlockEntity(engineShipPos) instanceof EngineBlockEntity && Stats.eventCount("blockEntity.recreated") > 0, null);
            }
            marker = 0;
            return true;
        }));

        STEPS.add(new Step("disassembly height guard leaves normal ships alone", 5, false, () -> {
            check("ship at y~120 may be disassembled", AssemblyGuard.disassemblyBlockedReason(level, ship) == null, null);
            return true;
        }));

        STEPS.add(new Step("disassemble back into the world", 900, false, () -> {
            if (marker == 0) {
                if (!(level.getBlockEntity(helmShipPos) instanceof ShipHelmBlockEntity helm)) {
                    skip("disassemble back into the world", "helm block entity missing");
                    return true;
                }
                helm.disassemble();
                marker = 1;
                return false;
            }
            if (Stats.hookCount("helm.disassemble") == 0) {
                if (level.getGameTime() % 20 == 0 && level.getBlockEntity(helmShipPos) instanceof ShipHelmBlockEntity helm) {
                    helm.disassemble(); // keep asking until Eureka finished aligning
                }
                return false;
            }
            if (!waitTicks(40)) {
                return false;
            }
            boolean found = false;
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-6, -4, -6), base.offset(6, 8, 6))) {
                if (level.getBlockState(p).getBlock() instanceof ShipHelmBlock) {
                    found = true;
                    break;
                }
            }
            check("hook: disassembly guard", Stats.hookCount("helm.disassemble") > 0, null);
            check("helm is back in the world after disassembly", found, null);
            marker = 0;
            return true;
        }));

        STEPS.add(new Step("all server mixins applied", 5, false, () -> {
            List<String> missing = CompatReport.missingMixins();
            check("all server mixins applied", missing.isEmpty(), missing.isEmpty() ? CompatReport.appliedMixins() : "missing " + missing);
            Map<String, Long> events = Stats.events();
            log("events so far: " + events);
            return true;
        }));
    }
}
