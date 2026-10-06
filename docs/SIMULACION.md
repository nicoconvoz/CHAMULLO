# CHAMULLO — Simulación con el gemelo digital

Generado por `android/simulator` (`./gradlew :simulator:run`). Corre **el núcleo real de la app** (Node, sobres, recibos, pagos, `Islands.decide`);
lo simulado es la radio: alcance Wi-Fi 80 m, 3 s para sumarse a una isla, 30 ms por trama. Son números de diseño, no de campo.

## Una plaza llena

20 celulares en 60 m. Más de 7 no entran en una isla: se forman varias y se conectan por ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (7 celulares · 3 celulares · 7 celulares · 7 celulares) |
| Cartas entregadas | **30 de 30** (100 %) |
| Entre islas distintas | 25 de 25 |
| Demora p50 / p95 | 0.5 s / 0.6 s |
| Viajes de ferry | 38 |
| Puentes fijos (un pie en cada isla) | 7 en pie al final, 8 armados |
| Caramelos cobrados (recibos verificados) | 17 |
| Cartas en bolsillos al final | 570 |
| La libreta | 31 páginas, igual en 20 de 20 celulares |
| 👑 Rey de la libreta | Plaza-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 1996 (los que más: Plaza-1 918, Plaza-7 740, Plaza-3 338) |
| La carta más avanzada que sigue en camino | a 24 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Plaza | Plaza-1 | Plaza-7, Plaza-3 | 24.0 |
| **Nacional** | **Plaza-1** | Plaza-7, Plaza-3 | 24.0 |

Reparto de una bolsa diaria de 1000 Lucas: Plaza-1 471, Plaza-7 412, Plaza-3 118.

_Tiempo de cómputo: 3.9 s._

## Dos pueblos que se tocan por el borde

