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
 * Level 0: a solid wallpaper mass carved into an organically growing space
 * (see Level0Plan).
 *
 * Every column is filled solid from y60 to y105; the corridor web, the room
 * islands and their hallway rays are carved out of that mass, so walls and
 * ceilings merge into each other by construction - there is no path to the
 * outside of the level, and above the mass the world is flat empty air.
 *
 * Floors move in half-block steps: even half-units are a full carpet block,
 * odd ones add a bottom slab on top, which makes every slope walkable
 * without jumping and gives the level its gently rising and falling rooms.
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

        // ---- Pass 1: solid mass everywhere ----
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = FILL_MIN_Y; y <= FILL_TOP_Y; y++) {
                    chunk.setBlockState(new BlockPos(baseX + lx, y, baseZ + lz), wallpaper, false);
                }
            }
        }

        // ---- Pass 2: carve the corridor web column by column ----
        // The chunk is a plain array of states: a carved column is one whose
        // block at the local floor line is carpet or slab. To honor "crossing
        // hallways merge, never step-walls", track carved floor heights in a
        // small map per chunk invocation.
        java.util.Map<Long, Integer> carvedFloors = new java.util.HashMap<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx, z = baseZ + lz;
                if (plan.inWeb(x, z)) {
                    carveMerged(chunk, x, z, plan.floorHalf(x, z), baseX, baseZ, carvedFloors,
                            carpet, ceilingTile, light, air, slabBottom);
                }
            }
        }

        // ---- Pass 3: rooms of this cell and its neighbors (rays reach across) ----
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                Level0Plan.Room room = plan.room(Level0Plan.cellOf(baseX) + dcx,
                        Level0Plan.cellOf(baseZ) + dcz);
                if (room == null) continue;
                carveRays(chunk, pos, room, carvedFloors,
                        carpet, ceilingTile, light, air, slabBottom);
                if (overlapsChunk(room, pos)) {
                    carveRoom(chunk, pos, plan, room, carpet, wallpaper, ceilingTile,
                            light, air, slabBottom, slabTop);
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    private static boolean overlapsChunk(Level0Plan.Room room, ChunkPos pos) {
        return pos.getMinBlockX() <= Level0Plan.roomMaxX(room) && pos.getMaxBlockX() >= Level0Plan.roomMinX(room)
                && pos.getMinBlockZ() <= Level0Plan.roomMaxZ(room) && pos.getMaxBlockZ() >= Level0Plan.roomMinZ(room);
    }

    /**
     * Carves a hallway column unless this column was already carved at a
     * floor more than half a block away: crossing hallways merge instead of
     * building step-walls, exactly like the simulation guarantees.
     */
    private void carveMerged(ChunkAccess chunk, int x, int z, int s, int baseX, int baseZ,
            java.util.Map<Long, Integer> carvedFloors,
            BlockState carpet, BlockState ceilingTile, BlockState light, BlockState air,
            BlockState slabBottom) {
        Long key = (long) x << 32 | (z & 0xFFFFFFFFL);
        Integer prev = carvedFloors.get(key);
        if (prev != null && Math.abs(prev - s) > 1) return; // keep the earlier floor
        carvedFloors.put(key, s);
        carveOpenColumn(chunk, x, z, s, baseX, baseZ, carpet, ceilingTile, light, air, slabBottom);
    }

    /**
     * One open column at a half-block floor height: even half-units are a
     * single carpet block, odd ones stack a bottom slab on top of the block.
     */
    private void carveOpenColumn(ChunkAccess chunk, int x, int z, int s, int baseX, int baseZ,
            BlockState carpet, BlockState ceilingTile, BlockState light, BlockState air,
            BlockState slabBottom) {
        int y0 = (s % 2 == 0) ? s / 2 : (s + 1) / 2; // block under the surface
        int ceilY = (s % 2 == 0) ? y0 + 4 : y0 + 4;
        BlockState floorTop = (s % 2 == 0) ? carpet : slabBottom;
        BlockState floorBottom = carpet;

        // carve air first, then floor pieces so the merge check sees both states
        for (int y = y0 + 1; y <= ceilY - 1; y++) {
            chunk.setBlockState(new BlockPos(x, y, z), air, false);
        }
        chunk.setBlockState(new BlockPos(x, y0, z), floorBottom, false);
        if (floorTop != floorBottom) {
            chunk.setBlockState(new BlockPos(x, y0 + 1, z), floorTop, false);
        }
        setCeiling(chunk, x, z, ceilY, baseX, baseZ, ceilingTile, light);
    }

    private void setCeiling(ChunkAccess chunk, int x, int z, int y, int baseX, int baseZ,
            BlockState ceilingTile, BlockState light) {
        BlockState state = (x - baseX) % LIGHT_EVERY == 3 && (z - baseZ) % LIGHT_EVERY == 3
                ? light : ceilingTile;
        chunk.setBlockState(new BlockPos(x, y, z), state, false);
    }

    // ---- Hallway rays ----

    /**
     * Carves one room's hallway rays inside this chunk. A ray starts at the
     * room wall and walks outward; its floor blends from the room's flat
     * floor into the web's height field over the first 70% of its length,
     * so the walk up or down is a gentle half-step ramp. Blind rays stop
     * early and end in a wall.
     */
    private void carveRays(ChunkAccess chunk, ChunkPos pos, Level0Plan.Room room,
            java.util.Map<Long, Integer> carvedFloors,
            BlockState carpet, BlockState ceilingTile, BlockState light, BlockState air,
            BlockState slabBottom) {
        int roomH = room.floorY() * 2; // half-units
        for (Level0Plan.Ray ray : plan.rays(room)) {
            for (int i = 0; i < ray.len(); i++) {
                int x = ray.dx() > 0 ? Level0Plan.roomMaxX(room) + 1 + ray.dx() * i
                        : ray.dx() < 0 ? Level0Plan.roomMinX(room) - 1 + ray.dx() * i
                        : room.centerX() + ray.dx() * i;
                int z = ray.dz() > 0 ? Level0Plan.roomMaxZ(room) + 1 + ray.dz() * i
                        : ray.dz() < 0 ? Level0Plan.roomMinZ(room) - 1 + ray.dz() * i
                        : room.centerZ() + ray.dz() * i;
                if (x < pos.getMinBlockX() || x > pos.getMaxBlockX()
                        || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ()) continue;
                if (ray.blind() && i >= ray.len() - 1) break; // dead end: keep the wall
                int webH = plan.floorHalf(x, z);
                int webCapped = Math.max(roomH - 12, Math.min(roomH + 12, webH));
                double t = Math.min(1.0, i / (ray.len() * 0.7));
                int s = (int) Math.round(lerd(roomH, webCapped, t));
                carveMerged(chunk, x, z, s, pos.getMinBlockX(), pos.getMinBlockZ(), carvedFloors,
                        carpet, ceilingTile, light, air, slabBottom);
            }
        }
    }

    private static double lerd(double a, double b, double t) {
        return a + (b - a) * (t * t * (3 - 2 * t));
    }

    // ---- Rooms ----

    private void carveRoom(ChunkAccess chunk, ChunkPos pos, Level0Plan plan, Level0Plan.Room room,
            BlockState carpet, BlockState wallpaper, BlockState ceilingTile, BlockState light,
            BlockState air, BlockState slabBottom, BlockState slabTop) {
        int h = room.floorY();
        int type = plan.roomType(room.cx(), room.cz());
        boolean tall = type == Level0Plan.ROOM_PILLARS || type == Level0Plan.ROOM_MEZZANINE
                || type == Level0Plan.ROOM_TALL;
        int ceil = tall ? h + 16 : h + 4;

        int rMinX = Math.max(pos.getMinBlockX(), Level0Plan.roomMinX(room));
        int rMaxX = Math.min(pos.getMaxBlockX(), Level0Plan.roomMaxX(room));
        int rMinZ = Math.max(pos.getMinBlockZ(), Level0Plan.roomMinZ(room));
        int rMaxZ = Math.min(pos.getMaxBlockZ(), Level0Plan.roomMaxZ(room));
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
            case Level0Plan.ROOM_PILLARS -> carvePillars(chunk, room, pos, rMinX, rMaxX, rMinZ, rMaxZ, h);
            case Level0Plan.ROOM_MEZZANINE -> carveMezzanine(chunk, room, pos, rMinX, rMaxX, rMinZ, rMaxZ, h,
                    carpet, slabBottom);
            case Level0Plan.ROOM_HOLES -> carveHoles(chunk, room, pos, rMinX, rMaxX, rMinZ, rMaxZ, h,
                    air, slabBottom, slabTop);
            default -> {
                if (type >= 6) {
                    RoomVariants.carve(chunk, pos, plan, room, type, carpet, wallpaper, ceilingTile,
                            light, air, slabBottom, slabTop);
                }
                // base plain, blackout and tall halls stay empty
            }
        }

        // Ceiling with lights (blackout rooms stay dark)
        for (int x = rMinX; x <= rMaxX; x++) {
            for (int z = rMinZ; z <= rMaxZ; z++) {
                BlockState state = type != Level0Plan.ROOM_BLACKOUT
                        && (x - pos.getMinBlockX()) % LIGHT_EVERY == 3 && (z - pos.getMinBlockZ()) % LIGHT_EVERY == 3
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
            ChunkPos pos, int rMinX, int rMaxX, int rMinZ, int rMaxZ, int h) {
        BlockState wallpaper = BackroomsBlocks.YELLOW_WALLPAPER.get().defaultBlockState();
        for (int x = rMinX + 2; x <= rMaxX - 2; x += 3) {
            for (int z = rMinZ + 2; z <= rMaxZ - 2; z += 3) {
                if (x < pos.getMinBlockX() || x > pos.getMaxBlockX()
                        || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ()) continue;
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
            ChunkPos pos, int rMinX, int rMaxX, int rMinZ, int rMaxZ, int h,
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
        for (int x = Math.max(dX1, pos.getMinBlockX()); x <= Math.min(dX2, pos.getMaxBlockX()); x++) {
            for (int z = Math.max(dZ1, pos.getMinBlockZ()); z <= Math.min(dZ2, pos.getMaxBlockZ()); z++) {
                chunk.setBlockState(new BlockPos(x, h + 4, z), carpet, false);
            }
        }

        // Staircase: 8 steps rising h+1 -> h+5, run along the sx wall, band at the sz wall
        int xLow = sx < 0 ? Level0Plan.roomMinX(room) : Level0Plan.roomMaxX(room) - 7;
        int zLow = sz < 0 ? Level0Plan.roomMinZ(room) : Level0Plan.roomMaxZ(room) - 2;
        for (int i = 0; i < 8; i++) {
            int x = sx < 0 ? xLow + i : xLow + (7 - i);
            int p = i + 1;
            for (int z = zLow; z < zLow + 3; z++) {
                if (x < pos.getMinBlockX() || x > pos.getMaxBlockX()
                        || z < pos.getMinBlockZ() || z > pos.getMaxBlockZ()) continue;
                chunk.setBlockState(new BlockPos(x, h, z), carpet, false);
                int space = h + (p + 1) / 2;
                BlockState step = (p % 2 == 1) ? slabBottom : carpet;
                chunk.setBlockState(new BlockPos(x, space, z), step, false);
            }
        }
    }

    /**
     * Torn floor: irregular shallow pits (depth 3), never under the room's
     * entry crossing. Each pit gets an escape half-step column in its
     * corner, so a player who falls in can always climb out.
     */
    private void carveHoles(ChunkAccess chunk, Level0Plan.Room room,
            ChunkPos pos, int rMinX, int rMaxX, int rMinZ, int rMaxZ, int h,
            BlockState air, BlockState slabBottom, BlockState slabTop) {
        Random rand = new Random(hashRoom(room.cx(), room.cz(), 777L));
        int gapCount = 2 + rand.nextInt(5); // 2..6
        for (int g = 0; g < gapCount; g++) {
            int w = 3 + rand.nextInt(5); // 3..7
            int d = 3 + rand.nextInt(3); // 3..5
            int gx = rMinX + 2 + rand.nextInt(Math.max(1, rMaxX - rMinX - 3 - w));
            int gz = rMinZ + 2 + rand.nextInt(Math.max(1, rMaxZ - rMinZ - 3 - d));
            if (gx <= room.centerX() + 3 && gx + w - 1 >= room.centerX() - 3
                    && gz <= room.centerZ() + 3 && gz + d - 1 >= room.centerZ() - 3) continue;
            for (int x = Math.max(gx, pos.getMinBlockX()); x <= Math.min(gx + w - 1, pos.getMaxBlockX()) && x <= rMaxX; x++) {
                for (int z = Math.max(gz, pos.getMinBlockZ()); z <= Math.min(gz + d - 1, pos.getMaxBlockZ()) && z <= rMaxZ; z++) {
                    for (int y = h; y >= h - 2; y--) {
                        chunk.setBlockState(new BlockPos(x, y, z), air, false);
                    }
                }
            }
            // Escape column in the pit's corner: +0.5, +0.5, then one jump to the floor
            if (gx >= pos.getMinBlockX() && gx <= pos.getMaxBlockX()
                    && gz >= pos.getMinBlockZ() && gz <= pos.getMaxBlockZ()) {
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
     * Honest walk height for a position: the floor field itself. Used by
     * the dimension spawn search, so it matches the carved space.
     */
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        return plan.floorSurface(x, z) + 1;
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
