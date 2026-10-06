// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
// Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
const test = require('node:test');
const assert = require('node:assert/strict');
const { createSim: create, withConfig: withCfg, DEFAULT_CONFIG } = require('./core.js');

// Unless a test says otherwise, every phone uses the normal radio mode (no long range), so paths are predictable.
const withConfig = (over = {}) => withCfg({ ...over, radio: { codedShare: 0, ...(over.radio || {}) } });
const createSim = (config = withConfig(), seed) => create(config, seed);

// A straight line of phones `gap` meters apart, starting at (x0, y0).
function line(sim, count, gap = 80, x0 = 0, y0 = 0) {
  const ids = [];
  for (let i = 0; i < count; i++) ids.push(sim.addNode({ x: x0 + i * gap, y: y0 }));
  return ids;
}

// A grid of phones `gap` meters apart.
function grid(sim, cols, rows, gap = 80, x0 = 0, y0 = 0) {
  const ids = [];
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) ids.push(sim.addNode({ x: x0 + c * gap, y: y0 + r * gap }));
  return ids;
}

const deliver = (sim, from, to, opts) => {
  sim.exchangeCards(from, to);
  const sent = sim.send(from, to, opts);
  assert.ok(sent.ok, `send failed: ${sent.reason}`);
  sim.runUntilIdle(120000);
  return { ...sent, d: sim.delivery(sent.msgId) };
};

test('determinism: the same seed gives the same result', () => {
  const run = () => {
    const sim = createSim(withConfig(), 7);
    const ids = grid(sim, 10, 10, 70);
    const { d } = deliver(sim, ids[0], ids[99]);
    return JSON.stringify([d, sim.metrics()]);
  };
  assert.equal(run(), run());
});

test('zones: a point maps to the cell, barrio, pueblo and region that contain it', () => {
  const sim = createSim();
  const id = sim.addNode({ x: 4500, y: 25000 });
  assert.deepEqual(sim.zoneOf(id, 'celda'), 'celda:45:250');
  assert.deepEqual(sim.zoneOf(id, 'barrio'), 'barrio:2:12');
  assert.deepEqual(sim.zoneOf(id, 'pueblo'), 'pueblo:0:1');
  assert.deepEqual(sim.zoneOf(id, 'region'), 'region:0:0');
});

test('neighbors: two phones link only within the shorter radio range, and never while offline', () => {
  const sim = createSim();
  const a = sim.addNode({ x: 0, y: 0 });
  const b = sim.addNode({ x: 90, y: 0 });
  const c = sim.addNode({ x: 250, y: 0 });
  const tower = sim.addNode({ x: 3000, y: 0, kind: 'antenna' });
  const tower2 = sim.addNode({ x: 7000, y: 0, kind: 'antenna' });
  assert.deepEqual(sim.neighbors(a), [b]);
  assert.ok(!sim.neighbors(c).includes(tower), 'a phone cannot reach a far antenna: its own radio is the limit');
  assert.ok(sim.neighbors(tower).includes(tower2), 'antennas reach each other');
  sim.setOnline(b, false);
  assert.deepEqual(sim.neighbors(a), []);
});

test('contact card: nobody can write to someone whose card they do not have', () => {
  const sim = createSim();
  const [a, , , d] = line(sim, 4);
  assert.deepEqual(sim.send(a, d), { ok: false, reason: 'no_card' });
});

test('relay chain: a letter crosses a line of phones and every carrier in the middle gets paid', () => {
  const sim = createSim();
  const [a, b, c, d] = line(sim, 4, 80);
  const { d: res } = deliver(sim, a, d);
  assert.equal(res.delivered, true);
  assert.deepEqual(res.path, [a, b, c, d]);
  assert.equal(res.hops, 3);
  assert.equal(sim.credits(b), 1);
  assert.equal(sim.credits(c), 1);
  assert.equal(sim.credits(a), 0, 'the origin does not get paid for sending its own letter');
  assert.equal(sim.credits(d), 0, 'the destination does not get paid for receiving');
});

test('double receipt: carriers are paid only after the origin confirms, not when the letter arrives', () => {
  const sim = createSim();
  const [a, b, , d] = line(sim, 4, 80);
  sim.exchangeCards(a, d);
  const { msgId } = sim.send(a, d);
  while (!sim.delivery(msgId).delivered) sim.step(10);
  assert.equal(sim.credits(b), 0, 'delivered but not yet confirmed by the origin');
  sim.runUntilIdle(60000);
  assert.ok(sim.delivery(msgId).confirmedAt > sim.delivery(msgId).deliveredAt);
  assert.equal(sim.credits(b), 1);
});

