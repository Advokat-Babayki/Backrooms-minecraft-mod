package com.advokatbabayki.backrooms;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Level 0 route plan: the maze is built around an invisible "virtual route".
 * The plan is a pure function of the seed: every chunk asks "what runs through
 * me" and all neighbors agree on shared borders without any cross-chunk state.
 *
 * Route model:
 * - One endless east-west line per cell row and one north-south line per cell
 *   column (48x48-block macro cells). Each line weaves inside its band
 *   (offsets 12..35) with anchors every 16 blocks, so corridors turn, drift,
 *   run far and cross each other; every row line crosses every column line.
 * - The middle anchor of every cell is pinned to 24, so both lines pass
 *   through the center region of every cell. The cell's room is placed on that
 *   region, so corridors always run through the room: nothing is ever sealed.
 * - Each cell has its own floor height (y 64..88, steps of 3) from quantized
 *   value noise. Where a line crosses into a cell with a different height the
 *   generator carves a short slab ramp, so the route climbs and descends and
 *   the level is multi-story.
 */
public final class Level0Plan {
    public static final int CHUNKS_PER_CELL = 3;                // macro cell = 3x3 chunks
    public static final int CELL_BLOCKS = CHUNKS_PER_CELL * 16; // 48
    public static final int BASE_FLOOR_Y = 64;                 // pinned height of the entry cell
    private static final int HEIGHT_STEP = 3;                  // one story = 3 blocks + ramp
    private static final int NOISE_SPACING = 6;                // cells per noise lattice step
    private static final int NOISE_LEVELS = 9;                 // story 0..8 -> y 64..88
    private static final int BAND_LO = 12, BAND_HI = 35;       // line offsets inside the cell
    private static final long SALT_LINES = 0x5DEECE66DL;
    private static final long SALT_HEIGHT = 0x1D2545F4914F6CDDL;
    private static final long SALT_ROOM = 0x2545F4914F6CDD1DL;

    private final long seed;

