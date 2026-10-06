# CHAMULLO — Simulación con el gemelo digital

Generado por `android/simulator` (`./gradlew :simulator:run`). Corre **el núcleo real de la app** (Node, sobres, recibos, pagos, `Islands.decide`);
lo simulado es la radio: alcance Wi-Fi 80 m, 3 s para sumarse a una isla, 30 ms por trama. Son números de diseño, no de campo.

## Una plaza llena

20 celulares en 60 m. Más de 7 no entran en una isla: se forman varias y se conectan por ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (7 celulares · 6 celulares · 2 celulares) |
| Cartas entregadas | **29 de 30** (97 %) |
| Entre islas distintas | 19 de 20 |
| Demora p50 / p95 | 9.2 s / 44.6 s |
| Viajes de ferry | 159 |
| Caramelos cobrados (recibos verificados) | 25 |
| Cartas en bolsillos al final | 579 |
| La carta más avanzada que sigue en camino | a 24 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Plaza | Plaza-1 | Plaza-19, Plaza-11 | 12.0 |
| **Nacional** | **Plaza-1** | Plaza-19, Plaza-11, Plaza-13 | 12.0 |

Reparto de una bolsa diaria de 1000 Lucas: Plaza-1 160, Plaza-19 120, Plaza-11 120, Plaza-13 80, Plaza-17 80.

_Tiempo de cómputo: 2.5 s._

## Dos pueblos que se tocan por el borde

Pueblos de 8 celulares, bordes a ~70 m. Las cartas cruzan solo con el ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (6 celulares · 5 celulares · 2 celulares) |
| Cartas entregadas | **18 de 20** (90 %) |
| Entre islas distintas | 16 de 18 |
| Demora p50 / p95 | 36.6 s / 89.9 s |
| Viajes de ferry | 160 |
| Caramelos cobrados (recibos verificados) | 17 |
| Cartas en bolsillos al final | 335 |
| La carta más avanzada que sigue en camino | a 110 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-3 | Norte-1, Norte-8 | 27.0 |
| Sur | Sur-3 | Sur-5, Sur-4 | 18.0 |
| **Nacional** | **Norte-3** | Sur-3, Norte-1, Norte-8 | 27.0 |

Reparto de una bolsa diaria de 1000 Lucas: Norte-3 321, Sur-3 214, Norte-1 179, Norte-8 71, Norte-4 71.

_Tiempo de cómputo: 1.1 s._

## Ruta de tres pueblos

Norte y Sur no se ven: todo pasa por el pueblo del medio, y su gente cobra por escasez.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (6 celulares · 4 celulares · 4 celulares) |
| Cartas entregadas | **17 de 20** (85 %) |
| Entre islas distintas | 17 de 20 |
| Demora p50 / p95 | 73.3 s / 248.0 s |
| Viajes de ferry | 131 |
| Caramelos cobrados (recibos verificados) | 45 |
| Cartas en bolsillos al final | 306 |
| La carta más avanzada que sigue en camino | a 200 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-1 | Norte-4 | 45.0 |
| Medio | Medio-1 | Medio-2, Medio-3 | 51.0 |
| Sur | Sur-2 | Sur-4, Sur-6 | 9.0 |
| **Nacional** | **Medio-1** | Norte-1, Medio-2, Medio-3 | 51.0 |

Reparto de una bolsa diaria de 1000 Lucas: Medio-1 298, Norte-1 263, Medio-2 193, Medio-3 88, Sur-2 53.

_Tiempo de cómputo: 1.1 s._

## Pueblos con viajeros

