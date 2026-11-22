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
 * A grid of corridors ("streets") with irregular pitch runs across the level;
 * every street drifts sideways at each segment instead of running dead straight.
 * The space between streets is cut into rectangular blocks; each block is built
 * as an open hall, a row of offices around an inner corridor, a suite with a
 * storage room, or two rooms joined by a wide opening. Walls vary in depth per
 * side, and a missing street segment can turn into a hall that joins the two
 * blocks on its sides. Everything is a pure function of the seed, so chunks
 * agree on borders.
 */
public final class Level0Plan {
    // ---- Tunables (mirrored by tools/level0-preview) ----
    public static final int PITCH = 40;
    public static final int JITTER = 9;
    public static final int WANDER = 2;
    public static final int STREET_WIDTH_MAX = 5;
    public static final int SEGMENT_CHANCE = 65;
    public static final int BLOCK_CHANCE = 100;
    public static final int DOOR_WIDTH = 3;
    public static final int WIDE_OPENING = 4;
    public static final int COLUMN_SPACING = 4;
    public static final int PARTITION_CHANCE = 40;
    public static final int FURNITURE_CABINET_CHANCE = 60; // % of rooms that get a cabinet
    public static final int FURNITURE_CABINET_MAX = 2;     // cabinets per room, at most
    public static final int FURNITURE_DESK_CHANCE = 65;    // % of rooms that get a desk
    public static final int FURNITURE_DESK_MAX = 2;        // desks per room, at most
    public static final int FURNITURE_SHELF_CHANCE = 35;   // % of rooms that get a shelf
    public static final int FURNITURE_CHAIR_CHANCE = 85;   // % of desks that get a chair
    public static final int FURNITURE_EXTRA_CHANCE = 55;   // % of rooms that get one extra item
    public static final int FURNITURE_PILE_CHANCE = 3;     // % of tall large halls that get a furniture pile
    public static final int FURNITURE_PILE_MIN_ROOM = 13;  // smallest hall side that fits a pile
    public static final int WALL_OPEN = 35;         // % of street-facing walls left open
    public static final int WALL_THICK = 10;        // % of street-facing walls two blocks thick
    public static final int HALL_CHANCE = 75;       // % of missing street segments turned into halls
    public static final int FALSE_DOOR_CHANCE = 12; // % of blocks with a door into a wall pocket
    public static final int DARK_CHANCE = 25;       // % of 48x48 cells without light panels
    public static final int DEAD_LIGHT_CHANCE = 25; // % of grid panels replaced by ceiling tile
    public static final int HALL_CEIL_MAX = 8;      // highest extra ceiling lift of a hall
    public static final int PLAZA_CHANCE = 4;       // % of 2x2 districts turned into one plaza
    public static final int PLAZA_COL_SPACING = 14;
    public static final int LIGHT_EVERY = 12;
    public static final int DARK_DISTRICT = 48;
    public static final int DISTRICT_SIZE = 2;      // blocks per height district
    public static final int RAISED_CHANCE = 30;     // % of districts on another floor
    public static final int LEVEL_HEIGHT = 3;       // blocks between floor levels

    public static final int BASE_FLOOR_Y = 64;
    public static final int STREET_FLOOR_HALF = BASE_FLOOR_Y * 2;

    private static final long SALT_SX = 0x1A2B3C4D5E6F7081L;
    private static final long SALT_SZ = 0x2B3C4D5E6F708192L;
    private static final long SALT_SW = 0x3C4D5E6F708192A3L;
    private static final long SALT_SEGV = 0x4D5E6F708192A3B4L;
    private static final long SALT_SEGH = 0x5E6F708192A3B4C5L;
    private static final long SALT_BLOCK = 0x6F708192A3B4C5D6L;
    private static final long SALT_DOOR = 0x708192A3B4C5D6E7L;
    private static final long SALT_DETAIL = 0x8192A3B4C5D6E7F8L;
    private static final long SALT_LEVEL = 0x92A3B4C5D6E7F809L;
    private static final long SALT_WANDER_X = 0xA3B4C5D6E7F8091AL;
    private static final long SALT_WANDER_Z = 0xB4C5D6E7F8091A2BL;
    private static final long SALT_WALL = 0xC5D6E7F8091A2B3CL;
    private static final long SALT_HALL = 0xD6E7F8091A2B3C4DL;
    private static final long SALT_LIGHT = 0xE7F8091A2B3C4D5EL;
    private static final long SALT_CEIL = 0xF8091A2B3C4D5E6FL;
    private static final long SALT_NICHE = 0x091A2B3C4D5E6F70L;
    private static final long SALT_PLAZA = 0x13579BDF2468ACE0L;
    private static final long SALT_STREET_FIX = 0x2468ACE013579BDFL;
    private static final long SALT_FURNITURE = 0x0F1E2D3C4B5A6978L;
    private static final long SALT_FURNITURE_VARIANT = 0x58A4C6E80B1D3F27L;
    private static final long SALT_FURNITURE_FACING = 0x6C2E4A8B0D1F3579L;
    private static final long SALT_PILE = 0x3A5C7E9B1D2F4068L;

    public record Room(int x0, int x1, int z0, int z1, String name) {}

    public record Door(int x0, int x1, int z0, int z1) {}

    /** Solid furniture inside a carved room: a column or a partial partition. */
    public record Solid(int x0, int x1, int z0, int z1) {}

    /** One carpet stair block: its block y and the side it rises toward. */
    public record StairStep(int x, int z, int y, String facing) {}

    /** Loose furniture on a room floor: height above the floor, kind, front side, model variant. */
    public record Furniture(int x, int z, int dy, String kind, String facing, int variant) {
        public Furniture(int x, int z, String kind, String facing, int variant) {
            this(x, z, 0, kind, facing, variant);
        }
    }

    public record Block(int i, int j, int x0, int x1, int z0, int z1, String layout,
            int level, int ceilExtra, List<Room> rooms, List<Door> doors, List<Solid> solids,
            List<StairStep> stairs, List<Furniture> furniture) {}

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