    public Level0Plan(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    private static int cellOf(int blockCoord) {
        return Math.floorDiv(blockCoord, CELL_BLOCKS);
    }

    public static int cellMinBlock(int cell) {
        return cell * CELL_BLOCKS;
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

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- Height field: one story per cell, gently drifting over distance ----

    /**
     * Floor height of a cell (y 64..88). Quantized value noise, so neighbors
     * usually share a height and never differ by more than two stories
     * (12 blocks of ramp at most, which fits inside the border chunk).
     */
    public int cellFloorY(int cx, int cz) {
        double fx = (double) cx / NOISE_SPACING;
        double fz = (double) cz / NOISE_SPACING;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double v00 = corner(ix, iz), v10 = corner(ix + 1, iz);
        double v01 = corner(ix, iz + 1), v11 = corner(ix + 1, iz + 1);
        double f = lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
        int q = (int) Math.round(f * (NOISE_LEVELS - 1));
        return BASE_FLOOR_Y + clampInt(q, 0, NOISE_LEVELS - 1) * HEIGHT_STEP;
    }

    private double corner(int i, int j) {
        return (hash(seed, i, j, SALT_HEIGHT) >>> 11) / (double) (1L << 53);
    }

    // ---- Route lines: one weaving line per cell row / cell column ----

    /**
     * Band anchor of a row's east-west line at a world 16-block column.
     * The middle column of every cell is pinned to 24 so the line is
     * guaranteed to pass through each cell's center region (and each room).
     */
    private int anchorEW(int cz, int colX) {
        if (Math.floorMod(colX, CHUNKS_PER_CELL) == 1) return 24;
        return BAND_LO + (int) Math.floorMod(hash(seed, cz, colX, SALT_LINES + 2),
                BAND_HI - BAND_LO + 1);
    }

    private int anchorNS(int cx, int colZ) {
        if (Math.floorMod(colZ, CHUNKS_PER_CELL) == 1) return 24;
        return BAND_LO + (int) Math.floorMod(hash(seed, cx, colZ, SALT_LINES + 3),
                BAND_HI - BAND_LO + 1);
    }

    /** World z of the east-west line of cell row cz at world x. Pure in x. */
    public int lineZ(int cz, int x) {
        int col = Math.floorDiv(x, 16);
        double t = smooth((x - col * 16) / 16.0);
        int z = clampInt((int) Math.round(lerp(anchorEW(cz, col), anchorEW(cz, col + 1), t)),
                BAND_LO, BAND_HI);
        return cellMinBlock(cz) + z;
    }

    /** World x of the north-south line of cell column cx at world z. Pure in z. */
    public int lineX(int cx, int z) {
        int col = Math.floorDiv(z, 16);
        double t = smooth((z - col * 16) / 16.0);
        int x = clampInt((int) Math.round(lerp(anchorNS(cx, col), anchorNS(cx, col + 1), t)),
                BAND_LO, BAND_HI);
        return cellMinBlock(cx) + x;
    }

    // ---- Rooms: anchored on the route's crossing region ----

    public record Room(int cx, int cz, int centerX, int centerZ, int size) {}

    /**
     * The room of a cell. Big rooms sit on the cell center, exactly where both
     * route lines pass; small rooms sit on the corner region that both lines
     * are pinned to enter (16..24 x 16..24 cell-local), so every room is
     * always crossed by corridors and can never seal a player in.
     */
    public Room room(int cx, int cz) {
        long h = hash(seed, cx, cz, SALT_ROOM);
        Random r = new Random(h);
        int size = 11 + r.nextInt(14); // 11..24: rooms dominate their cell
        int c = size >= 16 ? 24 : 20;
        return new Room(cx, cz, cellMinBlock(cx) + c, cellMinBlock(cz) + c, size);
    }

    /** Room bounds in block coords. */
    public static int roomMinX(Room r) { return r.centerX - r.size / 2; }
    public static int roomMaxX(Room r) { return r.centerX + r.size / 2; }
    public static int roomMinZ(Room r) { return r.centerZ - r.size / 2; }
    public static int roomMaxZ(Room r) { return r.centerZ + r.size / 2; }

    // ---- Chunk plan: what runs through one 16x16 chunk ----

    public record Segment(int kind, int fixed, int from, int to, int levelY, int cx, int cz) {}
    public record ChunkPlan(List<Segment> segments, Room room) {}

    public static final int SEG_EW = 0; // runs along X, fixed = center Z
    public static final int SEG_NS = 1; // runs along Z, fixed = center X

    public ChunkPlan planChunk(int chunkX, int chunkZ) {
        int baseX = chunkX * 16, baseZ = chunkZ * 16;
        int cellX = cellOf(baseX), cellZ = cellOf(baseZ);
        int levelY = cellFloorY(cellX, cellZ);
        List<Segment> segments = new ArrayList<>();

        // East-west line of this cell row: one segment per constant-z run.
        int runStart = baseX;
        int runZ = lineZ(cellZ, baseX);
        for (int x = baseX + 1; x <= baseX + 15; x++) {
            int z = lineZ(cellZ, x);
            if (z != runZ) {
                addEW(segments, baseZ, runStart, x - 1, runZ, levelY, cellX, cellZ);
                runStart = x;
                runZ = z;
            }
        }
        addEW(segments, baseZ, runStart, baseX + 15, runZ, levelY, cellX, cellZ);

        // North-south line of this cell column: one segment per constant-x run.
        runStart = baseZ;
        int runX = lineX(cellX, baseZ);
        for (int z = baseZ + 1; z <= baseZ + 15; z++) {
            int x = lineX(cellX, z);
            if (x != runX) {
                addNS(segments, baseX, runStart, z - 1, runX, levelY, cellX, cellZ);
                runStart = z;
                runX = x;
            }
        }
        addNS(segments, baseX, runStart, baseZ + 15, runX, levelY, cellX, cellZ);

        Room room = room(cellX, cellZ);
        boolean containsRoom = baseX <= roomMaxX(room) && baseX + 15 >= roomMinX(room)
                && baseZ <= roomMaxZ(room) && baseZ + 15 >= roomMinZ(room);
        return new ChunkPlan(segments, containsRoom ? room : null);
    }

    private static void addEW(List<Segment> out, int baseZ, int fromX, int toX, int z,
            int levelY, int cx, int cz) {
        if (z + 1 < baseZ || z - 1 > baseZ + 15) return; // band misses this chunk
        out.add(new Segment(SEG_EW, z, fromX, toX, levelY, cx, cz));
    }

    private static void addNS(List<Segment> out, int baseX, int fromZ, int toZ, int x,
            int levelY, int cx, int cz) {
        if (x + 1 < baseX || x - 1 > baseX + 15) return;
        out.add(new Segment(SEG_NS, x, fromZ, toZ, levelY, cx, cz));
    }
}
