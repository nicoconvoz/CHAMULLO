# CHAMULLO Simulator (la maqueta)

A deterministic model of the CHAMULLO protocol rules, built on the same recipe as ICEBREAK's P2P web simulator (`prototype/p2p/core.js` + `lab.html`): pure core, seeded RNG, spatial grid, metrics read model and a visual lab. Cryptography and real radios are **modeled, not executed**.

No cell towers, no internet, no Wi-Fi: phones talk with their own radio used raw ("el grito", see [Air Interface](../docs/specs/06-air-interface.md)).

## What it models

| Spec | In the simulator |
|---|---|
| Air Interface §3 — long-range and normal modes | `radio.codedRangeM` (both phones long-range) or `radio.normalRangeM` (otherwise); share set by `radio.codedShare` |
| Air Interface §5 — turns in the superframe | Each shout waits a random turn in `grito.superframeMs`, plus its airtime |
| Air Interface §6.3 — one shout, many ears | One shout names up to `routing.fanout` carriers; cost is counted in shouts |
| Discovery & Routing §4 — contact card | `exchangeCards(a, b)`; `send` fails with `no_card` without it |
| Discovery & Routing §6.1–6.2 — compass and river | The best `fanout` carriers toward the zone center (at least `minProgressM` closer); a slot another copy already holds stays covered |
| Discovery & Routing §6.3 — the lake | Widening within `widenDeg`, then going around with a per-letter detour budget |
| Discovery & Routing §6.4 — barrio search | Local eco inside the destination zone |
| Discovery & Routing §7 — privacy | Ephemeral sender id and per-letter destination tag in `wireLog()` |
| Proof of Relay §5.2 — OFFER / ACCEPT | A neighbor that already has the letter declines it |
| Proof of Relay §6–7 — double receipt | Carriers are paid only after the origin confirms the journey |
| Carry and forward (optional) | `routing.carry`: a letter with no way forward waits in a pocket while its carrier walks |

## Run

```bash
npm test
```

```bash
npm run build
```

Then open `lab.html` in a browser. `lab.src.html` is the source; `build.js` inlines `core.js` so the page works on its own.

```bash
node argentina.js 400 0 200 1 celda
```

Arguments: phones per city, share of phones with optional internet links, letters, seed, destination zone level.

## Findings so far (model numbers, not field measurements)

| Scenario | Delivered | Latency p50 | Shouts per letter |
|---|---|---|---|
| Barrio: 700 phones in 2 × 2 km, 70 % long range | 97 % | 3.6 s | ≈ 27 |
| Two islands 500 m apart, 35 % walking, carry on (30 min) | 23 of 30 | 1.4 s within an island; minutes across | — |
| Same islands, carry off | 13 of 30 | — | — |
| 11 Argentine cities, phones only | 100 % within a city, **0 % between cities** | 1.9 s | ≈ 45 |

- **Phones alone connect a town, not a country.** Between cities hundreds of kilometers apart, only carriers who travel (carry and forward) can move letters.
- **One shout, many ears pays off:** on a 30 × 30 grid the river touches 85 phones with 57 shouts (vs 166 point-to-point sends before).
- **Barrio-wide search is expensive:** searching a whole 2 km zone touches every phone in it. Smaller zones or smarter search are open work.
- Every number here must be replaced by field measurements from real phones (Air Interface §10).
