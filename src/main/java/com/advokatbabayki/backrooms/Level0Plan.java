package com.advokatbabayki.backrooms;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Corridor-first maze plan shared by every room chunk.
 * The plan is a deterministic pure function of the world seed: given a chunk
 * position it answers "what runs through this chunk" - corridors, junctions,
 * room anchors - so neighboring chunks agree on their shared borders without
 * any cross-chunk state.
 *
 * Layout model:
 * - Corridors live on a 48x48-block macro grid (3x3 chunks per cell).
 * - Each macro cell connects to some of its neighbors with "corridor paths";
 *   a path is a 3-wide hallway that meanders slightly inside its half of the
 *   cell pair, so long halls curve instead of forming a plain grid.
 * - Every cell hosts one room anchor (5-12 wide, centered on the cell) whose
 *   type is hashed; corridors feed into rooms, rooms never seal.
 */
public final class Level0Plan {
    public static final int CHUNKS_PER_CELL = 3;               // macro cell = 3x3 chunks
    public static final int CELL_BLOCKS = CHUNKS_PER_CELL * 16; // 48
    private static final int CORRIDOR_HALF = 1;                // 3-wide corridor: |offset| <= 1
    private static final long SALT_PATHS = 0x5DEECE66DL;
    private static final long SALT_ROOM = 0x2545F4914F6CDD1DL;

    private final long seed;

    public Level0Plan(long seed) {
        this.seed = seed;
    }

    private static int cellOf(int blockCoord) {
        return Math.floorDiv(blockCoord, CELL_BLOCKS);
    }

    private static int cellMinBlock(int cell) {
        return cell * CELL_BLOCKS;
    }

    private static int chunkOf(int blockCoord) {
        return Math.floorDiv(blockCoord, 16);
    }

    private static long hash(long seed, long x, long z, long salt) {
        long h = seed * 31 + salt;
        h = h * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h = h * 0x94D049BB133111EBL + z * 0x165667B19E3779F9L;
        h ^= h >>> 32;
        h *= 0x27D4EB2F165667C5L;
        h ^= h >>> 31;
        return h;
    }

    /** True when the cell (cx,cz) links to its east neighbor with a corridor. */
    public boolean pathEast(int cx, int cz) {
        return true; // every cell links east: the maze is a fully connected lattice
    }

    /** True when the cell (cx,cz) links to its south neighbor with a corridor. */
    public boolean pathSouth(int cx, int cz) {
        return true; // every cell links south
    }

    /**
     * Corridor center offset (0..35) where an east-bound corridor crosses a
     * given vertical line, identical for both cells sharing that line.
     */
    public int eastCorridorZ(int cx, int cz) {
        return (int) Math.floorMod(hash(seed, cx, cz, SALT_PATHS + 2), 24) + 12; // 12..35 inside the 48 cell
    }

    public int southCorridorX(int cx, int cz) {
        return (int) Math.floorMod(hash(seed, cx, cz, SALT_PATHS + 3), 24) + 12;
    }

    /** How far the corridor drifts (in blocks) from its line inside this cell: -4..+4. */
    public int corridorDrift(int cx, int cz) {
        return (int) Math.floorMod(hash(seed, cx, cz, SALT_PATHS + 4), 9) - 4;
    }

    /** Room anchor: center (block coords) and size (5..12). */
    public record Room(int cx, int cz, int centerX, int centerZ, int size) {}

    public Room room(int cx, int cz) {
        long h = hash(seed, cx, cz, SALT_ROOM);
        Random r = new Random(h);
        int size = 5 + r.nextInt(8); // 5..12
        // Center within the safe middle band so rooms never touch cell borders
        int centerX = cellMinBlock(cx) + 16 + r.nextInt(17); // 16..32
        int centerZ = cellMinBlock(cz) + 16 + r.nextInt(17);
        return new Room(cx, cz, centerX, centerZ, size);
    }

    /**
     * What a given 16x16 chunk contains: corridor segments plus possibly part
     * of the cell's room. The generator fills walls everywhere and carves
     * these openings, so any gap between features is solid wallpaper: no
     * floating geometry, no unreached rooms.
     */
    public record ChunkPlan(List<Segment> segments, Room room) {}

    /** A 3-wide walkable band along a line, clipped to the chunk. */
    public record Segment(int kind, int fixed, int from, int to, int levelY) {}

    public static final int SEG_EW = 0; // runs along X, fixed = center Z
    public static final int SEG_NS = 1; // runs along Z, fixed = center X

