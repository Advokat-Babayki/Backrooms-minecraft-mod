package com.advokatbabayki.backrooms;

import java.util.List;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Level 0: an endless office maze.
 * A chunk is one room 16x16; a deterministic hash of the chunk position picks
 * the room archetype: standard walls, arches, pillars, tall hall, holes or a
 * room with a sunken/lifted floor and connecting stairs.
 */
public class Level0Generator extends ChunkGenerator {
    public static final MapCodec<Level0Generator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
    ).apply(instance, Level0Generator::new));

    private static final int FLOOR_Y = 64;
    private static final int CEILING_Y = 68;
    private static final int TALL_CEILING_Y = 80;
    private static final int LIGHT_EVERY = 8;

    // Room archetypes, roughly matching the wiki's structural variations
    private static final int ROOM_STANDARD = 0;
    private static final int ROOM_ARCHES = 1;
    private static final int ROOM_PILLARS = 2;
    private static final int ROOM_TALL = 3;
    private static final int ROOM_HOLES = 4;
    private static final int ROOM_STAIRS = 5;

    public Level0Generator(BiomeSource biomeSource) {
        super(biomeSource);
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
        BlockState ceiling = BackroomsBlocks.CEILING_TILE.get().defaultBlockState();
        BlockState light = BackroomsBlocks.FLUORESCENT_PANEL.get().defaultBlockState();

        ChunkPos pos = chunk.getPos();
        RandomSource rand = chunkRandom(pos);
        int roomType = pickRoomType(pos);
        int ceilingY = (roomType == ROOM_TALL || roomType == ROOM_PILLARS) ? TALL_CEILING_Y : CEILING_Y;
        int floorShift = (roomType == ROOM_STAIRS) ? (rand.nextBoolean() ? -3 : 3) : 0;
        int floorY = FLOOR_Y + floorShift;

        int westDoorZ = 1 + rand.nextInt(13);
        int northDoorX = 1 + rand.nextInt(13);

        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;

                // Floor and ceiling
                chunk.setBlockState(new BlockPos(x, floorY, z), carpet, false);
                placeCeiling(chunk, x, z, lx, lz, floorY, ceilingY, ceiling, light);

                // Walls along chunk borders with doorways
                boolean westWall = lx == 0 && !(lz >= westDoorZ && lz <= westDoorZ + 1);
                boolean northWall = lz == 0 && !(lx >= northDoorX && lx <= northDoorX + 1);
                if (westWall || northWall) {
                    for (int y = floorY + 1; y <= ceilingY - 1; y++) {
                        chunk.setBlockState(new BlockPos(x, y, z), wallpaper, false);
                    }
                }

                // Arches: pillars along walls with gaps instead of solid walls
                if (roomType == ROOM_ARCHES && (lx % 5 == 0 || lz % 5 == 0)) {
                    if (lx % 5 == 0 && lz % 5 != 0 || lz % 5 == 0 && lx % 5 != 0) {
                        for (int y = floorY + 1; y <= floorY + 4; y++) {
                            chunk.setBlockState(new BlockPos(x, y, z), wallpaper, false);
                        }
                    }
                }

                // Pillars: freestanding columns on a 5x5 grid
                if (roomType == ROOM_PILLARS && lx % 5 == 2 && lz % 5 == 2) {
                    for (int y = floorY + 1; y <= ceilingY - 1; y++) {
                        chunk.setBlockState(new BlockPos(x, y, z), wallpaper, false);
                    }
                }

                // Holes: dark pits in the floor on a 6x6 grid
                if (roomType == ROOM_HOLES && lx % 6 == 3 && lz % 6 == 3) {
                    for (int y = floorY; y >= floorY - 8; y--) {
                        chunk.setBlockState(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
                    }
                }

                // Stairs room: a ramp of carpet steps between the two floor levels
                if (roomType == ROOM_STAIRS && lx >= 2 && lx <= 13 && lz >= 2 && lz <= 13) {
                    int steps = (floorShift < 0) ? (lz - 2) : (13 - lz);
                    if (steps >= 0 && steps <= 11 && steps <= Math.abs(floorShift)) {
                        int stepY = (floorShift < 0) ? FLOOR_Y - steps : FLOOR_Y + steps;
                        if (stepY != floorY) {
                            chunk.setBlockState(new BlockPos(x, stepY, z), carpet, false);
                        }
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    private void placeCeiling(ChunkAccess chunk, int x, int z, int lx, int lz, int floorY, int ceilingY,
            BlockState ceiling, BlockState light) {
        BlockState ceilingState = (lx % LIGHT_EVERY == 3 && lz % LIGHT_EVERY == 3) ? light : ceiling;
        chunk.setBlockState(new BlockPos(x, ceilingY, z), ceilingState, false);
    }

    private static int pickRoomType(ChunkPos pos) {
        // Deterministic per-room hash; standard rooms dominate, variations spice it up
        long h = mix(pos.x, pos.z, 987654321L);
        int v = (int) Math.floorMod(h, 100);
        if (v < 55) return ROOM_STANDARD;
        if (v < 70) return ROOM_ARCHES;
        if (v < 82) return ROOM_PILLARS;
        if (v < 90) return ROOM_TALL;
        if (v < 96) return ROOM_HOLES;
        return ROOM_STAIRS;
    }

    private static RandomSource chunkRandom(ChunkPos pos) {
        long mixed = mix(pos.x, pos.z, 42L);
        return RandomSource.create(mixed);
    }

    private static long mix(long x, long z, long salt) {
        long h = salt;
        h = h * 31 + x * 341873128712L;
        h = h * 31 + z * 132897987541L;
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
