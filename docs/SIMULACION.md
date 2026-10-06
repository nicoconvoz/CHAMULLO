# CHAMULLO — Simulación con el gemelo digital

Generado por `android/simulator` (`./gradlew :simulator:run`). Corre **el núcleo real de la app** (Node, sobres, recibos, pagos, `Islands.decide`);
lo simulado es la radio: alcance Wi-Fi 80 m, 3 s para sumarse a una isla, 30 ms por trama. Son números de diseño, no de campo.

## Una plaza llena

20 celulares en 60 m. Más de 7 no entran en una isla: se forman varias y se conectan por ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (7 celulares · 7 celulares · 4 celulares) |
| Cartas entregadas | **30 de 30** (100 %) |
| Entre islas distintas | 23 de 23 |
| Demora p50 / p95 | 16.4 s / 47.9 s |
| Viajes de ferry | 153 |
| Caramelos cobrados (recibos verificados) | 32 |
| Cartas en bolsillos al final | 568 |
| La carta más avanzada que sigue en camino | a 24 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Plaza | Plaza-20 | Plaza-1, Plaza-2 | 12.0 |
| **Nacional** | **Plaza-20** | Plaza-1, Plaza-2, Plaza-16 | 12.0 |

Reparto de una bolsa diaria de 1000 Lucas: Plaza-20 125, Plaza-1 125, Plaza-2 94, Plaza-16 94, Plaza-7 94.

_Tiempo de cómputo: 5.4 s._

## Dos pueblos que se tocan por el borde

Pueblos de 8 celulares, bordes a ~70 m. Las cartas cruzan solo con el ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (5 celulares · 2 celulares · 4 celulares · 2 celulares) |
| Cartas entregadas | **12 de 20** (60 %) |
| Entre islas distintas | 12 de 20 |
| Demora p50 / p95 | 27.6 s / 70.3 s |
| Viajes de ferry | 153 |
| Caramelos cobrados (recibos verificados) | 15 |
| Cartas en bolsillos al final | 249 |
| La carta más avanzada que sigue en camino | a 110 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-3 | Norte-1, Norte-5 | 19.8 |
| Sur | Sur-6 | — | 3.0 |
| **Nacional** | **Norte-3** | Norte-1, Norte-5, Norte-6 | 19.8 |

Reparto de una bolsa diaria de 1000 Lucas: Norte-3 451, Norte-1 206, Norte-5 137, Norte-6 137, Sur-6 69.

_Tiempo de cómputo: 1.2 s._

## Ruta de tres pueblos