test('river, not flood: a letter in a dense grid touches only a corridor of carriers', () => {
  const sim = createSim(withConfig({ routing: { zoneLevel: 'celda' } }));
  const ids = grid(sim, 30, 30, 60);
  const { msgId } = deliver(sim, ids[0], ids[ids.length - 1]);
  const m = sim.messageStats(msgId);
  assert.equal(sim.delivery(msgId).delivered, true);
  assert.ok(m.touched < ids.length / 2, `touched ${m.touched} of ${ids.length}`);
});

test('dedup: no carrier forwards the same letter twice', () => {
  const sim = createSim(withConfig({ routing: { zoneLevel: 'celda' } }));
  const ids = grid(sim, 12, 12, 60);
  const { msgId } = deliver(sim, ids[0], ids[ids.length - 1]);
  const gritos = sim.wireLog().filter(w => w.msgId === msgId);
  const perSender = {};
  for (const w of gritos) perSender[w.from] = (perSender[w.from] || 0) + 1;
  for (const [from, n] of Object.entries(perSender)) assert.equal(n, 1, `${from} shouted the same letter ${n} times`);
  const fan = sim.cfg.routing.fanout;
  for (const w of gritos) {
    if (sim.zoneOf(w.from, sim.cfg.routing.zoneLevel) === sim.zoneOf(ids[ids.length - 1], sim.cfg.routing.zoneLevel)) continue; // local eco shouts to everyone
    assert.ok(w.to.length <= fan, `${w.from} named ${w.to.length} carriers outside the destination zone`);
  }
});

test('the lake: when no neighbor gets closer, the river widens or goes around and still arrives', () => {
  const sim = createSim(withConfig({ routing: { zoneLevel: 'celda' } }));
  // A U-shaped shore around an empty lake: the straight line from A to D crosses water.
  const ids = [];
  for (let x = 0; x <= 800; x += 80) ids.push(sim.addNode({ x, y: 0 }));        // south shore
  for (let y = 80; y <= 800; y += 80) ids.push(sim.addNode({ x: 800, y }));     // east shore
  for (let x = 720; x >= 0; x -= 80) ids.push(sim.addNode({ x, y: 800 }));      // north shore
  const a = sim.addNode({ x: 0, y: 80 });
  const d = ids[ids.length - 1]; // (0, 800): straight north of A, across the lake
  const { d: res } = deliver(sim, a, d);
  assert.equal(res.delivered, true);
  assert.ok(res.hops > 10, 'it went around the lake');
});

test('islands: with no path the letter is dropped, nobody is paid, and the reason is recorded', () => {
  const sim = createSim();
  const [a, b] = line(sim, 2, 80, 0, 0);
  const [c, d] = line(sim, 2, 80, 5000, 0);
  const { d: res, msgId } = deliver(sim, a, d);
  assert.equal(res.delivered, false);
  assert.equal(sim.credits(b) + sim.credits(c), 0);
  assert.ok(sim.messageStats(msgId).drops > 0);
  assert.ok(Object.values(sim.metrics().drops).reduce((s, n) => s + n, 0) > 0);
});

test('resilience: with two parallel paths, the river survives one carrier going offline', () => {
  const sim = createSim();
  const a = sim.addNode({ x: 0, y: 40 });
  const top = line(sim, 4, 80, 80, 0);
  const bottom = line(sim, 4, 80, 80, 80);
  const d = sim.addNode({ x: 400, y: 40 });
  sim.setOnline(top[1], false);
  const { d: res } = deliver(sim, a, d);
  assert.equal(res.delivered, true);
  assert.ok(!res.path.includes(top[1]));
});

test('barrio search: once inside the destination zone, a local eco finds the recipient', () => {
  const sim = createSim(withConfig({ routing: { zoneLevel: 'barrio' } }));
  const ids = grid(sim, 20, 20, 60, 2000, 2000); // fills barrio 1:1 partially
  const a = ids[0];
  const d = ids[ids.length - 1];
  const { d: res } = deliver(sim, a, d);
  assert.equal(res.delivered, true);
});

test('max hops: a letter is dropped when it exceeds the hop limit set by its origin', () => {
  const sim = createSim();
  const ids = line(sim, 12, 80);
  const { d: res, msgId } = deliver(sim, ids[0], ids[11], { maxHops: 5 });
  assert.equal(res.delivered, false);
  assert.ok(sim.messageStats(msgId).dropReasons.maxHops > 0);
});