Dos pueblos lejos (200 m); tres personas caminan entre ellos y llevan las cartas en el bolsillo.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (1 celulares · 4 celulares · 5 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 18 de 18 |
| Demora p50 / p95 | 97.7 s / 194.1 s |
| Viajes de ferry | 85 |
| Caramelos cobrados (recibos verificados) | 29 |
| Cartas en bolsillos al final | 250 |
| La carta más avanzada que sigue en camino | a 218 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Oeste | Oeste-5 | Oeste-1, Oeste-6 | 24.0 |
| Este | Este-2 | Este-6, Este-1 | 21.0 |
| **Nacional** | **Oeste-5** | Oeste-1, Este-2, Este-6 | 24.0 |

Reparto de una bolsa diaria de 1000 Lucas: Oeste-5 211, Oeste-1 184, Este-2 184, Este-6 158, Este-1 158.

_Tiempo de cómputo: 0.9 s._

## Ciudad de 1000

1000 celulares en ocho barrios de 125 que se tocan por el borde, con 40 personas caminando.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 139 (6 celulares · 8 celulares · 4 celulares · 2 celulares · 7 celulares · 8 celulares · 2 celulares · 8 celulares · 8 celulares · 7 celulares · 1 celulares · 6 celulares · 7 celulares · 6 celulares · 1 celulares · 7 celulares · 5 celulares · 7 celulares · 8 celulares · 7 celulares · 5 celulares · 5 celulares · 7 celulares · 5 celulares · 4 celulares · 2 celulares · 6 celulares · 4 celulares · 4 celulares · 7 celulares · 8 celulares · 5 celulares · 8 celulares · 6 celulares · 7 celulares · 7 celulares · 6 celulares · 8 celulares · 8 celulares · 1 celulares · 7 celulares · 6 celulares · 6 celulares · 6 celulares · 2 celulares · 4 celulares · 2 celulares · 5 celulares · 8 celulares · 7 celulares · 7 celulares · 2 celulares · 8 celulares · 8 celulares · 7 celulares · 7 celulares · 6 celulares · 6 celulares · 1 celulares · 7 celulares · 7 celulares · 6 celulares · 7 celulares · 6 celulares · 6 celulares · 8 celulares · 6 celulares · 2 celulares · 8 celulares · 7 celulares · 8 celulares · 8 celulares · 4 celulares · 7 celulares · 7 celulares · 2 celulares · 6 celulares · 2 celulares · 7 celulares · 6 celulares · 6 celulares · 2 celulares · 5 celulares · 6 celulares · 8 celulares · 1 celulares · 1 celulares · 5 celulares · 5 celulares · 4 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 4 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 4 celulares · 1 celulares · 2 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 4 celulares · 7 celulares · 7 celulares · 6 celulares · 3 celulares · 4 celulares · 5 celulares · 7 celulares · 6 celulares · 7 celulares · 8 celulares · 1 celulares · 6 celulares · 6 celulares · 8 celulares · 7 celulares · 7 celulares · 6 celulares · 6 celulares · 7 celulares · 6 celulares · 8 celulares · 7 celulares · 5 celulares · 2 celulares · 4 celulares · 7 celulares · 3 celulares · 8 celulares · 8 celulares · 7 celulares · 3 celulares · 6 celulares) |
| Cartas entregadas | **54 de 120** (45 %) |
| Entre islas distintas | 54 de 120 |
| Demora p50 / p95 | 86.7 s / 189.5 s |
| Viajes de ferry | 14065 |
| Caramelos cobrados (recibos verificados) | 155 |
| Cartas en bolsillos al final | 53273 |
| Copias soltadas, y por qué | eco local agotado: 21859 |
| La carta más avanzada que sigue en camino | a 795 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Barrio-3 | Barrio-3-97 | Barrio-3-13, Barrio-3-45 | 9.0 |
| Barrio-2 | Barrio-2-99 | Barrio-2-15, Barrio-2-62 | 6.0 |
| Barrio-8 | Barrio-8-59 | Barrio-8-44, Barrio-8-105 | 6.0 |
| Barrio-6 | Barrio-6-21 | Barrio-6-106, Barrio-6-99 | 6.0 |
| Barrio-5 | Barrio-5-66 | Barrio-5-119, Barrio-5-116 | 9.0 |
| Barrio-1 | Barrio-1-92 | Barrio-1-79, Barrio-1-89 | 6.0 |
| Barrio-7 | Barrio-7-62 | Barrio-7-27, Barrio-7-113 | 6.0 |
| Barrio-4 | Barrio-4-28 | Barrio-4-22, Barrio-4-24 | 6.0 |
| **Nacional** | **Barrio-3-97** | Barrio-5-66, Barrio-3-13, Barrio-3-45 | 9.0 |

Reparto de una bolsa diaria de 1000 Lucas: Barrio-3-97 12, Barrio-5-66 12, Barrio-3-13 12, Barrio-3-45 8, Barrio-8-59 8.

_Tiempo de cómputo: 147.9 s._

## Provincia: tres pueblos

Norte, Centro y Sur, a 2 km uno de otro y en barrios distintos, unidos por una ruta con un celular cada 55 m. En cada pueblo, algunos prestan Internet: el puente para el salto grande.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 45 (8 celulares · 6 celulares · 3 celulares · 7 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 2 celulares · 2 celulares · 2 celulares · 2 celulares · 2 celulares · 3 celulares · 2 celulares · 2 celulares · 1 celulares · 7 celulares · 2 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 2 celulares · 2 celulares · 1 celulares · 6 celulares · 3 celulares · 6 celulares · 4 celulares · 6 celulares · 7 celulares · 7 celulares) |
| Cartas entregadas | **30 de 30** (100 %) |
| Entre islas distintas | 29 de 29 |
| Demora p50 / p95 | 10.9 s / 38.0 s |
| Viajes de ferry | 5180 |
| Caramelos cobrados (recibos verificados) | 44 |
| Cartas en bolsillos al final | 1234 |
| Por Internet (el puente) | 413 KB, de 16 puentes |
| Lucas de los puentes (bolsa diaria de 1000) | 697 |
| Precio de la información | 0.1 Lucas por KB llevado (12814 KB entre aire e Internet) |
| Copias soltadas, y por qué | eco local agotado: 11 |
| La carta más avanzada que sigue en camino | a 4034 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Sur | Sur-23 | Sur-21, Sur-24 | 21.0 |
| Norte | Norte-22 | Norte-25, Norte-21 | 18.0 |
| Ruta | Ruta-1 | Ruta-72 | 15.0 |
| **Nacional** | **Sur-23** | Norte-22, Norte-25, Sur-21 | 21.0 |

Reparto de una bolsa diaria de 1000 Lucas: Sur-23 106, Norte-22 91, Norte-25 91, Sur-21 76, Ruta-1 76.

_Tiempo de cómputo: 20.1 s._

