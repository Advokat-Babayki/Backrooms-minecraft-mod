/*
 * Level 0 preview core: a JS port of Level0Plan.java (seed 0 semantics).
 * Keep DEFAULT_CONFIG in sync with the tunables in Level0Plan.java.
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

  const SALT_WEB1 = 0x5DEECE66Dn;
  const SALT_WEB2 = 0x1D2545F4914F6CDDn;
  const SALT_WEB3 = 0x2545F4914F6CDDn;
  const SALT_WIDTH = 0x6CDD1D2545F4914Dn;
  const SALT_FLOOR = 0x2545F4914F6CDD1Dn;
  const SALT_ROOM = 0x2545F4914F6CDD1Dn;
  const SALT_RAYS = 0x8D1D4C6CDD2545F4n;

  const CELL = 48;
  const BASE_FLOOR_Y = 64;
  const MAX_FLOOR_Y = 88;
  const ROOM_MAX_SIZE = 40;
  const EMPTY = -32768;

  const DEFAULT_CONFIG = Object.freeze({
    seed: 0,
    web1Spacing: 160,
    web2Spacing: 190,
    web3Spacing: 120,
    web3WidthFactor: 0.7,
    widthSpacing: 48,
    widthBase: 0.045,
    widthBreathMin: 0.9,
    widthBreathRange: 1.1,
    widthGrowMin: 0.55,
    widthGrowMax: 1.0,
    growDistance: 288,
    floorSpacing: 192,
    floorLevels: 9,
    roomNearGate: 3,
    roomFarGate: 5,
    roomNearRadius: 240,
    roomSizeMin: 8,
    roomSizeRandom: 21,
    rayCount: 2,
    rayMaxReach: 12,
    rayBlindOneIn: 4,
    rayBlindLen: 6
  });

  const ROOM_NAMES = {
    6: "ARCHES", 7: "HALF-WALL", 8: "FALSE DOORS", 9: "FLOODED", 10: "CARPET RIDGE",
    11: "STAIRS TO NOWHERE", 12: "FALLEN LIGHTS", 13: "WALL GAP", 14: "DEBRIS",
    15: "CROSS WALLS", 16: "RED TINT", 17: "WIRE SPOOLS", 18: "PEELING WALLS",
    19: "CRAWL TUNNEL", 20: "PAPER STACKS", 21: "GRID HOLES", 22: "DROPPED CEILING",
    23: "BENCHES", 24: "THRESHOLD", 25: "WALL SCRAWL", 26: "SLOT WINDOWS", 27: "NEST",
    28: "PIPE CHASE", 29: "SUNKEN SQUARE", 30: "WALL BULGE", 31: "CORRIDOR STUB",
    32: "MIRROR PAIR", 33: "LEDGE WALK", 34: "BIG CLEAN", 35: "TWO PILLAR ISLAND",
    36: "SLIT LIGHT", 37: "STEP ROWS", 38: "NOOK CIRCLE", 39: "FLOODED PIT",
    40: "KEYHOLE", 41: "MONITOR WALL", 42: "STEEP PIT", 43: "LOFT SLAB",
    44: "PAPER BLIZZARD", 45: "TIGHT GRID", 46: "BUMP CEILING", 47: "SOAKED RUN",
    48: "CROOKED PILLARS", 49: "BROKEN TILES", 50: "ENDCAP"
  };

  let CFG = Object.assign({}, DEFAULT_CONFIG);
  let SEED = 0n;
  let F = null;             // current noise fields
  let view0 = { x0: 0, z0: 0, W: 0 };

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

  function makeField(spacing, salt, minX, minZ, size) {
    const iMin = Math.floor(minX / spacing) - 1;
    const iMax = Math.floor((minX + size) / spacing) + 1;
    const jMin = Math.floor(minZ / spacing) - 1;
    const jMax = Math.floor((minZ + size) / spacing) + 1;
    const nx = iMax - iMin + 1;
    const nz = jMax - jMin + 1;
    const vals = new Float64Array(nx * nz);
    for (let j = 0; j < nz; j++) {
      for (let i = 0; i < nx; i++) {
        vals[j * nx + i] = Number(hash64(iMin + i, jMin + j, salt) >> 11n) / 9007199254740992;
      }
    }
    return function (x, z) {
      const fx = x / spacing, fz = z / spacing;
      const ix = Math.floor(fx), iz = Math.floor(fz);
      const tx = smooth(fx - ix), tz = smooth(fz - iz);
      const ai = ix - iMin, aj = iz - jMin;
      const v00 = vals[aj * nx + ai], v10 = vals[aj * nx + ai + 1];
      const v01 = vals[(aj + 1) * nx + ai], v11 = vals[(aj + 1) * nx + ai + 1];
      return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    };
  }

  function makeFields(x0, z0, W) {
    const pad = Math.max(0, CFG.rayMaxReach) * 2 + 24;
    const minX = x0 - pad, minZ = z0 - pad, size = W + 2 * pad;
    return {
      web1: makeField(CFG.web1Spacing, SALT_WEB1, minX, minZ, size),
      web2: makeField(CFG.web2Spacing, SALT_WEB2, minX, minZ, size),
      web3: makeField(CFG.web3Spacing, SALT_WEB3, minX, minZ, size),
      width: makeField(CFG.widthSpacing, SALT_WIDTH, minX, minZ, size),
      floor: makeField(CFG.floorSpacing, SALT_FLOOR, minX, minZ, size)
    };
  }

  function floorHalf(x, z) {
    const q = F.floor(x, z) * (CFG.floorLevels - 1);
    return Math.round((BASE_FLOOR_Y + q * 3) * 2);
  }

  function floorSurface(x, z) {
    return Math.round(floorHalf(x, z) / 2);
  }

  function webHalfWidth(x, z) {
    const breathe = CFG.widthBreathMin + CFG.widthBreathRange * F.width(x, z);
    const ramp = CFG.growDistance > 0
      ? Math.min(1, Math.sqrt(x * x + z * z) / CFG.growDistance) : 1;
    const grow = CFG.widthGrowMin + (CFG.widthGrowMax - CFG.widthGrowMin) * ramp;
    return CFG.widthBase * breathe * grow;
  }

  function inWeb(x, z) {
    const w = webHalfWidth(x, z);
    if (Math.abs(F.web1(x, z) - 0.5) < w) return true;
    if (Math.abs(F.web2(x, z) - 0.5) < w) return true;
    return Math.abs(F.web3(x, z) - 0.5) < w * CFG.web3WidthFactor;
  }

  const DIR_X = [1, 0, -1, 0];
  const DIR_Z = [0, 1, 0, -1];

  let roomCache = new Map();

  function room(cx, cz) {
    const key = cx + "," + cz;
    if (roomCache.has(key)) return roomCache.get(key);
    const r = computeRoom(cx, cz);
    roomCache.set(key, r);
    return r;
  }

  function computeRoom(cx, cz) {
    const h = hash64(cx, cz, SALT_ROOM);
    const origin = cx === 0 && cz === 0;
    const dist = Math.sqrt((cx * CELL + 24) * (cx * CELL + 24) + (cz * CELL + 24) * (cz * CELL + 24));
    const gate = dist < CFG.roomNearRadius ? CFG.roomNearGate : CFG.roomFarGate;
    if (!origin && Number(h % 8n) >= gate) return null;

    const rand = CFG.roomSizeRandom > 0 ? Number((h >> 20n) % BigInt(CFG.roomSizeRandom)) : 0;
    const size = Math.min(CFG.roomSizeMin + rand, ROOM_MAX_SIZE);
    const half = Math.floor(size / 2);
    const centerX = cx * CELL + clampInt(6 + Number((h >> 40n) % 37n), half + 2, 46 - half);
    const centerZ = cz * CELL + clampInt(6 + Number((h >> 8n) % 37n), half + 2, 46 - half);

    // A room only exists when the web is close: its doors are short.
    const hits = [];
    for (let d = 0; d < 4; d++) {
      const hit = findDoor(centerX, centerZ, half, DIR_X[d], DIR_Z[d], d);
      if (hit) hits.push(hit);
    }
    if (!hits.length) return null;
    hits.sort((a, b) => a.len - b.len || a.dir - b.dir);

    const roomH = (hits[0].webHalf + 1) & ~1; // nearest full block
    const doors = [];
    for (const hit of hits) {
      if (doors.length >= CFG.rayCount) break;
      if (Math.abs(hit.webHalf - roomH) > 1) continue;
      doors.push({ dx: hit.dx, dz: hit.dz, len: hit.len, blind: false });
    }
    if (!doors.length) return null;

    if (CFG.rayBlindOneIn > 0 && Number((h >> 16n) % BigInt(CFG.rayBlindOneIn)) === 0) {
      for (let d = 0; d < 4; d++) {
        const used = doors.some(dd => dd.dx === DIR_X[d] && dd.dz === DIR_Z[d]);
        if (!used) {
          doors.push({ dx: DIR_X[d], dz: DIR_Z[d], len: CFG.rayBlindLen, blind: true });
          break;
        }
      }
    }
    const floorY = clampInt(Math.floor(roomH / 2), BASE_FLOOR_Y, MAX_FLOOR_Y);
    return { cx, cz, centerX, centerZ, size, floorY, doors };
  }

  function findDoor(centerX, centerZ, half, dx, dz, dir) {
    for (let o = 2; o <= CFG.rayMaxReach; o++) {
      let x, z;
      if (dz === 0) {
        x = centerX + (dx > 0 ? half + o : -half - o);
        z = centerZ;
        if (inWeb(x, z)) return { dx, dz, len: o, webHalf: floorHalf(x, z), dir };
        if (inWeb(x, z + 1)) return { dx, dz, len: o, webHalf: floorHalf(x, z + 1), dir };
      } else {
        z = centerZ + (dz > 0 ? half + o : -half - o);
        x = centerX;
        if (inWeb(x, z)) return { dx, dz, len: o, webHalf: floorHalf(x, z), dir };
        if (inWeb(x + 1, z)) return { dx, dz, len: o, webHalf: floorHalf(x + 1, z), dir };
      }
    }
    return null;
  }

  function typeValue(cx, cz) {
    let a = (BigInt(cx) * K1) & MASK;
    let b = (BigInt(cz) * K2) & MASK;
    let h = a ^ b;
    h ^= h >> 29n;
    h = (h * K3) & MASK;
    h ^= h >> 32n;
    return Number(h % 100n);
  }

  function roomType(cx, cz) {
    const v = typeValue(cx, cz);
    let t;
    if (v < 18) t = 0;
    else if (v < 28) t = 1;
    else if (v < 36) t = 2;
    else if (v < 44) t = 3;
    else if (v < 52) t = 4;
    else if (v < 62) t = 5;
    else t = 6 + Math.floor(((v - 62) * 45 + 22) / 38);
    if (t === 2) {
      const r = room(cx, cz);
      if (!r) t = 0;
      else if (r.size < 20) t = 1;
    }
    return t;
  }

  function roomTypeName(type) {
    if (type >= 6) return ROOM_NAMES[type] || ("VARIANT " + type);
    return ["PLAIN", "PILLARS", "MEZZANINE", "HOLES", "BLACKOUT", "TALL"][type] || "PLAIN";
  }

  function begin(cfg) {
    CFG = Object.assign({}, DEFAULT_CONFIG, cfg || {});
    SEED = BigInt(Math.trunc(CFG.seed) || 0);
    roomCache = new Map();
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
    view0 = { x0, z0, W };
    F = makeFields(x0, z0, W);

    const floor = new Int16Array(W * W).fill(EMPTY);
    const merge = new Int16Array(W * W).fill(EMPTY);
    const kind = new Uint8Array(W * W); // 0 mass, 1 web, 2 ray, 3 room

    // ---- web (rooms and their wall rings punch holes in it) ----
    for (let pz = 0; pz < W; pz++) {
      const z = z0 + pz;
      const cellZ = Math.floor(z / CELL);
      const row = pz * W;
      for (let px = 0; px < W; px++) {
        const x = x0 + px;
        const own = room(Math.floor(x / CELL), cellZ);
        if (own) {
          const half = Math.floor(own.size / 2);
          if (x >= own.centerX - half - 1 && x <= own.centerX + half + 1
              && z >= own.centerZ - half - 1 && z <= own.centerZ + half + 1) continue;
        }
        if (inWeb(x, z)) {
          const s = floorHalf(x, z);
          merge[row + px] = s;
          floor[row + px] = s;
          kind[row + px] = 1;
        }
      }
    }

    // ---- rooms + short doorways (ascending cells, matching the generator) ----
    const reach = CFG.rayMaxReach + 2;
    const rooms = [];
    const roomIndex = new Map();
    const cellMinX = Math.floor((x0 - reach) / CELL) - 1;
    const cellMaxX = Math.floor((x0 + W - 1 + reach) / CELL) + 1;
    const cellMinZ = Math.floor((z0 - reach) / CELL) - 1;
    const cellMaxZ = Math.floor((z0 + W - 1 + reach) / CELL) + 1;

    const putDoor = (x, z, s) => {
      const px = x - x0, pz = z - z0;
      if (px < 0 || px >= W || pz < 0 || pz >= W) return;
      const idx = pz * W + px;
      const prev = merge[idx];
      let sc = s;
      if (prev !== EMPTY) {
        if (sc > prev + 1) sc = prev + 1;
        else if (sc < prev - 1) sc = prev - 1;
      }
      merge[idx] = sc;
      floor[idx] = sc;
      kind[idx] = 2;
    };

    for (let ccx = cellMinX; ccx <= cellMaxX; ccx++) {
      for (let ccz = cellMinZ; ccz <= cellMaxZ; ccz++) {
        const rm = room(ccx, ccz);
        if (!rm) continue;
        const half = Math.floor(rm.size / 2);
        const rMinX = rm.centerX - half, rMaxX = rm.centerX + half;
        const rMinZ = rm.centerZ - half, rMaxZ = rm.centerZ + half;
        const entry = {
          cx: rm.cx, cz: rm.cz, centerX: rm.centerX, centerZ: rm.centerZ,
          size: rm.size, floorY: rm.floorY, type: roomType(ccx, ccz), sealed: false
        };
        rooms.push(entry);
        roomIndex.set(ccx + "," + ccz, entry);

        const nearRaster = rMaxX + reach >= x0 && rMinX - reach <= x0 + W - 1
          && rMaxZ + reach >= z0 && rMinZ - reach <= z0 + W - 1;
        if (!nearRaster) continue;

        const roomH = rm.floorY * 2;
        for (const door of rm.doors) {
          for (let o = 1; o <= door.len; o++) {
            if (door.dx !== 0) {
              const x = (door.dx > 0 ? rm.centerX + half : rm.centerX - half) + door.dx * o;
              putDoor(x, rm.centerZ, roomH);
              putDoor(x, rm.centerZ + 1, roomH);
            } else {
              const z = (door.dz > 0 ? rm.centerZ + half : rm.centerZ - half) + door.dz * o;
              putDoor(rm.centerX, z, roomH);
              putDoor(rm.centerX + 1, z, roomH);
            }
          }
        }

        // Room floor is authoritative (the generator carves the whole box at roomH).
        const aX = Math.max(x0, rMinX), bX = Math.min(x0 + W - 1, rMaxX);
        const aZ = Math.max(z0, rMinZ), bZ = Math.min(z0 + W - 1, rMaxZ);
        for (let x = aX; x <= bX; x++) {
          for (let z = aZ; z <= bZ; z++) {
            const idx = (z - z0) * W + (x - x0);
            floor[idx] = roomH;
            kind[idx] = 3;
          }
        }
      }
    }

    // ---- reachability from the entry room ----
    const spawn = room(0, 0);
    let visited = null;
    let spawnInside = false;
    if (spawn) {
      const sx = spawn.centerX - x0, sz = spawn.centerZ - z0;
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
          if (px > 0) tryStep(idx - 1, s);
          if (px < W - 1) tryStep(idx + 1, s);
          if (pz > 0) tryStep(idx - W, s);
          if (pz < W - 1) tryStep(idx + W, s);
        }
        function tryStep(ni, s) {
          if (visited[ni]) return;
          const ns = floor[ni];
          if (ns === EMPTY) return;
          if (ns - s <= 1) { // half-step up, any fall down
            visited[ni] = 1;
            queue[tail++] = ni;
          }
        }
      }
    }

    // ---- stats over an inset window (raster edges are not trustworthy) ----
    const margin = Math.min(48, Math.floor(W / 6));
    let carved = 0, carvedInner = 0, reachInner = 0;
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
    for (let i = 0; i < floor.length; i++) if (floor[i] !== EMPTY) carved++;

    if (visited) {
      for (const entry of rooms) {
        const px = entry.centerX - x0, pz = entry.centerZ - z0;
        if (px < margin || px >= W - margin || pz < margin || pz >= W - margin) continue;
        entry.sealed = !visited[pz * W + px];
      }
    }
    const sealedCount = rooms.filter(r => r.sealed).length;

    const carvedPct = 100 * carvedInner / (Math.max(1, (W - 2 * margin) * (W - 2 * margin)));
    const reachPct = visited && carvedInner > 0 ? 100 * reachInner / carvedInner : null;

    return {
      cfg: Object.assign({}, CFG),
      x0, z0, W, margin,
      floor, kind,
      visited,
      rooms, roomIndex,
      spawn: spawn ? { x: spawn.centerX, z: spawn.centerZ, y: spawn.floorY, inside: spawnInside } : null,
      stats: {
        span: W, carved, carvedInner, carvedPct, reachPct,
        sealedRooms: visited ? sealedCount : null,
        rooms: rooms.length,
        roomsInner: rooms.filter(r => {
          const px = r.centerX - x0, pz = r.centerZ - z0;
          return px >= margin && px < W - margin && pz >= margin && pz < W - margin;
        }).length,
        floorMin: floorMin === 9999 ? null : floorMin / 2,
        floorMax: floorMax === -9999 ? null : floorMax / 2,
        spawnInside
      },
      probe(wx, wz) {
        const px = wx - x0, pz = wz - z0;
        if (px < 0 || px >= W || pz < 0 || pz >= W) return null;
        const idx = pz * W + px;
        const s = floor[idx];
        const cellX = Math.floor(wx / CELL), cellZ = Math.floor(wz / CELL);
        const rm = roomIndex.get(cellX + "," + cellZ) || null;
        return {
          x: wx, z: wz, cellX, cellZ,
          kind: kind[idx],
          carved: s !== EMPTY,
          floorHalf: s,
          floorY: s === EMPTY ? null : s / 2,
          reachable: visited ? !!visited[idx] : null,
          room: rm
        };
      }
    };
  }

  return {
    DEFAULT_CONFIG,
    ROOM_NAMES,
    build,
    roomType,
    roomTypeName,
    floorSurface,
    debug: {
      inWeb: (x, z) => inWeb(x, z),
      floorHalf: (x, z) => floorHalf(x, z),
      webHalfWidth: (x, z) => webHalfWidth(x, z),
      room: (cx, cz) => room(cx, cz)
    }
  };
});
