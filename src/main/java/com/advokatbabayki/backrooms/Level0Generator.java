package com.advokatbabayki.backrooms;

import java.util.List;
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
 * Level 0: a solid wallpaper mass carved into an axis-aligned office plan
 * (see Level0Plan). Streets, rooms, doorways, columns and partial partitions
 * are all cut out of the same monolith, so walls and ceilings merge by
 * construction and the player can never reach the roof.
 *
 * Floors use the continuous half-block field: every slope is walkable without
 * jumping.
 */
public class Level0Generator extends ChunkGenerator {
    public static final MapCodec<Level0Generator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            net.minecraft.util.ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("maze_seed", 0).forGetter(g -> 0)
    ).apply(instance, Level0Generator::new));

    private static final int FILL_MIN_Y = 60;
    private static final int FILL_TOP_Y = 105;
    private static final int LIGHT_EVERY = 6;

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

        ChunkPos pos = chunk.getPos();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        // ---- solid mass ----
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = FILL_MIN_Y; y <= FILL_TOP_Y; y++) {
                    chunk.setBlockState(new BlockPos(baseX + lx, y, baseZ + lz), wallpaper, false);
                }
            }
        }

        int[] xs = plan.streetRange(baseX, baseX + 15);
        int[] zs = plan.streetRange(baseZ, baseZ + 15);

        // ---- streets ----
        for (int i = xs[0]; i <= xs[1]; i++) {
            int sx = plan.streetX(i);
            int half = plan.streetHalf(i);
            for (int j = zs[0]; j <= zs[1]; j++) {
                if (!plan.segV(i, j)) continue;
                int za = Math.max(plan.streetZ(j), baseZ);
                int zb = Math.min(plan.streetZ(j + 1), baseZ + 15);
                for (int x = sx - half; x <= sx + half; x++) {
                    for (int z = za; z <= zb; z++) {
                        if (x < baseX || x > baseX + 15) continue;
                        carveColumn(chunk, x, z, Level0Plan.STREET_FLOOR_HALF,
                                carpet, ceilingTile, light, air, slabBottom);
                    }
                }
            }
        }
        for (int j = zs[0]; j <= zs[1]; j++) {
            int sz = plan.streetZ(j);
            int half = plan.streetHalf(j);
            for (int i = xs[0]; i <= xs[1]; i++) {
                if (!plan.segH(i, j)) continue;
                int xa = Math.max(plan.streetX(i), baseX);
                int xb = Math.min(plan.streetX(i + 1), baseX + 15);
                for (int z = sz - half; z <= sz + half; z++) {
                    for (int x = xa; x <= xb; x++) {
                        if (z < baseZ || z > baseZ + 15) continue;
                        carveColumn(chunk, x, z, Level0Plan.STREET_FLOOR_HALF,
                                carpet, ceilingTile, light, air, slabBottom);
                    }
                }
            }
        }

        // ---- blocks: rooms, columns, doorways ----
        for (int i = xs[0]; i <= xs[1]; i++) {
            for (int j = zs[0]; j <= zs[1]; j++) {
                Level0Plan.Block block = plan.block(i, j);
                if (block == null) continue;
                if (block.x1() < baseX || block.x0() > baseX + 15
                        || block.z1() < baseZ || block.z0() > baseZ + 15) continue;

                int blockHalf = Level0Plan.levelHalf(block.level());
                for (Level0Plan.Room room : block.rooms()) {
                    int x0 = Math.max(room.x0(), baseX);
                    int x1 = Math.min(room.x1(), baseX + 15);
                    int z0 = Math.max(room.z0(), baseZ);
                    int z1 = Math.min(room.z1(), baseZ + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            carveColumn(chunk, x, z, blockHalf,
                                    carpet, ceilingTile, light, air, slabBottom);
                        }
                    }
                }

                for (Level0Plan.Solid solid : block.solids()) {
                    int x0 = Math.max(solid.x0(), baseX);
                    int x1 = Math.min(solid.x1(), baseX + 15);
                    int z0 = Math.max(solid.z0(), baseZ);
                    int z1 = Math.min(solid.z1(), baseZ + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            fillSolid(chunk, x, z, blockHalf, wallpaper);
                        }
                    }
                }

                for (Level0Plan.Door door : block.doors()) {
                    boolean streetDoor = door.x0() == block.x0() || door.x1() == block.x1()
                            || door.z0() == block.z0() || door.z1() == block.z1();
                    int doorHalf = streetDoor ? Level0Plan.STREET_FLOOR_HALF : blockHalf;
                    int x0 = Math.max(door.x0(), baseX);
                    int x1 = Math.min(door.x1(), baseX + 15);
                    int z0 = Math.max(door.z0(), baseZ);
                    int z1 = Math.min(door.z1(), baseZ + 15);
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            carveColumn(chunk, x, z, doorHalf,
                                    carpet, ceilingTile, light, air, slabBottom);
                        }
                    }
                }

                for (Level0Plan.StairStep step : block.stairs()) {
                    if (step.x() < baseX || step.x() > baseX + 15
                            || step.z() < baseZ || step.z() > baseZ + 15) continue;
                    carveColumn(chunk, step.x(), step.z(), step.s(),
                            carpet, ceilingTile, light, air, slabBottom);
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    /** One carved column at the continuous floor height. */
    private void carveColumn(ChunkAccess chunk, int x, int z, int s,
            BlockState carpet, BlockState ceilingTile, BlockState light, BlockState air,
            BlockState slabBottom) {
        if (x < chunk.getPos().getMinBlockX() || x > chunk.getPos().getMaxBlockX()
                || z < chunk.getPos().getMinBlockZ() || z > chunk.getPos().getMaxBlockZ()) return;
        boolean half = (s & 1) != 0;
        int y0 = half ? (s - 1) / 2 : s / 2;
        int ceilY = y0 + (half ? 5 : 4);

        for (int y = y0 + 1; y < ceilY; y++) {
            chunk.setBlockState(new BlockPos(x, y, z), air, false);
        }
        chunk.setBlockState(new BlockPos(x, y0, z), carpet, false);
        if (half) {
            chunk.setBlockState(new BlockPos(x, y0 + 1, z),
                    slabBottom.setValue(SlabBlock.TYPE, SlabType.BOTTOM), false);
        }
        BlockState ceilingState = Math.floorMod(x, LIGHT_EVERY) == 3
                && Math.floorMod(z, LIGHT_EVERY) == 3 ? light : ceilingTile;
        chunk.setBlockState(new BlockPos(x, ceilY, z), ceilingState, false);
    }

    /** A column or a partial partition: solid wallpaper from floor to ceiling. */
    private void fillSolid(ChunkAccess chunk, int x, int z, int s, BlockState wallpaper) {
        if (x < chunk.getPos().getMinBlockX() || x > chunk.getPos().getMaxBlockX()
                || z < chunk.getPos().getMinBlockZ() || z > chunk.getPos().getMaxBlockZ()) return;
        boolean half = (s & 1) != 0;
        int y0 = half ? (s - 1) / 2 : s / 2;
        int ceilY = y0 + (half ? 5 : 4);
        for (int y = y0; y <= ceilY; y++) {
            chunk.setBlockState(new BlockPos(x, y, z), wallpaper, false);
        }
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
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
            RandomState random) {
        return Level0Plan.BASE_FLOOR_Y + 1;
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
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random,
            ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
    }
}
