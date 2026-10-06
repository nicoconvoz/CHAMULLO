# CHAMULLO — Simulación con el gemelo digital

Generado por `android/simulator` (`./gradlew :simulator:run`). Corre **el núcleo real de la app** (Node, sobres, recibos, pagos, `Islands.decide`);
lo simulado es la radio: alcance Wi-Fi 80 m, 3 s para sumarse a una isla, 30 ms por trama. Son números de diseño, no de campo.

## Una plaza llena

20 celulares en 60 m. Más de 7 no entran en una isla: se forman varias y se conectan por ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (7 celulares · 2 celulares · 8 celulares) |
| Cartas entregadas | **30 de 30** (100 %) |
| Entre islas distintas | 21 de 21 |
| Demora p50 / p95 | 10.4 s / 35.9 s |
| Viajes de ferry | 147 |
| Caramelos cobrados (recibos verificados) | 21 |
| Cartas en bolsillos al final | 564 |
| La carta más avanzada que sigue en camino | a 24 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Plaza | Plaza-9 | Plaza-15, Plaza-5 | 9.0 |
| **Nacional** | **Plaza-9** | Plaza-15, Plaza-5, Plaza-7 | 9.0 |

Reparto de una bolsa diaria de 1000 Lucas: Plaza-9 143, Plaza-15 95, Plaza-5 95, Plaza-7 95, Plaza-14 95.

_Tiempo de cómputo: 3.4 s._

## Dos pueblos que se tocan por el borde

Pueblos de 8 celulares, bordes a ~70 m. Las cartas cruzan solo con el ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 2 (6 celulares · 7 celulares) |
| Cartas entregadas | **14 de 20** (70 %) |
| Entre islas distintas | 14 de 20 |
| Demora p50 / p95 | 39.8 s / 86.6 s |
| Viajes de ferry | 158 |
| Caramelos cobrados (recibos verificados) | 6 |
| Cartas en bolsillos al final | 338 |
| La carta más avanzada que sigue en camino | a 110 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-3 | Norte-1, Norte-8 | 27.0 |
| Sur | Sur-3 | — | 9.0 |
| **Nacional** | **Norte-3** | Sur-3, Norte-1, Norte-8 | 27.0 |

Reparto de una bolsa diaria de 1000 Lucas: Norte-3 500, Sur-3 167, Norte-1 167, Norte-8 56, Norte-4 56.

_Tiempo de cómputo: 1.2 s._

## Ruta de tres pueblos