Norte y Sur no se ven: todo pasa por el pueblo del medio, y su gente cobra por escasez.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (6 celulares · 4 celulares · 4 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 20 de 20 |
| Demora p50 / p95 | 141.8 s / 393.5 s |
| Viajes de ferry | 82 |
| Caramelos cobrados (recibos verificados) | 56 |
| Cartas en bolsillos al final | 312 |
| La carta más avanzada que sigue en camino | a 200 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Medio | Medio-3 | Medio-1, Medio-2 | 51.0 |
| Norte | Norte-1 | Norte-3, Norte-4 | 42.0 |
| Sur | Sur-2 | — | 27.0 |
| **Nacional** | **Medio-3** | Medio-1, Norte-1, Sur-2 | 51.0 |

Reparto de una bolsa diaria de 1000 Lucas: Medio-3 250, Medio-1 250, Norte-1 206, Sur-2 132, Medio-2 44.

_Tiempo de cómputo: 1.8 s._

## Pueblos con viajeros

Dos pueblos lejos (200 m); tres personas caminan entre ellos y llevan las cartas en el bolsillo.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (1 celulares · 4 celulares · 1 celulares · 4 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 16 de 16 |
| Demora p50 / p95 | 97.7 s / 157.9 s |
| Viajes de ferry | 86 |
| Caramelos cobrados (recibos verificados) | 24 |
| Cartas en bolsillos al final | 236 |
| La carta más avanzada que sigue en camino | a 218 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Oeste | Oeste-1 | Oeste-6, Oeste-5 | 21.0 |
| Este | Este-6 | Este-2, Este-1 | 21.0 |
| **Nacional** | **Este-6** | Oeste-1, Este-2, Este-1 | 21.0 |

Reparto de una bolsa diaria de 1000 Lucas: Este-6 233, Oeste-1 233, Este-2 200, Este-1 200, Oeste-6 67.

_Tiempo de cómputo: 1.2 s._

## Ciudad de 1000

1000 celulares en ocho barrios de 125 que se tocan por el borde, con 40 personas caminando.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 138 (8 celulares · 2 celulares · 7 celulares · 4 celulares · 5 celulares · 6 celulares · 6 celulares · 3 celulares · 8 celulares · 3 celulares · 6 celulares · 4 celulares · 2 celulares · 2 celulares · 4 celulares · 7 celulares · 5 celulares · 1 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 6 celulares · 7 celulares · 8 celulares · 2 celulares · 8 celulares · 6 celulares · 6 celulares · 5 celulares · 6 celulares · 2 celulares · 5 celulares · 2 celulares · 7 celulares · 4 celulares · 6 celulares · 7 celulares · 6 celulares · 6 celulares · 5 celulares · 8 celulares · 7 celulares · 7 celulares · 6 celulares · 6 celulares · 6 celulares · 3 celulares · 8 celulares · 6 celulares · 6 celulares · 8 celulares · 2 celulares · 7 celulares · 5 celulares · 8 celulares · 7 celulares · 5 celulares · 3 celulares · 5 celulares · 7 celulares · 5 celulares · 7 celulares · 5 celulares · 5 celulares · 3 celulares · 3 celulares · 4 celulares · 8 celulares · 6 celulares · 7 celulares · 4 celulares · 3 celulares · 6 celulares · 7 celulares · 5 celulares · 8 celulares · 4 celulares · 4 celulares · 7 celulares · 2 celulares · 6 celulares · 4 celulares · 3 celulares · 8 celulares · 6 celulares · 2 celulares · 7 celulares · 7 celulares · 5 celulares · 8 celulares · 7 celulares · 2 celulares · 7 celulares · 4 celulares · 4 celulares · 8 celulares · 8 celulares · 8 celulares · 6 celulares · 5 celulares · 2 celulares · 7 celulares · 3 celulares · 6 celulares · 7 celulares · 3 celulares · 7 celulares · 5 celulares · 6 celulares · 7 celulares · 8 celulares · 6 celulares · 7 celulares · 8 celulares · 4 celulares · 7 celulares · 5 celulares · 7 celulares · 8 celulares · 7 celulares · 4 celulares · 6 celulares · 1 celulares · 3 celulares · 7 celulares · 8 celulares · 6 celulares · 7 celulares · 8 celulares · 2 celulares · 3 celulares · 7 celulares · 5 celulares · 6 celulares · 5 celulares · 2 celulares) |
| Cartas entregadas | **86 de 120** (72 %) |
| Entre islas distintas | 82 de 116 |
| Demora p50 / p95 | 149.6 s / 356.9 s |
| Viajes de ferry | 14392 |
| Caramelos cobrados (recibos verificados) | 117 |
| Cartas en bolsillos al final | 31576 |
| Copias soltadas, y por qué | la tiene otro: 11462, eco local agotado: 6436, promesa sin carta: 32 |
| La carta más avanzada que sigue en camino | a 795 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Barrio-1 | Barrio-1-5 | Barrio-1-102, Barrio-1-45 | 9.0 |
| Barrio-5 | Barrio-5-13 | Barrio-5-47, Barrio-5-1 | 21.0 |
| Barrio-6 | Barrio-6-123 | Barrio-6-69, Barrio-6-107 | 12.0 |
| Barrio-3 | Barrio-3-12 | Barrio-3-19, Barrio-3-114 | 18.0 |
| Barrio-2 | Barrio-2-57 | Barrio-2-39, Barrio-2-90 | 18.0 |
| Barrio-4 | Barrio-4-57 | Barrio-4-55, Barrio-4-72 | 12.0 |
| Barrio-7 | Barrio-7-105 | Barrio-7-116, Barrio-7-65 | 12.0 |
| Barrio-8 | Barrio-8-5 | Barrio-8-121, Barrio-8-95 | 9.0 |
| **Nacional** | **Barrio-5-13** | Barrio-2-57, Barrio-3-12, Barrio-3-19 | 21.0 |

Reparto de una bolsa diaria de 1000 Lucas: Barrio-5-13 10, Barrio-2-57 8, Barrio-3-12 8, Barrio-3-19 6, Barrio-6-123 5.

_Tiempo de cómputo: 125.8 s._

## Provincia: tres pueblos

Norte, Centro y Sur, a 2 km uno de otro y en barrios distintos, unidos por una ruta con un celular cada 55 m. En cada pueblo, algunos prestan Internet: el puente para el salto grande.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 46 (7 celulares · 5 celulares · 8 celulares · 3 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 2 celulares · 2 celulares · 2 celulares · 2 celulares · 2 celulares · 3 celulares · 2 celulares · 2 celulares · 2 celulares · 2 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 2 celulares · 2 celulares · 7 celulares · 5 celulares · 7 celulares · 7 celulares · 4 celulares · 6 celulares · 7 celulares · 4 celulares) |
| Cartas entregadas | **30 de 30** (100 %) |
| Entre islas distintas | 29 de 29 |
| Demora p50 / p95 | 3.1 s / 46.8 s |
| Viajes de ferry | 5262 |
| Caramelos cobrados (recibos verificados) | 45 |
| Cartas en bolsillos al final | 1731 |
| Por Internet (el puente) | 444 KB, de 16 puentes |
| Lucas de los puentes (bolsa diaria de 1000) | 775 |
| Precio de la información | 0.1 Lucas por KB llevado (17407 KB entre aire e Internet) |
| Copias soltadas, y por qué | promesa sin carta: 2, la tiene otro: 38, eco local agotado: 10 |
| La carta más avanzada que sigue en camino | a 4034 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-21 | Norte-20, Norte-22 | 18.0 |
| Sur | Sur-23 | Sur-24, Sur-20 | 18.0 |
| Ruta | Ruta-2 | — | 3.0 |
| **Nacional** | **Sur-23** | Norte-21, Sur-24, Sur-20 | 18.0 |

Reparto de una bolsa diaria de 1000 Lucas: Sur-23 113, Norte-21 113, Sur-24 94, Sur-20 94, Norte-20 57.

_Tiempo de cómputo: 19.8 s._

