/*
 * Level 0 preview core: an axis-aligned office floor plan.
 * Street grid with irregular pitch = long straight corridors and 90-degree
 * turns; blocks between streets are cut into offices, halls, suites and
 * merged rooms. Keep DEFAULT_CONFIG in sync with Level0Plan.java.
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

  const SALT_FLOOR = 0x2545F4914F6CDD1Dn;
  const SALT_SX = 0x1A2B3C4D5E6F7081n;
  const SALT_SZ = 0x2B3C4D5E6F708192n;
  const SALT_SW = 0x3C4D5E6F708192A3n;
  const SALT_SEGV = 0x4D5E6F708192A3B4n;
  const SALT_SEGH = 0x5E6F708192A3B4C5n;
  const SALT_BLOCK = 0x6F708192A3B4C5D6n;
  const SALT_DOOR = 0x708192A3B4C5D6E7n;
  const SALT_DETAIL = 0x8192A3B4C5D6E7F8n;
  const SALT_LEVEL = 0x92A3B4C5D6E7F809n;

  const EMPTY = -32768;
  const BASE_FLOOR_Y = 64;
  const MAX_FLOOR_Y = 88;

  const DEFAULT_CONFIG = Object.freeze({
    seed: 0,
    pitch: 32,
    jitter: 9,
    streetWidthMax: 7,
    segmentChance: 65,
    blockChance: 100,
    maxRooms: 3,
    doorWidth: 3,
    wideOpening: 4,
    columnSpacing: 4,
    partitionChance: 40,
    districtSize: 2,
    raisedChance: 30,
    levelHeight: 3
  });

  const LAYOUT_NAMES = {
    hall: "OPEN HALL",
    offices: "OFFICES",
    suite: "SUITE",
    merge: "MERGED ROOMS"
  };

  let CFG = Object.assign({}, DEFAULT_CONFIG);
  let SEED = 0n;
  let F = null;
  let SPAWN_I = 0, SPAWN_J = 0;
  let blockCache = new Map();

  function smooth(t) { return t * t * (3 - 2 * t); }
  function lerp(a, b, t) { return a + (b - a) * t; }
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

  // ---- Streets ----

  function streetJitter(i, salt) {
    const range = 2 * CFG.jitter + 1;
    return Number(hash64(i, 0, salt) % BigInt(range)) - CFG.jitter;
  }

  function streetX(i) {
    return i * CFG.pitch + (CFG.jitter > 0 ? streetJitter(i, SALT_SX) : 0);
  }

  function streetZ(j) {
    return j * CFG.pitch + (CFG.jitter > 0 ? streetJitter(j, SALT_SZ) : 0);
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
    return Number(hash64(i, j, SALT_SEGV) % 100n) < BigInt(CFG.segmentChance);
  }

  /** Horizontal street j, segment between vertical streets i and i+1. */
  function segH(i, j) {
    if (i === SPAWN_I && (j === SPAWN_J || j === SPAWN_J + 1)) return true;
    return Number(hash64(i, j, SALT_SEGH) % 100n) < BigInt(CFG.segmentChance);
  }

  function streetRange(min, max) {
    const pad = CFG.jitter + Math.floor(CFG.streetWidthMax / 2) + 4;
    return [
      Math.floor((min - pad) / CFG.pitch) - 1,
      Math.ceil((max + pad) / CFG.pitch) + 1
    ];
  }

  function spawnCell() {
    let i = 0;
    while (streetX(i) > 0) i--;
    while (streetX(i + 1) <= 0) i++;
    SPAWN_I = i;
    let j = 0;
    while (streetZ(j) > 0) j--;
    while (streetZ(j + 1) <= 0) j++;
    SPAWN_J = j;
  }

  function blockAt(x, z) {
    let i = Math.floor(x / CFG.pitch);
    while (streetX(i) > x) i--;
    while (streetX(i + 1) <= x) i++;
    let j = Math.floor(z / CFG.pitch);
    while (streetZ(j) > z) j--;
    while (streetZ(j + 1) <= z) j++;
    return { i, j };
  }

  // ---- Blocks and rooms ----

  function blockBounds(i, j) {
    return {
      x0: streetX(i) + streetHalf(i) + 1,
      x1: streetX(i + 1) - streetHalf(i + 1) - 1,
      z0: streetZ(j) + streetHalf(j) + 1,
      z1: streetZ(j + 1) - streetHalf(j + 1) - 1
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
    if (!forced && Number(hb % 100n) >= BigInt(CFG.blockChance)) {
      blockCache.set(key, null);
      return null;
    }
    const sides = blockSides(i, j);
    const present = ["n", "e", "s", "w"].filter(k => sides[k]);
    if (!present.length) {
      blockCache.set(key, null);
      return null;
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

    const ix0 = b.x0 + 1, ix1 = b.x1 - 1;
    const iz0 = b.z0 + 1, iz1 = b.z1 - 1;
    if (layout !== "hall" && layout !== "suite") {
      const along = vertical ? ix1 - ix0 : iz1 - iz0;
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
        if (forced && Math.abs(x - (r.x0 + r.x1) / 2) <= 2 && Math.abs(z - (r.z0 + r.z1) / 2) <= 2) return;
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
          rooms.push({ ...inner, name: LAYOUT_NAMES.suite });
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
    const info = {
      i, j, x0: b.x0, x1: b.x1, z0: b.z0, z1: b.z1,
      layout, level, rooms, doors, solids, stairs, forced
    };
    blockCache.set(key, info);
    return info;
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
      const sx = streetX(i), half = streetHalf(i);
      for (let j = sj0; j <= sj1; j++) {
        if (!segV(i, j)) continue;
        const za = streetZ(j), zb = streetZ(j + 1);
        for (let x = sx - half; x <= sx + half; x++) {
          for (let z = Math.max(za, z0); z <= Math.min(zb, z0 + W - 1); z++) open(x, z, 1, STREET_HALF);
        }
      }
    }
    for (let j = sj0; j <= sj1; j++) {
      const sz = streetZ(j), half = streetHalf(j);
      for (let i = si0; i <= si1; i++) {
        if (!segH(i, j)) continue;
        const xa = streetX(i), xb = streetX(i + 1);
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
        for (const d of b.doors) {
          const street = d.x0 === b.x0 || d.x1 === b.x1 || d.z0 === b.z0 || d.z1 === b.z1;
          const ds = street ? STREET_HALF : bh;
          for (let x = d.x0; x <= d.x1; x++) {
            for (let z = d.z0; z <= d.z1; z++) open(x, z, 2, ds);
          }
        }
        for (const st of b.stairs) {
          if (st.x < x0 || st.x >= x0 + W || st.z < z0 || st.z >= z0 + W) continue;
          open(st.x, st.z, 4, 2 * st.y - 1);
        }
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
      spawn,
      stats: {
        span: W, carved, carvedInner,
        carvedPct: 100 * carvedInner / Math.max(1, (W - 2 * margin) * (W - 2 * margin)),
        reachPct: visited && carvedInner > 0 ? 100 * reachInner / carvedInner : null,
        sealedRooms: visited ? sealedCount : null,
        rooms: rooms.length,
        roomsInner,
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
    build,
    blockLayoutAt,
    levelHalf,
    blockLevel,
    debug: {
      streetX, streetZ, streetWidth, segV, segH, blockInfo, spawnCell
    }
  };
});
