package com.advokatbabayki.backrooms;

import javax.annotation.Nullable;

/**
 * Level 0 route plan: an organically growing space, a pure function of the
 * seed. Every chunk asks "what runs through me" and all neighbors agree on
 * their shared borders without any cross-chunk state.
 *
 * Model:
 * - The corridor web is built from two independent value-noise fields: a
 *   column is carved when either field is near its level set (|f| < w).
 *   Level sets are endless wandering curves that cross each other and
 *   themselves, so the web turns, loops and branches without any grid and
 *   never dead-ends by construction.
 * - Corridor width breathes with its own noise, and the whole web grows
 *   denser and wider with distance from the origin: the world starts tight
 *   and keeps opening up the farther the wanderer goes.
 * - Rooms are rare flat islands hashed per 48x48 cell (size 8..28, center
 *   anywhere, no guaranteed exits). Each room pins its floor to the local
 *   web height and reaches out with several hallway rays; some rays are
 *   deliberately blind and end in walls (dead ends).
 * - The floor height itself is one continuous field (y 64..88): it moves in
 *   half-block steps at most, so every slope is walkable without jumping.
 */
public final class Level0Plan {
    public static final int CHUNKS_PER_CELL = 3;                // room placement lattice
    public static final int CELL_BLOCKS = CHUNKS_PER_CELL * 16; // 48
    public static final int BASE_FLOOR_Y = 64;
    public static final int MAX_FLOOR_Y = 88;
    private static final int FLOOR_SPACING = 192;               // one story drift per ~2 cells
    private static final int FLOOR_LEVELS = 9;                 // stories 0..8 -> y 64..88
    private static final int WEB1_SPACING = 160;               // corridor meander wavelength
    private static final int WEB2_SPACING = 190;
    private static final int WEB3_SPACING = 120;               // a denser third field
    private static final int WIDTH_SPACING = 48;               // width breathing wavelength
    private static final long SALT_WEB1 = 0x5DEECE66DL;
    private static final long SALT_WEB2 = 0x1D2545F4914F6CDDL;
    private static final long SALT_WEB3 = 0x2545F4914F6CDD1D5L;
    private static final long SALT_WIDTH = 0x6CDD1D2545F4914DL;
    private static final long SALT_FLOOR = 0x2545F4914F6CDD1DL;
    private static final long SALT_ROOM = 0x2545F4914F6CDD1D8L;
    private static final long SALT_RAYS = 0x8D1D4C6CDD2545F4L;

    private final long seed;

