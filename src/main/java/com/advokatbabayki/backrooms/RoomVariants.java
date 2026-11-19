package com.advokatbabayki.backrooms;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Fifty Level 0 room variations (see backrooms-wiki.wikidot.com/level-0).
 * Every recipe carves only inside the room minus a 2-block margin, so the
 * outer shell with the hallway mouths always survives, and pits or walls
 * keep the room center (the /backrooms entry point) clear or escapable.
 */
final class RoomVariants {
    private RoomVariants() {}

    static final int FIRST = 6;
    static final int LAST = 50;

    /** Short name for the /backrooms room command. */
    static String name(int type) {
        return switch (type) {
            case 6 -> "ARCHES";
            case 7 -> "HALF-WALL";
            case 8 -> "FALSE DOORS";
            case 9 -> "FLOODED";
            case 10 -> "CARPET RIDGE";
            case 11 -> "STAIRS TO NOWHERE";
            case 12 -> "FALLEN LIGHTS";
            case 13 -> "WALL GAP";
            case 14 -> "DEBRIS";
            case 15 -> "CROSS WALLS";
            case 16 -> "RED TINT";
            case 17 -> "WIRE SPOOLS";
            case 18 -> "PEELING WALLS";
            case 19 -> "CRAWL TUNNEL";
            case 20 -> "PAPER STACKS";
            case 21 -> "GRID HOLES";
            case 22 -> "DROPPED CEILING";
            case 23 -> "BENCHES";
            case 24 -> "THRESHOLD";
            case 25 -> "WALL SCRAWL";
            case 26 -> "SLOT WINDOWS";
            case 27 -> "NEST";
            case 28 -> "PIPE CHASE";
            case 29 -> "SUNKEN SQUARE";
            case 30 -> "WALL BULGE";
            case 31 -> "CORRIDOR STUB";
            case 32 -> "MIRROR PAIR";
            case 33 -> "LEDGE WALK";
            case 34 -> "BIG CLEAN";
            case 35 -> "TWO PILLAR ISLAND";
            case 36 -> "SLIT LIGHT";
            case 37 -> "STEP ROWS";
            case 38 -> "NOOK CIRCLE";
            case 39 -> "FLOODED PIT";
            case 40 -> "KEYHOLE";
            case 41 -> "MONITOR WALL";
            case 42 -> "STEEP PIT";
            case 43 -> "LOFT SLAB";
            case 44 -> "PAPER BLIZZARD";
            case 45 -> "TIGHT GRID";
            case 46 -> "BUMP CEILING";
            case 47 -> "SOAKED RUN";
            case 48 -> "CROOKED PILLARS";
            case 49 -> "BROKEN TILES";
            case 50 -> "ENDCAP";
            default -> "PLAIN";
        };
    }

