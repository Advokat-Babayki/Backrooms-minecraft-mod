package com.advokatbabayki.backrooms;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Level 0: solid wallpaper mass carved by a corridor-first maze (see Level0Plan).
 * Every chunk is filled solid between floor and ceiling, then the plan's
 * corridors and the cell's room are carved out as air. Because the mass is
 * solid, any two carved features that touch are automatically connected and
 * anything not carved is a wall: no floating geometry, no unreachable rooms.
 *
 * Levels: the plan hands each cell a base level (y 61/64/67); corridors that
 * cross cells at different levels get short stair runs inside the higher
 * cell's edge, so the maze gently rises and falls as you walk it.
 */
public class Level0Generator extends ChunkGenerator {
    public static final MapCodec<Level0Generator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            net.minecraft.util.ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("maze_seed", 0).forGetter(g -> 0)
    ).apply(instance, Level0Generator::new));

    private static final int FLOOR_Y = 64;
    private static final int CEILING_Y = 68;
    private static final int TALL_CEILING_Y = 80;
    private static final int DOOR_HALF = 1;   // 3-wide openings
    private static final int LIGHT_EVERY = 6;

    // Room archetypes inside the carved room anchors
    private static final int ROOM_PLAIN = 0;
    private static final int ROOM_PILLARS = 1;
    private static final int ROOM_MEZZANINE = 2;
    private static final int ROOM_HOLES = 3;
    private static final int ROOM_BLACKOUT = 4;

    private Level0Plan plan;

    /** The plan is a pure function of the seed; the codec decodes it from the dimension JSON. */
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

    /** The world seed reaches us only through the level during generation. */
    private Level0Plan plan(WorldGenRegion region) {
        if (plan == null) {
            plan = new Level0Plan(region.getLevel().getSeed());
        }
        return plan;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {
        BlockState carpet = BackroomsBlocks.MOIST_CARPET.get().defaultBlockState();
        BlockState wallpaper = BackroomsBlocks.YELLOW_WALLPAPER.get().defaultBlockState();
        BlockState ceilingTile = BackroomsBlocks.CEILING_TILE.get().defaultBlockState();
        BlockState light = BackroomsBlocks.FLUORESCENT_PANEL.get().defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        ChunkPos pos = chunk.getPos();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        Level0Plan plan = this.plan;

        Level0Plan.ChunkPlan chunkPlan = plan.planChunk(pos.x, pos.z);
        Level0Plan.Room room = chunkPlan.room();

        boolean tallRoom = false;
        int roomType = ROOM_PLAIN;
        if (room != null) {
            roomType = pickRoomType(room.cx(), room.cz());
            tallRoom = roomType == ROOM_PILLARS || roomType == ROOM_MEZZANINE;
        }

        // ---- Pass 1: fill the whole chunk solid from floor to ceiling ----
        int localCeiling = CEILING_Y;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                boolean inRoom = room != null
                        && x >= Level0Plan.roomMinX(room) && x <= Level0Plan.roomMaxX(room)
                        && z >= Level0Plan.roomMinZ(room) && z <= Level0Plan.roomMaxZ(room);
                int ceilHere = (inRoom && tallRoom) ? TALL_CEILING_Y : CEILING_Y;

                // Floor
                chunk.setBlockState(new BlockPos(x, FLOOR_Y, z), carpet, false);
                // Solid mass
                for (int y = FLOOR_Y + 1; y <= ceilHere - 1; y++) {
                    chunk.setBlockState(new BlockPos(x, y, z), wallpaper, false);
                }
                // Ceiling with lights
                BlockState ceilingState = (lx % LIGHT_EVERY == 3 && lz % LIGHT_EVERY == 3)
                        && !(room != null && roomType == ROOM_BLACKOUT) ? light : ceilingTile;
                chunk.setBlockState(new BlockPos(x, ceilHere, z), ceilingState, false);
            }
        }

        // ---- Pass 2: carve corridors ----
        for (Level0Plan.Segment seg : chunkPlan.segments()) {
            for (int a = seg.from(); a <= seg.to(); a++) {
                for (int w = -DOOR_HALF; w <= DOOR_HALF; w++) {
                    int x = seg.kind() == Level0Plan.SEG_EW ? a : seg.fixed() + w;
                    int z = seg.kind() == Level0Plan.SEG_EW ? seg.fixed() + w : a;
                    if (x < baseX || x > baseX + 15 || z < baseZ || z > baseZ + 15) continue;
                    for (int y = FLOOR_Y + 1; y <= CEILING_Y - 1; y++) {
                        chunk.setBlockState(new BlockPos(x, y, z), air, false);
                    }
                }
            }
        }

        // ---- Pass 3: carve the room (its own ceiling height if tall) ----
        if (room != null) {
            int rMinX = Level0Plan.roomMinX(room), rMaxX = Level0Plan.roomMaxX(room);
            int rMinZ = Level0Plan.roomMinZ(room), rMaxZ = Level0Plan.roomMaxZ(room);
            int roomCeil = tallRoom ? TALL_CEILING_Y : CEILING_Y;
            for (int x = Math.max(baseX, rMinX); x <= Math.min(baseX + 15, rMaxX); x++) {
                for (int z = Math.max(baseZ, rMinZ); z <= Math.min(baseZ + 15, rMaxZ); z++) {
                    for (int y = FLOOR_Y + 1; y <= roomCeil - 1; y++) {
                        chunk.setBlockState(new BlockPos(x, y, z), air, false);
                    }
                }
            }
            // Room interior features
            carveRoomInterior(chunk, room, roomType, baseX, baseZ, carpet, wallpaper, air);
            // Re-place ceiling lights over the carved room
            for (int x = Math.max(baseX, rMinX); x <= Math.min(baseX + 15, rMaxX); x++) {
                for (int z = Math.max(baseZ, rMinZ); z <= Math.min(baseZ + 15, rMaxZ); z++) {
                    int lx = x - baseX, lz = z - baseZ;
                    if (roomType != ROOM_BLACKOUT && lx % LIGHT_EVERY == 3 && lz % LIGHT_EVERY == 3) {
                        chunk.setBlockState(new BlockPos(x, roomCeil, z), light, false);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    private void carveRoomInterior(ChunkAccess chunk, Level0Plan.Room room, int roomType,
            int baseX, int baseZ, BlockState carpet, BlockState wallpaper, BlockState air) {
        int rMinX = Math.max(baseX, Level0Plan.roomMinX(room));
        int rMaxX = Math.min(baseX + 15, Level0Plan.roomMaxX(room));
        int rMinZ = Math.max(baseZ, Level0Plan.roomMinZ(room));
        int rMaxZ = Math.min(baseZ + 15, Level0Plan.roomMaxZ(room));

        switch (roomType) {
            case ROOM_PILLARS -> {
                for (int x = rMinX + 2; x <= rMaxX - 2; x += 3) {
                    for (int z = rMinZ + 2; z <= rMaxZ - 2; z += 3) {
                        for (int y = FLOOR_Y + 1; y <= TALL_CEILING_Y - 1; y++) {
                            chunk.setBlockState(new BlockPos(x, y, z), wallpaper, false);
                        }
                    }
                }
            }
            case ROOM_MEZZANINE -> {
                // Second floor slab over half the room + solid stairs up along one wall
                for (int x = rMinX; x <= rMaxX; x++) {
                    for (int z = rMinZ; z <= (rMinZ + rMaxZ) / 2; z++) {
                        chunk.setBlockState(new BlockPos(x, 71, z), carpet, false);
                    }
                }
                // Stairs along the east wall climbing north->up onto the slab
                for (int z = rMaxZ; z > (rMinZ + rMaxZ) / 2; z--) {
                    int step = rMaxZ - z; // 1..N
                    int top = 65 + step;
                    if (top >= 71) break;
                    for (int x = rMaxX - 1; x <= rMaxX; x++) {
                        for (int y = FLOOR_Y + 1; y <= top; y++) {
                            chunk.setBlockState(new BlockPos(x, y, z),
                                    y == top ? carpet : wallpaper, false);
                        }
                    }
                }
            }
            case ROOM_HOLES -> {
                // Floor slabs torn out: several irregular floor gaps with dark shallow
                // depth, matching the reference art (no deep shafts, sharp edges).
                Random rand = new Random(hashRoom(room.cx(), room.cz(), 777L));
                int gapCount = 2 + rand.nextInt(5); // 2..6
                for (int g = 0; g < gapCount; g++) {
                    int w = 3 + rand.nextInt(5); // 3..7
                    int d = 3 + rand.nextInt(3); // 3..5
                    int gx = rMinX + rand.nextInt(Math.max(1, rMaxX - rMinX - w + 1));
                    int gz = rMinZ + rand.nextInt(Math.max(1, rMaxZ - rMinZ - d + 1));
                    for (int x = Math.max(rMinX, gx); x <= Math.min(rMaxX, gx + w - 1); x++) {
                        for (int z = Math.max(rMinZ, gz); z <= Math.min(rMaxZ, gz + d - 1); z++) {
                            // Tear the floor out down to bare dark depth
                            for (int y = FLOOR_Y; y >= FLOOR_Y - 3; y--) {
                                chunk.setBlockState(new BlockPos(x, y, z), air, false);
                            }
                        }
                    }
                }
            }
            case ROOM_BLACKOUT -> { /* plain dark room, no extra carving */ }
            default -> { /* plain room */ }
        }
    }

    private static long hashRoom(int cx, int cz, long salt) {
        long h = (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xBF58476D1CE4E5B9L) ^ (salt * 0x94D049BB133111EBL);
        h ^= h >>> 29;
        h *= 0x165667B19E3779F9L;
        h ^= h >>> 32;
        return h;
    }

    private static int pickRoomType(int cx, int cz) {
        long h = (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        int v = (int) Math.floorMod(h, 100);
        if (v < 40) return ROOM_PLAIN;
        if (v < 62) return ROOM_PILLARS;
        if (v < 78) return ROOM_MEZZANINE;
        if (v < 90) return ROOM_HOLES;
        return ROOM_BLACKOUT;
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

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return FLOOR_Y + 1;
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
