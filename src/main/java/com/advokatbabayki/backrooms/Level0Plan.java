package com.advokatbabayki.backrooms;

import java.util.ArrayList;
import java.util.List;

/**
 * Level 0 route plan: rooms connected by short hallways, a pure function of
 * the seed. Every chunk asks "what runs through me" and all neighbors agree
 * on their shared borders without any cross-chunk state.
 *
 * Layout model:
 * - Every 48x48-block cell hosts one room (11..24 wide, hashed center).
 * - Every cell border carries a hallway link between the two rooms: it
 *   leaves the first room's wall at a hashed offset, turns once inside the
 *   solid mass (an L-jog) and enters the second room at another hashed
 *   offset. Hallways never form a world-spanning grid: each border has its
 *   own attach points.
 * - Every border is linked, so the room graph is a fully connected lattice:
 *   no sealed rooms, no dead ends - every hallway ends inside a room.
 * - Each cell has its own floor height (y 64..88) from slow value noise;
 *   neighbors differ by at most one story (3 blocks), and the hallway ramps
 *   between the rooms in the gap, never touching room interiors.
 */
public final class Level0Plan {
    public static final int CHUNKS_PER_CELL = 3;                // macro cell = 3x3 chunks
    public static final int CELL_BLOCKS = CHUNKS_PER_CELL * 16; // 48
    public static final int BASE_FLOOR_Y = 64;
    private static final int HEIGHT_STEP = 3;                  // one story
    private static final int NOISE_SPACING = 12;               // slow: neighbors differ by <= 1 story
    private static final int NOISE_LEVELS = 9;                 // story 0..8 -> y 64..88
    private static final long SALT_HEIGHT = 0x1D2545F4914F6CDDL;
    private static final long SALT_ROOM = 0x2545F4914F6CDD1DL;
    private static final long SALT_LINK = 0x6CDD1D2545F4914DL;

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

    // ---- Height field: one story per cell, slow enough to ramp in the gaps ----

    /**
     * Floor height of a cell (y 64..88). Quantized value noise, slow enough
     * that adjacent cells differ by at most one story, so a hallway ramp
     * (6 blocks) always fits in the gap between the two rooms.
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

    // ---- Rooms ----

    public record Room(int cx, int cz, int centerX, int centerZ, int size) {}

    /**
     * The room of a cell. Center is hashed into 18..30 so the widest room
     * (half 12) never reaches past 42, leaving a >= 5-block wall band around
     * the cell border for hallway jogs and ramps.
     */
    public Room room(int cx, int cz) {
        long h = hash(seed, cx, cz, SALT_ROOM);
        int size = 11 + (int) Math.floorMod(h, 14); // 11..24
        int centerX = cellMinBlock(cx) + 18 + (int) Math.floorMod(h >>> 20, 13); // 18..30
        int centerZ = cellMinBlock(cz) + 18 + (int) Math.floorMod(h >>> 40, 13);
        return new Room(cx, cz, centerX, centerZ, size);
    }

