package com.advokatbabayki.backrooms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Level 0 plan: an axis-aligned office floor.
 *
 * A grid of straight corridors ("streets") with irregular pitch runs across
 * the level. The space between streets is cut into rectangular blocks; each
 * block is built as an open hall, a row of offices around an inner corridor,
 * a suite with a storage room, or two rooms joined by a wide opening.
 * Everything is a pure function of the seed, so chunks agree on borders.
 */
public final class Level0Plan {
    // ---- Tunables (mirrored by tools/level0-preview) ----
    public static final int PITCH = 64;
    public static final int JITTER = 20;
    public static final int STREET_WIDTH_MAX = 7;
    public static final int SEGMENT_CHANCE = 65;
    public static final int BLOCK_CHANCE = 86;
    public static final int MAX_ROOMS = 3;
    public static final int DOOR_WIDTH = 3;
    public static final int WIDE_OPENING = 6;
    public static final int COLUMN_SPACING = 6;
    public static final int PARTITION_CHANCE = 40;
    public static final int FLOOR_SPACING = 192;
    public static final int FLOOR_LEVELS = 9;

    public static final int BASE_FLOOR_Y = 64;
    public static final int MAX_FLOOR_Y = 88;

    private static final long SALT_FLOOR = 0x2545F4914F6CDD1DL;
    private static final long SALT_SX = 0x1A2B3C4D5E6F7081L;
    private static final long SALT_SZ = 0x2B3C4D5E6F708192L;
    private static final long SALT_SW = 0x3C4D5E6F708192A3L;
    private static final long SALT_SEGV = 0x4D5E6F708192A3B4L;
    private static final long SALT_SEGH = 0x5E6F708192A3B4C5L;
    private static final long SALT_BLOCK = 0x6F708192A3B4C5D6L;
    private static final long SALT_DOOR = 0x708192A3B4C5D6E7L;
    private static final long SALT_DETAIL = 0x8192A3B4C5D6E7F8L;

    public record Room(int x0, int x1, int z0, int z1, String name) {}

    public record Door(int x0, int x1, int z0, int z1) {}

    /** Solid furniture inside a carved room: a column or a partial partition. */
    public record Solid(int x0, int x1, int z0, int z1) {}

    public record Block(int i, int j, int x0, int x1, int z0, int z1, String layout,
            List<Room> rooms, List<Door> doors, List<Solid> solids) {}

    private final long seed;
    private final ConcurrentHashMap<Long, Optional<Block>> cache = new ConcurrentHashMap<>();
    private int spawnI;
    private int spawnJ;

    public Level0Plan(long seed) {
        this.seed = seed;
        findSpawnCell();
    }

    public long seed() {
        return seed;
    }

    // ---- Deterministic helpers ----

    private long hash(long x, long z, long salt) {
        long h = seed * 31 + salt;
        h = h * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h = h * 0x94D049BB133111EBL + z * 0x165667B19E3779F9L;
        h ^= h >>> 32;
        h *= 0x27D4EB2F165667C5L;
        h ^= h >>> 31;
        return h;
    }