test('privacy: carriers see a per-letter tag, never the recipient id or the real sender', () => {
  const sim = createSim();
  const [a, , , d] = line(sim, 4, 80);
  sim.exchangeCards(a, d);
  const m1 = sim.send(a, d).msgId;
  const m2 = sim.send(a, d).msgId;
  sim.runUntilIdle(60000);
  const seen = sim.wireLog();
  assert.ok(seen.length > 0);
  for (const w of seen) {
    assert.notEqual(w.dst, d);
    assert.notEqual(w.src, a);
  }
  const tag = id => seen.find(w => w.msgId === id).dst;
  assert.notEqual(tag(m1), tag(m2), 'the tag changes with every letter');
});

test('internet links: two distant towns connect through members with internet', () => {
  const sim = createSim(withConfig({ internet: { links: 4 } }));
  const ush = line(sim, 3, 80, 0, 0);
  const bsas = line(sim, 3, 80, 2_400_000, 0);
  sim.setInternet(ush[2], true);
  sim.setInternet(bsas[0], true);
  sim.linkInternet();
  const { d: res } = deliver(sim, ush[0], bsas[2]);
  assert.equal(res.delivered, true);
  assert.ok(res.path.includes(ush[2]) && res.path.includes(bsas[0]));
});

test('metrics: deliveries, latency and cost per letter are reported', () => {
  const sim = createSim();
  const ids = grid(sim, 8, 8, 60);
  for (let i = 0; i < 8; i++) deliver(sim, ids[i], ids[63 - i]);
  const m = sim.metrics();
  assert.equal(m.messages.sent, 8);
  assert.equal(m.messages.delivered, 8);
  assert.equal(m.messages.deliveryRatio, 1);
  assert.ok(m.latency.p50 > 0 && m.latency.p95 >= m.latency.p50);
  assert.ok(m.hops.p50 > 0);
  assert.ok(m.cost.gritosPerLetter >= m.hops.p50);
});

test('el grito: two long-range phones reach farther; with one normal phone the shorter mode decides', () => {
  const sim = createSim(withConfig({ radio: { codedShare: 0 } }));
  const a = sim.addNode({ x: 0, y: 0, coded: true });
  const b = sim.addNode({ x: 180, y: 0, coded: true });
  const c = sim.addNode({ x: 0, y: 180, coded: false });
  assert.ok(sim.neighbors(a).includes(b), 'coded to coded at 180 m');
  assert.ok(!sim.neighbors(a).includes(c), 'coded to normal at 180 m is out of range');
});

test('el grito: one shout names up to `fanout` carriers and all of them hear it', () => {
  const sim = createSim(withConfig({ routing: { zoneLevel: 'celda' } }));
  const ids = grid(sim, 10, 10, 60);
  const { msgId } = deliver(sim, ids[0], ids[99]);
  const first = sim.wireLog().find(w => w.msgId === msgId && w.from === ids[0]);
  assert.equal(first.to.length, 2);
  assert.equal(sim.messageStats(msgId).gritos, sim.wireLog().filter(w => w.msgId === msgId).length);
});

test('turns: every hop waits for its slot in the superframe, plus the airtime', () => {
  const sim = createSim();
  const ids = line(sim, 4, 80);
  const { d: res } = deliver(sim, ids[0], ids[3]);
  const g = sim.cfg.grito, air = g.gritoBytes * 8 / g.airKbps;
  const took = res.deliveredAt;
  assert.ok(took >= 3 * air, `took ${took} ms`);
  assert.ok(took <= 3 * (g.superframeMs + air), `took ${took} ms`);
});

test('walking: phones with speed move toward their targets as time passes', () => {
  const sim = createSim(withConfig({ world: { areaM: 1000 } }));
  const a = sim.addNode({ x: 500, y: 500, speed: 1.4 });
  const before = { ...sim.node(a) };
  sim.step(10000);
  const after = sim.node(a);
  const moved = Math.hypot(after.x - before.x, after.y - before.y);
  assert.ok(moved > 10 && moved <= 14.01, `moved ${moved} m in 10 s`);
});

test('guardar y llevar: with carrying on, a stuck letter waits in a pocket and is delivered when its carrier walks over', () => {
  const sim = createSim(withConfig({ routing: { carry: true } }));
  const [a, b] = line(sim, 2, 80, 0, 0);
  const [c, d] = line(sim, 2, 80, 5000, 0);
  sim.exchangeCards(a, d);
  const { msgId } = sim.send(a, d);
  sim.step(5000);
  assert.equal(sim.delivery(msgId).delivered, false);
  assert.ok(sim.pockets().length > 0, 'someone is carrying the letter');
  sim.moveTo(b, 4950, 0);
  sim.step(10000);
  assert.equal(sim.delivery(msgId).delivered, true);
});