    /** Carve one variant room; runs after floor/air, before the ceiling pass. */
    static void carve(ChunkAccess chunk, ChunkPos pos, Level0Plan plan, Level0Plan.Room room,
            int type, BlockState carpet, BlockState wallpaper, BlockState ceilingTile,
            BlockState light, BlockState air, BlockState slabBottom, BlockState slabTop) {
        int h = room.floorY(); // room floor is pinned flat for the variants
        RandomSource r = RandomSource.create(hashRoom(room.cx(), room.cz(), type * 0x9E37L + 7));
        int cX = room.centerX(), cZ = room.centerZ();
        int mnx = Level0Plan.roomMinX(room) + 2, mxx = Level0Plan.roomMaxX(room) - 2;
        int mnz = Level0Plan.roomMinZ(room) + 2, mxz = Level0Plan.roomMaxZ(room) - 2;
        if (mnx > mxx || mnz > mxz) return;

        switch (type) {
            case 6 -> { // freestanding arch walls with walk-through gaps
                int rows = 2 + r.nextInt(2);
                for (int i = 0; i < rows; i++) {
                    int z = mnz + 3 + r.nextInt(Math.max(1, mxz - mnz - 6));
                    for (int x = mnx; x <= mxx; x++) {
                        if (near(x, z, cX, cZ, 2) || (x / 5 + i) % 4 == 3) continue;
                        put(chunk, pos, x, h + 1, z, wallpaper);
                        put(chunk, pos, x, h + 2, z, wallpaper);
                    }
                }
            }
            case 7 -> { // one jumpable half-height divider
                int z = mnz + 3 + r.nextInt(Math.max(1, mxz - mnz - 6));
                for (int x = mnx; x <= mxx; x++) {
                    if (near(x, z, cX, cZ, 2)) continue;
                    put(chunk, pos, x, h + 1, z, wallpaper);
                }
            }
            case 8 -> { // door-shaped niches against the walls
                for (int i = 0; i < 3 + r.nextInt(3); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    put(chunk, pos, x, h + 1, mnz, wallpaper);
                    put(chunk, pos, x, h + 2, mnz, wallpaper);
                    put(chunk, pos, x, h + 3, mnz, carpet);
                    put(chunk, pos, x, h + 1, mnz + 1, air);
                    put(chunk, pos, x, h + 2, mnz + 1, air);
                }
            }
            case 9 -> { // ankle-deep water patches
                for (int i = 0; i < 2 + r.nextInt(3); i++) {
                    int w = 2 + r.nextInt(3), d = 2 + r.nextInt(3);
                    int x0 = mnx + r.nextInt(Math.max(1, mxx - mnx - w));
                    int z0 = mnz + r.nextInt(Math.max(1, mxz - mnz - d));
                    if (rectHits(x0, z0, w, d, cX, cZ, 2)) continue;
                    for (int x = x0; x < x0 + w; x++)
                        for (int z = z0; z < z0 + d; z++) put(chunk, pos, x, h, z, water());
                }
            }
            case 10 -> { // raised carpet ridges to walk along
                int rows = 2 + r.nextInt(2);
                for (int i = 0; i < rows; i++) {
                    int z = mnz + 2 + r.nextInt(Math.max(1, mxz - mnz - 3));
                    for (int x = mnx; x <= mxx; x++) put(chunk, pos, x, h + 1, z, slabBottom);
                }
            }
            case 11 -> { // a staircase that just stops in the air
                int x = r.nextBoolean() ? mnx : mxx;
                int z = mnz + 2 + r.nextInt(Math.max(1, mxz - mnz - 8));
                int dz = r.nextBoolean() ? 1 : -1;
                for (int i = 0; i < 5; i++) {
                    put(chunk, pos, x, h + 1, z + dz * i, slabBottom);
                    put(chunk, pos, x, h + 1, z + dz * i, carpet);
                }
                for (int i = 1; i < 4; i++) {
                    put(chunk, pos, x, h + 1 + i, z + dz * (i + 4), slabBottom);
                }
            }
            case 12 -> { // fluorescent panels torn out of the ceiling, lying around
                for (int i = 0; i < 2 + r.nextInt(3); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                    if (near(x, z, cX, cZ, 2)) continue;
                    put(chunk, pos, x, h + 1, z, light);
                }
            }
            case 13 -> { // one wall row with two clean gaps
                int z = mnz + 3 + r.nextInt(Math.max(1, mxz - mnz - 6));
                int g1 = mnx + 2 + r.nextInt(Math.max(1, (mxx - mnx) / 2 - 2));
                int g2 = (mnx + mxx) / 2 + 2 + r.nextInt(Math.max(1, (mxx - mnx) / 2 - 2));
                for (int x = mnx; x <= mxx; x++) {
                    if (near(x, z, cX, cZ, 2) || (x >= g1 && x < g1 + 3) || (x >= g2 && x < g2 + 3)) continue;
                    put(chunk, pos, x, h + 1, z, wallpaper);
                    put(chunk, pos, x, h + 2, z, wallpaper);
                }
            }
            case 14 -> { // scattered half-blocks of building trash
                for (int i = 0; i < 8 + r.nextInt(7); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                    if (near(x, z, cX, cZ, 2)) continue;
                    put(chunk, pos, x, h + 1, z, r.nextBoolean() ? slabBottom : slabTop);
                }
            }
            case 15 -> { // two crossing half-walls, gaps at every arm
                int zm = (mnz + mxz) / 2, xm = (mnx + mxx) / 2;
                for (int x = mnx; x <= mxx; x++) {
                    if (near(x, zm, cX, cZ, 3) || ((x - mnx) / 4) % 2 == 1) continue;
                    put(chunk, pos, x, h + 1, zm, wallpaper);
                }
                for (int z = mnz; z <= mxz; z++) {
                    if (near(xm, z, cX, cZ, 3) || ((z - mnz) / 4) % 2 == 1) continue;
                    put(chunk, pos, xm, h + 1, z, wallpaper);
                }
            }
            case 16 -> { // a patch of red - the level peeling through
                int w = 2 + r.nextInt(2), d = 1 + r.nextInt(2);
                int x0 = r.nextBoolean() ? mnx : mxx - w + 1;
                int z0 = mnz + r.nextInt(Math.max(1, mxz - mnz - d));
                for (int x = x0; x < x0 + w; x++)
                    for (int z = z0; z < z0 + d; z++) {
                        put(chunk, pos, x, h + 1, z, redWallpaper());
                        put(chunk, pos, x, h + 2, z, redWallpaper());
                    }
            }
            case 17 -> { // cable spools: stacked slab pairs
                for (int i = 0; i < 3 + r.nextInt(4); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                    if (near(x, z, cX, cZ, 2)) continue;
                    put(chunk, pos, x, h + 1, z, slabBottom);
                    put(chunk, pos, x, h + 1, z, slabTop);
                }
            }
            case 18 -> { // wallpaper hanging off the walls in strips
                for (int i = 0; i < 5 + r.nextInt(6); i++) {
                    int z = r.nextBoolean() ? mnz : mxz;
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    put(chunk, pos, x, h + 2, z, slabTop);
                    if (r.nextBoolean()) put(chunk, pos, x, h + 1, z, slabTop);
                }
            }
            case 19 -> { // a low tunnel across the room, roof of slabs
                int z = mnz + 3 + r.nextInt(Math.max(1, mxz - mnz - 6));
                int len = Math.min(8, mxx - mnx);
                int x0 = mnx + r.nextInt(Math.max(1, mxx - mnx - len));
                for (int x = x0; x < x0 + len; x++) {
                    put(chunk, pos, x, h + 3, z, slabBottom);
                    put(chunk, pos, x, h + 1, z - 1, wallpaper);
                    put(chunk, pos, x, h + 2, z - 1, wallpaper);
                    put(chunk, pos, x, h + 1, z + 1, wallpaper);
                    put(chunk, pos, x, h + 2, z + 1, wallpaper);
                }
            }
            case 20 -> { // piles of paper sheets
                for (int i = 0; i < 4 + r.nextInt(5); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                    if (near(x, z, cX, cZ, 2)) continue;
                    int height = 1 + r.nextInt(3);
                    for (int y = 1; y <= height; y++) put(chunk, pos, x, h + y, z, carpet);
                }
            }
            case 21 -> { // neat grid of small pits with escape steps
                for (int gx = mnx + 2; gx < mxx - 1; gx += 4)
                    for (int gz = mnz + 2; gz < mxz - 1; gz += 4) {
                        if (rectHits(gx, gz, 2, 2, cX, cZ, 3)) continue;
                        pit2(chunk, pos, gx, gz, 2, 2, h, air, carpet);
                    }
            }
            case 22 -> { // a dropped ceiling patch over part of the room
                int w = (mxx - mnx) / 2 + 1, d = (mxz - mnz) / 2 + 1;
                int x0 = r.nextBoolean() ? mnx : mxx - w + 1;
                int z0 = r.nextBoolean() ? mnz : mxz - d + 1;
                for (int x = x0; x < x0 + w; x++)
                    for (int z = z0; z < z0 + d; z++) put(chunk, pos, x, h + 3, z, ceilingTile);
            }
            case 23 -> { // sitting-height benches in rows
                int rows = 1 + r.nextInt(3);
                for (int i = 0; i < rows; i++) {
                    int z = mnz + 2 + i * 3 + r.nextInt(2);
                    if (z > mxz) break;
                    for (int x = mnx + 1; x < mxx; x++) put(chunk, pos, x, h + 1, z, slabTop);
                }
            }
            case 24 -> { // raised threshold strip at one edge
                int z = r.nextBoolean() ? mnz + 1 : mxz - 1;
                for (int x = mnx; x <= mxx; x++) {
                    put(chunk, pos, x, h + 1, z, slabBottom);
                }
            }
            case 25 -> { // strange marks on the wall band
                for (int i = 0; i < 3 + r.nextInt(4); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    int z = r.nextBoolean() ? mnz : mxz;
                    put(chunk, pos, x, h + 2, z, slabBottom);
                }
            }
            case 26 -> { // wall with see-through slots at eye height
                int z = mnz + 3 + r.nextInt(Math.max(1, mxz - mnz - 6));
                for (int x = mnx; x <= mxx; x++) {
                    if (near(x, z, cX, cZ, 2)) continue;
                    put(chunk, pos, x, h + 1, z, wallpaper);
                    if ((x / 2) % 2 == 1) continue;
                    put(chunk, pos, x, h + 2, z, wallpaper);
                }
            }
            case 27 -> { // a nest of carpet blocks in a corner
                int cx0 = r.nextBoolean() ? mnx : mxx - 2;
                int cz0 = r.nextBoolean() ? mnz : mxz - 2;
                for (int x = cx0; x < cx0 + 3; x++)
                    for (int z = cz0; z < cz0 + 3; z++) {
                        int height = 1 + r.nextInt(2);
                        for (int y = 1; y <= height; y++) put(chunk, pos, x, h + y, z, carpet);
                    }
            }
            case 28 -> { // pipes run along one wall at slab height
                int z = r.nextBoolean() ? mnz : mxz;
                for (int x = mnx; x <= mxx; x++)
                    put(chunk, pos, x, h + 2, z, slabBottom);
            }
            case 29 -> { // sunken square off the center with a step out
                int w = 4, x0 = cX + (r.nextBoolean() ? 4 : -4 - w);
                int z0 = cZ + (r.nextBoolean() ? 4 : -4 - w);
                if (x0 < mnx || z0 < mnz || x0 + w > mxx || z0 + w > mxz) return;
                pit2(chunk, pos, x0, z0, w, w, h, air, carpet);
            }
            case 30 -> { // the wall bulges into the room in bumps
                for (int i = 0; i < 4 + r.nextInt(4); i++) {
                    int x = r.nextBoolean() ? mnx : mxx;
                    int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                    put(chunk, pos, x, h + 1, z, wallpaper);
                    if (r.nextBoolean()) put(chunk, pos, x, h + 2, z, wallpaper);
                }
            }
            case 31 -> { // a dead-end stub of hallway inside the room
                int z = mnz + 3 + r.nextInt(Math.max(1, mxz - mnz - 6));
                int len = 4 + r.nextInt(3);
                int x0 = r.nextBoolean() ? mnx : mxx - len;
                for (int x = x0; x < x0 + len; x++) {
                    put(chunk, pos, x, h + 1, z - 2, wallpaper);
                    put(chunk, pos, x, h + 2, z - 2, wallpaper);
                    put(chunk, pos, x, h + 3, z - 2, ceilingTile);
                    put(chunk, pos, x, h + 1, z + 2, wallpaper);
                    put(chunk, pos, x, h + 2, z + 2, wallpaper);
                    put(chunk, pos, x, h + 3, z - 1, ceilingTile);
                    put(chunk, pos, x, h + 3, z, ceilingTile);
                    put(chunk, pos, x, h + 3, z + 1, ceilingTile);
                }
                put(chunk, pos, x0 + len - 1, h + 3, z, light);
            }
            case 32 -> { // two parallel walls with aligned windows
                int z1 = mnz + 2 + r.nextInt(Math.max(1, mxz - mnz - 8));
                int z2 = z1 + 4;
                for (int x = mnx; x <= mxx; x++) {
                    if (near(x, z1, cX, cZ, 2) || (x / 5) % 3 == 2) {
                        // gap
                    } else {
                        put(chunk, pos, x, h + 1, z1, wallpaper);
                        put(chunk, pos, x, h + 2, z1, wallpaper);
                        put(chunk, pos, x, h + 1, z2, wallpaper);
                        put(chunk, pos, x, h + 2, z2, wallpaper);
                    }
                }
            }
            case 33 -> { // a ledge along the wall with two steps up
                int z = r.nextBoolean() ? mnz + 1 : mxz - 1;
                for (int x = mnx; x <= mxx; x++) {
                    put(chunk, pos, x, h + 2, z, slabBottom);
                }
                int sx = mnx + r.nextInt(Math.max(1, mxx - mnx));
                put(chunk, pos, sx, h + 1, z, carpet);
                put(chunk, pos, sx, h + 1, z + (z == mnz + 1 ? 1 : -1), slabBottom);
            }
            case 34 -> { // nothing at all - an eerily clean hall
            }
            case 35 -> { // two short pillars and a slab ring
                int x1 = cX - 3, x2 = cX + 3, z = cZ + (r.nextBoolean() ? 3 : -3);
                for (int y = 1; y <= 3; y++) {
                    put(chunk, pos, x1, h + y, z, wallpaper);
                    put(chunk, pos, x2, h + y, z, wallpaper);
                }
                for (int x = x1; x <= x2; x++) put(chunk, pos, x, h + 1, z + (r.nextBoolean() ? 1 : -1), slabBottom);
            }
            case 36 -> { // a single slit of lights across the ceiling
                int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                for (int x = mnx; x <= mxx; x += 2) put(chunk, pos, x, h + 4, z, light);
            }
            case 37 -> { // terraces climbing one side of the room
                int steps = Math.min(3, (mxx - mnx) / 4);
                for (int i = 1; i <= steps; i++) {
                    int x0 = mnx + (i - 1) * 4;
                    for (int x = x0; x < x0 + 4 && x <= mxx; x++)
                        for (int z = mnz; z <= mxz; z++)
                            for (int y = 1; y <= i; y++) put(chunk, pos, x, h + y, z, carpet);
                }
            }
            case 38 -> { // curved nook wall with a gap to slip through
                int x0 = r.nextBoolean() ? mnx : mxx - 5;
                int z0 = r.nextBoolean() ? mnz : mxz - 5;
                for (int i = 0; i < 6; i++) {
                    int x = x0 + (i < 3 ? i : 5);
                    int z = z0 + (i < 3 ? 0 : i - 3);
                    if (near(x, z, cX, cZ, 2) || i == 2 || i == 3) continue;
                    put(chunk, pos, x, h + 1, z, wallpaper);
                    put(chunk, pos, x, h + 2, z, wallpaper);
                }
            }
            case 39 -> { // a pit, and it has flooded
                int w = 3 + r.nextInt(2), d = 3 + r.nextInt(2);
                int x0 = cX + (r.nextBoolean() ? 4 : -4 - w);
                int z0 = cZ + (r.nextBoolean() ? 4 : -4 - d);
                if (x0 < mnx || z0 < mnz || x0 + w > mxx || z0 + d > mxz) return;
                pit2(chunk, pos, x0, z0, w, d, h, air, carpet);
                for (int x = x0; x < x0 + w; x++)
                    for (int z = z0 + 1; z < z0 + d; z++) put(chunk, pos, x, h - 1, z, water());
            }
            case 40 -> { // wall with one keyhole-shaped opening
                int z = mnz + 3 + r.nextInt(Math.max(1, mxz - mnz - 6));
                int kx = mnx + 2 + r.nextInt(Math.max(1, mxx - mnx - 4));
                for (int x = mnx; x <= mxx; x++) {
                    if (x == kx) continue;
                    put(chunk, pos, x, h + 1, z, wallpaper);
                    put(chunk, pos, x, h + 2, z, wallpaper);
                    if (near(x, z, cX, cZ, 2)) put(chunk, pos, x, h + 1, z, air);
                }
                put(chunk, pos, kx, h + 3, z, slabBottom);
            }
            case 41 -> { // dead screens embedded in a gapped wall
                int z = mnz + 3 + r.nextInt(Math.max(1, mxz - mnz - 6));
                for (int x = mnx; x <= mxx; x++) {
                    if (near(x, z, cX, cZ, 2) || (x / 6) % 2 == 1) continue;
                    put(chunk, pos, x, h + 1, z, wallpaper);
                    put(chunk, pos, x, h + 2, z, x % 3 == 0 ? light : wallpaper);
                }
            }
            case 42 -> { // one deep pit with a climbable corner
                int w = 3 + r.nextInt(2), d = 3 + r.nextInt(2);
                int x0 = cX + (r.nextBoolean() ? 4 : -4 - w);
                int z0 = cZ + (r.nextBoolean() ? 4 : -4 - d);
                if (x0 < mnx || z0 < mnz || x0 + w > mxx || z0 + d > mxz) return;
                for (int x = x0; x < x0 + w; x++)
                    for (int z = z0; z < z0 + d; z++) {
                        for (int y = 0; y >= -2; y--) put(chunk, pos, x, h + y, z, air);
                    }
                put(chunk, pos, x0, h - 2, z0, slabBottom);
                put(chunk, pos, x0, h - 1, z0, slabTop);
            }
            case 43 -> { // a small loft slab with slab steps up
                int w = 3 + r.nextInt(2);
                int x0 = r.nextBoolean() ? mnx : mxx - w;
                int z0 = r.nextBoolean() ? mnz : mxz - w;
                for (int x = x0; x < x0 + w; x++)
                    for (int z = z0; z < z0 + w; z++) put(chunk, pos, x, h + 3, z, slabBottom);
                put(chunk, pos, x0, h + 1, z0 + w + 1, slabBottom);
                put(chunk, pos, x0, h + 1, z0 + w + 1, carpet);
                put(chunk, pos, x0, h + 2, z0 + w + 2, carpet);
            }
            case 44 -> { // paper hanging mid-air everywhere
                for (int i = 0; i < 18 + r.nextInt(12); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                    int y = h + 2 + r.nextInt(2);
                    put(chunk, pos, x, y, z, r.nextBoolean() ? slabBottom : slabTop);
                }
            }
            case 45 -> { // dense pillar grid with aisles
                for (int x = mnx + 1; x <= mxx - 1; x += 2)
                    for (int z = mnz + 1; z <= mxz - 1; z += 2) {
                        if (near(x, z, cX, cZ, 2) || (z / 3) % 2 == 1) continue;
                        put(chunk, pos, x, h + 1, z, wallpaper);
                        put(chunk, pos, x, h + 2, z, wallpaper);
                    }
            }
            case 46 -> { // ceiling presses down in a corner patch
                int w = (mxx - mnx) / 2, d = (mxz - mnz) / 2;
                int x0 = r.nextBoolean() ? mnx : mxx - w;
                int z0 = r.nextBoolean() ? mnz : mxz - d;
                for (int x = x0; x < x0 + w; x++)
                    for (int z = z0; z < z0 + d; z++)
                        for (int y = 2; y <= 3; y++) put(chunk, pos, x, h + y, z, ceilingTile);
            }
            case 47 -> { // one long soaked strip to wade through
                int z = mnz + 2 + r.nextInt(Math.max(1, mxz - mnz - 3));
                for (int x = mnx; x <= mxx; x++) put(chunk, pos, x, h, z, water());
            }
            case 48 -> { // pillars that stopped halfway to the ceiling
                for (int i = 0; i < 5 + r.nextInt(4); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                    if (near(x, z, cX, cZ, 2)) continue;
                    int height = 1 + r.nextInt(3);
                    for (int y = 1; y <= height; y++) put(chunk, pos, x, h + y, z, wallpaper);
                }
            }
            case 49 -> { // ceiling tiles sagging on threads
                for (int i = 0; i < 4 + r.nextInt(5); i++) {
                    int x = mnx + r.nextInt(Math.max(1, mxx - mnx + 1));
                    int z = mnz + r.nextInt(Math.max(1, mxz - mnz + 1));
                    put(chunk, pos, x, h + 3, z, ceilingTile);
                    if (r.nextBoolean()) put(chunk, pos, x, h + 2, z, ceilingTile);
                }
            }
            case 50 -> { // the far end of everything: lit dead-end alcove
                int x0 = r.nextBoolean() ? mnx : mxx;
                for (int i = 0; i < 5; i++) {
                    int x = x0 + (x0 == mnx ? i : -i);
                    int z = cZ - 2 + i % 5;
                    put(chunk, pos, x, h + 1, z, wallpaper);
                    put(chunk, pos, x, h + 2, z, wallpaper);
                }
                put(chunk, pos, x0, h + 3, cZ, light);
            }
            default -> { }
        }
    }

