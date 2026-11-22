/*
 * Level 0 preview core: an axis-aligned office floor plan.
 * Streets drift sideways every segment instead of running dead straight;
 * blocks between them get walls of uneven depth, and a missing segment can
 * turn into a hall that joins the two blocks on its sides. Keep
 * DEFAULT_CONFIG in sync with Level0Plan.java.
 * Works in the browser (window.Level0Core) and in node (module.exports).
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.Level0Core = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MASK = (1n << 64n) - 1n;
  const K1 = 0x9E3779B97F4A7C15n;
  const K2 = 0xBF58476D1CE4E5B9n;
  const K3 = 0x94D049BB133111EBn;
  const K4 = 0x165667B19E3779F9n;
  const K5 = 0x27D4EB2F165667C5n;

  const SALT_SX = 0x1A2B3C4D5E6F7081n;
  const SALT_SZ = 0x2B3C4D5E6F708192n;
  const SALT_SW = 0x3C4D5E6F708192A3n;
  const SALT_SEGV = 0x4D5E6F708192A3B4n;
  const SALT_SEGH = 0x5E6F708192A3B4C5n;
  const SALT_BLOCK = 0x6F708192A3B4C5D6n;
  const SALT_DOOR = 0x708192A3B4C5D6E7n;
  const SALT_DETAIL = 0x8192A3B4C5D6E7F8n;
  const SALT_LEVEL = 0x92A3B4C5D6E7F809n;
  const SALT_WANDER_X = 0xA3B4C5D6E7F8091An;
  const SALT_WANDER_Z = 0xB4C5D6E7F8091A2Bn;
  const SALT_WALL = 0xC5D6E7F8091A2B3Cn;
  const SALT_HALL = 0xD6E7F8091A2B3C4Dn;
  const SALT_LIGHT = 0xE7F8091A2B3C4D5En;
  const SALT_CEIL = 0xF8091A2B3C4D5E6Fn;
  const SALT_NICHE = 0x091A2B3C4D5E6F70n;
  const SALT_PLAZA = 0x13579BDF2468ACE0n;
  const SALT_STREET_FIX = 0x2468ACE013579BDFn;
  const SALT_FURNITURE = 0x0F1E2D3C4B5A6978n;
  const SALT_FURNITURE_VARIANT = 0x58A4C6E80B1D3F27n;
  const SALT_FURNITURE_FACING = 0x6C2E4A8B0D1F3579n;
  const SALT_PILE = 0x3A5C7E9B1D2F4068n;

  const EMPTY = -32768;
  const BASE_FLOOR_Y = 64;
  const MAX_FLOOR_Y = 88;
  const LIGHT_EVERY = 12;
  const DARK_DISTRICT = 48;

  const DEFAULT_CONFIG = Object.freeze({
    seed: 0,
    pitch: 40,
    jitter: 9,
    streetWidthMax: 5,
    segmentChance: 65,
    blockChance: 100,
    doorWidth: 3,
    wideOpening: 4,
    columnSpacing: 4,
    partitionChance: 40,
    furnitureCabinetChance: 60,
    furnitureCabinetMax: 2,
    furnitureDeskChance: 65,
    furnitureDeskMax: 2,
    furnitureShelfChance: 35,
    furnitureChairChance: 85,
    furnitureExtraChance: 55,
    furniturePileChance: 3,
    furniturePileMinRoom: 13,
    districtSize: 2,
    raisedChance: 30,
    levelHeight: 3,
    wander: 2,
    wallOpen: 35,
    wallThick: 10,
    hallChance: 75,
    darkChance: 25,
    deadLightChance: 25,
    hallCeilMax: 8,
    falseDoorChance: 12,
    plazaChance: 4,
    plazaColSpacing: 14
  });

  const LAYOUT_NAMES = {
    hall: "OPEN HALL",
    offices: "OFFICES",
    suite: "SUITE",
    merge: "MERGED ROOMS",
    plaza: "PLAZA"
  };

  let CFG = Object.assign({}, DEFAULT_CONFIG);
  let SEED = 0n;
  let F = null;
  let SPAWN_I = 0, SPAWN_J = 0;
  let blockCache = new Map();

  function clampInt(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  function hash64(x, z, salt) {
    let h = (SEED * 31n + salt) & MASK;
    h = (h * K1 + BigInt(x) * K2) & MASK;
    h ^= h >> 29n;
    h = (h * K3 + BigInt(z) * K4) & MASK;
    h ^= h >> 32n;
    h = (h * K5) & MASK;
    h ^= h >> 31n;
    return h;
  }

  const STREET_HALF = BASE_FLOOR_Y * 2;

  /** Half-block floor of a whole height level. Level 0 is y64. */
  function levelHalf(level) {
    return STREET_HALF + 2 * CFG.levelHeight * level;
  }

  /** District level: most blocks 0, some whole districts +1/-1. */
  function blockLevel(i, j) {
    if (i === SPAWN_I && j === SPAWN_J) return 0;
    const di = Math.floor(i / CFG.districtSize);
    const dj = Math.floor(j / CFG.districtSize);
    const v = Number(hash64(di, dj, SALT_LEVEL) % 100n);
    if (v < Math.floor(CFG.raisedChance / 2)) return 1;
    if (v < CFG.raisedChance) return -1;
    return 0;
  }

  /** A whole district that one open hall replaces: level 0 only, never at spawn. */
  function plazaDistrict(di, dj) {
    if (di === Math.floor(SPAWN_I / CFG.districtSize)
      && dj === Math.floor(SPAWN_J / CFG.districtSize)) return false;
    if (blockLevel(di * CFG.districtSize, dj * CFG.districtSize) !== 0) return false;
    return Number(hash64(di, dj, SALT_PLAZA) % 100n) < BigInt(CFG.plazaChance);
  }

  function inPlaza(i, j) {
    return plazaDistrict(Math.floor(i / CFG.districtSize), Math.floor(j / CFG.districtSize));
  }

  function specialBlock(i, j) {
    return blockLevel(i, j) !== 0 || inPlaza(i, j);
  }

  // One external border per axis: the other one lies inside the district.
  function externalV(i) {
    return Math.floor((i - 1) / CFG.districtSize) === Math.floor(i / CFG.districtSize) ? i + 1 : i;
  }

  function externalH(j) {
    return Math.floor((j - 1) / CFG.districtSize) === Math.floor(j / CFG.districtSize) ? j + 1 : j;
  }

  // ---- Streets ----

  function streetJitter(i, salt) {
    const range = 2 * CFG.jitter + 1;
    return Number(hash64(i, 0, salt) % BigInt(range)) - CFG.jitter;
  }

  /** Side step of a street at one segment: it drifts instead of running straight. */
  function streetWander(idx, seg, salt) {
    if (!CFG.wander) return 0;
    return Number(hash64(idx, seg, salt) % BigInt(2 * CFG.wander + 1)) - CFG.wander;
  }

  function streetX(i, seg) {
    return i * CFG.pitch + (CFG.jitter > 0 ? streetJitter(i, SALT_SX) : 0)
      + streetWander(i, seg, SALT_WANDER_X);
  }

  function streetZ(j, seg) {
    return j * CFG.pitch + (CFG.jitter > 0 ? streetJitter(j, SALT_SZ) : 0)
      + streetWander(j, seg, SALT_WANDER_Z);
  }

  function streetWidth(i) {
    const maxSteps = Math.max(0, Math.floor((CFG.streetWidthMax - 3) / 2));
    return 3 + 2 * Number(hash64(i, 7, SALT_SW) % BigInt(maxSteps + 1));
  }

  function streetHalf(i) {
    return Math.floor(streetWidth(i) / 2);
  }

  /** Vertical street i, segment between horizontal streets j and j+1. */
  function segV(i, j) {
    if ((i === SPAWN_I || i === SPAWN_I + 1) && j === SPAWN_J) return true;
    if (Math.floor((i - 1) / CFG.districtSize) === Math.floor(i / CFG.districtSize)
      && sameRaisedLevel(i - 1, j, i, j)) return false;
    if (Math.floor((i - 1) / CFG.districtSize) === Math.floor(i / CFG.districtSize)
      && inPlaza(i - 1, j) && inPlaza(i, j)) return false;
    if (segRawV(i, j)) return true;
    return streetFixV(i, j);
  }

  /** Horizontal street j, segment between vertical streets i and i+1. */
  function segH(i, j) {
    if (i === SPAWN_I && (j === SPAWN_J || j === SPAWN_J + 1)) return true;
    if (Math.floor((j - 1) / CFG.districtSize) === Math.floor(j / CFG.districtSize)
      && sameRaisedLevel(i, j - 1, i, j)) return false;
    if (Math.floor((j - 1) / CFG.districtSize) === Math.floor(j / CFG.districtSize)
      && inPlaza(i, j - 1) && inPlaza(i, j)) return false;
    if (segRawH(i, j)) return true;
    return streetFixH(i, j);
  }

  function sameRaisedLevel(ai, aj, bi, bj) {
    const level = blockLevel(ai, aj);
    return level !== 0 && level === blockLevel(bi, bj);
  }

  function segRawV(i, j) {
    return Number(hash64(i, j, SALT_SEGV) % 100n) < BigInt(CFG.segmentChance);
  }

  function segRawH(i, j) {
    return Number(hash64(i, j, SALT_SEGH) % 100n) < BigInt(CFG.segmentChance);
  }

  // A block whose two outer borders both closed would have no street door at
  // all, so it would vanish and tear a hole in its district. Reopen one border.
  function fixVerticalAt(i, j, borderV) {
    if (!specialBlock(i, j) || externalV(i) !== borderV) return false;
    if (segRawV(borderV, j) || segRawH(i, externalH(j))) return false;
    return Number(hash64(i, j, SALT_STREET_FIX) % 2n) === 0;
  }

  function fixHorizontalAt(i, j, borderH) {
    if (!specialBlock(i, j) || externalH(j) !== borderH) return false;
    if (segRawH(i, borderH) || segRawV(externalV(i), j)) return false;
    return Number(hash64(i, j, SALT_STREET_FIX) % 2n) !== 0;
  }

  function streetFixV(v, j) {
    return fixVerticalAt(v - 1, j, v) || fixVerticalAt(v, j, v);
  }

  function streetFixH(i, h) {
    return fixHorizontalAt(i, h - 1, h) || fixHorizontalAt(i, h, h);
  }

  function streetRange(min, max) {
    const pad = CFG.jitter + CFG.wander + Math.floor(CFG.streetWidthMax / 2) + 4;
    return [
      Math.floor((min - pad) / CFG.pitch) - 1,
      Math.ceil((max + pad) / CFG.pitch) + 1
    ];
  }

  function spawnCell() {
    const c = blockAt(0, 0);
    SPAWN_I = c.i;
    SPAWN_J = c.j;
  }

  function blockAt(x, z) {
    let i = Math.floor(x / CFG.pitch);
    let j = Math.floor(z / CFG.pitch);
    // Street offsets depend on the other axis, so settle the cell in rounds.
    for (let k = 0; k < 6; k++) {
      while (streetZ(j, i) > z) j--;
      while (streetZ(j + 1, i) <= z) j++;
      while (streetX(i, j) > x) i--;
      while (streetX(i + 1, j) <= x) i++;
    }
    return { i, j };
  }

  // ---- Blocks and rooms ----

  function blockBounds(i, j) {
    return {
      x0: streetX(i, j) + streetHalf(i) + 1,
      x1: streetX(i + 1, j) - streetHalf(i + 1) - 1,
      z0: streetZ(j, i) + streetHalf(j) + 1,
      z1: streetZ(j + 1, i) - streetHalf(j + 1) - 1
    };
  }

  function blockSides(i, j) {
    return {
      n: segH(i, j),
      e: segV(i + 1, j),
      s: segH(i, j + 1),
      w: segV(i, j)
    };
  }

  function hashNum(a, b, salt) {
    return Number(hash64(a, b, salt) % 1000000n);
  }

  /** Tall halls: half get no lift, the rest rise by 2, 4, 6 or 8 blocks. */
  function hallCeilExtra(i, j, layout) {
    if (layout !== "hall") return 0;
    const r = hashNum(i, j, SALT_CEIL);
    const extra = r % 2 === 0 ? 0 : 2 * (1 + Math.floor(r / 2) % 4);
    return Math.min(extra, CFG.hallCeilMax);
  }

  /**
   * Rare 2x2 district replaced by one open hall. Every block carves its own
   * slice extended to the neighbouring slice, so the four slices join into one
   * room; internal street strips are crossed by plain overlap.
   */
  function plazaInfo(i, j, b, sides, present) {
    const di = Math.floor(i / CFG.districtSize);
    const dj = Math.floor(j / CFG.districtSize);
    const westIn = Math.floor((i - 1) / CFG.districtSize) === di;
    const eastIn = Math.floor((i + 1) / CFG.districtSize) === di;
    const northIn = Math.floor((j - 1) / CFG.districtSize) === dj;
    const southIn = Math.floor((j + 1) / CFG.districtSize) === dj;
    // One cell past the neighbour's outer wall line: the wall itself stays.
    const x0 = westIn ? streetX(i, j) - streetHalf(i) - 2 : b.x0 + 1;
    const x1 = eastIn ? streetX(i + 2, j) - streetHalf(i + 2) - 2 : b.x1 - 1;
    const z0 = northIn ? streetZ(j, i) - streetHalf(j) - 2 : b.z0 + 1;
    const z1 = southIn ? streetZ(j + 2, i) - streetHalf(j + 2) - 2 : b.z1 - 1;
    const rooms = [{ x0, x1, z0, z1, name: "PLAZA" }];
    const doors = [];
    const solids = [];
    const doorW = clampInt(CFG.doorWidth, 2, 5);
    const hashDoor = (side) => hash64(i, j * 97 + 9 * 11 + side, SALT_DOOR);
    for (const side of present) {
      if (side === "n" || side === "s") {
        const span = b.x1 - b.x0 - doorW - 1;
        if (span < 1) continue;
        const x = b.x0 + 1 + Number(hashDoor(side === "n" ? 1 : 2) % BigInt(span));
        const z = side === "n" ? b.z0 : b.z1;
        doors.push({ x0: x, x1: x + doorW - 1, z0: z, z1: z });
      } else {
        const span = b.z1 - b.z0 - doorW - 1;
        if (span < 1) continue;
        const z = b.z0 + 1 + Number(hashDoor(side === "e" ? 3 : 4) % BigInt(span));
        const x = side === "e" ? b.x1 : b.x0;
        doors.push({ x0: x, x1: x, z0: z, z1: z + doorW - 1 });
      }
    }
    // Columns live on one district-wide grid; each block keeps its own share.
    const spacing = Math.max(4, CFG.plazaColSpacing);
    const baseX = di * CFG.districtSize * CFG.pitch + 7;
    const baseZ = dj * CFG.districtSize * CFG.pitch + 7;
    const mx0 = Math.ceil((b.x0 - baseX) / spacing);
    const mx1 = Math.floor((b.x1 - baseX) / spacing);
    const mz0 = Math.ceil((b.z0 - baseZ) / spacing);
    const mz1 = Math.floor((b.z1 - baseZ) / spacing);
    for (let mx = mx0; mx <= mx1; mx++) {
      for (let mz = mz0; mz <= mz1; mz++) {
        const x = baseX + mx * spacing;
        const z = baseZ + mz * spacing;
        if (hashNum(x, z, SALT_PLAZA) % 100 < 80) {
          solids.push({ x0: x, x1: x, z0: z, z1: z });
        }
      }
    }
    addFalseDoor(i, j, b, sides, present, rooms, doors, solids, []);
    return {
      i, j, x0: b.x0, x1: b.x1, z0: b.z0, z1: b.z1,
      layout: "plaza", level: 0, ceilExtra: CFG.hallCeilMax,
      rooms, doors, solids, stairs: [], forced: false,
      furniture: furnitureOf(i, j, b, rooms, doors, solids, [], false)
    };
  }

  /** A fake doorway in a solid side wall, with a one- or two-block pocket behind it. */
  function addFalseDoor(i, j, b, sides, present, rooms, doors, solids, stairs) {
    if (CFG.falseDoorChance <= 0) return;
    if (hashNum(i, j, SALT_NICHE) % 100 >= CFG.falseDoorChance) return;
    const absent = ["n", "e", "s", "w"].filter(k => !sides[k]);
    if (!absent.length) return;
    const side = absent[hashNum(i + 3, j + 5, SALT_NICHE) % absent.length];
    const depth = 1 + hashNum(i + 7, j + 9, SALT_NICHE) % 2;
    const doorW = clampInt(CFG.doorWidth, 2, 5);
    let d, niche;
    if (side === "n" || side === "s") {
      const span = b.x1 - b.x0 - doorW - 1;
      if (span < 1) return;
      const x = b.x0 + 1 + hashNum(i + 11, j + 13, SALT_NICHE) % span;
      const wallZ = side === "n" ? b.z0 : b.z1;
      d = { x0: x, x1: x + doorW - 1, z0: wallZ, z1: wallZ };
      const nz0 = side === "n" ? b.z0 - depth : b.z1 + 1;
      niche = { x0: x - 1, x1: x + doorW, z0: nz0, z1: nz0 + depth - 1, name: "NICHE" };
    } else {
      const span = b.z1 - b.z0 - doorW - 1;
      if (span < 1) return;
      const z = b.z0 + 1 + hashNum(i + 11, j + 13, SALT_NICHE) % span;
      const wallX = side === "w" ? b.x0 : b.x1;
      d = { x0: wallX, x1: wallX, z0: z, z1: z + doorW - 1 };
      const nx0 = side === "w" ? b.x0 - depth : b.x1 + 1;
      niche = { x0: nx0, x1: nx0 + depth - 1, z0: z - 1, z1: z + doorW, name: "NICHE" };
    }
    // never join an existing doorway
    if (doors.some(o =>
      !(o.x1 < d.x0 - 1 || o.x0 > d.x1 + 1 || o.z1 < d.z0 - 1 || o.z0 > d.z1 + 1))) return;
    // the pocket must open from a real room, not from solid mass
    const touches = rooms.some(r =>
      (side === "n" && r.z0 === b.z0 + 1 && d.x0 >= r.x0 && d.x1 <= r.x1)
      || (side === "s" && r.z1 === b.z1 - 1 && d.x0 >= r.x0 && d.x1 <= r.x1)
      || (side === "w" && r.x0 === b.x0 + 1 && d.z0 >= r.z0 && d.z1 <= r.z1)
      || (side === "e" && r.x1 === b.x1 - 1 && d.z0 >= r.z0 && d.z1 <= r.z1));
    if (!touches) return;
    const bx0 = side === "n" || side === "s" ? d.x0 : (side === "w" ? b.x0 + 1 : b.x1 - 1);
    const bx1 = side === "n" || side === "s" ? d.x1 : bx0;
    const bz0 = side === "n" || side === "s" ? (side === "n" ? b.z0 + 1 : b.z1 - 1) : d.z0;
    const bz1 = side === "n" || side === "s" ? bz0 : d.z1;
    if (solids.some(s => !(s.x1 < bx0 || s.x0 > bx1 || s.z1 < bz0 || s.z0 > bz1))) return;
    // stairs occupy the inside of a raised block: no niche through them
    if (stairs.some(st => st.x >= d.x0 && st.x <= d.x1 && st.z >= d.z0 && st.z <= d.z1)) return;
    doors.push(d);
    rooms.push(niche);
  }

  const FURNITURE_FACINGS = ["north", "east", "south", "west"];

  /**
   * Furniture of a finished block. Cabinets, shelves and extras hug the first
   * ring inside a room wall; a desk stands on open floor with a chair in front
   * of it. Mirrors Level0Plan.computeFurniture; only hash picks the cells.
   */
  function furnitureOf(i, j, b, rooms, doors, solids, stairs, pileAllowed) {
    const out = [];
    const cabMax = Math.max(1, CFG.furnitureCabinetMax);
    const deskMax = Math.max(1, CFG.furnitureDeskMax);
    const onSolid = (x, z) => solids.some(s => x >= s.x0 && x <= s.x1 && z >= s.z0 && z <= s.z1);
    const nearStair = (x, z) => stairs.some(st => Math.abs(st.x - x) <= 1 && Math.abs(st.z - z) <= 1);
    const nearDoor = (x, z) =>
      doors.some(d => x >= d.x0 - 1 && x <= d.x1 + 1 && z >= d.z0 - 1 && z <= d.z1 + 1);
    const inDoor = (x, z) => doors.some(d => x >= d.x0 && x <= d.x1 && z >= d.z0 && z <= d.z1);
    const has = (x, z) => out.some(f => f.x === x && f.z === z);
    const touches = (x, z) => out.some(f => Math.abs(f.x - x) + Math.abs(f.z - z) === 1);

    const variantOf = (kind, x, z) => {
      const count = kind === "desk" ? 3
        : (kind === "chair" || kind === "cabinet" || kind === "shelf" || kind === "plant") ? 2 : 1;
      return count <= 1 ? 0 : hashNum(x, z, SALT_FURNITURE_VARIANT) % count;
    };

    function addWall(r, want, kind) {
      if (want <= 0) return;
      const w0 = r.x0 + 1, w1 = r.x1 - 1, d0 = r.z0 + 1, d1 = r.z1 - 1;
      const rw = w1 - w0 + 1, rd = d1 - d0 - 1;
      if (rw < 2 || rd < 1) return;
      // Wall furniture needs a wall behind it; an open street side has none.
      const openW = r.x0 === b.x0, openE = r.x1 === b.x1;
      const openN = r.z0 === b.z0, openS = r.z1 === b.z1;
      const ring = 2 * rw + 2 * rd;
      const start = hashNum(r.x0, r.z0 + kind.length, SALT_FURNITURE) % ring;
      let placed = 0;
      for (let k = 0; k < ring && placed < want; k++) {
        const idx = (start + k) % ring;
        let x, z;
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
        const west = x === w0, east = x === w1, north = z === d0, south = z === d1;
        if ((west && openW) || (east && openE) || (north && openN) || (south && openS)) continue;
        if (onSolid(x, z) || nearStair(x, z) || nearDoor(x, z) || has(x, z)) continue;
        // the cell between the furniture and the wall stays walkable
        if ((west && (onSolid(x - 1, z) || nearStair(x - 1, z)))
          || (east && (onSolid(x + 1, z) || nearStair(x + 1, z)))
          || (north && (onSolid(x, z - 1) || nearStair(x, z - 1)))
          || (south && (onSolid(x, z + 1) || nearStair(x, z + 1)))) continue;
        // no furniture along the wall on either side of this one
        if (((west || east) && (has(x, z - 1) || has(x, z + 1)))
          || ((north || south) && (has(x - 1, z) || has(x + 1, z)))) continue;
        const facing = west ? "east" : east ? "west" : north ? "south" : "north";
        out.push({ x, z, kind, facing, variant: variantOf(kind, x, z) });
        placed++;
      }
    }

    function addChair(r, x, z, deskFacing) {
      if (hashNum(x, z, SALT_FURNITURE_FACING + 1n) % 100 >= CFG.furnitureChairChance) return;
      let dx = 0, dz = 0;
      if (deskFacing === "east") dx = 1;
      else if (deskFacing === "west") dx = -1;
      else if (deskFacing === "south") dz = 1;
      else dz = -1;
      const cx = x + dx, cz = z + dz;
      if (cx < r.x0 || cx > r.x1 || cz < r.z0 || cz > r.z1) return;
      if (onSolid(cx, cz) || nearStair(cx, cz) || inDoor(cx, cz) || has(cx, cz)) return;
      const facing = deskFacing === "north" ? "south"
        : deskFacing === "south" ? "north"
          : deskFacing === "east" ? "west" : "east";
      out.push({ x: cx, z: cz, kind: "chair", facing, variant: variantOf("chair", cx, cz) });
    }

    function addDesks(r, want) {
      if (want <= 0) return;
      const w = r.x1 - r.x0 + 1, d = r.z1 - r.z0 + 1;
      const area = w * d;
      const start = hashNum(r.x0, r.z0 + 3, SALT_FURNITURE) % area;
      let placed = 0;
      for (let k = 0; k < area && placed < want; k++) {
        const idx = (start + k) % area;
        const x = r.x0 + idx % w;
        const z = r.z0 + Math.floor(idx / w);
        // stay off the wall ring: a gap hall may carve one cell into the room
        if (x === r.x0 + 1 || x === r.x1 - 1 || z === r.z0 + 1 || z === r.z1 - 1) continue;
        if (onSolid(x, z) || nearStair(x, z) || inDoor(x, z) || has(x, z) || touches(x, z)) continue;
        let free = true;
        for (let n = 0; n < 4 && free; n++) {
          const nx = x + (n === 0 ? -1 : n === 1 ? 1 : 0);
          const nz = z + (n === 2 ? -1 : n === 3 ? 1 : 0);
          free = nx >= r.x0 && nx <= r.x1 && nz >= r.z0 && nz <= r.z1
            && !onSolid(nx, nz) && !nearStair(nx, nz) && !inDoor(nx, nz) && !has(nx, nz);
        }
        if (!free) continue;
        const facing = FURNITURE_FACINGS[hashNum(x, z, SALT_FURNITURE_FACING) % 4];
        out.push({ x, z, kind: "desk", facing, variant: variantOf("desk", x, z) });
        addChair(r, x, z, facing);
        placed++;
      }
    }

    function addPilePiece(x, z, dy) {
      const pick = hashNum(x, z + dy * 17, SALT_PILE) % 10;
      const kind = pick < 3 ? "desk" : pick < 5 ? "chair" : pick < 7 ? "shelf"
        : pick < 8 ? "trash" : pick < 9 ? "plant" : "printer";
      const facing = FURNITURE_FACINGS[hashNum(x, z + dy * 31, SALT_FURNITURE_FACING) % 4];
      out.push({ x, z, dy, kind, facing, variant: variantOf(kind, x, z) });
    }

    // A rare heap in the middle of a tall hall; anything in the 3x3 cancels it.
    function addPile() {
      if (i === SPAWN_I && j === SPAWN_J) return;
      if (hashNum(i, j, SALT_PILE) % 100 >= CFG.furniturePileChance) return;
      let room = null;
      for (const r of rooms) {
        if (r.x1 - r.x0 + 1 < CFG.furniturePileMinRoom
          || r.z1 - r.z0 + 1 < CFG.furniturePileMinRoom) continue;
        if (!room || (r.x1 - r.x0) * (r.z1 - r.z0)
          > (room.x1 - room.x0) * (room.z1 - room.z0)) room = r;
      }
      if (!room) return;
      const cx = Math.trunc((room.x0 + room.x1) / 2);
      const cz = Math.trunc((room.z0 + room.z1) / 2);
      for (let dx = -1; dx <= 1; dx++) {
        for (let dz = -1; dz <= 1; dz++) {
          const x = cx + dx, z = cz + dz;
          if (onSolid(x, z) || nearStair(x, z) || nearDoor(x, z) || has(x, z)) return;
        }
      }
      for (let dx = -1; dx <= 1; dx++) {
        for (let dz = -1; dz <= 1; dz++) addPilePiece(cx + dx, cz + dz, 0);
      }
      const side = hashNum(cx, cz, SALT_PILE + 1n) % 4;
      const mx = cx + (side === 0 ? 1 : side === 1 ? -1 : 0);
      const mz = cz + (side === 2 ? 1 : side === 3 ? -1 : 0);
      addPilePiece(cx, cz, 1);
      addPilePiece(mx, mz, 1);
      addPilePiece(cx, cz, 2);
    }

    for (let ri = 0; ri < rooms.length; ri++) {
      const r = rooms[ri];
      if (r.x1 - r.x0 < 5 || r.z1 - r.z0 < 5) continue;
      const wantC = hashNum(i * 5 + ri, j * 7 + 1, SALT_FURNITURE) % 100 < CFG.furnitureCabinetChance
        ? 1 + hashNum(i * 5 + ri, j * 7 + 2, SALT_FURNITURE) % cabMax : 0;
      addWall(r, wantC, "cabinet");
      const wantS = hashNum(i * 7 + ri, j * 11 + 5, SALT_FURNITURE) % 100 < CFG.furnitureShelfChance ? 1 : 0;
      addWall(r, wantS, "shelf");
      const wantD = hashNum(i * 11 + ri, j * 13 + 3, SALT_FURNITURE) % 100 < CFG.furnitureDeskChance
        ? 1 + hashNum(i * 11 + ri, j * 13 + 4, SALT_FURNITURE) % deskMax : 0;
      addDesks(r, wantD);
      if (hashNum(i * 13 + ri, j * 17 + 9, SALT_FURNITURE) % 100 < CFG.furnitureExtraChance) {
        const pick = hashNum(i * 17 + ri, j * 19 + 11, SALT_FURNITURE) % 10;
        const extra = pick < 4 ? "plant" : pick < 6 ? "cooler" : pick < 8 ? "trash" : "printer";
        addWall(r, 1, extra);
      }
    }
    if (pileAllowed) addPile();
    return out;
  }

  function blockInfo(i, j) {
    const key = i + "," + j;
    if (blockCache.has(key)) return blockCache.get(key);

    const b = blockBounds(i, j);
    if (b.x1 - b.x0 < 8 || b.z1 - b.z0 < 8) {
      blockCache.set(key, null);
      return null;
    }
    const hb = hash64(i, j, SALT_BLOCK);
    const forced = i === SPAWN_I && j === SPAWN_J;
    const level = blockLevel(i, j);
    const plaza = inPlaza(i, j);
    if (!forced && !plaza && Number(hb % 100n) >= BigInt(CFG.blockChance)) {
      blockCache.set(key, null);
      return null;
    }
    const sides = blockSides(i, j);
    const present = ["n", "e", "s", "w"].filter(k => sides[k]);
    if (!present.length && !plaza) {
      blockCache.set(key, null);
      return null;
    }
    if (plaza) {
      const info = plazaInfo(i, j, b, sides, present);
      blockCache.set(key, info);
      return info;
    }

    let layout;
    const lr = Number((hb >> 8n) % 100n);
    if (forced) layout = "hall";
    else if (lr < 20) layout = "hall";
    else if (lr < 75) layout = "offices";
    else if (lr < 90) layout = "suite";
    else layout = "merge";
    // A stair ramp at the street door would leave interior doorways on a ledge.
    if (level !== 0 && layout !== "hall" && layout !== "merge") layout = "hall";

    const primary = forced ? "n" : present[Number((hb >> 20n) % BigInt(present.length))];
    const vertical = primary === "n" || primary === "s";

    // Uneven wall depth per side: 0 opens the room onto the street, 2 is a thick wall.
    const ins = { n: 1, e: 1, s: 1, w: 1 };
    if (level === 0 && !forced) {
      const pick = (side, idx) => {
        if (!sides[side]) return 1;
        const t = hashNum(i * 7 + idx, j, SALT_WALL) % 100;
        if (t < CFG.wallOpen) return 0;
        return t >= 100 - CFG.wallThick ? 2 : 1;
      };
      ins.n = pick("n", 1);
      ins.e = pick("e", 2);
      ins.s = pick("s", 3);
      ins.w = pick("w", 4);
      if (b.x1 - b.x0 - ins.w - ins.e < 6) { ins.w = 1; ins.e = 1; }
      if (b.z1 - b.z0 - ins.n - ins.s < 6) { ins.n = 1; ins.s = 1; }
    }
    const ix0 = b.x0 + ins.w, ix1 = b.x1 - ins.e;
    const iz0 = b.z0 + ins.n, iz1 = b.z1 - ins.s;
    if (layout !== "hall" && layout !== "suite") {
      // Pick the layout by the nominal block size: uneven walls only trim the room.
      const along = vertical ? b.x1 - b.x0 - 2 : b.z1 - b.z0 - 2;
      if (along < 13) layout = "hall";
    }
    const rooms = [];
    const doors = [];
    const solids = [];

    const doorW = clampInt(CFG.doorWidth, 2, 5);
    const hashDoor = (roomIdx, side) => hash64(i, j * 97 + roomIdx * 11 + side, SALT_DOOR);

    // Narrow door gap through a 1-block wall.
    function doorN(roomIdx, x0, x1, wallZ) {
      const span = x1 - x0 - doorW - 1;
      if (span < 1) return;
      const dx = x0 + 1 + Number(hashDoor(roomIdx, 1) % BigInt(span));
      doors.push({ x0: dx, x1: dx + doorW - 1, z0: wallZ, z1: wallZ });
    }
    function doorS(roomIdx, x0, x1, wallZ) {
      const span = x1 - x0 - doorW - 1;
      if (span < 1) return;
      const dx = x0 + 1 + Number(hashDoor(roomIdx, 2) % BigInt(span));
      doors.push({ x0: dx, x1: dx + doorW - 1, z0: wallZ, z1: wallZ });
    }
    function doorEast(roomIdx, z0, z1, wallX) {
      const span = z1 - z0 - doorW - 1;
      if (span < 1) return;
      const dz = z0 + 1 + Number(hashDoor(roomIdx, 3) % BigInt(span));
      doors.push({ x0: wallX, x1: wallX, z0: dz, z1: dz + doorW - 1 });
    }
    function doorWest(roomIdx, z0, z1, wallX) {
      const span = z1 - z0 - doorW - 1;
      if (span < 1) return;
      const dz = z0 + 1 + Number(hashDoor(roomIdx, 4) % BigInt(span));
      doors.push({ x0: wallX, x1: wallX, z0: dz, z1: dz + doorW - 1 });
    }

    function addStreetDoor(roomIdx, r, side) {
      if (side === "n") doorN(roomIdx, r.x0, r.x1, b.z0);
      else if (side === "s") doorS(roomIdx, r.x0, r.x1, b.z1);
      else if (side === "e") doorEast(roomIdx, r.z0, r.z1, b.x1);
      else doorWest(roomIdx, r.z0, r.z1, b.x0);
    }

    // A room's extra exits on other present sides: loops and cross-links.
    function extraDoors(roomIdx, r) {
      for (const side of present) {
        if (side === primary) continue;
        const touches =
          (side === "n" && r.z0 === iz0) || (side === "s" && r.z1 === iz1) ||
          (side === "e" && r.x1 === ix1) || (side === "w" && r.x0 === ix0);
        if (!touches) continue;
        if (hashNum(i, j * 31 + roomIdx, SALT_DETAIL) % 100 < 45) {
          addStreetDoor(roomIdx + 50, r, side);
        }
      }
    }

    function addColumns(r) {
      if (hashNum(i, j, SALT_DETAIL) % 100 >= 40) return;
      const cs = clampInt(CFG.columnSpacing, 4, 12) + hashNum(i, j, SALT_DETAIL) % 3;
      const w = r.x1 - r.x0, d = r.z1 - r.z0;
      if (w < cs || d < cs) return;
      const alongX = w >= d;
      const addCol = (x, z) => {
        if (forced && Math.abs(x - Math.trunc((r.x0 + r.x1) / 2)) <= 2
          && Math.abs(z - Math.trunc((r.z0 + r.z1) / 2)) <= 2) return;
        if (hashNum(x, z, SALT_DETAIL) % 100 < 80) {
          solids.push({ x0: x, x1: x, z0: z, z1: z });
        }
      };
      if (alongX) {
        const zc = Math.trunc((r.z0 + r.z1) / 2) + (hashNum(i, j, SALT_DETAIL) % 5) - 2;
        for (let x = r.x0 + 2; x <= r.x1 - 2; x += cs) addCol(x, zc);
        if (d > 12) {
          const z2 = zc + Math.max(4, Math.floor(d / 3));
          if (z2 < r.z1 - 2) for (let x = r.x0 + 2; x <= r.x1 - 2; x += cs) addCol(x, z2);
        }
      } else {
        const xc = Math.trunc((r.x0 + r.x1) / 2) + (hashNum(i, j, SALT_DETAIL) % 5) - 2;
        for (let z = r.z0 + 2; z <= r.z1 - 2; z += cs) addCol(xc, z);
        if (w > 12) {
          const x2 = xc + Math.max(4, Math.floor(w / 3));
          if (x2 < r.x1 - 2) for (let z = r.z0 + 2; z <= r.z1 - 2; z += cs) addCol(x2, z);
        }
      }
    }

    function addPartitions(r, roomIdx) {
      if (CFG.partitionChance <= 0) return;
      if (hashNum(i * 13 + roomIdx, j, SALT_DETAIL) % 100 >= CFG.partitionChance) return;
      const side = hashNum(i, j + roomIdx, SALT_DETAIL) % 4;
      const len = 2 + hashNum(i + roomIdx, j, SALT_DETAIL) % 3;
      const w = r.x1 - r.x0, d = r.z1 - r.z0;
      let s = null;
      if (side === 0 && w > len + 4) {
        const x = r.x0 + 2 + hashNum(roomIdx, i, SALT_DETAIL) % (w - len - 4);
        s = { x0: x, x1: x, z0: r.z0, z1: Math.min(r.z0 + len, r.z1 - 3) };
      } else if (side === 1 && d > len + 4) {
        const z = r.z0 + 2 + hashNum(roomIdx, j, SALT_DETAIL) % (d - len - 4);
        s = { x0: r.x1, x1: r.x1, z0: z, z1: z + len };
      } else if (side === 2 && d > len + 4) {
        const z = r.z0 + 2 + hashNum(roomIdx, i + 7, SALT_DETAIL) % (d - len - 4);
        s = { x0: r.x0, x1: r.x0, z0: z, z1: z + len };
      } else if (w > len + 4) {
        const x = r.x0 + 2 + hashNum(roomIdx, j + 9, SALT_DETAIL) % (w - len - 4);
        s = { x0: x, x1: x, z0: Math.max(r.z1 - len, r.z0 + 3), z1: r.z1 };
      }
      if (!s) return;
      // never wall off a doorway
      const blocksDoor = doors.some(d2 =>
        !(s.x1 < d2.x0 - 1 || s.x0 > d2.x1 + 1 || s.z1 < d2.z0 - 1 || s.z0 > d2.z1 + 1));
      if (!blocksDoor) solids.push(s);
    }

    if (layout === "hall") {
      const r = { x0: ix0, x1: ix1, z0: iz0, z1: iz1 };
      rooms.push({ ...r, name: LAYOUT_NAMES.hall });
      for (const side of present) addStreetDoor(9, r, side);
      addColumns(r);
      addPartitions(r, 9);
    } else if (layout === "suite") {
      const r = { x0: ix0, x1: ix1, z0: iz0, z1: iz1 };
      rooms.push({ ...r, name: LAYOUT_NAMES.suite });
      for (const side of present) addStreetDoor(9, r, side);
      addColumns(r);
      // nested storage room in a hashed corner
      const sw = 6, sd = 6;
      if (r.x1 - r.x0 >= sw + 2 && r.z1 - r.z0 >= sd + 2) {
        const corner = hashNum(i, j, SALT_DETAIL) % 4;
        const nx0 = corner === 0 || corner === 3 ? r.x0 + 2 : r.x1 - sw - 2;
        const nz0 = corner < 2 ? r.z0 + 2 : r.z1 - sd - 2;
        const nx1 = nx0 + sw, nz1 = nz0 + sd;
        const inner = { x0: nx0 + 1, x1: nx1 - 1, z0: nz0 + 1, z1: nz1 - 1 };
        if (inner.x1 - inner.x0 >= 4 && inner.z1 - inner.z0 >= 4) {
          // storage walls (the inner room itself is already part of the big room)
          solids.push(
            { x0: nx0, x1: nx1, z0: nz0, z1: nz0 },
            { x0: nx0, x1: nx1, z0: nz1, z1: nz1 },
            { x0: nx0, x1: nx0, z0: nz0, z1: nz1 },
            { x0: nx1, x1: nx1, z0: nz0, z1: nz1 }
          );
          rooms.push({ ...inner, name: "STORAGE" });
          // door from storage to the main suite
          const dsp = inner.x1 - inner.x0 - 3;
          if (dsp > 0) {
            const dx = inner.x0 + 1 + Number(hashDoor(7, 3) % BigInt(dsp));
            doors.push({ x0: dx, x1: dx + 2, z0: nz1, z1: nz1 });
          }
        }
      }
    } else if (layout === "merge") {
      // two rooms with a wide opening in the dividing wall
      let cut = vertical
        ? ix0 + Math.floor((ix1 - ix0) * 0.5) + (hashNum(i, j, SALT_DETAIL) % 5) - 2
        : iz0 + Math.floor((iz1 - iz0) * 0.5) + (hashNum(i, j, SALT_DETAIL) % 5) - 2;
      cut = vertical
        ? clampInt(cut, ix0 + 6, ix1 - 6)
        : clampInt(cut, iz0 + 6, iz1 - 6);
      if (vertical) {
        const a = { x0: ix0, x1: cut - 1, z0: iz0, z1: iz1 };
        const c = { x0: cut + 1, x1: ix1, z0: iz0, z1: iz1 };
        rooms.push({ ...a, name: LAYOUT_NAMES.merge }, { ...c, name: LAYOUT_NAMES.merge });
        addStreetDoor(1, a, primary); addStreetDoor(2, c, primary);
        extraDoors(1, a); extraDoors(2, c);
        const ow = clampInt(CFG.wideOpening, 4, 10);
        const span = iz1 - iz0 - ow - 1;
        if (span > 0) {
          const z = iz0 + 1 + hashNum(i, j, SALT_DETAIL) % span;
          doors.push({ x0: cut, x1: cut, z0: z, z1: z + ow - 1 });
        }
        addPartitions(a, 1); addPartitions(c, 2);
      } else {
        const a = { x0: ix0, x1: ix1, z0: iz0, z1: cut - 1 };
        const c = { x0: ix0, x1: ix1, z0: cut + 1, z1: iz1 };
        rooms.push({ ...a, name: LAYOUT_NAMES.merge }, { ...c, name: LAYOUT_NAMES.merge });
        addStreetDoor(1, a, primary); addStreetDoor(2, c, primary);
        extraDoors(1, a); extraDoors(2, c);
        const ow = clampInt(CFG.wideOpening, 4, 10);
        const span = ix1 - ix0 - ow - 1;
        if (span > 0) {
          const x = ix0 + 1 + hashNum(i, j, SALT_DETAIL) % span;
          doors.push({ x0: x, x1: x + ow - 1, z0: cut, z1: cut });
        }
        addPartitions(a, 1); addPartitions(c, 2);
      }
    } else {
      // OFFICES: a straight hallway from the primary street into the block,
      // with offices on both sides. The hallway may dead-end in the block or
      // pass through to the opposite street.
      const alongZ = vertical;
      const crossLen = alongZ ? ix1 - ix0 : iz1 - iz0;
      if (crossLen < 10) {
        const r = { x0: ix0, x1: ix1, z0: iz0, z1: iz1 };
        rooms.push({ ...r, name: LAYOUT_NAMES.hall });
        for (const side of present) addStreetDoor(9, r, side);
        addColumns(r);
      } else {
        const corridorW = clampInt(CFG.doorWidth + 1, 3, 5);
        const crossStart = alongZ ? ix0 : iz0;
        const c0 = crossStart + 2 + hashNum(i, j, SALT_DETAIL) % Math.max(1, crossLen - 4 - corridorW);
        const c1 = c0 + corridorW - 1;
        const corridor = alongZ
          ? { x0: c0, x1: c1, z0: iz0, z1: iz1 }
          : { x0: ix0, x1: ix1, z0: c0, z1: c1 };
        rooms.push({ ...corridor, name: "OFFICE CORRIDOR", corridor: true });

        const nDoor = (z) => doors.push({ x0: corridor.x0, x1: corridor.x1, z0: z, z1: z });
        const eDoor = (x) => doors.push({ x0: x, x1: x, z0: corridor.z0, z1: corridor.z1 });
        if (primary === "n") nDoor(b.z0);
        if (primary === "s") nDoor(b.z1);
        if (primary === "e") eDoor(b.x1);
        if (primary === "w") eDoor(b.x0);
        const opp = { n: "s", s: "n", e: "w", w: "e" }[primary];
        if (sides[opp]) {
          if (opp === "n") nDoor(b.z0);
          if (opp === "s") nDoor(b.z1);
          if (opp === "e") eDoor(b.x1);
          if (opp === "w") eDoor(b.x0);
        }

        function carveStrip(x0, x1, z0, z1, wallAxis, wallPos, stripIdx) {
          if (x1 - x0 < 3 || z1 - z0 < 3) return;
          const long = alongZ ? z1 - z0 + 1 : x1 - x0 + 1;
          let n = 1 + hashNum(i + stripIdx * 17, j, SALT_DETAIL) % 3;
          n = Math.min(n, Math.max(1, Math.floor(long / 6)));
          const spans = [];
          if (n <= 1) {
            spans.push([0, long - 1]);
          } else {
            const cuts = [];
            let prev = -1;
            for (let k = 1; k < n; k++) {
              const base = Math.round(long * k / n);
              const jit = (hashNum(i + k + stripIdx, j, SALT_DETAIL) % 7) - 3;
              const lo = (prev < 0 ? 0 : prev + 1) + 5;
              const hi = long - 5 * (n - k);
              const c = clampInt(base + jit, lo, Math.max(lo, hi));
              cuts.push(c);
              prev = c;
            }
            let start = 0;
            for (const c of cuts) { spans.push([start, c - 1]); start = c + 1; }
            spans.push([start, long - 1]);
          }
          let idx2 = 0;
          for (const [s0, s1] of spans) {
            idx2++;
            if (s1 - s0 + 1 < 6) continue;
            const r = alongZ
              ? { x0, x1, z0: z0 + s0, z1: z0 + s1 }
              : { x0: x0 + s0, x1: x0 + s1, z0, z1 };
            // offices are 10-18 deep: split the strip across its depth too,
            // so a block does not turn into a few giant halls
            const d0 = alongZ ? r.x0 : r.z0;
            const d1 = alongZ ? r.x1 : r.z1;
            const parts = [];
            let splitWall = -1;
            if (d1 - d0 >= 9) {
              splitWall = d0 + 2 + hashNum(i + idx2, j + stripIdx, SALT_DETAIL) % Math.max(1, d1 - d0 - 4);
              const outerPositive = wallPos > (d0 + d1) / 2;
              const outerSpan = outerPositive ? [d0, splitWall - 1] : [splitWall + 1, d1];
              const innerSpan = outerPositive ? [splitWall + 1, d1] : [d0, splitWall - 1];
              parts.push(
                { rect: alongZ ? { x0: outerSpan[0], x1: outerSpan[1], z0: r.z0, z1: r.z1 }
                               : { x0: r.x0, x1: r.x1, z0: outerSpan[0], z1: outerSpan[1] }, outer: true },
                { rect: alongZ ? { x0: innerSpan[0], x1: innerSpan[1], z0: r.z0, z1: r.z1 }
                               : { x0: r.x0, x1: r.x1, z0: innerSpan[0], z1: innerSpan[1] }, outer: false }
              );
              // door between the outer and inner office
              if (alongZ) {
                const span = r.z1 - r.z0 - doorW - 1;
                if (span >= 1) {
                  const z = r.z0 + 1 + Number(hashDoor(idx2 * 5 + stripIdx, 5) % BigInt(span));
                  doors.push({ x0: splitWall, x1: splitWall, z0: z, z1: z + doorW - 1 });
                }
              } else {
                const span = r.x1 - r.x0 - doorW - 1;
                if (span >= 1) {
                  const x = r.x0 + 1 + Number(hashDoor(idx2 * 5 + stripIdx, 6) % BigInt(span));
                  doors.push({ x0: x, x1: x + doorW - 1, z0: splitWall, z1: splitWall });
                }
              }
            } else {
              parts.push({ rect: r, outer: true });
            }
            for (const part of parts) {
              const rr = part.rect;
              rooms.push({ ...rr, name: LAYOUT_NAMES.offices });
              if (part.outer) {
                if (wallAxis === "z") {
                  const span = (rr.z1 - rr.z0) - doorW - 1;
                  if (span >= 1) {
                    const z = rr.z0 + 1 + Number(hashDoor(stripIdx * 7 + idx2, 3) % BigInt(span));
                    doors.push({ x0: wallPos, x1: wallPos, z0: z, z1: z + doorW - 1 });
                  }
                } else {
                  const span = (rr.x1 - rr.x0) - doorW - 1;
                  if (span >= 1) {
                    const x = rr.x0 + 1 + Number(hashDoor(stripIdx * 7 + idx2, 1) % BigInt(span));
                    doors.push({ x0: x, x1: x + doorW - 1, z0: wallPos, z1: wallPos });
                  }
                }
              }
              extraDoors(stripIdx * 7 + idx2 + (part.outer ? 0 : 13), rr);
              addPartitions(rr, stripIdx * 7 + idx2 + (part.outer ? 0 : 13));
            };
          }
        }

        if (alongZ) {
          carveStrip(ix0, c0 - 2, iz0, iz1, "z", c0 - 1, 1);
          carveStrip(c1 + 2, ix1, iz0, iz1, "z", c1 + 1, 2);
        } else {
          carveStrip(ix0, ix1, iz0, c0 - 2, "x", c0 - 1, 1);
          carveStrip(ix0, ix1, c1 + 2, iz1, "x", c1 + 1, 2);
        }
      }
    }

    // A door is pointless on an open side, and a street door must pierce a thick wall.
    for (let k = doors.length - 1; k >= 0; k--) {
      const d = doors[k];
      const onOpen = (ins.n === 0 && d.z0 === b.z0 && d.z1 === b.z0)
        || (ins.s === 0 && d.z0 === b.z1 && d.z1 === b.z1)
        || (ins.w === 0 && d.x0 === b.x0 && d.x1 === b.x0)
        || (ins.e === 0 && d.x0 === b.x1 && d.x1 === b.x1);
      if (onOpen) { doors.splice(k, 1); continue; }
      if (d.z0 === b.z0 && d.z1 === b.z0 && ins.n > 1) d.z1 = b.z0 + ins.n - 1;
      else if (d.z0 === b.z1 && d.z1 === b.z1 && ins.s > 1) d.z0 = b.z1 - ins.s + 1;
      else if (d.x0 === b.x0 && d.x1 === b.x0 && ins.w > 1) d.x1 = b.x0 + ins.w - 1;
      else if (d.x0 === b.x1 && d.x1 === b.x1 && ins.e > 1) d.x0 = b.x1 - ins.e + 1;
    }
    if (level !== 0) {
      // One stair run per block: crossing runs cut off the corners of small halls.
      let kept = false;
      for (let k = doors.length - 1; k >= 0; k--) {
        const d = doors[k];
        const street = d.x0 === b.x0 || d.x1 === b.x1 || d.z0 === b.z0 || d.z1 === b.z1;
        if (!street) continue;
        if (kept) doors.splice(k, 1);
        else kept = true;
      }
    }
    const stairs = [];
    if (level !== 0) {
      const up = level > 0;
      const roomY = BASE_FLOOR_Y + CFG.levelHeight * level;
      for (const d of doors) {
        const street = d.x0 === b.x0 || d.x1 === b.x1 || d.z0 === b.z0 || d.z1 === b.z1;
        if (!street) continue;
        if (d.z0 === b.z0) {
          for (let k = 0; k < CFG.levelHeight; k++) {
            const z = b.z0 + 1 + k;
            const y = up ? roomY - CFG.levelHeight + 1 + k : roomY + CFG.levelHeight - k;
            for (let x = d.x0; x <= d.x1; x++) stairs.push({ x, z, y, facing: up ? "s" : "n" });
          }
        } else if (d.z1 === b.z1) {
          for (let k = 0; k < CFG.levelHeight; k++) {
            const z = b.z1 - 1 - k;
            const y = up ? roomY - CFG.levelHeight + 1 + k : roomY + CFG.levelHeight - k;
            for (let x = d.x0; x <= d.x1; x++) stairs.push({ x, z, y, facing: up ? "n" : "s" });
          }
        } else if (d.x0 === b.x0) {
          for (let k = 0; k < CFG.levelHeight; k++) {
            const x = b.x0 + 1 + k;
            const y = up ? roomY - CFG.levelHeight + 1 + k : roomY + CFG.levelHeight - k;
            for (let z = d.z0; z <= d.z1; z++) stairs.push({ x, z, y, facing: up ? "e" : "w" });
          }
        } else if (d.x1 === b.x1) {
          for (let k = 0; k < CFG.levelHeight; k++) {
            const x = b.x1 - 1 - k;
            const y = up ? roomY - CFG.levelHeight + 1 + k : roomY + CFG.levelHeight - k;
            for (let z = d.z0; z <= d.z1; z++) stairs.push({ x, z, y, facing: up ? "w" : "e" });
          }
        }
      }
      if (stairs.length) {
        const clash = (so) => stairs.some(st =>
          st.x >= so.x0 && st.x <= so.x1 && st.z >= so.z0 && st.z <= so.z1);
        const kept = solids.filter(so => !clash(so));
        solids.length = 0;
        solids.push(...kept);
      }
    }
    // A door that opens into a shallow pocket in the wall: a dead end that still
    // reads as an opening. Added last so it never becomes a stair exit.
    addFalseDoor(i, j, b, sides, present, rooms, doors, solids, stairs);
    const ceilExtra = hallCeilExtra(i, j, layout);
    const info = {
      i, j, x0: b.x0, x1: b.x1, z0: b.z0, z1: b.z1,
      layout, level, ceilExtra,
      rooms, doors, solids, stairs, forced,
      furniture: furnitureOf(i, j, b, rooms, doors, solids, stairs,
        layout === "hall" && ceilExtra > 0)
    };
    blockCache.set(key, info);
    return info;
  }

  /**
   * A room that replaces a missing street segment and joins the two blocks it
   * separated. Null when the gap stays solid.
   */
  function hallRect(i, j, vertical) {
    if (vertical ? segV(i, j) : segH(i, j)) return null;
    // Both blocks must exist on the same floor, or the hole becomes a ledge.
    const a = vertical ? blockInfo(i - 1, j) : blockInfo(i, j - 1);
    const c = blockInfo(i, j);
    if (!a || !c || a.level !== c.level) return null;
    // The plaza owns its whole district; a gap hall would cut into it.
    if (inPlaza(a.i, a.j) || inPlaza(c.i, c.j)) return null;
    // Streets on the main floor stay random, raised bridges are guaranteed.
    if (a.level === 0) {
      if (!CFG.hallChance) return null;
      if (hashNum(i * 31 + (vertical ? 1 : 2), j, SALT_HALL) % 100 >= CFG.hallChance) return null;
    }
    const along = vertical ? streetZ(j + 1, i) - streetZ(j, i)
      : streetX(i + 1, j) - streetX(i, j);
    // Fill the whole gap between the two crossings, with a small wall at each end.
    const cap0 = vertical ? streetHalf(j) : streetHalf(i);
    const cap1 = vertical ? streetHalf(j + 1) : streetHalf(i + 1);
    const gap = along - cap0 - cap1 - 4;
    if (gap < 8) return null;
    const len = gap - hashNum(i, j * 17, SALT_HALL) % 3;
    const start = cap0 + 2 + (gap - len) * (hashNum(i + 5, j, SALT_HALL) % 2);
    let rect;
    if (vertical) {
      const sx = streetX(i, j), half = streetHalf(i);
      const z0 = streetZ(j, i) + start;
      // Two blocks deep per side: enough to reach a room behind a 2-deep wall.
      rect = { x0: sx - half - 2, x1: sx + half + 2, z0, z1: z0 + len - 1 };
    } else {
      const sz = streetZ(j, i), half = streetHalf(j);
      const x0 = streetX(i, j) + start;
      rect = { x0, x1: x0 + len - 1, z0: sz - half - 2, z1: sz + half + 2 };
    }
    // A hall is only worth carving when it actually opens into a room on both sides.
    const hits = (bl) => bl.rooms.some(r =>
      !(r.x1 < rect.x0 || r.x0 > rect.x1 || r.z1 < rect.z0 || r.z0 > rect.z1));
    return hits(a) && hits(c) ? rect : null;
  }

  /** Dark district: whole 48x48 cells without fluorescent panels. */
  function darkAt(x, z) {
    if (CFG.darkChance <= 0) return false;
    return Number(hash64(Math.floor(x / DARK_DISTRICT), Math.floor(z / DARK_DISTRICT),
      SALT_LIGHT) % 100n) < BigInt(CFG.darkChance);
  }

  /** A panel exactly on the 12x12 grid, unless its district is dark or the tube is dead. */
  function lightAt(x, z) {
    const mod = (v) => ((v % LIGHT_EVERY) + LIGHT_EVERY) % LIGHT_EVERY;
    if (mod(x) !== 3 || mod(z) !== 3) return false;
    if (darkAt(x, z)) return false;
    return Number(hash64(x, z, SALT_LIGHT) % 100n) >= BigInt(CFG.deadLightChance);
  }

  function begin(cfg) {
    CFG = Object.assign({}, DEFAULT_CONFIG, cfg || {});
    SEED = BigInt(Math.trunc(CFG.seed) || 0);
    blockCache = new Map();
    spawnCell();
  }

  /**
   * Renders the plan over a span x span block window.
   * Returns typed arrays + stats; no colors (the UI owns presentation).
   */
  function build(cfg, view) {
    begin(cfg);
    const span = clampInt(Math.round((view && view.span) || 768), 160, 2048);
    const W = span;
    const cx = Math.round((view && view.cx) || 0);
    const cz = Math.round((view && view.cz) || 0);
    const x0 = Math.floor(cx - W / 2);
    const z0 = Math.floor(cz - W / 2);

    const floor = new Int16Array(W * W).fill(EMPTY);
    const kind = new Uint8Array(W * W); // 0 mass, 1 street, 2 door, 3 room, 4 stair
    const furn = new Uint8Array(W * W); // 0 none, 1 cabinet (blocked), 2 other furniture
    let cabinetCount = 0, deskCount = 0, chairCount = 0, shelfCount = 0, extraCount = 0;

    const open = (x, z, k, s) => {
      const px = x - x0, pz = z - z0;
      if (px < 0 || px >= W || pz < 0 || pz >= W) return;
      const idx = pz * W + px;
      floor[idx] = s;
      kind[idx] = k;
    };
    const solid = (x, z) => {
      const px = x - x0, pz = z - z0;
      if (px < 0 || px >= W || pz < 0 || pz >= W) return;
      const idx = pz * W + px;
      floor[idx] = EMPTY;
      kind[idx] = 0;
    };

    // ---- streets ----
    const [si0, si1] = streetRange(x0, x0 + W - 1);
    const [sj0, sj1] = streetRange(z0, z0 + W - 1);
    for (let i = si0; i <= si1; i++) {
      const half = streetHalf(i);
      for (let j = sj0; j <= sj1; j++) {
        if (!segV(i, j)) continue;
        const sx = streetX(i, j);
        const za = streetZ(j, i), zb = streetZ(j + 1, i);
        for (let x = sx - half; x <= sx + half; x++) {
          for (let z = Math.max(za, z0); z <= Math.min(zb, z0 + W - 1); z++) open(x, z, 1, STREET_HALF);
        }
      }
    }
    for (let j = sj0; j <= sj1; j++) {
      const half = streetHalf(j);
      for (let i = si0; i <= si1; i++) {
        if (!segH(i, j)) continue;
        const sz = streetZ(j, i);
        const xa = Math.min(streetX(i, j - 1), streetX(i, j));
        const xb = Math.max(streetX(i + 1, j - 1), streetX(i + 1, j));
        for (let z = sz - half; z <= sz + half; z++) {
          for (let x = Math.max(xa, x0); x <= Math.min(xb, x0 + W - 1); x++) open(x, z, 1, STREET_HALF);
        }
      }
    }

    // ---- blocks ----
    const rooms = [];
    const blockMap = new Map();
    for (let i = si0; i <= si1; i++) {
      for (let j = sj0; j <= sj1; j++) {
        const b = blockInfo(i, j);
        if (!b) continue;
        if (b.x1 < x0 || b.x0 > x0 + W - 1 || b.z1 < z0 || b.z0 > z0 + W - 1) continue;
        blockMap.set(i + "," + j, b);
        const bh = levelHalf(b.level);
        for (const r of b.rooms) {
          for (let x = Math.max(r.x0, x0); x <= Math.min(r.x1, x0 + W - 1); x++) {
            for (let z = Math.max(r.z0, z0); z <= Math.min(r.z1, z0 + W - 1); z++) open(x, z, 3, bh);
          }
          rooms.push({
            i, j, x: Math.floor((r.x0 + r.x1) / 2), z: Math.floor((r.z0 + r.z1) / 2),
            x0: r.x0, x1: r.x1, z0: r.z0, z1: r.z1,
            name: r.name, layout: b.layout, sealed: false
          });
        }
        for (const s of b.solids) {
          for (let x = s.x0; x <= s.x1; x++) {
            for (let z = s.z0; z <= s.z1; z++) solid(x, z);
          }
        }
        const blockSidesNow = blockSides(b.i, b.j);
        for (const d of b.doors) {
          const street = (d.x0 === b.x0 && blockSidesNow.w) || (d.x1 === b.x1 && blockSidesNow.e)
            || (d.z0 === b.z0 && blockSidesNow.n) || (d.z1 === b.z1 && blockSidesNow.s);
          const ds = street ? STREET_HALF : bh;
          for (let x = d.x0; x <= d.x1; x++) {
            for (let z = d.z0; z <= d.z1; z++) open(x, z, 2, ds);
          }
        }
        for (const st of b.stairs) {
          if (st.x < x0 || st.x >= x0 + W || st.z < z0 || st.z >= z0 + W) continue;
          open(st.x, st.z, 4, 2 * st.y - 1);
        }
        for (const f of b.furniture) {
          if (f.x < x0 || f.x >= x0 + W || f.z < z0 || f.z >= z0 + W) continue;
          const fi = (f.z - z0) * W + (f.x - x0);
          const fresh = furn[fi] === 0; // overlapping plaza slices may both claim a cell
          furn[fi] = f.kind === "cabinet" ? 1 : 2;
          if (fresh) {
            if (f.kind === "cabinet") cabinetCount++;
            else if (f.kind === "desk") deskCount++;
            else if (f.kind === "chair") chairCount++;
            else if (f.kind === "shelf") shelfCount++;
            else extraCount++;
          }
        }
      }
    }

    // ---- halls in place of missing street segments ----
    const addHall = (h, i, j, vertical) => {
      if (!h) return;
      if (h.x1 < x0 || h.x0 > x0 + W - 1 || h.z1 < z0 || h.z0 > z0 + W - 1) return;
      const a = vertical ? blockInfo(i - 1, j) : blockInfo(i, j - 1);
      if (!a) return;
      const hallHalf = levelHalf(a.level);
      for (let x = Math.max(h.x0, x0); x <= Math.min(h.x1, x0 + W - 1); x++) {
        for (let z = Math.max(h.z0, z0); z <= Math.min(h.z1, z0 + W - 1); z++) open(x, z, 5, hallHalf);
      }
      rooms.push({
        i, j, x: Math.floor((h.x0 + h.x1) / 2), z: Math.floor((h.z0 + h.z1) / 2),
        x0: h.x0, x1: h.x1, z0: h.z0, z1: h.z1,
        name: "OPEN HALL (GAP)", layout: "hall", sealed: false
      });
    };
    for (let i = si0; i <= si1; i++) {
      for (let j = sj0; j <= sj1; j++) {
        addHall(hallRect(i, j, true), i, j, true);
        addHall(hallRect(i, j, false), i, j, false);
      }
    }

    // ---- reachability from the entry hall ----
    const spawnBlock = blockInfo(SPAWN_I, SPAWN_J);
    let spawn = null;
    if (spawnBlock && spawnBlock.rooms.length) {
      const r = spawnBlock.rooms[0];
      spawn = {
        x: Math.trunc((r.x0 + r.x1) / 2),
        z: Math.trunc((r.z0 + r.z1) / 2),
        y: Math.round(levelHalf(spawnBlock.level) / 2)
      };
    }
    let visited = null;
    let spawnInside = false;
    if (spawn) {
      const sx = spawn.x - x0, sz = spawn.z - z0;
      spawnInside = sx >= 0 && sx < W && sz >= 0 && sz < W;
      if (spawnInside) {
        visited = new Uint8Array(W * W);
        const queue = new Int32Array(W * W);
        let head = 0, tail = 0;
        const start = sz * W + sx;
        visited[start] = 1;
        queue[tail++] = start;
        while (head < tail) {
          const idx = queue[head++];
          const px = idx % W, pz = (idx - px) / W;
          const s = floor[idx];
          if (px > 0) tryStep(idx, idx - 1, s);
          if (px < W - 1) tryStep(idx, idx + 1, s);
          if (pz > 0) tryStep(idx, idx - W, s);
          if (pz < W - 1) tryStep(idx, idx + W, s);
        }
        function tryStep(from, ni, s) {
          if (visited[ni]) return;
          if (furn[ni] === 1) return;
          const ns = floor[ni];
          if (ns === EMPTY) return;
          // one stair block hides two half-steps in the same column
          const climb = kind[from] === 4 && kind[ni] === 4 ? 2 : 1;
          if (ns - s <= climb) {
            visited[ni] = 1;
            queue[tail++] = ni;
          }
        }
      }
    }

    // ---- stats ----
    const margin = Math.min(48, Math.floor(W / 6));
    let carvedInner = 0, reachInner = 0;
    let floorMin = 9999, floorMax = -9999;
    for (let pz = margin; pz < W - margin; pz++) {
      const row = pz * W;
      for (let px = margin; px < W - margin; px++) {
        const idx = row + px;
        const s = floor[idx];
        if (s === EMPTY) continue;
        if (furn[idx] === 1) continue; // a cabinet is furniture, not walkable space
        carvedInner++;
        if (s < floorMin) floorMin = s;
        if (s > floorMax) floorMax = s;
        if (visited && visited[idx]) reachInner++;
      }
    }
    let carved = 0;
    for (let i = 0; i < floor.length; i++) if (floor[i] !== EMPTY) carved++;

    if (visited) {
      for (const r of rooms) {
        const cpx = r.x - x0, cpz = r.z - z0;
        // rooms cut by the preview edge would report false seals
        if (cpx < margin || cpx >= W - margin || cpz < margin || cpz >= W - margin) continue;
        let open = 0, seen = 0;
        for (let x = Math.max(r.x0, x0 + margin); x <= Math.min(r.x1, x0 + W - 1 - margin); x++) {
          for (let z = Math.max(r.z0, z0 + margin); z <= Math.min(r.z1, z0 + W - 1 - margin); z++) {
            const i2 = (z - z0) * W + (x - x0);
            if (floor[i2] === EMPTY) continue;
            open++;
            if (visited[i2]) seen++;
          }
        }
        if (open > 0) r.sealed = seen === 0;
      }
    }
    const sealedCount = rooms.filter(r => r.sealed).length;
    const roomsInner = rooms.filter(r => {
      const px = r.x - x0, pz = r.z - z0;
      return px >= margin && px < W - margin && pz >= margin && pz < W - margin;
    }).length;

    return {
      cfg: Object.assign({}, CFG),
      x0, z0, W, margin,
      floor, kind, visited,
      rooms, blockMap,
      spawn, furn,
      stats: {
        span: W, carved, carvedInner,
        carvedPct: 100 * carvedInner / Math.max(1, (W - 2 * margin) * (W - 2 * margin)),
        reachPct: visited && carvedInner > 0 ? 100 * reachInner / carvedInner : null,
        sealedRooms: visited ? sealedCount : null,
        rooms: rooms.length,
        roomsInner,
        cabinetCount,
        deskCount,
        chairCount,
        shelfCount,
        extraCount,
        floorMin: floorMin === 9999 ? null : floorMin / 2,
        floorMax: floorMax === -9999 ? null : floorMax / 2,
        spawnInside
      },
      probe(wx, wz) {
        const px = wx - x0, pz = wz - z0;
        if (px < 0 || px >= W || pz < 0 || pz >= W) return null;
        const idx = pz * W + px;
        const s = floor[idx];
        const { i, j } = blockAt(wx, wz);
        const info = blockMap.get(i + "," + j) || blockInfo(i, j);
        let onStairs = false;
        if (info) {
          for (const st of info.stairs) {
            if (st.x === wx && st.z === wz) { onStairs = true; break; }
          }
        }
        return {
          x: wx, z: wz, i, j,
          kind: kind[idx],
          carved: s !== EMPTY,
          floorHalf: s,
          floorY: s === EMPTY ? null : s / 2,
          onStairs,
          reachable: visited ? !!visited[idx] : null,
          block: info
        };
      }
    };
  }

  function blockLayoutAt(x, z) {
    const { i, j } = blockAt(x, z);
    return blockInfo(i, j);
  }

  return {
    DEFAULT_CONFIG,
    LAYOUT_NAMES,
    LIGHT_EVERY,
    DARK_DISTRICT,
    build,
    blockLayoutAt,
    levelHalf,
    blockLevel,
    lightAt,
    darkAt,
    debug: {
      streetX: (i, seg = 0) => streetX(i, seg), streetZ: (j, seg = 0) => streetZ(j, seg),
      streetWidth, segV, segH, blockInfo, hallRect, spawnCell,
      segRawV, segRawH, externalV, externalH, specialBlock, isPlaza: inPlaza
    }
  };
});