test('guardar y llevar: with carrying off, the same stuck letter is dropped', () => {
  const sim = createSim(withConfig({ routing: { carry: false } }));
  const [a, b] = line(sim, 2, 80, 0, 0);
  const [, d] = line(sim, 2, 80, 5000, 0);
  sim.exchangeCards(a, d);
  const { msgId } = sim.send(a, d);
  sim.step(5000);
  sim.moveTo(b, 4950, 0);
  sim.step(10000);
  assert.equal(sim.delivery(msgId).delivered, false);
  assert.equal(sim.pockets().length, 0);
});

test('read model: wireSince returns only the shouts at or after a given time', () => {
  const sim = createSim();
  const ids = line(sim, 6, 80);
  sim.exchangeCards(ids[0], ids[5]);
  sim.send(ids[0], ids[5]);
  sim.runUntilIdle(60000);
  const all = sim.wireLog();
  const cut = all[2].t;
  assert.deepEqual(sim.wireSince(cut), all.filter(w => w.t >= cut));
});

test('offer: a carrier only hands a letter to neighbors that do not have it yet, so no letter vanishes silently', () => {
  const sim = createSim(withConfig({ routing: { carry: true } }));
  const island = grid(sim, 8, 8, 60, 0, 0);
  const far = sim.addNode({ x: 6000, y: 6000 });
  sim.exchangeCards(island[0], far);
  const { msgId } = sim.send(island[0], far);
  sim.step(60000);
  assert.equal(sim.delivery(msgId).delivered, false);
  const kept = sim.pockets().filter(p => p.msgId === msgId).length;
  const dropped = sim.messageStats(msgId).drops;
  assert.ok(kept + dropped > 0, 'the letter must be kept in a pocket or dropped with a reason');
  assert.equal(sim.metrics().duplicates, 0, 'nobody is handed a letter it already has');
});

for (const zoneLevel of ['celda', 'barrio']) {
  test(`guardar y llevar: a letter stuck on an island goes to a pocket before running out of hops (${zoneLevel})`, () => {
    const sim = createSim(withConfig({ routing: { carry: true, zoneLevel } }));
    const island = grid(sim, 6, 6, 60, 0, 0);
    const far = sim.addNode({ x: 900, y: 900 });
    sim.exchangeCards(island[0], far);
    const { msgId } = sim.send(island[0], far);
    sim.step(60000);
    assert.ok(sim.pockets().some(p => p.msgId === msgId), 'someone keeps it');
    assert.equal(sim.messageStats(msgId).dropReasons.maxHops, undefined);
  });
}

test('guardar y llevar: going around a big island does not reset the detour budget, so the letter is kept, not exhausted', () => {
  const sim = createSim(withConfig({ routing: { carry: true, zoneLevel: 'celda' } }));
  const island = grid(sim, 12, 12, 60, 0, 0);
  const far = sim.addNode({ x: 1500, y: 300 });
  sim.exchangeCards(island[0], far);
  const { msgId } = sim.send(island[0], far);
  sim.step(120000);
  assert.ok(sim.pockets().some(p => p.msgId === msgId), 'someone keeps it');
  assert.equal(sim.messageStats(msgId).dropReasons.maxHops, undefined);
});

test('no silent loss: with carrying on, every letter not delivered and not expired is in a pocket or dropped with a reason', () => {
  const sim = createSim(withConfig({ radio: { codedShare: 0.7 }, routing: { carry: true, zoneLevel: 'celda' }, world: { areaM: 2000 } }), 1);
  let s = 9301 + 49297; const r = () => ((s = (s * 9301 + 49297) % 233280) / 233280);
  const disc = (cx, cy, R) => { const a = r() * 2 * Math.PI, d = Math.sqrt(r()) * R; return { x: cx + Math.cos(a) * d, y: cy + Math.sin(a) * d }; };
  for (let i = 0; i < 360; i++) { const p = r() < 0.5 ? disc(450, 1000, 300) : disc(1550, 1000, 300); sim.addNode({ ...p, speed: r() < 0.35 ? 1.4 : 0 }); }
  const ids = sim.nodeIds(); const sent = [];
  for (let i = 0; i < 30; i++) { const a = ids[Math.floor(r() * ids.length)]; let b = a; while (b === a) b = ids[Math.floor(r() * ids.length)]; sim.exchangeCards(a, b); sent.push(sim.send(a, b).msgId); }
  sim.step(30 * 60 * 1000);
  const pocketed = new Set(sim.pockets().map(p => p.msgId));
  for (const id of sent) {
    if (sim.delivery(id).delivered) continue;
    assert.ok(pocketed.has(id) || sim.messageStats(id).drops > 0, `${id} vanished`);
  }
});