    // ---- helpers ----

    private static void put(ChunkAccess chunk, ChunkPos pos, int x, int y, int z, BlockState s) {
        if (x >= pos.getMinBlockX() && x <= pos.getMaxBlockX()
                && z >= pos.getMinBlockZ() && z <= pos.getMaxBlockZ()) {
            chunk.setBlockState(new BlockPos(x, y, z), s, false);
        }
    }

    private static boolean near(int x, int z, int cX, int cZ, int d) {
        return Math.abs(x - cX) <= d && Math.abs(z - cZ) <= d;
    }

    private static boolean rectHits(int x0, int z0, int w, int d, int cX, int cZ, int m) {
        return x0 <= cX + m && x0 + w - 1 >= cX - m && z0 <= cZ + m && z0 + d - 1 >= cZ - m;
    }

    /** Depth-2 pit whose corner column keeps a step block, so you can jump out. */
    private static void pit2(ChunkAccess chunk, ChunkPos pos, int x0, int z0, int w, int d,
            int h, BlockState air, BlockState carpet) {
        for (int x = x0; x < x0 + w; x++)
            for (int z = z0; z < z0 + d; z++) {
                if (x == x0 && z == z0) continue; // escape corner
                put(chunk, pos, x, h, z, air);
                put(chunk, pos, x, h - 1, z, air);
            }
        put(chunk, pos, x0, h - 1, z0, carpet); // the step out
    }

    private static long hashRoom(int cx, int cz, long salt) {
        long v = (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xBF58476D1CE4E5B9L) ^ (salt * 0x94D049BB133111EBL);
        v ^= v >>> 29;
        v *= 0x165667B19E3779F9L;
        v ^= v >>> 32;
        return v;
    }

    private static BlockState water() {
        return Blocks.WATER.defaultBlockState();
    }

    private static BlockState redWallpaper() {
        return BackroomsBlocks.RED_WALLPAPER.get().defaultBlockState();
    }
}