    private int hashNum(long x, long z, long salt) {
        return (int) umod(hash(x, z, salt), 1_000_000L);
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Unsigned remainder, matching the JS preview hash semantics. */
    private static long umod(long h, long n) {
        return Long.remainderUnsigned(h, n);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double floorNoise(double x, double z) {
        double spacing = FLOOR_SPACING;
        double fx = x / spacing, fz = z / spacing;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double v00 = corner(ix, iz), v10 = corner(ix + 1, iz);
        double v01 = corner(ix, iz + 1), v11 = corner(ix + 1, iz + 1);
        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    }

    private double corner(int i, int j) {
        return (hash(i, j, SALT_FLOOR) >>> 11) / (double) (1L << 53);
    }

    /** Floor surface in half-block units. */
    public int floorHalf(double x, double z) {
        double q = floorNoise(x, z) * (FLOOR_LEVELS - 1);
        return (int) Math.round((BASE_FLOOR_Y + q * 3) * 2);
    }

    public int floorSurface(double x, double z) {
        return (int) Math.round(floorHalf(x, z) / 2.0);
    }

    // ---- Streets ----

    public int streetX(int i) {
        long range = 2L * JITTER + 1;
        int jitter = (int) umod(hash(i, 0, SALT_SX), range) - JITTER;
        return i * PITCH + jitter;
    }

    public int streetZ(int j) {
        long range = 2L * JITTER + 1;
        int jitter = (int) umod(hash(j, 0, SALT_SZ), range) - JITTER;
        return j * PITCH + jitter;
    }

    public int streetWidth(int i) {
        int maxSteps = Math.max(0, (STREET_WIDTH_MAX - 3) / 2);
        return 3 + 2 * (int) umod(hash(i, 7, SALT_SW), maxSteps + 1L);
    }

    public int streetHalf(int i) {
        return streetWidth(i) / 2;
    }

    /** Vertical street i between horizontal streets j and j+1. */
    public boolean segV(int i, int j) {
        if ((i == spawnI || i == spawnI + 1) && j == spawnJ) return true;
        return umod(hash(i, j, SALT_SEGV), 100L) < SEGMENT_CHANCE;
    }

    /** Horizontal street j between vertical streets i and i+1. */
    public boolean segH(int i, int j) {
        if (i == spawnI && (j == spawnJ || j == spawnJ + 1)) return true;
        return umod(hash(i, j, SALT_SEGH), 100L) < SEGMENT_CHANCE;
    }

    private void findSpawnCell() {
        int i = 0;
        while (streetX(i) > 0) i--;
        while (streetX(i + 1) <= 0) i++;
        spawnI = i;
        int j = 0;
        while (streetZ(j) > 0) j--;
        while (streetZ(j + 1) <= 0) j++;
        spawnJ = j;
    }

    public int spawnI() { return spawnI; }
    public int spawnJ() { return spawnJ; }

    /** Block containing a point. */
    public int[] blockOf(int x, int z) {
        int i = Math.floorDiv(x, PITCH);
        while (streetX(i) > x) i--;
        while (streetX(i + 1) <= x) i++;
        int j = Math.floorDiv(z, PITCH);
        while (streetZ(j) > z) j--;
        while (streetZ(j + 1) <= z) j++;
        return new int[] { i, j };
    }

    /** Street indices whose bands can touch the given coordinate range. */
    public int[] streetRange(int min, int max) {
        int pad = JITTER + STREET_WIDTH_MAX / 2 + 4;
        return new int[] {
                Math.floorDiv(min - pad, PITCH) - 1,
                Math.floorDiv(max + pad, PITCH) + 1
        };
    }

    public int[] spawnPoint() {
        Block b = block(spawnI, spawnJ);
        if (b == null || b.rooms().isEmpty()) {
            return new int[] { 0, 0, floorSurface(0, 0) };
        }
        Room r = b.rooms().get(0);
        int x = (r.x0() + r.x1()) / 2;
        int z = (r.z0() + r.z1()) / 2;
        return new int[] { x, z, floorSurface(x, z) };
    }

    // ---- Blocks ----

    @Nullable
    public Block block(int i, int j) {
        long key = ((long) i << 32) ^ (j & 0xFFFFFFFFL);
        Optional<Block> cached = cache.get(key);
        if (cached != null) return cached.orElse(null);
        Block b = computeBlock(i, j);
        cache.put(key, Optional.ofNullable(b));
        return b;
    }

    @Nullable
    private Block computeBlock(int i, int j) {
        int x0 = streetX(i) + streetHalf(i) + 1;
        int x1 = streetX(i + 1) - streetHalf(i + 1) - 1;
        int z0 = streetZ(j) + streetHalf(j) + 1;
        int z1 = streetZ(j + 1) - streetHalf(j + 1) - 1;
        if (x1 - x0 < 8 || z1 - z0 < 8) return null;

        long hb = hash(i, j, SALT_BLOCK);
        boolean forced = i == spawnI && j == spawnJ;
        if (!forced && umod(hb, 100L) >= BLOCK_CHANCE) return null;

        boolean sn = segH(i, j), se = segV(i + 1, j), ss = segH(i, j + 1), sw = segV(i, j);
        List<String> present = new ArrayList<>();
        if (sn) present.add("n");
        if (se) present.add("e");
        if (ss) present.add("s");
        if (sw) present.add("w");
        if (present.isEmpty()) return null;

        String layout;
        int lr = (int) umod(hb >>> 8, 100L);
        if (forced) layout = "hall";
        else if (lr < 20) layout = "hall";
        else if (lr < 75) layout = "offices";
        else if (lr < 90) layout = "suite";
        else layout = "merge";

        String primary = forced ? "n"
                : present.get((int) umod(hb >>> 20, present.size()));
        boolean vertical = primary.equals("n") || primary.equals("s");

        int ix0 = x0 + 1, ix1 = x1 - 1;
        int iz0 = z0 + 1, iz1 = z1 - 1;
        if (!layout.equals("hall") && !layout.equals("suite")) {
            int along = vertical ? ix1 - ix0 : iz1 - iz0;
            if (along < 26) layout = "hall";
        }

        Builder b = new Builder(i, j);
        switch (layout) {
            case "hall" -> buildHall(b, ix0, ix1, iz0, iz1, present, sn, se, ss, sw, forced);
            case "suite" -> buildSuite(b, ix0, ix1, iz0, iz1, present, forced);
            case "merge" -> buildMerge(b, ix0, ix1, iz0, iz1, present, vertical);
            default -> buildOffices(b, ix0, ix1, iz0, iz1, present, sn, se, ss, sw, vertical, primary, forced);
        }
        return new Block(i, j, x0, x1, z0, z1, layout,
                List.copyOf(b.rooms), List.copyOf(b.doors), List.copyOf(b.solids));
    }

    private final class Builder {
        final int i, j;
        final List<Room> rooms = new ArrayList<>();
        final List<Door> doors = new ArrayList<>();
        final List<Solid> solids = new ArrayList<>();

        Builder(int i, int j) {
            this.i = i;
            this.j = j;
        }

        int hashNum(long x, long z, long salt) {
            return Level0Plan.this.hashNum(x, z, salt);
        }

        long hashDoor(int roomIdx, int side) {
            return hash(i, j * 97L + roomIdx * 11L + side, SALT_DOOR);
        }

        void doorN(int roomIdx, int x0, int x1, int wallZ) {
            int span = x1 - x0 - DOOR_WIDTH - 1;
            if (span < 1) return;
            int dx = x0 + 1 + (int) umod(hashDoor(roomIdx, 1), span);
            doors.add(new Door(dx, dx + DOOR_WIDTH - 1, wallZ, wallZ));
        }

        void doorS(int roomIdx, int x0, int x1, int wallZ) {
            int span = x1 - x0 - DOOR_WIDTH - 1;
            if (span < 1) return;
            int dx = x0 + 1 + (int) umod(hashDoor(roomIdx, 2), span);
            doors.add(new Door(dx, dx + DOOR_WIDTH - 1, wallZ, wallZ));
        }

        void doorE(int roomIdx, int z0, int z1, int wallX) {
            int span = z1 - z0 - DOOR_WIDTH - 1;
            if (span < 1) return;
            int dz = z0 + 1 + (int) umod(hashDoor(roomIdx, 3), span);
            doors.add(new Door(wallX, wallX, dz, dz + DOOR_WIDTH - 1));
        }

        void doorW(int roomIdx, int z0, int z1, int wallX) {
            int span = z1 - z0 - DOOR_WIDTH - 1;
            if (span < 1) return;
            int dz = z0 + 1 + (int) umod(hashDoor(roomIdx, 4), span);
            doors.add(new Door(wallX, wallX, dz, dz + DOOR_WIDTH - 1));
        }

        void streetDoor(int roomIdx, Room r, String side, int bx0, int bx1, int bz0, int bz1) {
            switch (side) {
                case "n" -> doorN(roomIdx, r.x0(), r.x1(), bz0);
                case "s" -> doorS(roomIdx, r.x0(), r.x1(), bz1);
                case "e" -> doorE(roomIdx, r.z0(), r.z1(), bx1);
                default -> doorW(roomIdx, r.z0(), r.z1(), bx0);
            }
        }

        void extraDoors(int roomIdx, Room r, List<String> present, String primary,
                int ix0, int ix1, int iz0, int iz1, int bx0, int bx1, int bz0, int bz1) {
            for (String side : present) {
                if (side.equals(primary)) continue;
                boolean touches = (side.equals("n") && r.z0() == iz0)
                        || (side.equals("s") && r.z1() == iz1)
                        || (side.equals("e") && r.x1() == ix1)
                        || (side.equals("w") && r.x0() == ix0);
                if (!touches) continue;
                if (hashNum(i, j * 31L + roomIdx, SALT_DETAIL) % 100 < 45) {
                    streetDoor(roomIdx + 50, r, side, bx0, bx1, bz0, bz1);
                }
            }
        }

        void columns(Room r, boolean spawnBlock,
                int rx0, int rx1, int rz0, int rz1) {
            if (hashNum(i, j, SALT_DETAIL) % 100 >= 40) return;
            int cs = COLUMN_SPACING + hashNum(i, j, SALT_DETAIL) % 3;
            int w = rx1 - rx0, d = rz1 - rz0;
            if (w < cs || d < cs) return;
            boolean alongX = w >= d;
            if (alongX) {
                int zc = (rz0 + rz1) / 2 + hashNum(i, j, SALT_DETAIL) % 5 - 2;
                for (int x = rx0 + 3; x <= rx1 - 3; x += cs) addColumn(x, zc, spawnBlock, rx0, rx1, rz0, rz1);
                if (d > 24) {
                    int z2 = zc + Math.max(7, d / 3);
                    if (z2 < rz1 - 2) for (int x = rx0 + 3; x <= rx1 - 3; x += cs)
                        addColumn(x, z2, spawnBlock, rx0, rx1, rz0, rz1);
                }
            } else {
                int xc = (rx0 + rx1) / 2 + hashNum(i, j, SALT_DETAIL) % 5 - 2;
                for (int z = rz0 + 3; z <= rz1 - 3; z += cs) addColumn(xc, z, spawnBlock, rx0, rx1, rz0, rz1);
                if (w > 24) {
                    int x2 = xc + Math.max(7, w / 3);
                    if (x2 < rx1 - 2) for (int z = rz0 + 3; z <= rz1 - 3; z += cs)
                        addColumn(x2, z, spawnBlock, rx0, rx1, rz0, rz1);
                }
            }
        }

        private void addColumn(int x, int z, boolean spawnBlock, int rx0, int rx1, int rz0, int rz1) {
            if (spawnBlock && Math.abs(x - (rx0 + rx1) / 2) <= 2 && Math.abs(z - (rz0 + rz1) / 2) <= 2) return;
            if (hashNum(x, z, SALT_DETAIL) % 100 < 80) {
                solids.add(new Solid(x, x, z, z));
            }
        }

        void partitions(Room r, int roomIdx) {
            if (PARTITION_CHANCE <= 0) return;
            if (hashNum(i * 13L + roomIdx, j, SALT_DETAIL) % 100 >= PARTITION_CHANCE) return;
            int side = hashNum(i, j + roomIdx, SALT_DETAIL) % 4;
            int len = 4 + hashNum(i + roomIdx, j, SALT_DETAIL) % 5;
            int w = r.x1() - r.x0(), d = r.z1() - r.z0();
            Solid s = null;
            if (side == 0 && w > len + 6) {
                int x = r.x0() + 3 + hashNum(roomIdx, i, SALT_DETAIL) % (w - len - 4);
                s = new Solid(x, x, r.z0(), Math.min(r.z0() + len, r.z1() - 3));
            } else if (side == 1 && d > len + 6) {
                int z = r.z0() + 3 + hashNum(roomIdx, j, SALT_DETAIL) % (d - len - 4);
                s = new Solid(r.x1(), r.x1(), z, z + len);
            } else if (side == 2 && d > len + 6) {
                int z = r.z0() + 3 + hashNum(roomIdx, i + 7, SALT_DETAIL) % (d - len - 4);
                s = new Solid(r.x0(), r.x0(), z, z + len);
            } else if (w > len + 6) {
                int x = r.x0() + 3 + hashNum(roomIdx, j + 9, SALT_DETAIL) % (w - len - 4);
                s = new Solid(x, x, Math.max(r.z1() - len, r.z0() + 3), r.z1());
            }
            if (s == null) return;
            for (Door d2 : doors) {
                if (!(s.x1() < d2.x0() - 1 || s.x0() > d2.x1() + 1
                        || s.z1() < d2.z0() - 1 || s.z0() > d2.z1() + 1)) {
                    return; // never wall off a doorway
                }
            }
            solids.add(s);
        }
    }

    private void buildHall(Builder b, int ix0, int ix1, int iz0, int iz1,
            List<String> present, boolean sn, boolean se, boolean ss, boolean sw, boolean forced) {
        Room r = new Room(ix0, ix1, iz0, iz1, "OPEN HALL");
        b.rooms.add(r);
        for (String side : present) b.streetDoor(9, r, side, ix0 - 1, ix1 + 1, iz0 - 1, iz1 + 1);
        b.columns(r, forced, ix0, ix1, iz0, iz1);
        b.partitions(r, 9);
    }

    private void buildSuite(Builder b, int ix0, int ix1, int iz0, int iz1,
            List<String> present, boolean forced) {
        Room r = new Room(ix0, ix1, iz0, iz1, "SUITE");
        b.rooms.add(r);
        for (String side : present) b.streetDoor(9, r, side, ix0 - 1, ix1 + 1, iz0 - 1, iz1 + 1);
        b.columns(r, forced, ix0, ix1, iz0, iz1);

        int sw = Math.min(10, Math.max(6, (ix1 - ix0) / 3));
        int sd = Math.min(10, Math.max(6, (iz1 - iz0) / 3));
        int corner = b.hashNum(b.i, b.j, SALT_DETAIL) % 4;
        int nx0 = corner == 0 || corner == 3 ? ix0 + 3 : ix1 - sw - 3;
        int nz0 = corner < 2 ? iz0 + 3 : iz1 - sd - 3;
        int nx1 = nx0 + sw, nz1 = nz0 + sd;
        if (nx1 - nx0 < 5 || nz1 - nz0 < 5) return;
        b.solids.add(new Solid(nx0, nx1, nz0, nz0));
        b.solids.add(new Solid(nx0, nx1, nz1, nz1));
        b.solids.add(new Solid(nx0, nx0, nz0, nz1));
        b.solids.add(new Solid(nx1, nx1, nz0, nz1));
        Room storage = new Room(nx0 + 1, nx1 - 1, nz0 + 1, nz1 - 1, "STORAGE");
        b.rooms.add(storage);
        int span = storage.x1() - storage.x0() - 3;
        if (span > 0) {
            int dx = storage.x0() + 1 + (int) umod(b.hashDoor(7, 3), span);
            b.doors.add(new Door(dx, dx + 2, nz1, nz1));
        }
    }

    private void buildMerge(Builder b, int ix0, int ix1, int iz0, int iz1,
            List<String> present, boolean vertical) {
        int cut;
        if (vertical) {
            cut = ix0 + (ix1 - ix0) / 2 + b.hashNum(b.i, b.j, SALT_DETAIL) % 9 - 4;
            cut = clampInt(cut, ix0 + 8, ix1 - 8);
            Room a = new Room(ix0, cut - 1, iz0, iz1, "MERGED ROOMS");
            Room c = new Room(cut + 1, ix1, iz0, iz1, "MERGED ROOMS");
            b.rooms.add(a);
            b.rooms.add(c);
            for (String side : present) {
                b.streetDoor(1, a, side, ix0 - 1, ix1 + 1, iz0 - 1, iz1 + 1);
                b.streetDoor(2, c, side, ix0 - 1, ix1 + 1, iz0 - 1, iz1 + 1);
            }
            int ow = clampInt(WIDE_OPENING, 4, 10);
            int span = iz1 - iz0 - ow - 1;
            if (span > 0) {
                int z = iz0 + 1 + b.hashNum(b.i, b.j, SALT_DETAIL) % span;
                b.doors.add(new Door(cut, cut, z, z + ow - 1));
            }
            b.partitions(a, 1);
            b.partitions(c, 2);
        } else {
            cut = iz0 + (iz1 - iz0) / 2 + b.hashNum(b.i, b.j, SALT_DETAIL) % 9 - 4;
            cut = clampInt(cut, iz0 + 8, iz1 - 8);
            Room a = new Room(ix0, ix1, iz0, cut - 1, "MERGED ROOMS");
            Room c = new Room(ix0, ix1, cut + 1, iz1, "MERGED ROOMS");
            b.rooms.add(a);
            b.rooms.add(c);
            for (String side : present) {
                b.streetDoor(1, a, side, ix0 - 1, ix1 + 1, iz0 - 1, iz1 + 1);
                b.streetDoor(2, c, side, ix0 - 1, ix1 + 1, iz0 - 1, iz1 + 1);
            }
            int ow = clampInt(WIDE_OPENING, 4, 10);
            int span = ix1 - ix0 - ow - 1;
            if (span > 0) {
                int x = ix0 + 1 + b.hashNum(b.i, b.j, SALT_DETAIL) % span;
                b.doors.add(new Door(x, x + ow - 1, cut, cut));
            }
            b.partitions(a, 1);
            b.partitions(c, 2);
        }
    }

    private void buildOffices(Builder b, int ix0, int ix1, int iz0, int iz1,
            List<String> present, boolean sn, boolean se, boolean ss, boolean sw,
            boolean vertical, String primary, boolean forced) {
        int crossLen = vertical ? ix1 - ix0 : iz1 - iz0;
        if (crossLen < 20) {
            Room r = new Room(ix0, ix1, iz0, iz1, "OPEN HALL");
            b.rooms.add(r);
            for (String side : present) b.streetDoor(9, r, side, ix0 - 1, ix1 + 1, iz0 - 1, iz1 + 1);
            b.columns(r, forced, ix0, ix1, iz0, iz1);
            return;
        }
        int corridorW = clampInt(DOOR_WIDTH + 1, 3, 5);
        int crossStart = vertical ? ix0 : iz0;
        int c0 = crossStart + 4 + b.hashNum(b.i, b.j, SALT_DETAIL) % Math.max(1, crossLen - 8 - corridorW);
        int c1 = c0 + corridorW - 1;
        int bx0 = ix0 - 1, bx1 = ix1 + 1, bz0 = iz0 - 1, bz1 = iz1 + 1;
        Room corridor = vertical
                ? new Room(c0, c1, iz0, iz1, "OFFICE CORRIDOR")
                : new Room(ix0, ix1, c0, c1, "OFFICE CORRIDOR");
        b.rooms.add(corridor);

        if (primary.equals("n")) b.doors.add(new Door(corridor.x0(), corridor.x1(), bz0, bz0));
        if (primary.equals("s")) b.doors.add(new Door(corridor.x0(), corridor.x1(), bz1, bz1));
        if (primary.equals("e")) b.doors.add(new Door(bx1, bx1, corridor.z0(), corridor.z1()));
        if (primary.equals("w")) b.doors.add(new Door(bx0, bx0, corridor.z0(), corridor.z1()));
        String opp = switch (primary) { case "n" -> "s"; case "s" -> "n"; case "e" -> "w"; default -> "e"; };
        if (present.contains(opp)) {
            if (opp.equals("n")) b.doors.add(new Door(corridor.x0(), corridor.x1(), bz0, bz0));
            if (opp.equals("s")) b.doors.add(new Door(corridor.x0(), corridor.x1(), bz1, bz1));
            if (opp.equals("e")) b.doors.add(new Door(bx1, bx1, corridor.z0(), corridor.z1()));
            if (opp.equals("w")) b.doors.add(new Door(bx0, bx0, corridor.z0(), corridor.z1()));
        }

        if (vertical) {
            carveStrip(b, ix0, c0 - 2, iz0, iz1, true, c0 - 1, 1, present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
            carveStrip(b, c1 + 2, ix1, iz0, iz1, true, c1 + 1, 2, present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
        } else {
            carveStrip(b, ix0, ix1, iz0, c0 - 2, false, c0 - 1, 1, present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
            carveStrip(b, ix0, ix1, c1 + 2, iz1, false, c1 + 1, 2, present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
        }
    }

    private void carveStrip(Builder b, int x0, int x1, int z0, int z1, boolean alongZ,
            int wallPos, int stripIdx, List<String> present, String primary,
            int ix0, int ix1, int iz0, int iz1, int bx0, int bx1, int bz0, int bz1) {
        if (x1 - x0 < 5 || z1 - z0 < 5) return;
        int longLen = alongZ ? z1 - z0 + 1 : x1 - x0 + 1;
        int n = 1 + b.hashNum(b.i + stripIdx * 17L, b.j, SALT_DETAIL) % 3;
        n = Math.min(n, Math.max(1, longLen / 12));
        List<int[]> spans = new ArrayList<>();
        if (n <= 1) {
            spans.add(new int[] { 0, longLen - 1 });
        } else {
            List<Integer> cuts = new ArrayList<>();
            int prev = -1;
            for (int k = 1; k < n; k++) {
                int base = Math.round((float) longLen * k / n);
                int jit = b.hashNum(b.i + k + stripIdx, b.j, SALT_DETAIL) % 7 - 3;
                int lo = (prev < 0 ? 0 : prev + 1) + 10;
                int hi = longLen - 10 * (n - k);
                int c = clampInt(base + jit, lo, Math.max(lo, hi));
                cuts.add(c);
                prev = c;
            }
            int start = 0;
            for (int c : cuts) {
                spans.add(new int[] { start, c - 1 });
                start = c + 1;
            }
            spans.add(new int[] { start, longLen - 1 });
        }
        int idx2 = 0;
        for (int[] sp : spans) {
            idx2++;
            if (sp[1] - sp[0] + 1 < 8) continue;
            int rx0 = alongZ ? x0 : x0 + sp[0];
            int rx1 = alongZ ? x1 : x0 + sp[1];
            int rz0 = alongZ ? z0 + sp[0] : z0;
            int rz1 = alongZ ? z0 + sp[1] : z1;
            addOffice(b, rx0, rx1, rz0, rz1, alongZ, wallPos, stripIdx, idx2,
                    present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
        }
    }

    private void addOffice(Builder b, int rx0, int rx1, int rz0, int rz1,
            boolean alongZ, int wallPos, int stripIdx, int idx2,
            List<String> present, String primary,
            int ix0, int ix1, int iz0, int iz1, int bx0, int bx1, int bz0, int bz1) {
        int d0 = alongZ ? rx0 : rz0;
        int d1 = alongZ ? rx1 : rz1;
        List<Room> parts = new ArrayList<>();
        int splitWall = -1;
        if (d1 - d0 >= 18) {
            splitWall = d0 + 4 + b.hashNum(b.i + idx2, b.j + stripIdx, SALT_DETAIL)
                    % Math.max(1, d1 - d0 - 8);
            boolean outerPositive = wallPos > (d0 + d1) / 2;
            int oLo = outerPositive ? d0 : splitWall + 1;
            int oHi = outerPositive ? splitWall - 1 : d1;
            int iLo = outerPositive ? splitWall + 1 : d0;
            int iHi = outerPositive ? d1 : splitWall - 1;
            parts.add(alongZ ? new Room(oLo, oHi, rz0, rz1, "OFFICES")
                    : new Room(rx0, rx1, oLo, oHi, "OFFICES"));
            parts.add(alongZ ? new Room(iLo, iHi, rz0, rz1, "OFFICES")
                    : new Room(rx0, rx1, iLo, iHi, "OFFICES"));
            if (alongZ) {
                int span = rz1 - rz0 - DOOR_WIDTH - 1;
                if (span >= 1) {
                    int z = rz0 + 1 + (int) umod(b.hashDoor(idx2 * 5 + stripIdx, 5), span);
                    b.doors.add(new Door(splitWall, splitWall, z, z + DOOR_WIDTH - 1));
                }
            } else {
                int span = rx1 - rx0 - DOOR_WIDTH - 1;
                if (span >= 1) {
                    int x = rx0 + 1 + (int) umod(b.hashDoor(idx2 * 5 + stripIdx, 6), span);
                    b.doors.add(new Door(x, x + DOOR_WIDTH - 1, splitWall, splitWall));
                }
            }
        } else {
            parts.add(new Room(rx0, rx1, rz0, rz1, "OFFICES"));
        }
        for (int p = 0; p < parts.size(); p++) {
            Room rr = parts.get(p);
            boolean outer = p == 0;
            b.rooms.add(rr);
            if (outer) {
                if (alongZ) {
                    int span = rr.z1() - rr.z0() - DOOR_WIDTH - 1;
                    if (span >= 1) {
                        int z = rr.z0() + 1 + (int) umod(b.hashDoor(stripIdx * 7 + idx2, 3), span);
                        b.doors.add(new Door(wallPos, wallPos, z, z + DOOR_WIDTH - 1));
                    }
                } else {
                    int span = rr.x1() - rr.x0() - DOOR_WIDTH - 1;
                    if (span >= 1) {
                        int x = rr.x0() + 1 + (int) umod(b.hashDoor(stripIdx * 7 + idx2, 1), span);
                        b.doors.add(new Door(x, x + DOOR_WIDTH - 1, wallPos, wallPos));
                    }
                }
            }
            b.extraDoors(stripIdx * 7 + idx2 + (outer ? 0 : 13), rr, present, primary,
                    ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
            b.partitions(rr, stripIdx * 7 + idx2 + (outer ? 0 : 13));
        }
    }
}
