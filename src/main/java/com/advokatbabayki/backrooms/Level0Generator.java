package com.advokatbabayki.backrooms;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Level 0: a solid wallpaper mass carved around the virtual route (Level0Plan).
 *
 * Every column is filled solid from y60 to y105; everything the player can
 * walk in is carved out of that mass, so walls and ceilings merge into each
 * other by construction - there is no path to the outside of the level, and
 * above the mass the world is flat empty air.
 *
 * The route lines and rooms are carved at the cell's floor height; where a
 * line crosses into a neighboring cell at a different height, a slab ramp
 * (half-step, half-block per block) climbs or descends inside the source
 * cell's edge, so the maze rises and falls without jumps.
 */
public class Level0Generator extends ChunkGenerator {
    public static final MapCodec<Level0Generator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            net.minecraft.util.ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("maze_seed", 0).forGetter(g -> 0)
    ).apply(instance, Level0Generator::new));

    /** Solid mass bounds; all carving happens strictly inside them. */
    private static final int FILL_MIN_Y = 60;
    private static final int FILL_TOP_Y = 105;
    private static final int LIGHT_EVERY = 6;
    private static final int CORRIDOR_HALF = 1; // 3-wide route bands

    private final Level0Plan plan;

    public Level0Generator(BiomeSource biomeSource, long seed) {
        super(biomeSource);
        this.plan = new Level0Plan(seed);
    }

    public Level0Generator(BiomeSource biomeSource) {
        this(biomeSource, 0L);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {
        BlockState carpet = BackroomsBlocks.MOIST_CARPET.get().defaultBlockState();
        BlockState wallpaper = BackroomsBlocks.YELLOW_WALLPAPER.get().defaultBlockState();
        BlockState ceilingTile = BackroomsBlocks.CEILING_TILE.get().defaultBlockState();
        BlockState light = BackroomsBlocks.FLUORESCENT_PANEL.get().defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState slabBottom = BackroomsBlocks.WALLPAPER_SLAB.get().defaultBlockState();
        BlockState slabTop = slabBottom.setValue(SlabBlock.TYPE, SlabType.TOP);

        ChunkPos pos = chunk.getPos();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        Level0Plan.ChunkPlan chunkPlan = plan.planChunk(pos.x, pos.z);

        // ---- Pass 1: solid mass everywhere ----
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = FILL_MIN_Y; y <= FILL_TOP_Y; y++) {
                    chunk.setBlockState(new BlockPos(baseX + lx, y, baseZ + lz), wallpaper, false);
                }
            }
        }

        // ---- Pass 2: carve the hallway links (with ramps where height changes) ----
        for (Level0Plan.Segment seg : chunkPlan.segments()) {
            if (seg.kind() == Level0Plan.SEG_EW) {
                carveLine(chunk, seg, baseX, baseZ, true,
                        carpet, ceilingTile, light, air, slabBottom);
            } else {
                carveLine(chunk, seg, baseX, baseZ, false,
                        carpet, ceilingTile, light, air, slabBottom);
            }
        }

        // ---- Pass 3: carve the cell's room ----
        Level0Plan.Room room = chunkPlan.room();
        if (room != null) {
            carveRoom(chunk, plan, room, baseX, baseZ,
                    carpet, ceilingTile, light, air, slabBottom, slabTop);
        }

        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * Carves one hallway run. Ramp columns (rampBase..rampBase+2|delta|)
     * become alternating bottom-slab / full-block steps of +0.5 each,
     * walkable without jumping; the generator's caller pre-clips runs.
     */
    private void carveLine(ChunkAccess chunk, Level0Plan.Segment seg,
            int baseX, int baseZ, boolean alongX,
            BlockState carpet, BlockState ceilingTile, BlockState light, BlockState air,
            BlockState slabBottom) {
        int rampLen = seg.rampDelta() != 0 ? 2 * Math.abs(seg.rampDelta()) : 0;

        for (int a = seg.from(); a <= seg.to(); a++) {
            int p = rampLen > 0 && a >= seg.rampBase() && a < seg.rampBase() + rampLen
                    ? a - seg.rampBase() + 1 : 0;
            for (int w = -CORRIDOR_HALF; w <= CORRIDOR_HALF; w++) {
                int x = alongX ? a : seg.fixed() + w;
                int z = alongX ? seg.fixed() + w : a;
                if (x < baseX || x > baseX + 15 || z < baseZ || z > baseZ + 15) continue;
                carveStep(chunk, x, z, seg.levelY(), p, seg.rampDelta(), baseX, baseZ,
                        carpet, ceilingTile, light, air, slabBottom);
            }
        }
    }

    /** One column of a route line: flat corridor piece or one ramp step. */
    private void carveStep(ChunkAccess chunk, int x, int z, int h, int p, int d,
            int baseX, int baseZ,
            BlockState carpet, BlockState ceilingTile, BlockState light, BlockState air,
            BlockState slabBottom) {
        int hi = h + Math.max(0, d);
        int lo = h + Math.min(0, d);
        if (p == 0) {
            chunk.setBlockState(new BlockPos(x, h, z), carpet, false);
            for (int y = h + 1; y <= h + 3; y++) {
                chunk.setBlockState(new BlockPos(x, y, z), air, false);
            }
            setCeiling(chunk, x, z, h + 4, baseX, baseZ, ceilingTile, light);
            return;
        }
        // Ramp column
        for (int y = lo + 1; y <= hi + 3; y++) {
            chunk.setBlockState(new BlockPos(x, y, z), air, false);
        }
        if (d > 0) {
            chunk.setBlockState(new BlockPos(x, h, z), carpet, false); // base under rising steps
        }
        int space = d > 0 ? h + (p + 1) / 2 : h - ((p % 2 == 1) ? (p - 1) / 2 : p / 2);
        BlockState step = (p % 2 == 1) ? slabBottom : carpet;
        chunk.setBlockState(new BlockPos(x, space, z), step, false);
        setCeiling(chunk, x, z, hi + 4, baseX, baseZ, ceilingTile, light);
    }

    private void setCeiling(ChunkAccess chunk, int x, int z, int y, int baseX, int baseZ,
            BlockState ceilingTile, BlockState light) {
        BlockState state = (x - baseX) % LIGHT_EVERY == 3 && (z - baseZ) % LIGHT_EVERY == 3
                ? light : ceilingTile;
        chunk.setBlockState(new BlockPos(x, y, z), state, false);
    }

    // ---- Rooms ----

    private void carveRoom(ChunkAccess chunk, Level0Plan plan, Level0Plan.Room room,
            int baseX, int baseZ,
            BlockState carpet, BlockState ceilingTile, BlockState light, BlockState air,
            BlockState slabBottom, BlockState slabTop) {
        int h = plan.cellFloorY(room.cx(), room.cz());
        int type = plan.roomType(room.cx(), room.cz());
        boolean tall = type == Level0Plan.ROOM_PILLARS || type == Level0Plan.ROOM_MEZZANINE
                || type == Level0Plan.ROOM_TALL;
        int ceil = tall ? h + 16 : h + 4;

        int rMinX = Math.max(baseX, Level0Plan.roomMinX(room));
        int rMaxX = Math.min(baseX + 15, Level0Plan.roomMaxX(room));
        int rMinZ = Math.max(baseZ, Level0Plan.roomMinZ(room));
        int rMaxZ = Math.min(baseZ + 15, Level0Plan.roomMaxZ(room));
        if (rMinX > rMaxX || rMinZ > rMaxZ) return;

        // Floor and open volume
        for (int x = rMinX; x <= rMaxX; x++) {
            for (int z = rMinZ; z <= rMaxZ; z++) {
                chunk.setBlockState(new BlockPos(x, h, z), carpet, false);
                for (int y = h + 1; y <= ceil - 1; y++) {
                    chunk.setBlockState(new BlockPos(x, y, z), air, false);
                }
            }
        }

        // Interior features
        switch (type) {
            case Level0Plan.ROOM_PILLARS -> carvePillars(chunk, room, baseX, baseZ, rMinX, rMaxX, rMinZ, rMaxZ, h);
            case Level0Plan.ROOM_MEZZANINE -> carveMezzanine(chunk, room, baseX, baseZ, rMinX, rMaxX, rMinZ, rMaxZ, h,
                    carpet, slabBottom);
            case Level0Plan.ROOM_HOLES -> carveHoles(chunk, room, baseX, baseZ, rMinX, rMaxX, rMinZ, rMaxZ, h,
                    air, slabBottom, slabTop);
            default -> { /* plain, blackout and tall halls stay empty */ }
        }

        // Ceiling with lights (blackout rooms stay dark)
        for (int x = rMinX; x <= rMaxX; x++) {
            for (int z = rMinZ; z <= rMaxZ; z++) {
                BlockState state = type != Level0Plan.ROOM_BLACKOUT
                        && (x - baseX) % LIGHT_EVERY == 3 && (z - baseZ) % LIGHT_EVERY == 3
                        ? light : ceilingTile;
                chunk.setBlockState(new BlockPos(x, ceil, z), state, false);
            }
        }
    }

    /**
     * Pillar forest. Pillars never stand on the room center (the entry
     * point); the hallway mouths at the walls stay open either way.
     */
    private void carvePillars(ChunkAccess chunk, Level0Plan.Room room,
            int baseX, int baseZ, int rMinX, int rMaxX, int rMinZ, int rMaxZ, int h) {
        BlockState wallpaper = BackroomsBlocks.YELLOW_WALLPAPER.get().defaultBlockState();
        for (int x = rMinX + 2; x <= rMaxX - 2; x += 3) {
            for (int z = rMinZ + 2; z <= rMaxZ - 2; z += 3) {
                if (x < baseX || x > baseX + 15 || z < baseZ || z > baseZ + 15) continue;
                boolean atCenter = Math.abs(x - room.centerX()) <= 2 && Math.abs(z - room.centerZ()) <= 2;
                if (atCenter) continue;
                for (int y = h + 1; y <= h + 15; y++) {
                    chunk.setBlockState(new BlockPos(x, y, z), wallpaper, false);
                }
            }
        }
    }

    /**
     * Mezzanine: a raised deck filling one hashed corner of the room, with a
     * slab staircase hugging the two walls of that corner. The deck leaves a
     * 3-high passage underneath and 11 blocks of headroom on top.
     */
    private void carveMezzanine(ChunkAccess chunk, Level0Plan.Room room,
            int baseX, int baseZ, int rMinX, int rMaxX, int rMinZ, int rMaxZ, int h,
            BlockState carpet, BlockState slabBottom) {
        int half = room.size() / 2;
        long hr = hashRoom(room.cx(), room.cz(), 42L);
        int sx = Math.floorMod(hr, 2) == 0 ? -1 : 1;
        int sz = Math.floorMod(hr >>> 16, 2) == 0 ? -1 : 1;

        // Deck: corner block layer at h+4
        int dX1 = Math.min(room.centerX() + sx * 3, room.centerX() + sx * (half - 2));
        int dX2 = Math.max(room.centerX() + sx * 3, room.centerX() + sx * (half - 2));
        int dZ1 = Math.min(room.centerZ() + sz * 3, room.centerZ() + sz * (half - 2));
        int dZ2 = Math.max(room.centerZ() + sz * 3, room.centerZ() + sz * (half - 2));
        for (int x = Math.max(dX1, baseX); x <= Math.min(dX2, baseX + 15); x++) {
            for (int z = Math.max(dZ1, baseZ); z <= Math.min(dZ2, baseZ + 15); z++) {
                chunk.setBlockState(new BlockPos(x, h + 4, z), carpet, false);
            }
        }

        // Staircase: 8 steps rising h+1 -> h+5, run along the sx wall, band at the sz wall
        int xLow = sx < 0 ? rMinX : rMaxX - 7;
        int zLow = sz < 0 ? rMinZ : rMaxZ - 2;
        for (int i = 0; i < 8; i++) {
            int x = sx < 0 ? xLow + i : xLow + (7 - i);
            int p = i + 1;
            for (int z = zLow; z < zLow + 3; z++) {
                if (x < baseX || x > baseX + 15 || z < baseZ || z > baseZ + 15) continue;
                chunk.setBlockState(new BlockPos(x, h, z), carpet, false);
                int space = h + (p + 1) / 2;
                BlockState step = (p % 2 == 1) ? slabBottom : carpet;
                chunk.setBlockState(new BlockPos(x, space, z), step, false);
            }
        }
    }

    /**
     * Torn floor: irregular shallow pits (depth 3), never under the room's
     * entry crossing. Each pit gets an escape half-step column in its corner,
     * so a player who falls in can always climb out.
     */
    private void carveHoles(ChunkAccess chunk, Level0Plan.Room room,
            int baseX, int baseZ, int rMinX, int rMaxX, int rMinZ, int rMaxZ, int h,
            BlockState air, BlockState slabBottom, BlockState slabTop) {
        Random rand = new Random(hashRoom(room.cx(), room.cz(), 777L));
        int gapCount = 2 + rand.nextInt(5); // 2..6
        for (int g = 0; g < gapCount; g++) {
            int w = 3 + rand.nextInt(5); // 3..7
            int d = 3 + rand.nextInt(3); // 3..5
            int gx = rMinX + 2 + rand.nextInt(Math.max(1, rMaxX - rMinX - 3 - w));
            int gz = rMinZ + 2 + rand.nextInt(Math.max(1, rMaxZ - rMinZ - 3 - d));
            // Keep the entry crossing (room center) walkable
            if (gx <= room.centerX() + 3 && gx + w - 1 >= room.centerX() - 3
                    && gz <= room.centerZ() + 3 && gz + d - 1 >= room.centerZ() - 3) continue;
            for (int x = Math.max(gx, baseX); x <= Math.min(gx + w - 1, baseX + 15) && x <= rMaxX; x++) {
                for (int z = Math.max(gz, baseZ); z <= Math.min(gz + d - 1, baseZ + 15) && z <= rMaxZ; z++) {
                    for (int y = h; y >= h - 2; y--) {
                        chunk.setBlockState(new BlockPos(x, y, z), air, false);
                    }
                }
            }
            // Escape column in the pit's corner: +0.5, +0.5, then one jump to the floor
            if (gx >= baseX && gx <= baseX + 15 && gz >= baseZ && gz <= baseZ + 15) {
                chunk.setBlockState(new BlockPos(gx, h - 2, gz), slabBottom, false);
                chunk.setBlockState(new BlockPos(gx, h - 1, gz), slabTop, false);
            }
        }
    }

    private static long hashRoom(int cx, int cz, long salt) {
        long h = (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xBF58476D1CE4E5B9L) ^ (salt * 0x94D049BB133111EBL);
        h ^= h >>> 29;
        h *= 0x165667B19E3779F9L;
        h ^= h >>> 32;
        return h;
    }

    @Override
    public int getGenDepth() {
        return 128;
    }

    @Override
    public int getSeaLevel() {
        return 0;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    /**
     * Honest walk height for a position: one block above that cell's floor.
     * Used by the dimension spawn search, so it must match the carved space.
     */
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return plan.cellFloorY(Math.floorDiv(x, Level0Plan.CELL_BLOCKS), Math.floorDiv(z, Level0Plan.CELL_BLOCKS)) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        return new NoiseColumn(0, new BlockState[0]);
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager,
            StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
    }
}
