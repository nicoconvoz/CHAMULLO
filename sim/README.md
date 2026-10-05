# CHAMULLO Simulator (la maqueta)

A deterministic model of the CHAMULLO protocol rules, built on the same recipe as ICEBREAK's P2P web simulator (`prototype/p2p/core.js`): pure core, seeded RNG, spatial grid and a metrics read model. Cryptography and real transports are **modeled, not executed**.

## What it models

| Spec | In the simulator |
|---|---|
| Discovery & Routing §4 — contact card | `exchangeCards(a, b)`; `send` fails with `no_card` without it |
| Discovery & Routing §6.1–6.2 — compass and river | Greedy progress toward the destination zone center, up to `routing.fanout` copies |
| Discovery & Routing §6.3 — the lake | Widening within `routing.widenDeg`, then perimeter mode with a hop budget |
| Discovery & Routing §6.4 — barrio search | Local eco inside the destination zone, bounded by the zone and `routing.localTtl` |
| Discovery & Routing §7 — privacy | Ephemeral sender id and per-letter destination tag in `wireLog()` |
| Proof of Relay §4, §6, §7 | Hop path per copy; carriers are paid only after the origin confirms |
| Transports | Radio (phone ≈ 100 m, antenna ≈ 5 km, the shorter range decides) and internet links between members |

## Run

```bash
npm test
```

```bash
node argentina.js 400 0.3 200 1 celda
```

Arguments: phones per city, share of phones with internet, letters, seed, destination zone level (`celda`, `barrio`, `pueblo`, `region`).

## First findings (seed 1, 11 cities, 400 phones each)

| Internet share | Delivered | Latency p50 | Hops p50 | Transmissions per letter |
|---|---|---|---|---|
| 30 % | 89 % | 585 ms | 11 | ≈ 525 |
| 5 % | 43 % | 720 ms | 13 | ≈ 174 |

- With phones only (no antennas), **cities are islands**: letters between cities depend entirely on members with internet.
- **Local eco at barrio level is expensive:** on a 30 × 30 grid, the river touches 85 phones (166 transmissions), while a barrio-wide eco touches all 900 (≈ 5,900 transmissions).
- **Fanout over internet links multiplies copies:** every hop with progress forks into 2, so cost grows fast on long routes. Candidates: lower fanout after the first hops, or fanout 1 on internet links.

These are first-model numbers, not calibrated results.
