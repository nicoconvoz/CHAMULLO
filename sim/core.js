/*
 * CHAMULLO network simulator core.
 *
 * Deterministic model of the protocol rules in docs/specs: contact cards, compass routing toward a
 * destination zone with bounded fanout (the river), widening and perimeter mode around empty areas
 * (the lake), local eco inside the destination zone, per-letter destination tags, hop chains and the
 * double receipt (destination delivery + origin confirmation) before carriers are paid.
 *
 * Built on the same recipe as ICEBREAK's prototype/p2p/core.js: pure, seeded RNG, spatial grid,
 * metrics read model. Cryptography and real transports are modeled, not executed.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.CHAMULLO_SIM = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const DEFAULT_CONFIG = {
    version: '0.1.0',
    radio: { phoneRangeM: 100, antennaRangeM: 5000, hopMs: 45 },
    internet: { links: 4, hopMs: 90 },
    zones: { celda: 100, barrio: 2000, pueblo: 20000, region: 150000 },
    routing: { fanout: 2, widenDeg: 90, perimeterBudget: 64, localTtl: 64, maxHops: 64, zoneLevel: 'barrio' },
    envelope: { expS: 3600 },
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
    let now = 0, nextId = 1, nextMsg = 1;
    const nodes = new Map();
    const antennas = [];
    const messages = new Map();
    const queue = createQueue();
    const wire = [];
    const totals = { duplicates: 0, drops: { maxHops: 0, stuck: 0, noRoute: 0, expired: 0, linkLost: 0 } };

    const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
    const range = n => (n.kind === 'antenna' ? cfg.radio.antennaRangeM : cfg.radio.phoneRangeM);
    const get = id => nodes.get(id);

    /* ---------- zones ---------- */
    const zoneIdx = (p, level) => { const s = cfg.zones[level]; return [Math.floor(p.x / s), Math.floor(p.y / s)]; };
    const zoneKey = (p, level) => { const [zx, zy] = zoneIdx(p, level); return `${level}:${zx}:${zy}`; };
    function zoneCenter(key) {
      const [level, zx, zy] = key.split(':'); const s = cfg.zones[level];
      return { x: (+zx + 0.5) * s, y: (+zy + 0.5) * s };
    }

    /* ---------- spatial index: grid sized to the phone range; antennas are few, scanned apart ---------- */
    const G = cfg.radio.phoneRangeM;
    const grid = new Map();
    const ckey = (cx, cy) => `${cx}:${cy}`;
    function gridPut(n) { const k = ckey(Math.floor(n.x / G), Math.floor(n.y / G)); if (!grid.has(k)) grid.set(k, []); grid.get(k).push(n); }
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
      const n = {
        id, x: o.x ?? 0, y: o.y ?? 0, kind: o.kind || 'phone', online: o.online ?? true,
        internet: !!o.internet, inet: new Set(), cards: new Map(), account: o.account || id,
        tagSecret: fnv1a(`tag|${id}|${Math.floor(rng() * 0xffffffff)}`), seen: new Set(), credits: 0
      };
      nodes.set(id, n); gridPut(n);
      if (n.kind === 'antenna') antennas.push(n);
      return id;
    }
    function setOnline(id, on) { get(id).online = on; }
    function setInternet(id, on) { const n = get(id); n.internet = on; if (!on) { for (const m of n.inet) get(m).inet.delete(id); n.inet.clear(); } }
    // Each member with internet keeps up to `links` long links to other members with internet.
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
    // Radio neighbors: both radios must reach, so the shorter range decides. Internet links are added on top.
    function neighborNodes(n) {
      if (!n.online) return [];
      const found = new Map();
      for (const m of within(n, Math.min(range(n), cfg.radio.phoneRangeM))) found.set(m.id, m);
      if (n.kind === 'antenna') for (const m of antennas) if (dist(n, m) <= cfg.radio.antennaRangeM) found.set(m.id, m);
      for (const id of n.inet) found.set(id, get(id));
      found.delete(n.id);
      return [...found.values()].filter(m => m.online).sort((a, b) => dist(n, a) - dist(n, b) || (a.id < b.id ? -1 : 1));
    }
    const linkMs = (a, b) => (a.inet.has(b.id) ? cfg.internet.hopMs : cfg.radio.hopMs);

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
        touched: new Set([from]), transmissions: 0, drops: 0, dropReasons: {}
      };
      messages.set(msgId, m);
      A.seen.add(msgId);
      route(A, m, { path: [from], pFrom: null, pHops: 0, local: null });
      return { ok: true, msgId };
    }
    function drop(m, reason) { m.drops++; m.dropReasons[reason] = (m.dropReasons[reason] || 0) + 1; totals.drops[reason]++; }
    function transmit(n, target, m, copy) {
      m.transmissions++;
      if (wire.length < cfg.log.wireMax) wire.push({ t: now, from: n.id, to: target.id, msgId: m.id, src: m.src, dst: m.tag });
      queue.push({ at: now + linkMs(n, target), type: 'arrive', node: target.id, msg: m.id, copy: { ...copy, path: [...copy.path, target.id] } });
    }

    // The forwarding decision a carrier makes with what it can see: its neighbors, the zone and the compass.
    function route(n, m, copy) {
      const hops = copy.path.length - 1;
      if (now > m.exp) return drop(m, 'expired');
      if (hops >= m.maxHops) return drop(m, 'maxHops');
      const nbrs = neighborNodes(n).filter(x => !copy.path.includes(x.id));
      const level = cfg.routing.zoneLevel;

      if (zoneKey(n, level) === m.zone) { // local eco inside the destination zone
        const local = copy.local == null ? cfg.routing.localTtl : copy.local - 1;
        if (local < 0) return;
        for (const t of nbrs) if (zoneKey(t, level) === m.zone) transmit(n, t, m, { ...copy, local });
        return;
      }

      const here = dist(n, m.center);
      let { pFrom, pHops } = copy;
      if (pFrom != null && here < pFrom) { pFrom = null; pHops = 0; }
      const progress = nbrs.filter(x => dist(x, m.center) < here).sort((a, b) => dist(a, m.center) - dist(b, m.center));
      if (pFrom == null && progress.length) { // the river
        for (const t of progress.slice(0, cfg.routing.fanout)) transmit(n, t, m, { ...copy, pFrom: null, pHops: 0 });
        return;
      }
      // the lake: widen toward the compass first, otherwise go around it
      if (pFrom == null) pFrom = here;
      if (++pHops > cfg.routing.perimeterBudget) return drop(m, 'stuck');
      const b = bearing(n, m.center);
      const wide = nbrs.filter(x => angleDiff(bearing(n, x), b) <= cfg.routing.widenDeg).sort((x, y) => angleDiff(bearing(n, x), b) - angleDiff(bearing(n, y), b));
      const around = [...nbrs].sort((x, y) => dist(x, m.center) - dist(y, m.center));
      const next = wide[0] || around[0];
      if (!next) return drop(m, 'noRoute');
      transmit(n, next, m, { ...copy, pFrom, pHops });
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
        // the journey goes back to the origin, which confirms it; only then carriers are paid
        queue.push({ at: now + (now - m.sentAt), type: 'confirm', msg: m.id });
        return;
      }
      route(n, m, ev.copy);
    }
    function confirm(ev) {
      const m = messages.get(ev.msg); m.confirmedAt = now;
      for (const id of m.path.slice(1, -1)) get(id).credits++;
    }
    function handle(ev) { if (ev.type === 'arrive') arrive(ev); else if (ev.type === 'confirm') confirm(ev); }

    /* ---------- clock ---------- */
    function step(ms) { const end = now + ms; while (queue.size && queue.peek().at <= end) { const ev = queue.pop(); now = ev.at; handle(ev); } now = end; }
    function runUntilIdle(maxMs) { const end = now + maxMs; while (queue.size && queue.peek().at <= end) { const ev = queue.pop(); now = ev.at; handle(ev); } }

    /* ---------- read model ---------- */
    function delivery(msgId) {
      const m = messages.get(msgId);
      return { delivered: m.delivered, deliveredAt: m.deliveredAt, confirmedAt: m.confirmedAt, path: m.path, hops: m.path ? m.path.length - 1 : null };
    }
    function messageStats(msgId) {
      const m = messages.get(msgId);
      return { touched: m.touched.size, transmissions: m.transmissions, drops: m.drops, dropReasons: { ...m.dropReasons } };
    }
    function metrics() {
      const all = [...messages.values()]; const done = all.filter(m => m.delivered);
      const transmissions = all.reduce((s, m) => s + m.transmissions, 0);
      return {
        t: now, nodes: nodes.size, online: [...nodes.values()].filter(n => n.online).length,
        messages: { sent: all.length, delivered: done.length, confirmed: all.filter(m => m.confirmedAt != null).length, deliveryRatio: all.length ? done.length / all.length : 0 },
        latency: { p50: pct(done.map(m => m.deliveredAt - m.sentAt), 0.5), p95: pct(done.map(m => m.deliveredAt - m.sentAt), 0.95) },
        hops: { p50: pct(done.map(m => m.path.length - 1), 0.5), p95: pct(done.map(m => m.path.length - 1), 0.95), max: done.reduce((x, m) => Math.max(x, m.path.length - 1), 0) },
        cost: { transmissions, transmissionsPerLetter: all.length ? transmissions / all.length : 0, touchedPerLetter: all.length ? all.reduce((s, m) => s + m.touched.size, 0) / all.length : 0 },
        drops: { ...totals.drops }, duplicates: totals.duplicates
      };
    }

    return {
      cfg, addNode, setOnline, setInternet, linkInternet, exchangeCards, send,
      neighbors: id => neighborNodes(get(id)).map(m => m.id),
      zoneOf: (id, level = cfg.routing.zoneLevel) => zoneKey(get(id), level),
      node: id => get(id), nodeIds: () => [...nodes.keys()], credits: id => get(id).credits,
      delivery, messageStats, metrics, wireLog: () => wire.slice(),
      step, runUntilIdle, now: () => now
    };
  }

  return { DEFAULT_CONFIG, createSim, withConfig };
});