    /** Room bounds in block coords. */
    public static int roomMinX(Room r) { return r.centerX - r.size / 2; }
    public static int roomMaxX(Room r) { return r.centerX + r.size / 2; }
    public static int roomMinZ(Room r) { return r.centerZ - r.size / 2; }
    public static int roomMaxZ(Room r) { return r.centerZ + r.size / 2; }

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
            case ROOM_PILLARS -> "PILLARS";
            case ROOM_MEZZANINE -> "MEZZANINE";
            case ROOM_HOLES -> "HOLES";
            case ROOM_BLACKOUT -> "BLACKOUT";
            case ROOM_TALL -> "TALL";
            default -> "PLAIN";
        };
    }

    // ---- Hallway links: one per cell border, room wall to room wall ----

    /**
     * The hallway over the east border of cell (cx,cz): leaves the cell's
     * room at a hashed z, jogs once inside the solid gap, and enters the
     * east neighbor's room at another hashed z. Both cells compute the same
     * geometry because everything is keyed on the western cell's coords.
     */
    private record Link(int zOut, int zIn, int jog) {}

    private Link linkEast(int cx, int cz) {
        Room a = room(cx, cz);
        Room b = room(cx + 1, cz);
        int zOut = roomMinZ(a) + 1 + (int) Math.floorMod(hash(seed, cx, cz, SALT_LINK + 1), a.size - 3);
        int zIn = roomMinZ(b) + 1 + (int) Math.floorMod(hash(seed, cx, cz, SALT_LINK + 2), b.size - 3);
        // Jog lives strictly west of the ramp band (45..47 local), inside the gap
        int lo = roomMaxX(a) + 1, hi = cellMinBlock(cx) + 44;
        int jog = lo + (int) Math.floorMod(hash(seed, cx, cz, SALT_LINK + 3), Math.max(1, hi - lo + 1));
        return new Link(zOut, zIn, jog);
    }

    private Link linkSouth(int cx, int cz) {
        Room a = room(cx, cz);
        Room b = room(cx, cz + 1);
        int zOut = roomMinX(a) + 1 + (int) Math.floorMod(hash(seed, cx, cz, SALT_LINK + 4), a.size - 3);
        int zIn = roomMinX(b) + 1 + (int) Math.floorMod(hash(seed, cx, cz, SALT_LINK + 5), b.size - 3);
        int lo = roomMaxZ(a) + 1, hi = cellMinBlock(cz) + 44;
        int jog = lo + (int) Math.floorMod(hash(seed, cx, cz, SALT_LINK + 6), Math.max(1, hi - lo + 1));
        return new Link(zOut, zIn, jog); // for south links: zOut/zIn are X offsets
    }

    // ---- Chunk plan ----

    /**
     * A straight hallway run, clipped to the chunk. Ramp columns carry
     * rampBase (world position of ramp step 1) and rampDelta (height change
     * at the border); flat runs have rampDelta = 0.
     */
    public record Segment(int kind, int fixed, int from, int to, int levelY,
            int rampBase, int rampDelta) {}

    public record ChunkPlan(List<Segment> segments, Room room) {}

    public static final int SEG_EW = 0; // runs along X, fixed = center Z
    public static final int SEG_NS = 1; // runs along Z, fixed = center X

    public ChunkPlan planChunk(int chunkX, int chunkZ) {
        int baseX = chunkX * 16, baseZ = chunkZ * 16;
        int cellX = cellOf(baseX), cellZ = cellOf(baseZ);
        List<Segment> segments = new ArrayList<>();

        // Hallways over the west and east borders
        addEastLink(segments, baseX, baseZ, cellX - 1, cellZ);
        addEastLink(segments, baseX, baseZ, cellX, cellZ);
        // Hallways over the north and south borders
        addSouthLink(segments, baseX, baseZ, cellX, cellZ - 1);
        addSouthLink(segments, baseX, baseZ, cellX, cellZ);

        Room room = room(cellX, cellZ);
        boolean containsRoom = baseX <= roomMaxX(room) && baseX + 15 >= roomMinX(room)
                && baseZ <= roomMaxZ(room) && baseZ + 15 >= roomMinZ(room);
        return new ChunkPlan(segments, containsRoom ? room : null);
    }

    /** Emit the clipped runs of the hallway over the east border of (cx,cz). */
    private void addEastLink(List<Segment> out, int baseX, int baseZ, int cx, int cz) {
        Room a = room(cx, cz), b = room(cx + 1, cz);
        int hA = cellFloorY(cx, cz), hB = cellFloorY(cx + 1, cz);
        Link link = linkEast(cx, cz);
        int border = cellMinBlock(cx + 1);
        int d = hB - hA;
        int rampBase = d != 0 ? border - Math.abs(d) : Integer.MIN_VALUE;
        int rampEnd = d != 0 ? rampBase + 2 * Math.abs(d) - 1 : Integer.MIN_VALUE;

        if (link.zOut() == link.zIn()) {
            addRun(out, SEG_EW, link.zOut(), roomMaxX(a), d != 0 ? rampBase - 1 : roomMinX(b), hA, baseX, baseZ);
        } else {
            addRun(out, SEG_EW, link.zOut(), roomMaxX(a), link.jog(), hA, baseX, baseZ);
            addRun(out, SEG_NS, link.jog(), Math.min(link.zOut(), link.zIn()), Math.max(link.zOut(), link.zIn()), hA, baseX, baseZ);
            addRun(out, SEG_EW, link.zIn(), link.jog(), d != 0 ? rampBase - 1 : roomMinX(b), hA, baseX, baseZ);
        }
        if (d != 0) {
            addRamp(out, SEG_EW, link.zIn(), rampBase, rampEnd, hA, d, baseX, baseZ);
            addRun(out, SEG_EW, link.zIn(), rampEnd + 1, roomMinX(b), hB, baseX, baseZ);
        }
    }

    /** Emit the clipped runs of the hallway over the south border of (cx,cz). */
    private void addSouthLink(List<Segment> out, int baseX, int baseZ, int cx, int cz) {
        Room a = room(cx, cz), b = room(cx, cz + 1);
        int hA = cellFloorY(cx, cz), hB = cellFloorY(cx, cz + 1);
        Link link = linkSouth(cx, cz);
        int border = cellMinBlock(cz + 1);
        int d = hB - hA;
        int rampBase = d != 0 ? border - Math.abs(d) : Integer.MIN_VALUE;
        int rampEnd = d != 0 ? rampBase + 2 * Math.abs(d) - 1 : Integer.MIN_VALUE;

        if (link.zOut() == link.zIn()) {
            addRun(out, SEG_NS, link.zOut(), roomMaxZ(a), d != 0 ? rampBase - 1 : roomMinZ(b), hA, baseX, baseZ);
        } else {
            addRun(out, SEG_NS, link.zOut(), roomMaxZ(a), link.jog(), hA, baseX, baseZ);
            addRun(out, SEG_EW, link.jog(), Math.min(link.zOut(), link.zIn()), Math.max(link.zOut(), link.zIn()), hA, baseX, baseZ);
            addRun(out, SEG_NS, link.zIn(), link.jog(), d != 0 ? rampBase - 1 : roomMinZ(b), hA, baseX, baseZ);
        }
        if (d != 0) {
            addRamp(out, SEG_NS, link.zIn(), rampBase, rampEnd, hA, d, baseX, baseZ);
            addRun(out, SEG_NS, link.zIn(), rampEnd + 1, roomMinZ(b), hB, baseX, baseZ);
        }
    }

    /** A flat 3-wide run, clipped to the chunk. */
    private void addRun(List<Segment> out, int kind, int fixed, int from, int to, int levelY,
            int baseX, int baseZ) {
        if (kind == SEG_EW) {
            int lo = Math.max(from, baseX), hi = Math.min(to, baseX + 15);
            if (lo <= hi && fixed + 1 >= baseZ && fixed - 1 <= baseZ + 15) {
                out.add(new Segment(SEG_EW, fixed, lo, hi, levelY, Integer.MIN_VALUE, 0));
            }
        } else {
            int lo = Math.max(from, baseZ), hi = Math.min(to, baseZ + 15);
            if (lo <= hi && fixed + 1 >= baseX && fixed - 1 <= baseX + 15) {
                out.add(new Segment(SEG_NS, fixed, lo, hi, levelY, Integer.MIN_VALUE, 0));
            }
        }
    }

    /** A ramp run: 2*|delta| columns of half-steps, clipped to the chunk. */
    private void addRamp(List<Segment> out, int kind, int fixed, int rampBase, int rampEnd,
            int levelY, int delta, int baseX, int baseZ) {
        if (kind == SEG_EW) {
            int lo = Math.max(rampBase, baseX), hi = Math.min(rampEnd, baseX + 15);
            if (lo <= hi && fixed + 1 >= baseZ && fixed - 1 <= baseZ + 15) {
                out.add(new Segment(SEG_EW, fixed, lo, hi, levelY, rampBase, delta));
            }
        } else {
            int lo = Math.max(rampBase, baseZ), hi = Math.min(rampEnd, baseZ + 15);
            if (lo <= hi && fixed + 1 >= baseX && fixed - 1 <= baseX + 15) {
                out.add(new Segment(SEG_NS, fixed, lo, hi, levelY, rampBase, delta));
            }
        }
    }
}