Pueblos de 8 celulares, bordes a ~70 m. Las cartas cruzan solo con el ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (3 celulares · 6 celulares · 5 celulares · 5 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 20 de 20 |
| Demora p50 / p95 | 0.6 s / 1.4 s |
| Viajes de ferry | 54 |
| Puentes fijos (un pie en cada isla) | 4 en pie al final, 4 armados |
| Caramelos cobrados (recibos verificados) | 26 |
| Cartas en bolsillos al final | 300 |
| La libreta | 22 páginas, igual en 16 de 16 celulares |
| 👑 Rey de la libreta | Norte-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 1019 (los que más: Norte-1 380, Norte-3 344, Sur-2 295) |
| La carta más avanzada que sigue en camino | a 110 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-3 | Norte-1 | 30.0 |
| Sur | Sur-2 | — | 30.0 |
| **Nacional** | **Norte-3** | Sur-2, Norte-1 | 30.0 |

Reparto de una bolsa diaria de 1000 Lucas: Norte-3 385, Sur-2 385, Norte-1 231.

_Tiempo de cómputo: 1.7 s._

## Ruta de tres pueblos

Norte y Sur no se ven: todo pasa por el pueblo del medio, y su gente cobra por escasez.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 6 (3 celulares · 3 celulares · 3 celulares · 4 celulares · 5 celulares · 2 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 20 de 20 |
| Demora p50 / p95 | 1.4 s / 1.7 s |
| Viajes de ferry | 48 |
| Puentes fijos (un pie en cada isla) | 5 en pie al final, 7 armados |
| Caramelos cobrados (recibos verificados) | 74 |
| Cartas en bolsillos al final | 282 |
| La libreta | 23 páginas, igual en 16 de 16 celulares |
| 👑 Rey de la libreta | Norte-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 1037 (los que más: Medio-2 230, Medio-1 230, Norte-3 212) |
| La carta más avanzada que sigue en camino | a 200 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Medio | Medio-2 | Medio-1 | 60.0 |
| Sur | Sur-3 | — | 36.0 |
| Norte | Norte-3 | Norte-1 | 51.0 |
| **Nacional** | **Medio-2** | Medio-1, Norte-3, Sur-3 | 60.0 |

Reparto de una bolsa diaria de 1000 Lucas: Medio-2 270, Medio-1 270, Norte-3 230, Sur-3 162, Norte-1 68.

_Tiempo de cómputo: 1.9 s._

## Pueblos con viajeros

Dos pueblos lejos (200 m); tres personas caminan entre ellos y llevan las cartas en el bolsillo.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (4 celulares · 3 celulares · 2 celulares · 4 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 19 de 19 |
| Demora p50 / p95 | 68.8 s / 138.6 s |
| Viajes de ferry | 50 |
| Puentes fijos (un pie en cada isla) | 2 en pie al final, 24 armados |
| Caramelos cobrados (recibos verificados) | 13 |
| Cartas en bolsillos al final | 292 |
| La libreta | 9 páginas, igual en 12 de 12 celulares |
| 👑 Rey de la libreta | Este-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 2018 (los que más: Oeste-4 1048, Este-3 350, Este-1 266) |
| La carta más avanzada que sigue en camino | a 207 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Este | Este-3 | Este-1, Este-4 | 36.0 |
| Oeste | Oeste-1 | Oeste-4, Oeste-6 | 36.0 |
| **Nacional** | **Este-3** | Oeste-1, Este-1, Oeste-4 | 36.0 |

Reparto de una bolsa diaria de 1000 Lucas: Este-3 286, Oeste-1 286, Este-1 262, Oeste-4 119, Oeste-6 24.

_Tiempo de cómputo: 1.1 s._

## Ciudad de 1000

1000 celulares en ocho barrios de 125 que se tocan por el borde, con 40 personas caminando.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 199 (7 celulares · 7 celulares · 7 celulares · 6 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 2 celulares · 7 celulares · 7 celulares · 7 celulares · 6 celulares · 8 celulares · 7 celulares · 6 celulares · 1 celulares · 7 celulares · 6 celulares · 7 celulares · 1 celulares · 7 celulares · 2 celulares · 6 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 8 celulares · 8 celulares · 6 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 6 celulares · 8 celulares · 7 celulares · 7 celulares · 5 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 5 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 6 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 5 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 6 celulares · 6 celulares · 7 celulares · 8 celulares · 6 celulares · 7 celulares · 7 celulares · 6 celulares · 5 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 5 celulares · 8 celulares · 7 celulares · 7 celulares · 6 celulares · 5 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 8 celulares · 4 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 8 celulares · 7 celulares · 6 celulares · 1 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 7 celulares · 6 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 6 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 3 celulares · 7 celulares · 7 celulares · 2 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 4 celulares · 7 celulares · 7 celulares · 7 celulares · 2 celulares · 5 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 8 celulares · 7 celulares · 6 celulares · 8 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares) |
| Cartas entregadas | **97 de 120** (81 %) |
| Entre islas distintas | 96 de 119 |
| Demora p50 / p95 | 8.6 s / 264.4 s |
| Viajes de ferry | 3444 |
| Puentes fijos (un pie en cada isla) | 409 en pie al final, 1088 armados |
| Caramelos cobrados (recibos verificados) | 353 |
| Cartas en bolsillos al final | 78052 |
| La libreta | 74 páginas, igual en 997 de 1000 celulares |
| 👑 Rey de la libreta | Barrio-6-2 y 20 nobles |
| Lucas pagadas por la libreta | 3864 (los que más: Barrio-6-2 106, Barrio-8-28 100, Barrio-4-81 69) |
| Copias soltadas, y por qué | eco local agotado: 23532, la tiene otro: 15225, promesa sin carta: 28 |
| La carta más avanzada que sigue en camino | a 795 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Barrio-3 | Barrio-3-27 | Barrio-3-43, Barrio-3-82 | 13.5 |
| Barrio-7 | Barrio-7-33 | Barrio-7-65, Barrio-7-53 | 15.0 |
| Barrio-6 | Barrio-6-123 | Barrio-6-102, Barrio-6-33 | 12.0 |
| Barrio-1 | Barrio-1-83 | Barrio-1-23, Barrio-1-25 | 15.0 |
| Barrio-8 | Barrio-8-2 | Barrio-8-47, Barrio-8-61 | 12.0 |
| Barrio-4 | Barrio-4-81 | Barrio-4-107, Barrio-4-52 | 12.0 |
| Barrio-2 | Barrio-2-106 | Barrio-2-33, Barrio-2-93 | 14.1 |
| Barrio-5 | Barrio-5-23 | Barrio-5-29, Barrio-5-69 | 18.0 |
| **Nacional** | **Barrio-5-23** | Barrio-7-33, Barrio-1-83, Barrio-1-23 | 18.0 |

Reparto de una bolsa diaria de 1000 Lucas: Barrio-5-23 10, Barrio-7-33 9, Barrio-1-83 9, Barrio-1-23 9, Barrio-5-29 8.

_Tiempo de cómputo: 563.4 s._

## Provincia: tres pueblos

Norte, Centro y Sur, a 2 km uno de otro y en barrios distintos, unidos por una ruta con un celular cada 55 m. En cada pueblo, algunos prestan Internet: el puente para el salto grande.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 48 (5 celulares · 8 celulares · 8 celulares · 8 celulares · 7 celulares · 3 celulares · 2 celulares · 2 celulares · 2 celulares · 1 celulares · 3 celulares · 2 celulares · 3 celulares · 3 celulares · 3 celulares · 3 celulares · 2 celulares · 1 celulares · 3 celulares · 3 celulares · 2 celulares · 7 celulares · 2 celulares · 2 celulares · 3 celulares · 3 celulares · 3 celulares · 2 celulares · 3 celulares · 2 celulares · 1 celulares · 2 celulares · 2 celulares · 1 celulares · 1 celulares · 2 celulares · 3 celulares · 3 celulares · 3 celulares · 7 celulares · 7 celulares · 5 celulares · 8 celulares · 6 celulares · 6 celulares · 7 celulares · 8 celulares · 7 celulares) |
| Cartas entregadas | **30 de 30** (100 %) |
| Entre islas distintas | 30 de 30 |
| Demora p50 / p95 | 1.5 s / 13.1 s |
| Viajes de ferry | 1273 |
| Puentes fijos (un pie en cada isla) | 49 en pie al final, 58 armados |
| Caramelos cobrados (recibos verificados) | 54 |
| Cartas en bolsillos al final | 2154 |
| La libreta | 34 páginas, igual en 68 de 147 celulares |
| 👑 Rey de la libreta | Norte-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 2175 (los que más: Norte-25 597, Norte-23 394, Norte-7 318) |
| Por Internet (el puente) | 4528 KB, de 16 puentes |
| Lucas de los puentes (bolsa diaria de 1000) | 791 |
| Precio de la información | 0.0 Lucas por KB llevado (23397 KB entre aire e Internet) |
| Copias soltadas, y por qué | la tiene otro: 15, eco local agotado: 33 |
| La carta más avanzada que sigue en camino | a 4034 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Sur | Sur-23 | Sur-22, Sur-21 | 27.0 |
| Norte | Norte-25 | Norte-20, Norte-23 | 24.0 |
| Ruta | Ruta-1 | — | 3.0 |
| **Nacional** | **Sur-23** | Norte-25, Sur-22, Norte-20 | 27.0 |

Reparto de una bolsa diaria de 1000 Lucas: Sur-23 155, Norte-25 138, Sur-22 103, Norte-20 86, Norte-23 86.

_Tiempo de cómputo: 19.1 s._

