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

    // ---- Tunables ----
    // The same knobs live in tools/level0-preview (defaults must match).
    // Tune there, then apply the exported config here.
    public static final int WEB1_SPACING = 160;                // corridor meander wavelength
    public static final int WEB2_SPACING = 190;
    public static final int WEB3_SPACING = 120;                // a denser third field
    public static final double WEB3_WIDTH_FACTOR = 0.7;
    public static final double WIDTH_BASE = 0.045;
    public static final double WIDTH_BREATH_MIN = 0.9;
    public static final double WIDTH_BREATH_RANGE = 1.1;
    public static final double WIDTH_GROW_MIN = 0.55;
    public static final double WIDTH_GROW_MAX = 1.0;
    public static final double GROW_DISTANCE = 288.0;
    public static final int FLOOR_SPACING = 192;               // one story drift per ~2 cells
    public static final int FLOOR_LEVELS = 9;                  // stories 0..8 -> y 64..88
    public static final int ROOM_NEAR_GATE = 3;                // presence out of 8 cells
    public static final int ROOM_FAR_GATE = 5;
    public static final double ROOM_NEAR_RADIUS = 240.0;
    public static final int ROOM_SIZE_MIN = 8;
    public static final int ROOM_SIZE_RANDOM = 21;
    public static final int ROOM_MAX_SIZE = 40;
    public static final int RAY_COUNT = 2;                // doorways per room
    public static final int RAY_MAX_REACH = 12;           // how far a door looks for the web
    public static final int RAY_BLIND_ONE_IN = 4;         // short dead-end stubs; 0 = none
    public static final int RAY_BLIND_LEN = 6;

    public static final int WIDTH_SPACING = 48;               // width breathing wavelength
    private static final long SALT_WEB1 = 0x5DEECE66DL;
    private static final long SALT_WEB2 = 0x1D2545F4914F6CDDL;
    private static final long SALT_WEB3 = 0x2545F4914F6CDDL;
    private static final long SALT_WIDTH = 0x6CDD1D2545F4914DL;
    private static final long SALT_FLOOR = 0x2545F4914F6CDD1DL;
    private static final long SALT_ROOM = 0x2545F4914F6CDD1DL;


    private final long seed;
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.Optional<Room>> roomCache =
            new java.util.concurrent.ConcurrentHashMap<>();

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

    /** Half-width (in field units) of the web band at this column. */
    private double webHalfWidth(double x, double z) {
        double breathe = WIDTH_BREATH_MIN + WIDTH_BREATH_RANGE
                * noise(x, z, WIDTH_SPACING, SALT_WIDTH);
        double ramp = GROW_DISTANCE > 0
                ? Math.min(1.0, Math.sqrt(x * x + z * z) / GROW_DISTANCE) : 1.0;
        double grow = WIDTH_GROW_MIN + (WIDTH_GROW_MAX - WIDTH_GROW_MIN) * ramp;
        return WIDTH_BASE * breathe * grow;
    }

    /** True when this column is carved open as part of the corridor web. */
    public boolean inWeb(double x, double z) {
        double w = webHalfWidth(x, z);
        double f1 = noise(x, z, WEB1_SPACING, SALT_WEB1) - 0.5;
        if (Math.abs(f1) < w) return true;
        double f2 = noise(x, z, WEB2_SPACING, SALT_WEB2) - 0.5;
        if (Math.abs(f2) < w) return true;
        return Math.abs(noise(x, z, WEB3_SPACING, SALT_WEB3) - 0.5) < w * WEB3_WIDTH_FACTOR;
    }

    // ---- Rooms: flat rooms that always sit on the web ----

    /** One doorway: direction, length in blocks outside the room, blind = ends in a wall. */
    public record Door(int dx, int dz, int len, boolean blind) {}

    public record Room(int cx, int cz, int centerX, int centerZ, int size, int floorY,
            java.util.List<Door> doors) {}

    private record DoorHit(int dx, int dz, int len, int webHalf, int dir) {}

    private static final int[][] DOOR_DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /** Room bounds in block coords. */
    public static int roomMinX(Room r) { return r.centerX - r.size / 2; }
    public static int roomMaxX(Room r) { return r.centerX + r.size / 2; }
    public static int roomMinZ(Room r) { return r.centerZ - r.size / 2; }
    public static int roomMaxZ(Room r) { return r.centerZ + r.size / 2; }

    /**
     * The room of a cell, or null when the cell has none. A room only exists
     * when the corridor web comes within RAY_MAX_REACH of it: rooms are part
     * of the web, not islands connected by long straight spokes. One to
     * RAY_COUNT short doorways link the room to the nearest web columns.
     */
    @Nullable
    public Room room(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        java.util.Optional<Room> cached = roomCache.get(key);
        if (cached != null) return cached.orElse(null);
        Room room = computeRoom(cx, cz);
        roomCache.put(key, java.util.Optional.ofNullable(room));
        return room;
    }

    @Nullable
    private Room computeRoom(int cx, int cz) {
        long h = hash(seed, cx, cz, SALT_ROOM);
        boolean origin = cx == 0 && cz == 0;
        double dist = Math.sqrt((cellMinBlock(cx) + 24.0) * (cellMinBlock(cx) + 24.0)
                + (cellMinBlock(cz) + 24.0) * (cellMinBlock(cz) + 24.0));
        int gate = dist < ROOM_NEAR_RADIUS ? ROOM_NEAR_GATE : ROOM_FAR_GATE;
        if (!origin && Math.floorMod(h, 8) >= gate) return null;

        int size = Math.min(ROOM_SIZE_MIN + (int) Math.floorMod(h >>> 20, ROOM_SIZE_RANDOM),
                ROOM_MAX_SIZE);
        int half = size / 2;
        int centerX = cellMinBlock(cx) + clampInt(6 + (int) Math.floorMod(h >>> 40, 37), half + 2, 46 - half);
        int centerZ = cellMinBlock(cz) + clampInt(6 + (int) Math.floorMod(h >>> 8, 37), half + 2, 46 - half);

        java.util.List<DoorHit> hits = new java.util.ArrayList<>(4);
        for (int d = 0; d < 4; d++) {
            DoorHit hit = findDoor(centerX, centerZ, half, DOOR_DIRS[d][0], DOOR_DIRS[d][1], d);
            if (hit != null) hits.add(hit);
        }
        if (hits.isEmpty()) return null;
        hits.sort(java.util.Comparator.comparingInt(DoorHit::len).thenComparingInt(DoorHit::dir));

        // The shortest door fixes the room floor; the door floor is flat, so a
        // second door must land on the same story to stay walkable.
        int roomH = (hits.get(0).webHalf + 1) & ~1;
        java.util.List<Door> doors = new java.util.ArrayList<>();
        for (DoorHit hit : hits) {
            if (doors.size() >= RAY_COUNT) break;
            if (Math.abs(hit.webHalf - roomH) > 1) continue;
            doors.add(new Door(hit.dx, hit.dz, hit.len, false));
        }
        if (doors.isEmpty()) return null;

        // A short blind stub on a free side: a dead end, not a spoke.
        if (RAY_BLIND_ONE_IN > 0 && Math.floorMod(h >>> 16, RAY_BLIND_ONE_IN) == 0) {
            for (int d = 0; d < 4; d++) {
                boolean used = false;
                for (Door door : doors) {
                    if (door.dx() == DOOR_DIRS[d][0] && door.dz() == DOOR_DIRS[d][1]) used = true;
                }
                if (!used) {
                    doors.add(new Door(DOOR_DIRS[d][0], DOOR_DIRS[d][1], RAY_BLIND_LEN, true));
                    break;
                }
            }
        }
        int floorY = clampInt(roomH / 2, BASE_FLOOR_Y, MAX_FLOOR_Y);
        return new Room(cx, cz, centerX, centerZ, size, floorY, java.util.List.copyOf(doors));
    }

    /**
     * Scans outward on one side for the nearest web column. Returns null when
     * the web is farther than RAY_MAX_REACH. The scan starts at 2 because the
     * block right outside a room is reserved as its wall.
     */
    @Nullable
    private DoorHit findDoor(int centerX, int centerZ, int half, int dx, int dz, int dir) {
        for (int o = 2; o <= RAY_MAX_REACH; o++) {
            int x, z;
            if (dz == 0) {
                x = centerX + (dx > 0 ? half + o : -half - o);
                z = centerZ;
                if (inWeb(x, z)) return new DoorHit(dx, dz, o, floorHalf(x, z), dir);
                if (inWeb(x, z + 1)) return new DoorHit(dx, dz, o, floorHalf(x, z + 1), dir);
            } else {
                z = centerZ + (dz > 0 ? half + o : -half - o);
                x = centerX;
                if (inWeb(x, z)) return new DoorHit(dx, dz, o, floorHalf(x, z), dir);
                if (inWeb(x + 1, z)) return new DoorHit(dx, dz, o, floorHalf(x + 1, z), dir);
            }
        }
        return null;
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
