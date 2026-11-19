package com.advokatbabayki.backrooms;

import java.util.ArrayList;
import java.util.List;

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

    // ---- Route lines: one straight line per cell row / cell column ----

    /**
     * World z of the east-west line of cell row cz: a straight hallway at a
     * fixed offset inside the row, running the whole width of the world.
     * Straight lines give the level its endless-office look; rooms anchor on
     * the crossings of the row and column lines.
     */
    public int lineZ(int cz) {
        int offset = BAND_LO + (int) Math.floorMod(hash(seed, cz, 0, SALT_LINES + 2),
                BAND_HI - BAND_LO + 1);
        return cellMinBlock(cz) + offset;
    }

    /** World x of the north-south line of cell column cx. Straight, like lineZ. */
    public int lineX(int cx) {
        int offset = BAND_LO + (int) Math.floorMod(hash(seed, 0, cx, SALT_LINES + 3),
                BAND_HI - BAND_LO + 1);
        return cellMinBlock(cx) + offset;
    }

    // ---- Rooms: anchored on the route's crossing region ----

    public record Room(int cx, int cz, int centerX, int centerZ, int size) {}

    /**
     * The room of a cell, centered on the crossing of the two straight lines,
     * clamped into the safe middle band. The crossing always lands inside the
     * room: line offsets are 12..35, the clamp window is 16..32, so the drift
     * is at most 4 blocks - less than the smallest half-size (5).
     */
    public Room room(int cx, int cz) {
        int crossX = lineX(cx), crossZ = lineZ(cz);
        int centerX = clampInt(crossX, cellMinBlock(cx) + 16, cellMinBlock(cx) + 32);
        int centerZ = clampInt(crossZ, cellMinBlock(cz) + 16, cellMinBlock(cz) + 32);
        int size = 11 + (int) Math.floorMod(hash(seed, cx, cz, SALT_ROOM + 1), 14); // 11..24
        return new Room(cx, cz, centerX, centerZ, size);
    }

    // ---- Room types (shared by the generator and the /backrooms room command) ----

    public static final int ROOM_PLAIN = 0;
    public static final int ROOM_PILLARS = 1;
    public static final int ROOM_MEZZANINE = 2;
    public static final int ROOM_HOLES = 3;
    public static final int ROOM_BLACKOUT = 4;
    public static final int ROOM_TALL = 5;

    /**
     * Deterministic room archetype of a cell. A mezzanine needs an 8-step
     * staircase, so rooms too small for it fall back to pillars.
     */
    public int roomType(int cx, int cz) {
        long h = (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        int v = (int) Math.floorMod(h, 100);
        int t;
        if (v < 25) t = ROOM_PLAIN;
        else if (v < 45) t = ROOM_PILLARS;
        else if (v < 60) t = ROOM_MEZZANINE;
        else if (v < 75) t = ROOM_HOLES;
        else if (v < 85) t = ROOM_BLACKOUT;
        else t = ROOM_TALL;
        if (t == ROOM_MEZZANINE && room(cx, cz).size() < 20) t = ROOM_PILLARS;
        return t;
    }

    /** Short machine-readable name of a room archetype. */
    public static String roomTypeName(int type) {
        return switch (type) {
            case ROOM_PLAIN -> "PLAIN";
            case ROOM_PILLARS -> "PILLARS";
            case ROOM_MEZZANINE -> "MEZZANINE";
            case ROOM_HOLES -> "HOLES";
            case ROOM_BLACKOUT -> "BLACKOUT";
            case ROOM_TALL -> "TALL";
            default -> "PLAIN";
        };
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

        // Straight east-west line of this cell row
        int zLine = lineZ(cellZ);
        if (zLine + 1 >= baseZ && zLine - 1 <= baseZ + 15) {
            segments.add(new Segment(SEG_EW, zLine, baseX, baseX + 15, levelY, cellX, cellZ));
        }

        // Straight north-south line of this cell column
        int xLine = lineX(cellX);
        if (xLine + 1 >= baseX && xLine - 1 <= baseX + 15) {
            segments.add(new Segment(SEG_NS, xLine, baseZ, baseZ + 15, levelY, cellX, cellZ));
        }

        Room room = room(cellX, cellZ);
        boolean containsRoom = baseX <= roomMaxX(room) && baseX + 15 >= roomMinX(room)
                && baseZ <= roomMaxZ(room) && baseZ + 15 >= roomMinZ(room);
        return new ChunkPlan(segments, containsRoom ? room : null);
    }
}
