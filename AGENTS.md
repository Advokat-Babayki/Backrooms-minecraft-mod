# AGENTS.md

Instructions for coding agents working on this repository. Read this before
editing.

## What this is

NeoForge 1.21.1 mod (Java 21, ModDevGradle 2.0.148) that adds the Backrooms
Level 0 as a dimension with a custom `ChunkGenerator`. Mod id `backrooms`,
version 0.1.0. One dimension, no structures, no natural mob spawns, no datagen
providers yet.

## Build and run

```sh
./gradlew build        # build/libs/backrooms-0.1.0.jar
./gradlew runClient
./gradlew runServer
./gradlew runData
```

There are no unit tests, gametests or CI. "Verified" means: the build passes
and, for worldgen changes, the preview stats below are sane. The first build
needs network access for NeoForge and Parchment.

## Layout

| Path | Role |
|---|---|
| `src/main/java/.../Level0Plan.java` | Pure plan of the level: street grid, blocks, rooms, doors, columns, stairs, furniture and rare piles. All tunables live here as `public static final`. |
| `src/main/java/.../Level0Generator.java` | Writes the plan into chunk blocks: solid mass, carving, ceilings, lights, furniture. |
| `src/main/java/.../BackroomsWorldgen.java` | Registers the `backrooms:level0` chunk generator codec. |
| `src/main/java/.../BackroomsCommands.java` | `/backrooms enter`, `leave`, `room`, `anomaly` (nearest furniture pile). |
| `src/main/java/.../BackroomsBlocks.java` | Blocks and block items. |
| `src/main/java/.../BackroomsMod.java` | Mod entry point and creative tab. |
| `src/main/java/.../BackroomsSounds.java` | `backrooms:hum` sound event. |
| `src/main/resources/data/backrooms/` | Dimension, dimension type, biome. |
| `src/main/resources/assets/backrooms/` | Blockstates, models, textures, `sounds.json`, `hum.ogg`, lang. |
| `src/main/templates/META-INF/neoforge.mods.toml` | Metadata template expanded from `gradle.properties`. |
| `tools/level0-preview/` | Standalone HTML/JS preview of the plan; `core.js` is a port of `Level0Plan.java`. |
| `gen_hum.py` | Generates `hum.ogg`. |
| `gen_textures.py` | Generates block and furniture material textures. |
| `gen_furniture_models.py` | Generates furniture block models, blockstates and item models. |

## Hard invariants

1. **Chunk determinism.** `Level0Plan` must stay a pure function of
   `(seed, x, z)`. No `Random`, no wall-clock, no mutable state that depends on
   generation order. All randomness goes through `hash(x, z, salt)`. Neighbouring
   chunks must agree on their common border.
2. **Plan and preview are a pair.** `tools/level0-preview/core.js` mirrors
   `Level0Plan.java`: the same salts, the same hash step order, the same layout
   logic. `DEFAULT_CONFIG` mirrors the Java tunables 1:1 and the key table lives
   in `tools/level0-preview/README.md`. JS uses `BigInt` to reproduce unsigned
   64-bit math (`Long.remainderUnsigned`). Change both sides in one commit.
3. **Seed 0 is duplicated.** `data/backrooms/dimension/level0.json` relies on the
   `maze_seed` default of 0, and `BackroomsCommands` builds `new Level0Plan(0L)`.
   Change both (and the preview `seed`) or `/backrooms enter` lands in a different
   maze than the one that is generated.
4. **Vertical budget.** Generator depth is 128. Solid mass is y=60..105. Base
   floor is y=64, each level shifts it by ±3. A carved column's ceiling sits at
   floor + 4, or floor + 5 when a bottom slab is present; halls add up to
   `HALL_CEIL_MAX` more, hard-capped at y=100. Streets and gap halls stay at
   +4/5. A stair column is capped at `max(tread + 4, block ceiling)`: a raised
   stairwell stays open to the room, a lowered one keeps its stepped soffit and
   never hangs below that ceiling. Never carve outside the mass, and never let a
   raised district's ceiling exceed `FILL_TOP_Y`.
