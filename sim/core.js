/*
 * CHAMULLO network simulator core.
 *
 * Deterministic model of the protocol rules in docs/specs: the phone's own radio used raw ("el grito",
 * long-range or normal mode, one shout heard by every neighbor in range, a turn in the superframe per hop),
 * contact cards, compass routing toward a destination zone with bounded fanout (the river), widening and
 * perimeter mode around empty areas (the lake), local eco inside the destination zone, per-letter
 * destination tags, optional carry-and-forward by walking phones, and the double receipt (destination
 * delivery + origin confirmation) before carriers are paid.
 *
 * Built on the same recipe as ICEBREAK's prototype/p2p/core.js: pure, seeded RNG, spatial grid,
 * metrics read model. Cryptography and real radios are modeled, not executed.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.CHAMULLO_SIM = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const DEFAULT_CONFIG = {
    version: '0.2.0',
    // Normal LE 1M vs long-range Coded PHY (S=8): ≈1.5–2× the range (Air Interface §3.2). Antennas are optional home nodes.
    radio: { normalRangeM: 100, codedRangeM: 200, codedShare: 0.7, antennaRangeM: 5000 },
    // Air Interface §5: 1 s superframe; a shout waits for its turn, then spends its airtime at 125 kbit/s.
    grito: { superframeMs: 1000, airKbps: 125, gritoBytes: 400 },
    internet: { links: 4, hopMs: 90 },
    zones: { celda: 100, barrio: 2000, pueblo: 20000, region: 150000 },
    routing: { fanout: 2, minProgressM: 10, widenDeg: 90, perimeterBudget: 32, localTtl: 64, maxHops: 64, zoneLevel: 'barrio', carry: false },
    envelope: { expS: 3600 },
    world: { areaM: 2000 },
    mobility: { tickMs: 1000 },
    log: { wireMax: 200000 }
  };

  /* ---------- helpers ---------- */
  const clone = o => JSON.parse(JSON.stringify(o));
  function merge(base, over) {
    for (const k of Object.keys(over || {})) {
      if (over[k] && typeof over[k] === 'object' && !Array.isArray(over[k]) && base[k] && typeof base[k] === 'object') merge(base[k], over[k]);
      else base[k] = over[k];
    }
    return base;
  }
  const withConfig = over => merge(clone(DEFAULT_CONFIG), over);
  function fnv1a(str) { let h = 0x811c9dc5; for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; } return h.toString(16).padStart(8, '0'); }
  function rngFrom(seed) {
    let a = seed >>> 0;
    return function () {
      a = (a + 0x6D2B79F5) >>> 0; let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const pct = (arr, p) => { if (!arr.length) return 0; const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
  const bearing = (from, to) => ((Math.atan2(to.x - from.x, to.y - from.y) * 180 / Math.PI) + 360) % 360;
  const angleDiff = (a, b) => Math.abs(((a - b + 540) % 360) - 180);

  // Min-heap of scheduled events ordered by time, then insertion order (keeps runs deterministic).
  function createQueue() {
    const h = []; let seq = 0;
    const less = (i, j) => h[i].at < h[j].at || (h[i].at === h[j].at && h[i].seq < h[j].seq);
    const swap = (i, j) => { const t = h[i]; h[i] = h[j]; h[j] = t; };
    return {
      get size() { return h.length; },
      peek: () => h[0],
      push(ev) {
        ev.seq = seq++; h.push(ev);
        for (let i = h.length - 1; i > 0;) { const p = (i - 1) >> 1; if (!less(i, p)) break; swap(i, p); i = p; }
      },
      pop() {
        const top = h[0]; const last = h.pop();
        if (h.length) {
          h[0] = last;
          for (let i = 0; ;) {
            const l = 2 * i + 1, r = l + 1; let m = i;
            if (l < h.length && less(l, m)) m = l;
            if (r < h.length && less(r, m)) m = r;
            if (m === i) break; swap(i, m); i = m;
          }
        }
        return top;
      }
    };
  }

  /* ======================================================================= */
  function createSim(config = DEFAULT_CONFIG, seed = 1) {
    const cfg = clone(config);
    const rng = rngFrom(seed);
    let now = 0, nextId = 1, nextMsg = 1, lastTick = 0;
    const nodes = new Map();
    const antennas = [];
    const messages = new Map();
    const queue = createQueue();
    const wire = [];
    const events = [];
    const totals = { duplicates: 0, covered: 0, drops: { maxHops: 0, stuck: 0, noRoute: 0, expired: 0, linkLost: 0 } };

    const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
    const get = id => nodes.get(id);
    const log = (type, detail = '', data = null) => { events.push({ t: now, type, detail, data }); if (events.length > 500) events.shift(); };
    const phoneRange = n => (n.coded ? cfg.radio.codedRangeM : cfg.radio.normalRangeM);
    // Both radios must reach: between two phones the long-range mode works only if both have it.
    function linkRange(a, b) {
      if (a.kind === 'antenna' && b.kind === 'antenna') return cfg.radio.antennaRangeM;
      if (a.kind === 'antenna') return phoneRange(b);
      if (b.kind === 'antenna') return phoneRange(a);
      return a.coded && b.coded ? cfg.radio.codedRangeM : cfg.radio.normalRangeM;
    }

    /* ---------- zones ---------- */
    const zoneIdx = (p, level) => { const s = cfg.zones[level]; return [Math.floor(p.x / s), Math.floor(p.y / s)]; };
    const zoneKey = (p, level) => { const [zx, zy] = zoneIdx(p, level); return `${level}:${zx}:${zy}`; };
    function zoneCenter(key) {
      const [level, zx, zy] = key.split(':'); const s = cfg.zones[level];
      return { x: (+zx + 0.5) * s, y: (+zy + 0.5) * s };
    }

    /* ---------- spatial index: grid sized to the longest phone range; antennas are few, scanned apart ---------- */
    const G = Math.max(cfg.radio.normalRangeM, cfg.radio.codedRangeM);
    const grid = new Map();
    const ckey = (cx, cy) => `${cx}:${cy}`;
    function gridPut(n) { n.cell = ckey(Math.floor(n.x / G), Math.floor(n.y / G)); if (!grid.has(n.cell)) grid.set(n.cell, new Set()); grid.get(n.cell).add(n); }
    function gridMove(n) { const k = ckey(Math.floor(n.x / G), Math.floor(n.y / G)); if (k === n.cell) return; grid.get(n.cell).delete(n); gridPut(n); }
    function within(p, r) {
      const cx = Math.floor(p.x / G), cy = Math.floor(p.y / G), rings = Math.ceil(r / G);
      const out = [];
      for (let dx = -rings; dx <= rings; dx++) for (let dy = -rings; dy <= rings; dy++) {
        const cell = grid.get(ckey(cx + dx, cy + dy)); if (!cell) continue;
        for (const m of cell) if (dist(m, p) <= r) out.push(m);
      }
      return out;
    }

    /* ---------- nodes ---------- */
    function addNode(o = {}) {
      const id = 'n' + (nextId++);
      const coded = o.coded ?? (rng() < cfg.radio.codedShare);
      const n = {
        id, x: o.x ?? 0, y: o.y ?? 0, kind: o.kind || 'phone', coded, online: o.online ?? true,
        speed: o.speed ?? 0, tx: null, ty: null,
        internet: !!o.internet, inet: new Set(), cards: new Map(), pocket: new Map(),
        tagSecret: fnv1a(`tag|${id}|${Math.floor(rng() * 0xffffffff)}`), seen: new Set(), accepted: new Set(), credits: 0, shouts: 0
      };
      nodes.set(id, n); gridPut(n);
      if (n.kind === 'antenna') antennas.push(n);
      return id;
    }
    function setOnline(id, on) { get(id).online = on; log(on ? 'NODE_ONLINE' : 'NODE_OFFLINE', id); }
    function setInternet(id, on) { const n = get(id); n.internet = on; if (!on) { for (const m of n.inet) get(m).inet.delete(id); n.inet.clear(); } }
    function moveTo(id, x, y) { const n = get(id); n.x = x; n.y = y; n.tx = null; gridMove(n); }
    // Each member with internet keeps up to `links` long links to other members with internet (optional, off by default).
    function linkInternet() {
      const members = [...nodes.values()].filter(n => n.internet);
      for (const n of members) {
        const pool = members.filter(m => m !== n && !n.inet.has(m.id));
        for (let i = pool.length - 1; i > 0; i--) { const j = Math.floor(rng() * (i + 1)); [pool[i], pool[j]] = [pool[j], pool[i]]; }
        for (const m of pool) {
          if (n.inet.size >= cfg.internet.links) break;
          if (m.inet.size >= cfg.internet.links) continue;
          n.inet.add(m.id); m.inet.add(n.id);
        }
      }
    }
    function neighborNodes(n) {
      if (!n.online) return [];
      const found = new Map();
      for (const m of within(n, G)) if (dist(n, m) <= linkRange(n, m)) found.set(m.id, m);
      if (n.kind === 'antenna') for (const m of antennas) if (dist(n, m) <= cfg.radio.antennaRangeM) found.set(m.id, m);
      for (const id of n.inet) found.set(id, get(id));
      found.delete(n.id);
      return [...found.values()].filter(m => m.online).sort((a, b) => dist(n, a) - dist(n, b) || (a.id < b.id ? -1 : 1));
    }

    /* ---------- contact cards ---------- */
    function exchangeCards(a, b) {
      const A = get(a), B = get(b), level = cfg.routing.zoneLevel;
      A.cards.set(b, { zone: zoneKey(B, level), tagSecret: B.tagSecret });
      B.cards.set(a, { zone: zoneKey(A, level), tagSecret: A.tagSecret });
    }

    /* ---------- letters ---------- */
    const tagOf = (secret, nonce) => fnv1a(`TAG|${secret}|${nonce}`);
    function send(from, to, opts = {}) {
      const A = get(from); const card = A.cards.get(to);
      if (!card) return { ok: false, reason: 'no_card' };
      const msgId = 'm' + (nextMsg++);
      const nonce = fnv1a(`nonce|${msgId}|${Math.floor(rng() * 0xffffffff)}`);
      const m = {
        id: msgId, from, to, nonce, src: 'e' + fnv1a(`eph|${nonce}`), tag: tagOf(card.tagSecret, nonce),
        zone: card.zone, center: zoneCenter(card.zone), sentAt: now,
        exp: now + (opts.expS ?? cfg.envelope.expS) * 1000, maxHops: opts.maxHops ?? cfg.routing.maxHops,
        delivered: false, deliveredAt: null, confirmedAt: null, path: null,
        touched: new Set([from]), gritos: 0, drops: 0, dropReasons: {}
      };
      messages.set(msgId, m);
      A.seen.add(msgId); A.accepted.add(msgId);
      log('LETTER_SENT', `${from} → zona ${card.zone}`, { msg: msgId, from });
      forward(A, m, { path: [from], pFrom: null, pHops: 0, local: null });
      return { ok: true, msgId };
    }
    function drop(m, reason) { m.drops++; m.dropReasons[reason] = (m.dropReasons[reason] || 0) + 1; totals.drops[reason]++; }

    // One shout: everyone named hears it after the sender's turn comes and the frame is on the air.
    function shout(n, targets, m, copy) {
      m.gritos++; n.shouts++;
      const to = targets.map(t => t.id);
      for (const t of targets) t.accepted.add(m.id); // OFFER → ACCEPT happens before the letter travels
      if (wire.length < cfg.log.wireMax) wire.push({ t: now, from: n.id, to, msgId: m.id, src: m.src, dst: m.tag });
      const g = cfg.grito;
      const radioMs = rng() * g.superframeMs + g.gritoBytes * 8 / g.airKbps;
      for (const t of targets) {
        const at = now + (n.inet.has(t.id) ? cfg.internet.hopMs : radioMs);
        queue.push({ at, type: 'arrive', node: t.id, msg: m.id, from: n.id, copy: { ...copy, path: [...copy.path, t.id] } });
      }
    }

    // The forwarding decision a carrier makes with what it can see: its neighbors, the zone and the compass.
    // Returns true when it shouted, or the reason it could not.
    function route(n, m, copy) {
      const hops = copy.path.length - 1;
      if (now > m.exp) return 'expired';
      if (hops >= m.maxHops) return 'maxHops';
      const around = neighborNodes(n).filter(x => !copy.path.includes(x.id));
      // A neighbor that already has the letter declines the OFFER (Proof of Relay §5.2), so only fresh ones are candidates.
      const nbrs = around.filter(x => !x.accepted.has(m.id) && !x.seen.has(m.id));
      const level = cfg.routing.zoneLevel;

      if (zoneKey(n, level) === m.zone) { // local eco: one shout to every neighbor inside the zone
        const local = copy.local == null ? cfg.routing.localTtl : copy.local - 1;
        const inZone = nbrs.filter(t => zoneKey(t, level) === m.zone);
        if (local < 0 || !inZone.length) return 'ecoEnd';
        shout(n, inZone, m, { ...copy, local });
        return true;
      }

      const here = dist(n, m.center);
      let { pFrom, pHops } = copy;
      if (pFrom != null && here < pFrom) pFrom = null; // back on the river; the detour budget stays spent
      // the river: the best `fanout` carriers toward the zone; a place already taken by another copy stays covered
      const best = around.filter(x => dist(x, m.center) <= here - cfg.routing.minProgressM).sort((a, b) => dist(a, m.center) - dist(b, m.center)).slice(0, cfg.routing.fanout);
      if (pFrom == null && best.length) {
        const fresh = best.filter(x => nbrs.includes(x));
        if (!fresh.length) return 'covered';
        shout(n, fresh, m, { ...copy, pFrom: null, pHops });
        return true;
      }
      // the lake: widen toward the compass first, otherwise go around it
      if (pFrom == null) pFrom = here;
      if (++pHops > cfg.routing.perimeterBudget) return 'stuck';
      const b = bearing(n, m.center);
      const wide = nbrs.filter(x => angleDiff(bearing(n, x), b) <= cfg.routing.widenDeg).sort((x, y) => angleDiff(bearing(n, x), b) - angleDiff(bearing(n, y), b));
      const next = wide[0] || [...nbrs].sort((x, y) => dist(x, m.center) - dist(y, m.center))[0];
      if (!next) return 'noRoute';
      shout(n, [next], m, { ...copy, pFrom, pHops });
      return true;
    }
    // A carrier that cannot pass the letter on either keeps it in its pocket (carry mode) or drops it.
    function forward(n, m, copy) {
      const r = route(n, m, copy);
      if (r === true) return;
      if (r === 'ecoEnd' && !cfg.routing.carry) return; // the zone search ran out here; other branches may still find the recipient
      if (r === 'covered') { totals.covered++; if (!cfg.routing.carry) return; } // with carrying on, a spare copy is kept, never thrown away
      if (cfg.routing.carry && (r === 'stuck' || r === 'noRoute' || r === 'ecoEnd' || r === 'covered')) {
        n.pocket.set(m.id, { ...copy, pFrom: null }); // the detour budget stays spent: from the pocket only a real way forward counts
        log('LETTER_POCKETED', `${n.id} guarda ${m.id}`, { node: n.id, msg: m.id });
        return;
      }
      drop(m, r);
      log('LETTER_DROPPED', `${m.id} en ${n.id} · ${r}`, { node: n.id, msg: m.id });
    }

    function arrive(ev) {
      const n = get(ev.node), m = messages.get(ev.msg);
      if (!n.online) return drop(m, 'linkLost');
      m.touched.add(n.id);
      if (n.seen.has(m.id)) { totals.duplicates++; return; }
      n.seen.add(m.id);
      if (tagOf(n.tagSecret, m.nonce) === m.tag) { // the recipient recognizes its tag
        if (m.delivered) return;
        m.delivered = true; m.deliveredAt = now; m.path = ev.copy.path;
        log('LETTER_DELIVERED', `${m.id} en ${m.path.length - 1} saltos`, { msg: m.id, node: n.id });
        // the journey goes back to the origin, which confirms it; only then carriers are paid
        queue.push({ at: now + (now - m.sentAt), type: 'confirm', msg: m.id });
        return;
      }
      forward(n, m, ev.copy);
    }
    function confirm(ev) {
      const m = messages.get(ev.msg); m.confirmedAt = now;
      for (const id of m.path.slice(1, -1)) get(id).credits++;
      log('JOURNEY_CONFIRMED', `${m.id} · ${Math.max(0, m.path.length - 2)} carteros cobran`, { msg: m.id });
    }
    function handle(ev) { if (ev.type === 'arrive') arrive(ev); else if (ev.type === 'confirm') confirm(ev); }

    /* ---------- the world between events: walking and pockets ---------- */
    const movers = () => [...nodes.values()].some(n => n.speed && n.online);
    const pocketCount = () => { let c = 0; for (const n of nodes.values()) c += n.pocket.size; return c; };
    function tickWorld(dtS) {
      for (const n of nodes.values()) {
        if (!n.speed || !n.online) continue;
        if (n.tx === null || Math.hypot(n.tx - n.x, n.ty - n.y) < 2) { n.tx = rng() * cfg.world.areaM; n.ty = rng() * cfg.world.areaM; }
        const d = Math.hypot(n.tx - n.x, n.ty - n.y); const s = Math.min(d, n.speed * dtS);
        n.x += (n.tx - n.x) / d * s; n.y += (n.ty - n.y) / d * s; gridMove(n);
      }
      for (const n of nodes.values()) {
        if (!n.pocket.size || !n.online) continue;
        for (const [id, copy] of [...n.pocket]) {
          const m = messages.get(id);
          if (m.delivered) { n.pocket.delete(id); continue; }
          if (now > m.exp) { n.pocket.delete(id); drop(m, 'expired'); continue; }
          if (route(n, m, copy) === true) { n.pocket.delete(id); log('LETTER_UNPOCKETED', `${n.id} pasa ${id}`, { node: n.id, msg: id }); }
        }
      }
    }

    /* ---------- clock ---------- */
    function advance(end, untilIdle) {
      for (;;) {
        const nextEv = queue.size ? queue.peek().at : Infinity;
        const worldBusy = movers() || pocketCount() > 0;
        if (untilIdle && !queue.size && !worldBusy) return;
        const nextTick = worldBusy ? lastTick + cfg.mobility.tickMs : Infinity;
        const t = Math.min(nextEv, nextTick);
        if (t > end || t === Infinity) break;
        if (nextEv <= nextTick) { const ev = queue.pop(); now = ev.at; handle(ev); }
        else { now = nextTick; lastTick = now; tickWorld(cfg.mobility.tickMs / 1000); }
      }
      if (!untilIdle) { now = end; if (!(movers() || pocketCount() > 0)) lastTick = now; }
    }
    const step = ms => advance(now + ms, false);
    const runUntilIdle = maxMs => advance(now + maxMs, true);

    /* ---------- read model ---------- */
    function delivery(msgId) {
      const m = messages.get(msgId);
      return { delivered: m.delivered, deliveredAt: m.deliveredAt, confirmedAt: m.confirmedAt, path: m.path, hops: m.path ? m.path.length - 1 : null };
    }
    function messageStats(msgId) {
      const m = messages.get(msgId);
      return { touched: m.touched.size, gritos: m.gritos, drops: m.drops, dropReasons: { ...m.dropReasons } };
    }
    function metrics() {
      const all = [...messages.values()]; const done = all.filter(m => m.delivered);
      const gritos = all.reduce((s, m) => s + m.gritos, 0);
      return {
        t: now, nodes: nodes.size, online: [...nodes.values()].filter(n => n.online).length,
        messages: { sent: all.length, delivered: done.length, confirmed: all.filter(m => m.confirmedAt != null).length, deliveryRatio: all.length ? done.length / all.length : 0, pocketed: pocketCount() },
        latency: { p50: pct(done.map(m => m.deliveredAt - m.sentAt), 0.5), p95: pct(done.map(m => m.deliveredAt - m.sentAt), 0.95) },
        hops: { p50: pct(done.map(m => m.path.length - 1), 0.5), p95: pct(done.map(m => m.path.length - 1), 0.95), max: done.reduce((x, m) => Math.max(x, m.path.length - 1), 0) },
        cost: { gritos, gritosPerLetter: all.length ? gritos / all.length : 0, touchedPerLetter: all.length ? all.reduce((s, m) => s + m.touched.size, 0) / all.length : 0 },
        drops: { ...totals.drops }, duplicates: totals.duplicates, covered: totals.covered
      };
    }
    function snapshot() {
      return {
        t: now,
        nodes: [...nodes.values()].map(n => ({ id: n.id, x: n.x, y: n.y, kind: n.kind, coded: n.coded, online: n.online, credits: n.credits, shouts: n.shouts, pocket: n.pocket.size })),
        letters: [...messages.values()].map(m => ({ id: m.id, from: m.from, to: m.to, center: m.center, zone: m.zone, delivered: m.delivered, path: m.path, sentAt: m.sentAt, deliveredAt: m.deliveredAt, confirmedAt: m.confirmedAt }))
      };
    }

    return {
      cfg, addNode, setOnline, setInternet, linkInternet, moveTo, exchangeCards, send,
      neighbors: id => neighborNodes(get(id)).map(m => m.id),
      zoneOf: (id, level = cfg.routing.zoneLevel) => zoneKey(get(id), level),
      zoneCenter, linkRange: (a, b) => linkRange(get(a), get(b)),
      node: id => get(id), nodeIds: () => [...nodes.keys()], credits: id => get(id).credits,
      pockets: () => [...nodes.values()].flatMap(n => [...n.pocket.keys()].map(msgId => ({ node: n.id, msgId }))),
      delivery, messageStats, metrics, snapshot, wireLog: () => wire.slice(),
      wireSince: t => { let i = wire.length; while (i > 0 && wire[i - 1].t >= t) i--; return wire.slice(i); }, drainEvents: () => events.splice(0, events.length),
      setConfig: over => merge(cfg, over),
      step, runUntilIdle, now: () => now
    };
  }

  return { DEFAULT_CONFIG, createSim, withConfig };
});
