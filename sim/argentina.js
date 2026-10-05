// Scenario: phones clustered in Argentine cities; distant cities connect only through members with internet.
// Usage: node argentina.js [phonesPerCity] [internetShare] [letters] [seed] [zoneLevel]
const { createSim, withConfig } = require('./core.js');

const PHONES = +process.argv[2] || 400;
const INTERNET = process.argv[3] !== undefined ? +process.argv[3] : 0.3;
const LETTERS = +process.argv[4] || 200;
const SEED = +process.argv[5] || 1;
const ZONE = process.argv[6] || 'celda';
const CITY_RADIUS_M = 800;

const CITIES = [
  ['Ushuaia', -54.80, -68.30], ['Río Gallegos', -51.62, -69.22], ['Comodoro Rivadavia', -45.86, -67.48],
  ['Trelew', -43.25, -65.31], ['Bahía Blanca', -38.72, -62.27], ['Mendoza', -32.89, -68.83],
  ['Córdoba', -31.42, -64.18], ['Rosario', -32.95, -60.65], ['Santa Fe', -31.63, -60.70],
  ['Junín', -34.58, -60.95], ['Buenos Aires', -34.60, -58.38]
];
// Equirectangular projection to meters from a south-west origin (fine for a country-scale model).
const ORIGIN = { lat: -56, lon: -74 };
const toXY = (lat, lon) => ({ x: (lon - ORIGIN.lon) * 111320 * Math.cos(-45 * Math.PI / 180), y: (lat - ORIGIN.lat) * 110900 });

const sim = createSim(withConfig({ routing: { zoneLevel: ZONE } }), SEED);
let r = SEED * 9301 + 49297;
const rnd = () => ((r = (r * 9301 + 49297) % 233280) / 233280);

const byCity = new Map();
for (const [name, lat, lon] of CITIES) {
  const c = toXY(lat, lon); const ids = [];
  for (let i = 0; i < PHONES; i++) {
    const a = rnd() * 2 * Math.PI, d = Math.sqrt(rnd()) * CITY_RADIUS_M;
    ids.push(sim.addNode({ x: c.x + Math.cos(a) * d, y: c.y + Math.sin(a) * d, internet: rnd() < INTERNET }));
  }
  byCity.set(name, ids);
}
sim.linkInternet();

const names = [...byCity.keys()];
const sent = [];
for (let i = 0; i < LETTERS; i++) {
  const fromCity = names[Math.floor(rnd() * names.length)], toCity = names[Math.floor(rnd() * names.length)];
  const fromIds = byCity.get(fromCity), toIds = byCity.get(toCity);
  const from = fromIds[Math.floor(rnd() * fromIds.length)], to = toIds[Math.floor(rnd() * toIds.length)];
  if (from === to) continue;
  sim.exchangeCards(from, to);
  const res = sim.send(from, to);
  if (res.ok) sent.push({ fromCity, toCity, msgId: res.msgId });
}
const t0 = Date.now();
sim.runUntilIdle(3600 * 1000);
const m = sim.metrics();

const pairs = {};
for (const s of sent) {
  const k = s.fromCity === s.toCity ? 'same city' : 'between cities';
  pairs[k] ||= { sent: 0, delivered: 0 };
  pairs[k].sent++; if (sim.delivery(s.msgId).delivered) pairs[k].delivered++;
}
const carriers = sim.nodeIds().map(sim.credits).filter(c => c > 0).sort((a, b) => b - a);

console.log(JSON.stringify({
  setup: { cities: CITIES.length, phonesPerCity: PHONES, internetShare: INTERNET, letters: sent.length, seed: SEED, zoneLevel: ZONE },
  delivery: { ratio: +m.messages.deliveryRatio.toFixed(3), ...pairs },
  latencyMs: m.latency, hops: m.hops,
  cost: { transmissionsPerLetter: +m.cost.transmissionsPerLetter.toFixed(1), touchedPerLetter: +m.cost.touchedPerLetter.toFixed(1) },
  drops: m.drops,
  carriersPaid: carriers.length, topCarrierLetters: carriers.slice(0, 5),
  runMs: Date.now() - t0
}, null, 2));