5. **Reachability.** Doors are 3 blocks wide (`DOOR_WIDTH`). Every room must be
   reachable from spawn. Raised/lowered blocks get `LEVEL_HEIGHT` carpet stair
   blocks at one street door; solids that would cover a stair are dropped in
   `computeBlock`. Blocks of one raised district also join through halls at the
   district floor. Keep it that way.
6. **Hum.** The biome has exactly one sound: `ambient_sound: backrooms:hum`. Do
   not add `additions_sound` or `mood_sound` — they restart the loop at full
   volume and caused the abrupt hum fixed in commit `8a0a07c`.
7. **Generated assets.** `hum.ogg` comes from `gen_hum.py` (Python 3 + ffmpeg,
   libvorbis); block and item textures come from `gen_textures.py` (stdlib only),
   which also writes `build/texture_preview/` for tiling and mipmap checks.
   Edit the scripts, not the binaries.
8. **New block checklist.** Register in `BackroomsBlocks`, add the item to the
   creative tab in `BackroomsMod`, add `en_us` and `ru_ru` names, blockstate,
   block and item models, texture. Structure blocks (wallpaper, carpet,
   ceiling, slab, stairs) are intentionally unbreakable:
   `strength(-1, 3600000)`. Furniture is the only breakable family: wood is
   `strength(2.0, 3.0)` and mines with an axe, metal `strength(3.5, 6.0)` with
   a pickaxe. Each furniture block has a loot table in
   `data/backrooms/loot_table/blocks/`: wooden pieces salvage into their own
   planks, metal into iron nuggets, and the filing cabinet spills its contents
   through `Containers.dropContentsOnDestroy`. Furniture models, blockstates
   and item models come from `gen_furniture_models.py`; edit the script, not
   the JSON.
9. **Respawn.** Dying in a `backrooms:*` dimension keeps the player on that
   level: a bed set in that level wins, otherwise `BackroomsRespawnHandler`
   redirects the respawn to the level-0 entry point and leaves the player's
   own respawn data untouched. `/backrooms leave` clears a Backrooms respawn
   point. Bed sleeping needs `bed_works` and `natural` in the level-0
   dimension type; do not turn either off.

## Verify worldgen changes

```sh
node -e "const C=require('./tools/level0-preview/core.js'); console.log(C.build(C.DEFAULT_CONFIG,{span:768}).stats)"
```

Baseline at commit `8a0a07c`: `carvedPct` 86.3, `reachPct` 100, `sealedRooms` 0,
`floorMin`/`floorMax` 61/67, `spawnInside` true. Exact percentages may drift with
intentional changes; unreachable carved space and sealed rooms must stay at zero.

## Style

- Java 21, 4 spaces, records for plain data, utility classes `final` with a
  private constructor.
- Generator code uses plain loops, no streams — it runs per block.
- Comments in English and only for *why*. Player-facing command output is
  Russian today; keep new player text consistent with it.
- Keep tunables as `public static final` in `Level0Plan` so the preview can
  mirror them.
- No new dependencies for what the JDK, vanilla or NeoForge already provide.
- Encoding is UTF-8 (`options.encoding` in `build.gradle`).

## Do not

- Commit `build/`, `.gradle/`, `run/` or `Для вдохновения/`; the last one is
  ignored reference art. Do not copy assets from it into the mod.
- Change the license (`All Rights Reserved`) or mod id/name without updating
  `gradle.properties`, the `@Mod` annotation, the package and the metadata
  template together.
- Rely on vanilla world height (384) or skylight: this dimension is 128 tall and
  has no sky.
- Add features to `BackroomsModClient` beyond client-only setup; the main mod
  class must load on a dedicated server.

## Known dead code

None currently known.
