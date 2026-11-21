# Eureka: Smooth Sailing

A stability, crash-prevention and performance addon for **Eureka! Ships** on **Valkyrien Skies 2** (Forge 1.20.1).

It adds no blocks or items. It hooks into Eureka and fixes the bugs that crash servers, freeze them, or break ships.
Built for the *mods 4 stodd v3 (season 2)* pack (Eureka 1.6.3, Valkyrien Skies 2.4.11, Eureka Tweaks) and tested
against Eureka 1.6.3 and 1.6.4.

## What it fixes

### Crashes and physics explosions
| Problem in Eureka | What Smooth Sailing does |
|---|---|
| A single NaN/infinite force (from a 0 config value) flings the ship to infinity, loads absurd chunks, and can take the server down. | Every force/torque Eureka hands the physics engine is checked; non-finite ones are dropped, runaway ones (> 1000 m/s² by default) are scaled down. |
| Any exception in Eureka's per-ship physics tick hits the Valkyrien Skies physics thread. | The tick is wrapped; a failing ship skips that tick and the error is logged (rate limited). |
| Eureka Tweaks sliders go down to **0** for `elevationSnappiness`, `speedMassScale`, `linearMaxMass`, `maxSpeedFromEngines`, which Eureka divides by. `maxSizeForTurnSpeedPenalty < 0.5` makes Kotlin's `coerceIn` throw on the physics thread. | A config sanitizer checks `EurekaConfig` every second and clamps values to safe ranges. It logs each fix and tells online ops once. |
| With `maxBalloonsPerEngine > 0`, a ship with no balloons computes `0 × (0/0)` = NaN lift. | Balloon force is forced finite. |
| Cruise on/off messages are sent from the physics thread. | They are moved to the server thread. |
| Right-clicking or breaking a helm/engine whose block entity went missing throws every time. | A fresh block entity is put in place instead. |
| Helm wheel renderer crashes if the block state isn't a helm (Create contraptions, schematic previews, carried blocks). | Those renders are skipped, and the ship lookup is wrapped. |

### Server freezes (assembly)
* Eureka's helm search calls `getBlockState` on everything it touches, which **loads and generates chunks**. Unloaded chunks now count as air.
* Eureka only blacklists vanilla water and lava. Modded fluids (acid, oil, soda…) and bubble columns are never pulled into a ship.
* `maxShipBlocks = 0` ("no limit") is replaced with a cap of 131072 blocks.
* The extended `vs_eureka:assemble_blacklist` covers `#minecraft:dirt`, `#minecraft:sand`, ores, `#forge:stone` and the terrain blocks of the pack's biome mods (Aether, BoP, Alex's Caves, Regions Unexplored, BetterEnd/Nether). So a helm touching modded ground no longer tries to lift the island.
* There's a 1-second cooldown between assemble clicks.
* Disassembly is refused when the ship sticks out above the build limit or below the world bottom, since Eureka would delete those blocks.

### Broken ships
* **Floater double-count.** Powering a floater with redstone subtracted its buoyancy twice, so the count went negative and the ship got dragged underwater. Fixed.
* Part counts (helms, balloons, floaters, anchors) can no longer go negative.
* **Recount:** shortly after a ship loads, and every 10 minutes, the real Eureka blocks on it are counted and wrong numbers are corrected. This repairs ships already broken in old saves. `/smoothsailing recount` does it on demand.

### Valkyrien Skies 2.4.11 helm-dismount teleport (Eureka issue #500)
The pack runs VS 2.4.11, which Eureka itself warns about. Leaving a helm can teleport the player sideways (up to hundreds of blocks) or back to where they sat down, and can deal fall damage for the ship's height change.
When you sneak off a helm and end up further from the seat than walking could explain, you're put back next to it and the bogus fall distance is cleared.
Teleports, portals, commands and waystones are never undone.

### Performance and memory
* Engine heat changes are block-state changes: a light update, neighbour and shape updates, a forced synchronous chunk re-render on clients and a VS mass/collision update. They now use lightweight update flags and are rate limited per engine. The engine's power logic itself is unchanged.
* **Client memory leak.** Eureka's helm-wheel model getter captures the first `ModelBakery` forever, which keeps every baked model of the pack alive after each F3+T or resource-pack change. Wheels also go stale after a reload. Models now come from the live model manager.
* Ships no longer keep a reference to the last player that sat at their helm (it was never cleared, even after logout).
* A stale helm menu closes once the helm is gone, so it can't assemble or disassemble remotely.

## Install
Drop `smoothsailing-<version>.jar` into `mods/`. It requires Valkyrien Skies 2.4.x and Eureka 1.6.x. Eureka Tweaks is optional and supported.
The jar works server-only, client-only or on both sides: there are no registry entries and no custom packets, and vanilla-compatible clients can still connect.

## Commands (op level 2)
* `/smoothsailing status` shows versions, notes, which hooks are live, and what was prevented this session.
* `/smoothsailing recount` recounts the ship you sit on, look at or stand on.
* `/smoothsailing recount all` recounts every loaded ship.

## Config
`config/smoothsailing-common.toml`: every fix has its own switch and is documented in the file.
`config/smoothsailing-mixins.properties` is an emergency switch to turn single hooks off if another mod conflicts.

## Pack notes
* **Entity Culling**: if helm wheels or seats on ships vanish, add `valkyrienskies:ship_mounting_entity` to `tickCullingWhitelist` and `vs_eureka:ship_helm` to `blockEntityWhitelist`.

## Building
```
./gradlew build
```
The jar is written to `build/libs/`. Java 17 is needed. Valkyrien Skies and Eureka are compile-only dependencies, pulled from Modrinth Maven and the VS maven.

Every push runs GitHub Actions (`.github/workflows/build.yml`), which:
1. builds the jar;
2. installs a real Forge 1.20.1-47.4.20 dedicated server with Valkyrien Skies 2.4.11, Kotlin for Forge and Eureka (1.6.3, then 1.6.4);
3. runs the in-game self-test (`-Dsmoothsailing.selftest=true`). It joins a test player, builds and assembles a Eureka ship, then checks the physics guard, NaN handling, floater fix, recount, sanitizer, engine hook, helm seat/dismount fix, missing block entity recovery and disassembly. It also asserts that every hook actually ran;
4. uploads the jar and logs as an artifact and to the `ci-results` branch.

Tag a commit `v*` to get a GitHub release with the jar attached.

## License
MIT. Eureka (Apache-2.0) and Valkyrien Skies are not included and must be installed separately.