    /** Unsigned remainder, matching the JS preview hash semantics. */
    private static long umod(long h, long n) {
        return Long.remainderUnsigned(h, n);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Floor half-height of a whole level. Level 0 is y64. */
    public static int levelHalf(int level) {
        return STREET_FLOOR_HALF + 2 * LEVEL_HEIGHT * level;
    }

    /** District level of a block: most are 0, some whole districts are +1/-1. */
    public int blockLevel(int i, int j) {
        if (i == spawnI && j == spawnJ) return 0;
        int di = Math.floorDiv(i, DISTRICT_SIZE);
        int dj = Math.floorDiv(j, DISTRICT_SIZE);
        int v = (int) umod(hash(di, dj, SALT_LEVEL), 100);
        if (v < RAISED_CHANCE / 2) return 1;
        if (v < RAISED_CHANCE) return -1;
        return 0;
    }

    /** Uneven wall depth per side: 0 opens the room onto the street, 2 is a thick wall. */
    private int pickInset(int i, int j, int side, boolean present) {
        if (!present) return 1;
        int t = hashNum(i * 7L + side, j, SALT_WALL) % 100;
        if (t < WALL_OPEN) return 0;
        return t >= 100 - WALL_THICK ? 2 : 1;
    }

    // ---- Plazas and street rescue ----

    /** A whole district replaced by one open hall: level 0 only, never at spawn. */
    private boolean plazaDistrict(int di, int dj) {
        if (di == Math.floorDiv(spawnI, DISTRICT_SIZE)
                && dj == Math.floorDiv(spawnJ, DISTRICT_SIZE)) return false;
        if (blockLevel(di * DISTRICT_SIZE, dj * DISTRICT_SIZE) != 0) return false;
        return hashNum(di, dj, SALT_PLAZA) % 100 < PLAZA_CHANCE;
    }

    public boolean isPlaza(int i, int j) {
        return plazaDistrict(Math.floorDiv(i, DISTRICT_SIZE), Math.floorDiv(j, DISTRICT_SIZE));
    }

    private boolean specialBlock(int i, int j) {
        return blockLevel(i, j) != 0 || isPlaza(i, j);
    }

    /** One external border per axis: the other one lies inside the district. */
    private static int externalV(int i) {
        return Math.floorDiv(i - 1, DISTRICT_SIZE) == Math.floorDiv(i, DISTRICT_SIZE) ? i + 1 : i;
    }

    private static int externalH(int j) {
        return Math.floorDiv(j - 1, DISTRICT_SIZE) == Math.floorDiv(j, DISTRICT_SIZE) ? j + 1 : j;
    }

    /** A panel on the 12x12 grid, unless its district is dark or the tube is dead. */
    public boolean lightAt(int x, int z) {
        if (Math.floorMod(x, LIGHT_EVERY) != 3 || Math.floorMod(z, LIGHT_EVERY) != 3) return false;
        if (hashNum(Math.floorDiv(x, DARK_DISTRICT), Math.floorDiv(z, DARK_DISTRICT),
                SALT_LIGHT) % 100 < DARK_CHANCE) return false;
        return hashNum(x, z, SALT_LIGHT) % 100 >= DEAD_LIGHT_CHANCE;
    }

    /** Contents seed of a cabinet: the same cell always rolls the same loot. */
    public long furnitureLootSeed(int x, int z) {
        return hash(x, z, SALT_FURNITURE);
    }

    // ---- Streets ----

    /** Side step of a street at one segment: it drifts instead of running straight. */
    private int streetWander(int idx, int seg, long salt) {
        if (WANDER <= 0) return 0;
        return (int) umod(hash(idx, seg, salt), 2L * WANDER + 1) - WANDER;
    }

    public int streetX(int i, int seg) {
        long range = 2L * JITTER + 1;
        int jitter = JITTER > 0 ? (int) umod(hash(i, 0, SALT_SX), range) - JITTER : 0;
        return i * PITCH + jitter + streetWander(i, seg, SALT_WANDER_X);
    }

    public int streetZ(int j, int seg) {
        long range = 2L * JITTER + 1;
        int jitter = JITTER > 0 ? (int) umod(hash(j, 0, SALT_SZ), range) - JITTER : 0;
        return j * PITCH + jitter + streetWander(j, seg, SALT_WANDER_Z);
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
        if (Math.floorDiv(i - 1, DISTRICT_SIZE) == Math.floorDiv(i, DISTRICT_SIZE)
                && sameRaisedLevel(i - 1, j, i, j)) return false;
        if (Math.floorDiv(i - 1, DISTRICT_SIZE) == Math.floorDiv(i, DISTRICT_SIZE)
                && isPlaza(i - 1, j) && isPlaza(i, j)) return false;
        if (segRawV(i, j)) return true;
        return streetFixV(i, j);
    }

    /** Horizontal street j between vertical streets i and i+1. */
    public boolean segH(int i, int j) {
        if (i == spawnI && (j == spawnJ || j == spawnJ + 1)) return true;
        if (Math.floorDiv(j - 1, DISTRICT_SIZE) == Math.floorDiv(j, DISTRICT_SIZE)
                && sameRaisedLevel(i, j - 1, i, j)) return false;
        if (Math.floorDiv(j - 1, DISTRICT_SIZE) == Math.floorDiv(j, DISTRICT_SIZE)
                && isPlaza(i, j - 1) && isPlaza(i, j)) return false;
        if (segRawH(i, j)) return true;
        return streetFixH(i, j);
    }

    private boolean sameRaisedLevel(int ai, int aj, int bi, int bj) {
        int level = blockLevel(ai, aj);
        return level != 0 && level == blockLevel(bi, bj);
    }

    private boolean segRawV(int i, int j) {
        return umod(hash(i, j, SALT_SEGV), 100L) < SEGMENT_CHANCE;
    }

    private boolean segRawH(int i, int j) {
        return umod(hash(i, j, SALT_SEGH), 100L) < SEGMENT_CHANCE;
    }

    // A block whose two outer borders both closed would have no street door at
    // all, so it would vanish and tear a hole in its district. Reopen one border.
    private boolean fixVerticalAt(int i, int j, int borderV) {
        if (!specialBlock(i, j) || externalV(i) != borderV) return false;
        if (segRawV(borderV, j) || segRawH(i, externalH(j))) return false;
        return umod(hash(i, j, SALT_STREET_FIX), 2L) == 0;
    }

    private boolean fixHorizontalAt(int i, int j, int borderH) {
        if (!specialBlock(i, j) || externalH(j) != borderH) return false;
        if (segRawH(i, borderH) || segRawV(externalV(i), j)) return false;
        return umod(hash(i, j, SALT_STREET_FIX), 2L) != 0;
    }

    private boolean streetFixV(int v, int j) {
        return fixVerticalAt(v - 1, j, v) || fixVerticalAt(v, j, v);
    }

    private boolean streetFixH(int i, int h) {
        return fixHorizontalAt(i, h - 1, h) || fixHorizontalAt(i, h, h);
    }

    private void findSpawnCell() {
        int[] cell = blockOf(0, 0);
        spawnI = cell[0];
        spawnJ = cell[1];
    }

    public int spawnI() { return spawnI; }
    public int spawnJ() { return spawnJ; }

    /** Block containing a point. */
    public int[] blockOf(int x, int z) {
        int i = Math.floorDiv(x, PITCH);
        int j = Math.floorDiv(z, PITCH);
        // Street offsets depend on the other axis, so settle the cell in rounds.
        for (int k = 0; k < 6; k++) {
            while (streetZ(j, i) > z) j--;
            while (streetZ(j + 1, i) <= z) j++;
            while (streetX(i, j) > x) i--;
            while (streetX(i + 1, j) <= x) i++;
        }
        return new int[] { i, j };
    }

    /** Street indices whose bands can touch the given coordinate range. */
    public int[] streetRange(int min, int max) {
        int pad = JITTER + WANDER + STREET_WIDTH_MAX / 2 + 4;
        return new int[] {
                Math.floorDiv(min - pad, PITCH) - 1,
                Math.floorDiv(max + pad, PITCH) + 1
        };
    }

    public int[] spawnPoint() {
        Block b = block(spawnI, spawnJ);
        if (b == null || b.rooms().isEmpty()) {
            return new int[] { 0, 0, BASE_FLOOR_Y };
        }
        Room r = b.rooms().get(0);
        int x = (r.x0() + r.x1()) / 2;
        int z = (r.z0() + r.z1()) / 2;
        return new int[] { x, z, BASE_FLOOR_Y + LEVEL_HEIGHT * b.level() };
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
        int x0 = streetX(i, j) + streetHalf(i) + 1;
        int x1 = streetX(i + 1, j) - streetHalf(i + 1) - 1;
        int z0 = streetZ(j, i) + streetHalf(j) + 1;
        int z1 = streetZ(j + 1, i) - streetHalf(j + 1) - 1;
        if (x1 - x0 < 8 || z1 - z0 < 8) return null;

        long hb = hash(i, j, SALT_BLOCK);
        boolean forced = i == spawnI && j == spawnJ;
        int level = blockLevel(i, j);
        boolean plaza = isPlaza(i, j);
        if (!forced && !plaza && umod(hb, 100L) >= BLOCK_CHANCE) return null;

        boolean sn = segH(i, j), se = segV(i + 1, j), ss = segH(i, j + 1), sw = segV(i, j);
        List<String> present = new ArrayList<>();
        if (sn) present.add("n");
        if (se) present.add("e");
        if (ss) present.add("s");
        if (sw) present.add("w");
        if (present.isEmpty() && !plaza) return null;
        if (plaza) return buildPlaza(i, j, x0, x1, z0, z1, present);

        String layout;
        int lr = (int) umod(hb >>> 8, 100L);
        if (forced) layout = "hall";
        else if (lr < 20) layout = "hall";
        else if (lr < 75) layout = "offices";
        else if (lr < 90) layout = "suite";
        else layout = "merge";
        // A stair ramp at the street door would leave interior doorways on a ledge.
        if (level != 0 && !layout.equals("hall") && !layout.equals("merge")) {
            layout = "hall";
        }

        String primary = forced ? "n"
                : present.get((int) umod(hb >>> 20, present.size()));
        boolean vertical = primary.equals("n") || primary.equals("s");

        int insetN = 1, insetE = 1, insetS = 1, insetW = 1;
        if (level == 0 && !forced) {
            insetN = pickInset(i, j, 1, sn);
            insetE = pickInset(i, j, 2, se);
            insetS = pickInset(i, j, 3, ss);
            insetW = pickInset(i, j, 4, sw);
            if (x1 - x0 - insetW - insetE < 6) { insetW = 1; insetE = 1; }
            if (z1 - z0 - insetN - insetS < 6) { insetN = 1; insetS = 1; }
        }
        int ix0 = x0 + insetW, ix1 = x1 - insetE;
        int iz0 = z0 + insetN, iz1 = z1 - insetS;
        if (!layout.equals("hall") && !layout.equals("suite")) {
            // Pick the layout by the nominal block size: uneven walls only trim the room.
            int along = vertical ? x1 - x0 - 2 : z1 - z0 - 2;
            if (along < 13) layout = "hall";
        }
        int ceilExtra = hallCeilExtra(i, j, layout);

        Builder b = new Builder(i, j);
        switch (layout) {
            case "hall" -> buildHall(b, ix0, ix1, iz0, iz1, x0, x1, z0, z1,
                    present, sn, se, ss, sw, forced);
            case "suite" -> buildSuite(b, ix0, ix1, iz0, iz1, x0, x1, z0, z1, present, forced);
            case "merge" -> buildMerge(b, ix0, ix1, iz0, iz1, present, primary, vertical,
                    x0, x1, z0, z1);
            default -> buildOffices(b, ix0, ix1, iz0, iz1, x0, x1, z0, z1,
                    present, sn, se, ss, sw, vertical, primary, forced);
        }
        // A door is pointless on an open side, and a street door must pierce a thick wall.
        for (int k = b.doors.size() - 1; k >= 0; k--) {
            Door d = b.doors.get(k);
            boolean onOpen = (insetN == 0 && d.z0() == z0 && d.z1() == z0)
                    || (insetS == 0 && d.z0() == z1 && d.z1() == z1)
                    || (insetW == 0 && d.x0() == x0 && d.x1() == x0)
                    || (insetE == 0 && d.x0() == x1 && d.x1() == x1);
            if (onOpen) {
                b.doors.remove(k);
                continue;
            }
            int dx0 = d.x0(), dx1 = d.x1(), dz0 = d.z0(), dz1 = d.z1();
            if (dz0 == z0 && dz1 == z0 && insetN > 1) dz1 = z0 + insetN - 1;
            else if (dz0 == z1 && dz1 == z1 && insetS > 1) dz0 = z1 - insetS + 1;
            else if (dx0 == x0 && dx1 == x0 && insetW > 1) dx1 = x0 + insetW - 1;
            else if (dx0 == x1 && dx1 == x1 && insetE > 1) dx0 = x1 - insetE + 1;
            if (dx0 != d.x0() || dx1 != d.x1() || dz0 != d.z0() || dz1 != d.z1()) {
                b.doors.set(k, new Door(dx0, dx1, dz0, dz1));
            }
        }
        if (level != 0) {
            // One stair run per block: crossing runs cut off the corners of small halls.
            boolean kept = false;
            for (int k = b.doors.size() - 1; k >= 0; k--) {
                Door d = b.doors.get(k);
                boolean street = d.x0() == x0 || d.x1() == x1 || d.z0() == z0 || d.z1() == z1;
                if (!street) continue;
                if (kept) b.doors.remove(k);
                else kept = true;
            }
        }
        List<StairStep> stairs = new ArrayList<>();
        if (level != 0) {
            boolean up = level > 0;
            int roomY = BASE_FLOOR_Y + LEVEL_HEIGHT * level;
            for (Door d : b.doors) {
                boolean street = d.x0() == x0 || d.x1() == x1 || d.z0() == z0 || d.z1() == z1;
                if (!street) continue;
                if (d.z0() == z0) { // door in the north wall, stairs go south
                    for (int k = 0; k < LEVEL_HEIGHT; k++) {
                        int z = z0 + 1 + k;
                        int y = up ? roomY - LEVEL_HEIGHT + 1 + k : roomY + LEVEL_HEIGHT - k;
                        for (int x = d.x0(); x <= d.x1(); x++) stairs.add(new StairStep(x, z, y, up ? "s" : "n"));
                    }
                } else if (d.z1() == z1) { // south wall, stairs go north
                    for (int k = 0; k < LEVEL_HEIGHT; k++) {
                        int z = z1 - 1 - k;
                        int y = up ? roomY - LEVEL_HEIGHT + 1 + k : roomY + LEVEL_HEIGHT - k;
                        for (int x = d.x0(); x <= d.x1(); x++) stairs.add(new StairStep(x, z, y, up ? "n" : "s"));
                    }
                } else if (d.x0() == x0) { // west wall, stairs go east
                    for (int k = 0; k < LEVEL_HEIGHT; k++) {
                        int x = x0 + 1 + k;
                        int y = up ? roomY - LEVEL_HEIGHT + 1 + k : roomY + LEVEL_HEIGHT - k;
                        for (int z = d.z0(); z <= d.z1(); z++) stairs.add(new StairStep(x, z, y, up ? "e" : "w"));
                    }
                } else if (d.x1() == x1) { // east wall, stairs go west
                    for (int k = 0; k < LEVEL_HEIGHT; k++) {
                        int x = x1 - 1 - k;
                        int y = up ? roomY - LEVEL_HEIGHT + 1 + k : roomY + LEVEL_HEIGHT - k;
                        for (int z = d.z0(); z <= d.z1(); z++) stairs.add(new StairStep(x, z, y, up ? "w" : "e"));
                    }
                }
            }
            if (!stairs.isEmpty()) {
                List<Solid> kept = new ArrayList<>();
                for (Solid so : b.solids) {
                    boolean clash = false;
                    for (StairStep st : stairs) {
                        if (st.x() >= so.x0() && st.x() <= so.x1()
                                && st.z() >= so.z0() && st.z() <= so.z1()) {
                            clash = true;
                            break;
                        }
                    }
                    if (!clash) kept.add(so);
                }
                b.solids.clear();
                b.solids.addAll(kept);
            }
        }
        // A door that opens into a shallow pocket in the wall: a dead end that
        // still reads as an opening. Added last so it never becomes a stair exit.
        addFalseDoor(b, i, j, x0, x1, z0, z1, present, stairs);
        return new Block(i, j, x0, x1, z0, z1, layout, level, ceilExtra,
                List.copyOf(b.rooms), List.copyOf(b.doors), List.copyOf(b.solids),
                List.copyOf(stairs), computeFurniture(i, j, b.rooms, b.doors, b.solids, stairs,
                        x0, x1, z0, z1, layout.equals("hall") && ceilExtra > 0));
    }

    /** Tall halls: half get no lift, the rest rise by 2, 4, 6 or 8 blocks. */
    private int hallCeilExtra(int i, int j, String layout) {
        if (!layout.equals("hall")) return 0;
        int r = hashNum(i, j, SALT_CEIL);
        int extra = (r & 1) == 0 ? 0 : 2 * (1 + (r >>> 1) % 4);
        return Math.min(extra, HALL_CEIL_MAX);
    }

    /**
     * Rare district replaced by one open hall. Every block carves its own slice
     * extended to the neighbouring slice, so the four slices join into one room;
     * internal street strips are crossed by plain overlap.
     */
    private Block buildPlaza(int i, int j, int x0, int x1, int z0, int z1,
            List<String> present) {
        int di = Math.floorDiv(i, DISTRICT_SIZE);
        int dj = Math.floorDiv(j, DISTRICT_SIZE);
        boolean westIn = Math.floorDiv(i - 1, DISTRICT_SIZE) == di;
        boolean eastIn = Math.floorDiv(i + 1, DISTRICT_SIZE) == di;
        boolean northIn = Math.floorDiv(j - 1, DISTRICT_SIZE) == dj;
        boolean southIn = Math.floorDiv(j + 1, DISTRICT_SIZE) == dj;
        // One cell past the neighbour's outer wall line: the wall itself stays.
        int px0 = westIn ? streetX(i, j) - streetHalf(i) - 2 : x0 + 1;
        int px1 = eastIn ? streetX(i + 2, j) - streetHalf(i + 2) - 2 : x1 - 1;
        int pz0 = northIn ? streetZ(j, i) - streetHalf(j) - 2 : z0 + 1;
        int pz1 = southIn ? streetZ(j + 2, i) - streetHalf(j + 2) - 2 : z1 - 1;

        Builder b = new Builder(i, j);
        b.rooms.add(new Room(px0, px1, pz0, pz1, "PLAZA"));
        for (String side : present) {
            switch (side) {
                case "n" -> b.doorN(9, x0, x1, z0);
                case "s" -> b.doorS(9, x0, x1, z1);
                case "e" -> b.doorE(9, z0, z1, x1);
                default -> b.doorW(9, z0, z1, x0);
            }
        }
        // Columns live on one district-wide grid; each block keeps its own share.
        int spacing = Math.max(4, PLAZA_COL_SPACING);
        int baseX = di * DISTRICT_SIZE * PITCH + 7;
        int baseZ = dj * DISTRICT_SIZE * PITCH + 7;
        int mx0 = -Math.floorDiv(-(x0 - baseX), spacing);
        int mx1 = Math.floorDiv(x1 - baseX, spacing);
        int mz0 = -Math.floorDiv(-(z0 - baseZ), spacing);
        int mz1 = Math.floorDiv(z1 - baseZ, spacing);
        for (int mx = mx0; mx <= mx1; mx++) {
            for (int mz = mz0; mz <= mz1; mz++) {
                int x = baseX + mx * spacing;
                int z = baseZ + mz * spacing;
                if (hashNum(x, z, SALT_PLAZA) % 100 < 80) {
                    b.solids.add(new Solid(x, x, z, z));
                }
            }
        }
        addFalseDoor(b, i, j, x0, x1, z0, z1, present, List.of());
        return new Block(i, j, x0, x1, z0, z1, "plaza", 0, HALL_CEIL_MAX,
                List.copyOf(b.rooms), List.copyOf(b.doors), List.copyOf(b.solids), List.of(),
                computeFurniture(i, j, b.rooms, b.doors, b.solids, List.of(), x0, x1, z0, z1,
                        false));
    }

    /** A fake doorway in a solid side wall, with a one- or two-block pocket behind it. */
    private void addFalseDoor(Builder b, int i, int j, int x0, int x1, int z0, int z1,
            List<String> present, List<StairStep> stairs) {
        if (FALSE_DOOR_CHANCE <= 0) return;
        if (hashNum(i, j, SALT_NICHE) % 100 >= FALSE_DOOR_CHANCE) return;
        List<String> absent = new ArrayList<>(4);
        if (!present.contains("n")) absent.add("n");
        if (!present.contains("e")) absent.add("e");
        if (!present.contains("s")) absent.add("s");
        if (!present.contains("w")) absent.add("w");
        if (absent.isEmpty()) return;
        String side = absent.get(hashNum(i + 3L, j + 5L, SALT_NICHE) % absent.size());
        int depth = 1 + hashNum(i + 7L, j + 9L, SALT_NICHE) % 2;
        Door d;
        Room niche;
        if (side.equals("n") || side.equals("s")) {
            int span = x1 - x0 - DOOR_WIDTH - 1;
            if (span < 1) return;
            int x = x0 + 1 + hashNum(i + 11L, j + 13L, SALT_NICHE) % span;
            int wallZ = side.equals("n") ? z0 : z1;
            d = new Door(x, x + DOOR_WIDTH - 1, wallZ, wallZ);
            int nz0 = side.equals("n") ? z0 - depth : z1 + 1;
            niche = new Room(x - 1, x + DOOR_WIDTH, nz0, nz0 + depth - 1, "NICHE");
        } else {
            int span = z1 - z0 - DOOR_WIDTH - 1;
            if (span < 1) return;
            int z = z0 + 1 + hashNum(i + 11L, j + 13L, SALT_NICHE) % span;
            int wallX = side.equals("w") ? x0 : x1;
            d = new Door(wallX, wallX, z, z + DOOR_WIDTH - 1);
            int nx0 = side.equals("w") ? x0 - depth : x1 + 1;
            niche = new Room(nx0, nx0 + depth - 1, z - 1, z + DOOR_WIDTH, "NICHE");
        }
        for (Door o : b.doors) {
            // never join an existing doorway
            if (!(o.x1() < d.x0() - 1 || o.x0() > d.x1() + 1
                    || o.z1() < d.z0() - 1 || o.z0() > d.z1() + 1)) return;
        }
        // the pocket must open from a real room, not from solid mass
        boolean touches = false;
        for (Room r : b.rooms) {
            if (side.equals("n") && r.z0() == z0 + 1 && d.x0() >= r.x0() && d.x1() <= r.x1()) {
                touches = true;
                break;
            }
            if (side.equals("s") && r.z1() == z1 - 1 && d.x0() >= r.x0() && d.x1() <= r.x1()) {
                touches = true;
                break;
            }
            if (side.equals("w") && r.x0() == x0 + 1 && d.z0() >= r.z0() && d.z1() <= r.z1()) {
                touches = true;
                break;
            }
            if (side.equals("e") && r.x1() == x1 - 1 && d.z0() >= r.z0() && d.z1() <= r.z1()) {
                touches = true;
                break;
            }
        }
        if (!touches) return;
        int bx0 = side.equals("n") || side.equals("s") ? d.x0()
                : (side.equals("w") ? x0 + 1 : x1 - 1);
        int bx1 = side.equals("n") || side.equals("s") ? d.x1() : bx0;
        int bz0 = side.equals("n") || side.equals("s")
                ? (side.equals("n") ? z0 + 1 : z1 - 1) : d.z0();
        int bz1 = side.equals("n") || side.equals("s") ? bz0 : d.z1();
        for (Solid so : b.solids) {
            if (!(so.x1() < bx0 || so.x0() > bx1 || so.z1() < bz0 || so.z0() > bz1)) return;
        }
        for (StairStep st : stairs) {
            // stairs occupy the inside of a raised block: no niche through them
            if (st.x() >= d.x0() && st.x() <= d.x1()
                    && st.z() >= d.z0() && st.z() <= d.z1()) return;
        }
        b.doors.add(d);
        b.rooms.add(niche);
    }

    // ---- Furniture ----

    /**
     * Furniture of a finished block. Cabinets, shelves and the small extras hug
     * the first ring inside a room wall and never block a doorway, a stair or
     * the cell in front of them; a desk stands on open floor clear of the walls
     * with a chair in front of it. Only hash picks the cells, so the layout
     * stays a pure function of the seed.
     */
    private List<Furniture> computeFurniture(int i, int j, List<Room> rooms, List<Door> doors,
            List<Solid> solids, List<StairStep> stairs, int bx0, int bx1, int bz0, int bz1,
            boolean pileAllowed) {
        List<Furniture> out = new ArrayList<>();
        int cabMax = Math.max(1, FURNITURE_CABINET_MAX);
        int deskMax = Math.max(1, FURNITURE_DESK_MAX);
        for (int ri = 0; ri < rooms.size(); ri++) {
            Room r = rooms.get(ri);
            if (r.x1() - r.x0() < 5 || r.z1() - r.z0() < 5) continue;
            int wantC = hashNum(i * 5L + ri, j * 7L + 1, SALT_FURNITURE) % 100
                    < FURNITURE_CABINET_CHANCE
                    ? 1 + hashNum(i * 5L + ri, j * 7L + 2, SALT_FURNITURE) % cabMax : 0;
            addWallFurniture(out, r, wantC, "cabinet", doors, solids, stairs, bx0, bx1, bz0, bz1);
            int wantS = hashNum(i * 7L + ri, j * 11L + 5, SALT_FURNITURE) % 100
                    < FURNITURE_SHELF_CHANCE ? 1 : 0;
            addWallFurniture(out, r, wantS, "shelf", doors, solids, stairs, bx0, bx1, bz0, bz1);
            int wantD = hashNum(i * 11L + ri, j * 13L + 3, SALT_FURNITURE) % 100
                    < FURNITURE_DESK_CHANCE
                    ? 1 + hashNum(i * 11L + ri, j * 13L + 4, SALT_FURNITURE) % deskMax : 0;
            addDesks(out, r, wantD, doors, solids, stairs);
            if (hashNum(i * 13L + ri, j * 17L + 9, SALT_FURNITURE) % 100
                    < FURNITURE_EXTRA_CHANCE) {
                int pick = hashNum(i * 17L + ri, j * 19L + 11, SALT_FURNITURE) % 10;
                String extra = pick < 4 ? "plant" : pick < 6 ? "cooler"
                        : pick < 8 ? "trash" : "printer";
                addWallFurniture(out, r, 1, extra, doors, solids, stairs, bx0, bx1, bz0, bz1);
            }
        }
        if (pileAllowed) addPile(i, j, rooms, doors, solids, stairs, out);
        return List.copyOf(out);
    }

    /**
     * A rare heap of furniture in the middle of a tall hall. A column, a door,
     * a stair or existing furniture in the 3x3 center cancels the pile.
     */
    private void addPile(int i, int j, List<Room> rooms, List<Door> doors, List<Solid> solids,
            List<StairStep> stairs, List<Furniture> out) {
        if (i == spawnI && j == spawnJ) return;
        if (hashNum(i, j, SALT_PILE) % 100 >= FURNITURE_PILE_CHANCE) return;
        Room room = null;
        for (Room r : rooms) {
            if (r.x1() - r.x0() + 1 < FURNITURE_PILE_MIN_ROOM
                    || r.z1() - r.z0() + 1 < FURNITURE_PILE_MIN_ROOM) continue;
            if (room == null || (r.x1() - r.x0()) * (r.z1() - r.z0())
                    > (room.x1() - room.x0()) * (room.z1() - room.z0())) {
                room = r;
            }
        }
        if (room == null) return;
        int cx = (room.x0() + room.x1()) / 2;
        int cz = (room.z0() + room.z1()) / 2;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx, z = cz + dz;
                if (onSolid(solids, x, z) || nearStair(stairs, x, z) || nearDoor(doors, x, z)
                        || hasFurniture(out, x, z)) return;
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                addPilePiece(out, cx + dx, cz + dz, 0);
            }
        }
        int side = hashNum(cx, cz, SALT_PILE + 1) % 4;
        int mx = cx + (side == 0 ? 1 : side == 1 ? -1 : 0);
        int mz = cz + (side == 2 ? 1 : side == 3 ? -1 : 0);
        addPilePiece(out, cx, cz, 1);
        addPilePiece(out, mx, mz, 1);
        addPilePiece(out, cx, cz, 2);
    }

    private void addPilePiece(List<Furniture> out, int x, int z, int dy) {
        int pick = hashNum(x, z + dy * 17L, SALT_PILE) % 10;
        String kind = pick < 3 ? "desk" : pick < 5 ? "chair" : pick < 7 ? "shelf"
                : pick < 8 ? "trash" : pick < 9 ? "plant" : "printer";
        String facing = switch (hashNum(x, z + dy * 31L, SALT_FURNITURE_FACING) % 4) {
            case 0 -> "north";
            case 1 -> "east";
            case 2 -> "south";
            default -> "west";
        };
        out.add(new Furniture(x, z, dy, kind, facing, variantOf(kind, x, z)));
    }

    /**
     * Nearest furniture pile to a point, as {x, z, floorY, standX, standZ}.
     * Null when no pile exists within the scanned block square.
     */
    @Nullable
    public int[] nearestPile(int x, int z, int radiusBlocks) {
        int[] cell = blockOf(x, z);
        int[] best = null;
        long bestDist = Long.MAX_VALUE;
        for (int di = -radiusBlocks; di <= radiusBlocks; di++) {
            for (int dj = -radiusBlocks; dj <= radiusBlocks; dj++) {
                Block b = block(cell[0] + di, cell[1] + dj);
                if (b == null) continue;
                Furniture top = null;
                for (Furniture f : b.furniture()) {
                    if (f.dy() == 2) {
                        top = f;
                        break;
                    }
                }
                if (top == null) continue;
                long dx = top.x() - x;
                long dz = top.z() - z;
                long dist = dx * dx + dz * dz;
                if (dist >= bestDist) continue;
                int[] stand = pileStand(top.x(), top.z(), b);
                if (stand == null) continue;
                bestDist = dist;
                best = new int[] { top.x(), top.z(), levelHalf(b.level()) / 2, stand[0], stand[1] };
            }
        }
        return best;
    }

    /** A free room cell two or three blocks away from a pile center. */
    @Nullable
    private int[] pileStand(int cx, int cz, Block b) {
        for (int r = 2; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = cx + dx, z = cz + dz;
                    boolean inRoom = false;
                    for (Room room : b.rooms()) {
                        if (x >= room.x0() && x <= room.x1() && z >= room.z0() && z <= room.z1()) {
                            inRoom = true;
                            break;
                        }
                    }
                    if (!inRoom || onSolid(b.solids(), x, z) || inDoor(b.doors(), x, z)
                            || nearStair(b.stairs(), x, z) || hasFurniture(b.furniture(), x, z)) continue;
                    return new int[] { x, z };
                }
            }
        }
        return null;
    }

    private void addWallFurniture(List<Furniture> out, Room r, int want, String kind,
            List<Door> doors, List<Solid> solids, List<StairStep> stairs,
            int bx0, int bx1, int bz0, int bz1) {
        if (want <= 0) return;
        int w0 = r.x0() + 1, w1 = r.x1() - 1;
        int d0 = r.z0() + 1, d1 = r.z1() - 1;
        int rw = w1 - w0 + 1;
        int rd = d1 - d0 - 1;
        if (rw < 2 || rd < 1) return;
        // Wall furniture needs a wall behind it; an open street side has none.
        boolean openW = r.x0() == bx0, openE = r.x1() == bx1;
        boolean openN = r.z0() == bz0, openS = r.z1() == bz1;
        int ring = 2 * rw + 2 * rd;
        int start = hashNum(r.x0(), r.z0() + kind.length(), SALT_FURNITURE) % ring;
        int placed = 0;
        for (int k = 0; k < ring && placed < want; k++) {
            int idx = (start + k) % ring;
            int x, z;
            if (idx < rw) {
                x = w0 + idx;
                z = d0;
            } else if (idx < rw + rd) {
                x = w1;
                z = d0 + 1 + idx - rw;
            } else if (idx < 2 * rw + rd) {
                x = w1 - (idx - rw - rd);
                z = d1;
            } else {
                x = w0;
                z = d1 - 1 - (idx - 2 * rw - rd);
            }
            boolean west = x == w0, east = x == w1, north = z == d0, south = z == d1;
            if ((west && openW) || (east && openE) || (north && openN) || (south && openS)) continue;
            if (onSolid(solids, x, z) || nearStair(stairs, x, z) || nearDoor(doors, x, z)
                    || hasFurniture(out, x, z)) continue;
            // the cell between the furniture and the wall stays walkable
            if ((west && (onSolid(solids, x - 1, z) || nearStair(stairs, x - 1, z)))
                    || (east && (onSolid(solids, x + 1, z) || nearStair(stairs, x + 1, z)))
                    || (north && (onSolid(solids, x, z - 1) || nearStair(stairs, x, z - 1)))
                    || (south && (onSolid(solids, x, z + 1) || nearStair(stairs, x, z + 1)))) continue;
            // no furniture along the wall on either side of this one
            if (((west || east) && (hasFurniture(out, x, z - 1) || hasFurniture(out, x, z + 1)))
                    || ((north || south)
                            && (hasFurniture(out, x - 1, z) || hasFurniture(out, x + 1, z)))) continue;
            String facing = west ? "east" : east ? "west" : north ? "south" : "north";
            out.add(new Furniture(x, z, kind, facing, variantOf(kind, x, z)));
            placed++;
        }
    }

    private void addDesks(List<Furniture> out, Room r, int want, List<Door> doors,
            List<Solid> solids, List<StairStep> stairs) {
        if (want <= 0) return;
        int w = r.x1() - r.x0() + 1;
        int d = r.z1() - r.z0() + 1;
        int area = w * d;
        int start = hashNum(r.x0(), r.z0() + 3, SALT_FURNITURE) % area;
        int placed = 0;
        for (int k = 0; k < area && placed < want; k++) {
            int idx = (start + k) % area;
            int x = r.x0() + idx % w;
            int z = r.z0() + idx / w;
            // stay off the wall ring: a gap hall may carve one cell into the room
            if (x == r.x0() + 1 || x == r.x1() - 1 || z == r.z0() + 1 || z == r.z1() - 1) continue;
            if (onSolid(solids, x, z) || nearStair(stairs, x, z) || inDoor(doors, x, z)
                    || hasFurniture(out, x, z) || touchesFurniture(out, x, z)) continue;
            boolean free = true;
            for (int n = 0; n < 4 && free; n++) {
                int nx = x + (n == 0 ? -1 : n == 1 ? 1 : 0);
                int nz = z + (n == 2 ? -1 : n == 3 ? 1 : 0);
                free = nx >= r.x0() && nx <= r.x1() && nz >= r.z0() && nz <= r.z1()
                        && !onSolid(solids, nx, nz) && !nearStair(stairs, nx, nz)
                        && !inDoor(doors, nx, nz) && !hasFurniture(out, nx, nz);
            }
            if (!free) continue;
            String facing = switch (hashNum(x, z, SALT_FURNITURE_FACING) % 4) {
                case 0 -> "north";
                case 1 -> "east";
                case 2 -> "south";
                default -> "west";
            };
            out.add(new Furniture(x, z, "desk", facing, variantOf("desk", x, z)));
            addChair(out, r, x, z, facing, doors, solids, stairs);
            placed++;
        }
    }

    private void addChair(List<Furniture> out, Room r, int x, int z, String deskFacing,
            List<Door> doors, List<Solid> solids, List<StairStep> stairs) {
        if (hashNum(x, z, SALT_FURNITURE_FACING + 1) % 100 >= FURNITURE_CHAIR_CHANCE) return;
        int dx = switch (deskFacing) {
            case "east" -> 1;
            case "west" -> -1;
            default -> 0;
        };
        int dz = switch (deskFacing) {
            case "south" -> 1;
            case "north" -> -1;
            default -> 0;
        };
        int cx = x + dx, cz = z + dz;
        if (cx < r.x0() || cx > r.x1() || cz < r.z0() || cz > r.z1()) return;
        if (onSolid(solids, cx, cz) || nearStair(stairs, cx, cz) || inDoor(doors, cx, cz)
                || hasFurniture(out, cx, cz)) return;
        String facing = switch (deskFacing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
        out.add(new Furniture(cx, cz, "chair", facing, variantOf("chair", cx, cz)));
    }

    private int variantOf(String kind, int x, int z) {
        int count = switch (kind) {
            case "desk" -> 3;
            case "chair", "cabinet", "shelf", "plant" -> 2;
            default -> 1;
        };
        return count <= 1 ? 0 : hashNum(x, z, SALT_FURNITURE_VARIANT) % count;
    }

    private static boolean onSolid(List<Solid> solids, int x, int z) {
        for (Solid s : solids) {
            if (x >= s.x0() && x <= s.x1() && z >= s.z0() && z <= s.z1()) return true;
        }
        return false;
    }

    /** Stairs claim their own cell and the eight around them. */
    private static boolean nearStair(List<StairStep> stairs, int x, int z) {
        for (StairStep st : stairs) {
            if (Math.abs(st.x() - x) <= 1 && Math.abs(st.z() - z) <= 1) return true;
        }
        return false;
    }

    /** A doorway plus one cell: a cabinet keeps two cells of clearance. */
    private static boolean nearDoor(List<Door> doors, int x, int z) {
        for (Door d : doors) {
            if (x >= d.x0() - 1 && x <= d.x1() + 1 && z >= d.z0() - 1 && z <= d.z1() + 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean inDoor(List<Door> doors, int x, int z) {
        for (Door d : doors) {
            if (x >= d.x0() && x <= d.x1() && z >= d.z0() && z <= d.z1()) return true;
        }
        return false;
    }

    private static boolean hasFurniture(List<Furniture> out, int x, int z) {
        for (Furniture f : out) {
            if (f.x() == x && f.z() == z) return true;
        }
        return false;
    }

    /** Orthogonally touching furniture: a desk never sits on a cabinet's wall run. */
    private static boolean touchesFurniture(List<Furniture> out, int x, int z) {
        for (Furniture f : out) {
            if (Math.abs(f.x() - x) + Math.abs(f.z() - z) == 1) return true;
        }
        return false;
    }

    /**
     * Street-level room that replaces a missing street segment and joins the two
     * blocks on its sides. Null when the gap stays solid.
     */
    @Nullable
    public int[] hallRect(int i, int j, boolean vertical) {
        if (vertical ? segV(i, j) : segH(i, j)) return null;
        // Both blocks must exist on the same floor, or the hole becomes a ledge.
        Block a = vertical ? block(i - 1, j) : block(i, j - 1);
        Block c = block(i, j);
        if (a == null || c == null || a.level() != c.level()) return null;
        // The plaza owns its whole district; a gap hall would cut into it.
        if (isPlaza(a.i(), a.j()) || isPlaza(c.i(), c.j())) return null;
        // Streets on the main floor stay random, raised bridges are guaranteed.
        if (a.level() == 0) {
            if (HALL_CHANCE <= 0) return null;
            if (hashNum(i * 31L + (vertical ? 1 : 2), j, SALT_HALL) % 100 >= HALL_CHANCE) return null;
        }
        int along = vertical ? streetZ(j + 1, i) - streetZ(j, i)
                : streetX(i + 1, j) - streetX(i, j);
        // Fill the whole gap between the two crossings, with a small wall at each end.
        int cap0 = vertical ? streetHalf(j) : streetHalf(i);
        int cap1 = vertical ? streetHalf(j + 1) : streetHalf(i + 1);
        int gap = along - cap0 - cap1 - 4;
        if (gap < 8) return null;
        int len = gap - hashNum(i, j * 17L, SALT_HALL) % 3;
        int start = cap0 + 2 + (gap - len) * (hashNum(i + 5L, j, SALT_HALL) % 2);
        int hx0, hx1, hz0, hz1;
        if (vertical) {
            int sx = streetX(i, j), half = streetHalf(i);
            // Two blocks deep per side: enough to reach a room behind a 2-deep wall.
            hx0 = sx - half - 2;
            hx1 = sx + half + 2;
            hz0 = streetZ(j, i) + start;
            hz1 = hz0 + len - 1;
        } else {
            int sz = streetZ(j, i), half = streetHalf(j);
            hz0 = sz - half - 2;
            hz1 = sz + half + 2;
            hx0 = streetX(i, j) + start;
            hx1 = hx0 + len - 1;
        }
        // A hall is only worth carving when it actually opens into a room on both sides.
        if (!openedInto(a, hx0, hx1, hz0, hz1) || !openedInto(c, hx0, hx1, hz0, hz1)) return null;
        return new int[] { hx0, hx1, hz0, hz1 };
    }

    private static boolean openedInto(Block b, int x0, int x1, int z0, int z1) {
        for (Room r : b.rooms()) {
            if (!(r.x1() < x0 || r.x0() > x1 || r.z1() < z0 || r.z0() > z1)) return true;
        }
        return false;
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
                for (int x = rx0 + 2; x <= rx1 - 2; x += cs) addColumn(x, zc, spawnBlock, rx0, rx1, rz0, rz1);
                if (d > 12) {
                    int z2 = zc + Math.max(4, d / 3);
                    if (z2 < rz1 - 2) for (int x = rx0 + 2; x <= rx1 - 2; x += cs)
                        addColumn(x, z2, spawnBlock, rx0, rx1, rz0, rz1);
                }
            } else {
                int xc = (rx0 + rx1) / 2 + hashNum(i, j, SALT_DETAIL) % 5 - 2;
                for (int z = rz0 + 2; z <= rz1 - 2; z += cs) addColumn(xc, z, spawnBlock, rx0, rx1, rz0, rz1);
                if (w > 12) {
                    int x2 = xc + Math.max(4, w / 3);
                    if (x2 < rx1 - 2) for (int z = rz0 + 2; z <= rz1 - 2; z += cs)
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
            int len = 2 + hashNum(i + roomIdx, j, SALT_DETAIL) % 3;
            int w = r.x1() - r.x0(), d = r.z1() - r.z0();
            Solid s = null;
            if (side == 0 && w > len + 4) {
                int x = r.x0() + 2 + hashNum(roomIdx, i, SALT_DETAIL) % (w - len - 4);
                s = new Solid(x, x, r.z0(), Math.min(r.z0() + len, r.z1() - 3));
            } else if (side == 1 && d > len + 4) {
                int z = r.z0() + 2 + hashNum(roomIdx, j, SALT_DETAIL) % (d - len - 4);
                s = new Solid(r.x1(), r.x1(), z, z + len);
            } else if (side == 2 && d > len + 4) {
                int z = r.z0() + 2 + hashNum(roomIdx, i + 7, SALT_DETAIL) % (d - len - 4);
                s = new Solid(r.x0(), r.x0(), z, z + len);
            } else if (w > len + 4) {
                int x = r.x0() + 2 + hashNum(roomIdx, j + 9, SALT_DETAIL) % (w - len - 4);
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
            int bx0, int bx1, int bz0, int bz1,
            List<String> present, boolean sn, boolean se, boolean ss, boolean sw, boolean forced) {
        Room r = new Room(ix0, ix1, iz0, iz1, "OPEN HALL");
        b.rooms.add(r);
        for (String side : present) b.streetDoor(9, r, side, bx0, bx1, bz0, bz1);
        b.columns(r, forced, ix0, ix1, iz0, iz1);
        b.partitions(r, 9);
    }

    private void buildSuite(Builder b, int ix0, int ix1, int iz0, int iz1,
            int bx0, int bx1, int bz0, int bz1, List<String> present, boolean forced) {
        Room r = new Room(ix0, ix1, iz0, iz1, "SUITE");
        b.rooms.add(r);
        for (String side : present) b.streetDoor(9, r, side, bx0, bx1, bz0, bz1);
        b.columns(r, forced, ix0, ix1, iz0, iz1);

        int sw = 6, sd = 6;
        if (ix1 - ix0 < sw + 2 || iz1 - iz0 < sd + 2) return;
        int corner = b.hashNum(b.i, b.j, SALT_DETAIL) % 4;
        int nx0 = corner == 0 || corner == 3 ? ix0 + 2 : ix1 - sw - 2;
        int nz0 = corner < 2 ? iz0 + 2 : iz1 - sd - 2;
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
            List<String> present, String primary, boolean vertical,
            int bx0, int bx1, int bz0, int bz1) {
        int cut;
        if (vertical) {
            cut = ix0 + (ix1 - ix0) / 2 + b.hashNum(b.i, b.j, SALT_DETAIL) % 5 - 2;
            cut = clampInt(cut, ix0 + 6, ix1 - 6);
            Room a = new Room(ix0, cut - 1, iz0, iz1, "MERGED ROOMS");
            Room c = new Room(cut + 1, ix1, iz0, iz1, "MERGED ROOMS");
            b.rooms.add(a);
            b.rooms.add(c);
            b.streetDoor(1, a, primary, bx0, bx1, bz0, bz1);
            b.streetDoor(2, c, primary, bx0, bx1, bz0, bz1);
            b.extraDoors(1, a, present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
            b.extraDoors(2, c, present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
            int ow = clampInt(WIDE_OPENING, 4, 10);
            int span = iz1 - iz0 - ow - 1;
            if (span > 0) {
                int z = iz0 + 1 + b.hashNum(b.i, b.j, SALT_DETAIL) % span;
                b.doors.add(new Door(cut, cut, z, z + ow - 1));
            }
            b.partitions(a, 1);
            b.partitions(c, 2);
        } else {
            cut = iz0 + (iz1 - iz0) / 2 + b.hashNum(b.i, b.j, SALT_DETAIL) % 5 - 2;
            cut = clampInt(cut, iz0 + 6, iz1 - 6);
            Room a = new Room(ix0, ix1, iz0, cut - 1, "MERGED ROOMS");
            Room c = new Room(ix0, ix1, cut + 1, iz1, "MERGED ROOMS");
            b.rooms.add(a);
            b.rooms.add(c);
            b.streetDoor(1, a, primary, bx0, bx1, bz0, bz1);
            b.streetDoor(2, c, primary, bx0, bx1, bz0, bz1);
            b.extraDoors(1, a, present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
            b.extraDoors(2, c, present, primary, ix0, ix1, iz0, iz1, bx0, bx1, bz0, bz1);
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
            int bx0, int bx1, int bz0, int bz1,
            List<String> present, boolean sn, boolean se, boolean ss, boolean sw,
            boolean vertical, String primary, boolean forced) {
        int crossLen = vertical ? ix1 - ix0 : iz1 - iz0;
        if (crossLen < 10) {
            Room r = new Room(ix0, ix1, iz0, iz1, "OPEN HALL");
            b.rooms.add(r);
            for (String side : present) b.streetDoor(9, r, side, bx0, bx1, bz0, bz1);
            b.columns(r, forced, ix0, ix1, iz0, iz1);
            return;
        }
        int corridorW = clampInt(DOOR_WIDTH + 1, 3, 5);
        int crossStart = vertical ? ix0 : iz0;
        int c0 = crossStart + 2 + b.hashNum(b.i, b.j, SALT_DETAIL) % Math.max(1, crossLen - 4 - corridorW);
        int c1 = c0 + corridorW - 1;
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
        if (x1 - x0 < 3 || z1 - z0 < 3) return;
        int longLen = alongZ ? z1 - z0 + 1 : x1 - x0 + 1;
        int n = 1 + b.hashNum(b.i + stripIdx * 17L, b.j, SALT_DETAIL) % 3;
        n = Math.min(n, Math.max(1, longLen / 6));
        List<int[]> spans = new ArrayList<>();
        if (n <= 1) {
            spans.add(new int[] { 0, longLen - 1 });
        } else {
            List<Integer> cuts = new ArrayList<>();
            int prev = -1;
            for (int k = 1; k < n; k++) {
                int base = Math.round((float) longLen * k / n);
                int jit = b.hashNum(b.i + k + stripIdx, b.j, SALT_DETAIL) % 7 - 3;
                int lo = (prev < 0 ? 0 : prev + 1) + 5;
                int hi = longLen - 5 * (n - k);
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
            if (sp[1] - sp[0] + 1 < 6) continue;
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
        if (d1 - d0 >= 9) {
            splitWall = d0 + 2 + b.hashNum(b.i + idx2, b.j + stripIdx, SALT_DETAIL)
                    % Math.max(1, d1 - d0 - 4);
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
