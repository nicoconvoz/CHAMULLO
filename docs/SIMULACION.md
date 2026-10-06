# CHAMULLO — Simulación con el gemelo digital

Generado por `android/simulator` (`./gradlew :simulator:run`). Corre **el núcleo real de la app** (Node, sobres, recibos, pagos, `Islands.decide`);
lo simulado es la radio: alcance Wi-Fi 80 m, 3 s para sumarse a una isla, 30 ms por trama. Son números de diseño, no de campo.

## Una plaza llena

20 celulares en 60 m. Más de 7 no entran en una isla: se forman varias y se conectan por ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (2 celulares · 6 celulares · 6 celulares) |
| Cartas entregadas | **30 de 30** (100 %) |
| Entre islas distintas | 24 de 24 |
| Demora p50 / p95 | 30.4 s / 80.4 s |
| Viajes de ferry | 167 |
| Caramelos cobrados (recibos verificados) | 25 |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Plaza | Plaza-13 | Plaza-17, Plaza-20 | 11.3 |
| **Nacional** | **Plaza-13** | Plaza-17, Plaza-20, Plaza-5 | 11.3 |

Reparto de una bolsa diaria de 1000 ICE: Plaza-13 266, Plaza-17 214, Plaza-20 176, Plaza-5 73, Plaza-9 66.

_Tiempo de cómputo: 2.5 s._

## Dos pueblos que se tocan por el borde

Pueblos de 8 celulares, bordes a ~70 m. Las cartas cruzan solo con el ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (4 celulares · 1 celulares · 7 celulares · 2 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 18 de 18 |
| Demora p50 / p95 | 90.6 s / 225.6 s |
| Viajes de ferry | 160 |
| Caramelos cobrados (recibos verificados) | 38 |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-7 | Norte-1, Norte-4 | 26.3 |
| Sur | Sur-3 | Sur-6, Sur-5 | 11.7 |
| **Nacional** | **Norte-7** | Norte-1, Sur-3, Norte-4 | 26.3 |

Reparto de una bolsa diaria de 1000 ICE: Norte-7 325, Norte-1 184, Sur-3 146, Norte-4 112, Norte-8 82.

_Tiempo de cómputo: 1.8 s._

## Ruta de tres pueblos

Norte y Sur no se ven: todo pasa por el pueblo del medio, y su gente cobra por escasez.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (6 celulares · 2 celulares · 1 celulares · 6 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 20 de 20 |
| Demora p50 / p95 | 135.6 s / 425.4 s |
| Viajes de ferry | 124 |
| Caramelos cobrados (recibos verificados) | 37 |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Medio | Medio-3 | Medio-1 | 54.3 |
| Norte | Norte-1 | Norte-6, Norte-2 | 6.8 |
| Sur | Sur-2 | Sur-6, Sur-1 | 10.6 |
| **Nacional** | **Medio-3** | Medio-1, Sur-2, Norte-1 | 54.3 |

Reparto de una bolsa diaria de 1000 ICE: Medio-3 534, Medio-1 150, Sur-2 104, Norte-1 67, Norte-6 57.

_Tiempo de cómputo: 1.6 s._