    public ChunkPlan planChunk(int chunkX, int chunkZ) {
        List<Segment> segments = new ArrayList<>();

        int blockMinX = chunkX * 16;
        int blockMinZ = chunkZ * 16;
        int blockMaxX = blockMinX + 15;
        int blockMaxZ = blockMinZ + 15;

        int cellX = cellOf(blockMinX);
        int cellZ = cellOf(blockMinZ);
        int chunkInCellX = Math.floorMod(chunkX, CHUNKS_PER_CELL);
        int chunkInCellZ = Math.floorMod(chunkZ, CHUNKS_PER_CELL);

        // ---- Corridors: every cell links east and south (fully connected maze) ----
        // East corridor of cell (cx,cz): center line z = eastCorridorZ, x spans the whole cell.
        for (int dcx = -1; dcx <= 0; dcx++) {
            int cx = cellX + dcx;
            int cz = cellZ;
            int centerZCell = cellMinBlock(cz) + eastCorridorZ(cx, cz);
            int drift = corridorDrift(cx, cz);
            // The corridor spans the source cell from its west edge to the shared east edge.
            int fromX = cellMinBlock(cx);
            int toX = cellMinBlock(cx + 1); // exclusive
            // Drift: bend the corridor inside the source cell for organic curves.
            // Same value on both sides of the shared chunk column so pieces line up.
            int centerZ = centerZCell + drift * (chunkInCellZ - 1); // gentle shift per chunk column
            addEW(segments, blockMinX, blockMaxX, blockMinZ, blockMaxZ, fromX, toX - 1, centerZ);
        }

        for (int dcz = -1; dcz <= 0; dcz++) {
            int cz = cellZ + dcz;
            int cx = cellX;
            int centerXCell = cellMinBlock(cx) + southCorridorX(cx, cz);
            int drift = corridorDrift(cx, cz);
            int fromZ = cellMinBlock(cz);
            int toZ = cellMinBlock(cz + 1);
            int centerX = centerXCell + drift * (chunkInCellX - 1);
            addNS(segments, blockMinX, blockMaxX, blockMinZ, blockMaxZ, fromZ, toZ - 1, centerX);
        }

        // ---- Room: carved wherever its bounds intersect this chunk ----
        Room room = room(cellX, cellZ);
        int rMinX = room.centerX - room.size / 2;
        int rMaxX = room.centerX + room.size / 2;
        int rMinZ = room.centerZ - room.size / 2;
        int rMaxZ = room.centerZ + room.size / 2;
        boolean containsRoom = overlaps(blockMinX, blockMaxX, blockMinZ, blockMaxZ, rMinX, rMaxX, rMinZ, rMaxZ);

        return new ChunkPlan(segments, containsRoom ? room : null);
    }

    private static boolean overlaps(int aMinX, int aMaxX, int aMinZ, int aMaxZ,
            int bMinX, int bMaxX, int bMinZ, int bMaxZ) {
        return aMinX <= bMaxX && aMaxX >= bMinX && aMinZ <= bMaxZ && aMaxZ >= bMinZ;
    }

    private static void addEW(List<Segment> out, int cMinX, int cMaxX, int cMinZ, int cMaxZ,
            int fromX, int toX, int centerZ) {
        int lo = Math.max(fromX, cMinX);
        int hi = Math.min(toX, cMaxX);
        if (lo <= hi && centerZ - CORRIDOR_HALF <= cMaxZ && centerZ + CORRIDOR_HALF >= cMinZ) {
            out.add(new Segment(SEG_EW, centerZ, lo, hi, 0));
        }
    }

    private static void addNS(List<Segment> out, int cMinX, int cMaxX, int cMinZ, int cMaxZ,
            int fromZ, int toZ, int centerX) {
        int lo = Math.max(fromZ, cMinZ);
        int hi = Math.min(toZ, cMaxZ);
        if (lo <= hi && centerX - CORRIDOR_HALF <= cMaxX && centerX + CORRIDOR_HALF >= cMinX) {
            out.add(new Segment(SEG_NS, centerX, lo, hi, 0));
        }
    }

    /** Room bounds in block coords. */
    public static int roomMinX(Room r) { return r.centerX - r.size / 2; }
    public static int roomMaxX(Room r) { return r.centerX + r.size / 2; }
    public static int roomMinZ(Room r) { return r.centerZ - r.size / 2; }
    public static int roomMaxZ(Room r) { return r.centerZ + r.size / 2; }
}
