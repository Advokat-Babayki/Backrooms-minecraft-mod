package com.advokatbabayki.backrooms;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureManager;

/**
 * Level 0: an endless grid of office rooms.
 * Floor is moist carpet at y=64, walking space y=65..67, ceiling tiles at y=68.
 * Walls run along chunk borders with deterministic doorway gaps per wall.
 */
public class Level0Generator extends ChunkGenerator {
    public static final MapCodec<Level0Generator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
    ).apply(instance, Level0Generator::new));

    private static final int FLOOR_Y = 64;
    private static final int CEILING_Y = 68;
    private static final int LIGHT_EVERY = 8;

    public Level0Generator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {
        BlockState carpet = BackroomsBlocks.MOIST_CARPET.get().defaultBlockState();
        BlockState wallpaper = BackroomsBlocks.YELLOW_WALLPAPER.get().defaultBlockState();
        BlockState ceiling = BackroomsBlocks.CEILING_TILE.get().defaultBlockState();
        BlockState light = BackroomsBlocks.FLUORESCENT_PANEL.get().defaultBlockState();

        ChunkPos pos = chunk.getPos();
        RandomSource rand = chunkRandom(chunk);

        // Deterministic doorway offsets: 1..13, two blocks wide
        int westDoorZ = 1 + rand.nextInt(13);
        int northDoorX = 1 + rand.nextInt(13);

        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;

                // Floor
                chunk.setBlockState(new BlockPos(x, FLOOR_Y, z), carpet, false);

                // Ceiling with fluorescent panels
                BlockState ceilingState = (lx % LIGHT_EVERY == 3 && lz % LIGHT_EVERY == 3) ? light : ceiling;
                chunk.setBlockState(new BlockPos(x, CEILING_Y, z), ceilingState, false);

                // Walls along chunk borders, doorways carved at y=65..66
                boolean westWall = lx == 0 && !(lz >= westDoorZ && lz <= westDoorZ + 1);
                boolean northWall = lz == 0 && !(lx >= northDoorX && lx <= northDoorX + 1);
                if (westWall || northWall) {
                    for (int y = FLOOR_Y + 1; y <= CEILING_Y - 1; y++) {
                        chunk.setBlockState(new BlockPos(x, y, z), wallpaper, false);
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    private static RandomSource chunkRandom(ChunkAccess chunk) {
        long seed = chunk.getLevel().getSeed();
        ChunkPos pos = chunk.getPos();
        long mixed = seed ^ (pos.x * 341873128712L + pos.z * 132897987541L);
        return RandomSource.create(mixed);
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
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return FLOOR_Y + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        return new NoiseColumn(0, new BlockState[0]);
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random, StructureManager structureManager,
            ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
    }
}