Norte y Sur no se ven: todo pasa por el pueblo del medio, y su gente cobra por escasez.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (6 celulares · 2 celulares · 1 celulares · 7 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 20 de 20 |
| Demora p50 / p95 | 185.7 s / 266.9 s |
| Viajes de ferry | 141 |
| Caramelos cobrados (recibos verificados) | 68 |
| Cartas en bolsillos al final | 295 |
| La carta más avanzada que sigue en camino | a 200 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-1 | Norte-3 | 36.0 |
| Medio | Medio-4 | Medio-2, Medio-3 | 60.0 |
| Sur | Sur-1 | Sur-2, Sur-4 | 27.0 |
| **Nacional** | **Medio-4** | Norte-1, Sur-1, Medio-2 | 60.0 |

Reparto de una bolsa diaria de 1000 Lucas: Medio-4 294, Norte-1 176, Sur-1 132, Medio-2 88, Sur-2 88.

_Tiempo de cómputo: 1.2 s._

## Pueblos con viajeros

Dos pueblos lejos (200 m); tres personas caminan entre ellos y llevan las cartas en el bolsillo.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (1 celulares · 3 celulares · 1 celulares · 5 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 16 de 16 |
| Demora p50 / p95 | 103.5 s / 223.7 s |
| Viajes de ferry | 76 |
| Caramelos cobrados (recibos verificados) | 27 |
| Cartas en bolsillos al final | 235 |
| La carta más avanzada que sigue en camino | a 218 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Oeste | Oeste-1 | Oeste-3, Oeste-6 | 21.0 |
| Este | Este-6 | Este-2, Este-1 | 21.0 |
| **Nacional** | **Este-6** | Oeste-1, Este-2, Este-1 | 21.0 |

Reparto de una bolsa diaria de 1000 Lucas: Este-6 200, Oeste-1 200, Este-2 200, Este-1 171, Oeste-3 143.

_Tiempo de cómputo: 0.9 s._

## Ciudad de 1000

1000 celulares en ocho barrios de 125 que se tocan por el borde, con 40 personas caminando.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 139 (1 celulares · 5 celulares · 7 celulares · 5 celulares · 7 celulares · 1 celulares · 5 celulares · 6 celulares · 8 celulares · 7 celulares · 8 celulares · 4 celulares · 5 celulares · 5 celulares · 6 celulares · 7 celulares · 7 celulares · 2 celulares · 6 celulares · 8 celulares · 5 celulares · 7 celulares · 6 celulares · 6 celulares · 4 celulares · 3 celulares · 6 celulares · 7 celulares · 8 celulares · 1 celulares · 8 celulares · 6 celulares · 1 celulares · 7 celulares · 8 celulares · 7 celulares · 1 celulares · 3 celulares · 4 celulares · 8 celulares · 8 celulares · 6 celulares · 8 celulares · 7 celulares · 8 celulares · 6 celulares · 8 celulares · 7 celulares · 7 celulares · 2 celulares · 8 celulares · 3 celulares · 6 celulares · 5 celulares · 2 celulares · 8 celulares · 6 celulares · 1 celulares · 6 celulares · 6 celulares · 6 celulares · 7 celulares · 6 celulares · 5 celulares · 4 celulares · 4 celulares · 8 celulares · 3 celulares · 2 celulares · 5 celulares · 7 celulares · 3 celulares · 7 celulares · 6 celulares · 5 celulares · 3 celulares · 8 celulares · 4 celulares · 2 celulares · 1 celulares · 1 celulares · 8 celulares · 7 celulares · 7 celulares · 8 celulares · 5 celulares · 8 celulares · 5 celulares · 6 celulares · 2 celulares · 6 celulares · 6 celulares · 5 celulares · 7 celulares · 8 celulares · 1 celulares · 8 celulares · 5 celulares · 6 celulares · 7 celulares · 6 celulares · 6 celulares · 8 celulares · 3 celulares · 3 celulares · 7 celulares · 7 celulares · 3 celulares · 8 celulares · 8 celulares · 7 celulares · 5 celulares · 8 celulares · 8 celulares · 5 celulares · 7 celulares · 7 celulares · 7 celulares · 5 celulares · 7 celulares · 1 celulares · 2 celulares · 2 celulares · 6 celulares · 7 celulares · 8 celulares · 3 celulares · 4 celulares · 1 celulares · 8 celulares · 7 celulares · 3 celulares · 5 celulares · 6 celulares · 6 celulares · 7 celulares · 7 celulares · 3 celulares · 7 celulares) |
| Cartas entregadas | **51 de 120** (43 %) |
| Entre islas distintas | 50 de 118 |
| Demora p50 / p95 | 94.6 s / 149.3 s |
| Viajes de ferry | 14116 |
| Caramelos cobrados (recibos verificados) | 149 |
| Cartas en bolsillos al final | 49950 |
| Copias soltadas, y por qué | eco local agotado: 22487 |
| La carta más avanzada que sigue en camino | a 795 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Barrio-8 | Barrio-8-69 | Barrio-8-18, Barrio-8-48 | 6.0 |
| Barrio-4 | Barrio-4-102 | Barrio-4-90, Barrio-4-31 | 3.0 |
| Barrio-3 | Barrio-3-111 | Barrio-3-40, Barrio-3-32 | 6.0 |
| Barrio-7 | Barrio-7-10 | Barrio-7-106, Barrio-7-61 | 6.0 |
| Barrio-6 | Barrio-6-37 | Barrio-6-90, Barrio-6-123 | 6.0 |
| Barrio-1 | Barrio-1-113 | Barrio-1-42, Barrio-1-19 | 6.0 |
| Barrio-5 | Barrio-5-76 | Barrio-5-59, Barrio-5-71 | 9.0 |
| Barrio-2 | Barrio-2-52 | Barrio-2-81, Barrio-2-75 | 9.0 |
| **Nacional** | **Barrio-2-52** | Barrio-5-76, Barrio-3-111, Barrio-6-37 | 9.0 |

Reparto de una bolsa diaria de 1000 Lucas: Barrio-2-52 12, Barrio-5-76 12, Barrio-3-111 8, Barrio-6-37 8, Barrio-7-10 8.

_Tiempo de cómputo: 142.7 s._

## Provincia: tres pueblos

Norte, Centro y Sur, a 2 km uno de otro y en barrios distintos, unidos por una ruta con un celular cada 55 m. Acá trabaja la brújula.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 46 (4 celulares · 6 celulares · 6 celulares · 6 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 2 celulares · 2 celulares · 2 celulares · 2 celulares · 2 celulares · 3 celulares · 2 celulares · 2 celulares · 2 celulares · 3 celulares · 2 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 2 celulares · 2 celulares · 1 celulares · 2 celulares · 3 celulares · 7 celulares · 5 celulares · 7 celulares · 5 celulares · 8 celulares · 7 celulares) |
| Cartas entregadas | **0 de 30** (0 %) |
| Entre islas distintas | 0 de 30 |
| Demora p50 / p95 | 0.0 s / 0.0 s |
| Viajes de ferry | 5217 |
| Caramelos cobrados (recibos verificados) | 0 |
| Cartas en bolsillos al final | 39 |
| Copias soltadas, y por qué | promesa sin carta: 11, la tiene otro: 115 |
| La carta más avanzada que sigue en camino | a 2300 m del oeste |

_Tiempo de cómputo: 19.5 s._