    public Level0Plan(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    public static int cellOf(int blockCoord) {
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

    /** Smooth value noise in [0,1]; lattice corners hashed per seed and salt. */
    private double noise(double x, double z, int spacing, long salt) {
        double fx = x / spacing, fz = z / spacing;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double v00 = corner(ix, iz, salt), v10 = corner(ix + 1, iz, salt);
        double v01 = corner(ix, iz + 1, salt), v11 = corner(ix + 1, iz + 1, salt);
        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    }

    private double corner(int i, int j, long salt) {
        return (hash(seed, i, j, salt) >>> 11) / (double) (1L << 53);
    }

    // ---- Continuous floor field: y 64..88 in half-block steps ----

    /**
     * Floor surface of a column in half-block units (128..176), so 0.5-block
     * steps are exactly representable. The field moves at most one half-step
     * per block, which keeps every slope walkable without jumping.
     */
    public int floorHalf(double x, double z) {
        double q = noise(x, z, FLOOR_SPACING, SALT_FLOOR) * (FLOOR_LEVELS - 1); // stories 0..8
        return (int) Math.round((BASE_FLOOR_Y + q * 3) * 2);
    }

    /** Floor surface in whole blocks (for spawn searches and commands). */
    public int floorSurface(double x, double z) {
        return (int) Math.round(floorHalf(x, z) / 2.0);
    }

    // ---- The corridor web ----

    /** How open the world is here: 0 at the origin, 1 far away. */
    private double openness(double x, double z) {
        double d = Math.sqrt(x * x + z * z);
        return smooth(clampInt((int) Math.floor(d / 96.0), 0, 3) / 3.0
                + smooth(d / 96.0 - Math.floor(d / 96.0)) * 0.0); // smooth ramp over ~288 blocks
    }

    /** Half-width (in field units) of the web band at this column. */
    private double webHalfWidth(double x, double z) {
        double breathe = 0.9 + 1.1 * noise(x, z, WIDTH_SPACING, SALT_WIDTH);
        double grow = 0.55 + 0.45 * Math.min(1.0, Math.sqrt(x * x + z * z) / 288.0);
        return 0.045 * breathe * grow;
    }

    /** True when this column is carved open as part of the corridor web. */
    public boolean inWeb(double x, double z) {
        double w = webHalfWidth(x, z);
        double f1 = noise(x, z, WEB1_SPACING, SALT_WEB1) - 0.5;
        if (Math.abs(f1) < w) return true;
        double f2 = noise(x, z, WEB2_SPACING, SALT_WEB2) - 0.5;
        if (Math.abs(f2) < w) return true;
        return Math.abs(noise(x, z, WEB3_SPACING, SALT_WEB3) - 0.5) < w * 0.7;
    }

    // ---- Rooms: rare flat islands on the web ----

    public record Room(int cx, int cz, int centerX, int centerZ, int size, int floorY) {}

    /**
     * The room of a cell, or null when the cell has none. Rooms are rare and
     * irregular: presence, size and position are hashed, and cells near the
     * origin are stricter so the world starts tight and keeps opening up.
     */
    @Nullable
    public Room room(int cx, int cz) {
        long h = hash(seed, cx, cz, SALT_ROOM);
        boolean origin = cx == 0 && cz == 0;
        double dist = Math.sqrt((cellMinBlock(cx) + 24.0) * (cellMinBlock(cx) + 24.0)
                + (cellMinBlock(cz) + 24.0) * (cellMinBlock(cz) + 24.0));
        int gate = dist < 240 ? 3 : 5; // stricter near the origin
        if (!origin && Math.floorMod(h, 8) >= gate) return null;

        int size = 8 + (int) Math.floorMod(h >>> 20, 21); // 8..28
        int half = size / 2;
        int centerX = cellMinBlock(cx) + clampInt(6 + (int) Math.floorMod(h >>> 40, 37), half + 2, 46 - half);
        int centerZ = cellMinBlock(cz) + clampInt(6 + (int) Math.floorMod(h >>> 8, 37), half + 2, 46 - half);
        // Flat floor pinned to the local web height (rounded to whole blocks)
        int floorY = clampInt((floorHalf(centerX, centerZ) + 1) / 2, BASE_FLOOR_Y, MAX_FLOOR_Y);
        return new Room(cx, cz, centerX, centerZ, size, floorY);
    }

    /** Room bounds in block coords. */
    public static int roomMinX(Room r) { return r.centerX - r.size / 2; }
    public static int roomMaxX(Room r) { return r.centerX + r.size / 2; }
    public static int roomMinZ(Room r) { return r.centerZ - r.size / 2; }
    public static int roomMaxZ(Room r) { return r.centerZ + r.size / 2; }

    // ---- Hallway rays reaching out from a room ----

    /**
     * One hallway ray: an eight-way direction, a length in blocks, and
     * whether it is deliberately blind (a dead end in the mass).
     */
    public record Ray(int dx, int dz, int len, boolean blind) {}

    /**
     * Rays of a room: 5 hashed spokes. Each spoke grows from its minimum
     * length onward until it touches the web (hard cap 72), so every room
     * is structurally tied into the corridor network; plus a chance of one
     * blind spoke that stops short and ends in a wall (a dead end).
     */
    public java.util.List<Ray> rays(Room room) {
        java.util.List<Ray> out = new java.util.ArrayList<>();
        long h = hash(seed, room.cx(), room.cz(), SALT_RAYS);
        int[] dirsX = {1, 1, 0, -1, -1, -1, 0, 1};
        int[] dirsZ = {0, 1, 1, 1, 0, -1, -1, -1};
        int first = (int) Math.floorMod(h, 8);
        for (int i = 0; i < 5; i++) {
            int dir = (first + i * 3) % 8; // spread around the compass
            int min = 14 + (int) Math.floorMod(hash(seed, room.cx(), room.cz(), SALT_RAYS + 1 + i), 18);
            // Grow the spoke until it touches the web (capped)
            int len = min;
            int x = rayStartX(room, dirsX[dir]), z = rayStartZ(room, dirsZ[dir]);
            for (int s = min; s < 96; s++) {
                if (inWeb(x + dirsX[dir] * s, z + dirsZ[dir] * s)) break;
                len = s + 1;
            }
            // Always include one web column past the ray end so capped rays
            // still merge with the web.
            out.add(new Ray(dirsX[dir], dirsZ[dir], len + 1, false));
        }
        if (Math.floorMod(h >>> 16, 4) == 0) {
            int dir = (first + 1) % 8;
            out.add(new Ray(dirsX[dir], dirsZ[dir], 8, true)); // a blind dead end
        }
        return out;
    }

    private static int rayStartX(Room room, int dx) {
        return dx > 0 ? roomMaxX(room) + 1 : dx < 0 ? roomMinX(room) - 1 : room.centerX();
    }

    private static int rayStartZ(Room room, int dz) {
        return dz > 0 ? roomMaxZ(room) + 1 : dz < 0 ? roomMinZ(room) - 1 : room.centerZ();
    }

    // ---- Room types (shared by the generator and the /backrooms room command) ----

    public static final int ROOM_PLAIN = 0;
    public static final int ROOM_PILLARS = 1;
    public static final int ROOM_MEZZANINE = 2;
    public static final int ROOM_HOLES = 3;
    public static final int ROOM_BLACKOUT = 4;
    public static final int ROOM_TALL = 5;

    /**
     * Deterministic room archetype of a cell. Base types cover most rooms;
     * the rest spread roughly evenly over the 45 variation recipes (6..50).
     * A mezzanine needs an 8-step staircase, so small rooms fall back to
     * pillars.
     */
    public int roomType(int cx, int cz) {
        long h = (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        int v = (int) Math.floorMod(h, 100);
        int t;
        if (v < 18) t = ROOM_PLAIN;
        else if (v < 28) t = ROOM_PILLARS;
        else if (v < 36) t = ROOM_MEZZANINE;
        else if (v < 44) t = ROOM_HOLES;
        else if (v < 52) t = ROOM_BLACKOUT;
        else if (v < 62) t = ROOM_TALL;
        else t = 6 + ((v - 62) * 45 + 22) / 38; // 6..50, roughly uniform
        if (t == ROOM_MEZZANINE && room(cx, cz) == null) t = ROOM_PLAIN;
        else if (t == ROOM_MEZZANINE && room(cx, cz).size() < 20) t = ROOM_PILLARS;
        return t;
    }

    /** Short machine-readable name of a room archetype. */
    public static String roomTypeName(int type) {
        if (type >= 6) return RoomVariants.name(type);
        return switch (type) {
            case ROOM_PILLARS -> "PILLARS";
            case ROOM_MEZZANINE -> "MEZZANINE";
            case ROOM_HOLES -> "HOLES";
            case ROOM_BLACKOUT -> "BLACKOUT";
            case ROOM_TALL -> "TALL";
            default -> "PLAIN";
        };
    }
}
