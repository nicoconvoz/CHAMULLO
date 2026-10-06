# CHAMULLO — Simulación con el gemelo digital

Generado por `android/simulator` (`./gradlew :simulator:run`). Corre **el núcleo real de la app** (Node, sobres, recibos, pagos, `Islands.decide`);
lo simulado es la radio: alcance Wi-Fi 80 m, 3 s para sumarse a una isla, 30 ms por trama. Son números de diseño, no de campo.

## Una plaza llena

20 celulares en 60 m. Más de 7 no entran en una isla: se forman varias y se conectan por ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 3 (8 celulares · 8 celulares · 3 celulares) |
| Cartas entregadas | **30 de 30** (100 %) |
| Entre islas distintas | 19 de 19 |
| Demora p50 / p95 | 10.5 s / 38.4 s |
| Viajes de ferry | 154 |
| Caramelos cobrados (recibos verificados) | 19 |
| Cartas en bolsillos al final | 575 |
| La libreta | 20 páginas, igual en 20 de 20 celulares |
| 👑 Rey de la libreta | Plaza-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 1993 (los que más: Plaza-6 484, Plaza-17 314, Plaza-18 266) |
| La carta más avanzada que sigue en camino | a 24 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Plaza | Plaza-6 | Plaza-18, Plaza-13 | 12.0 |
| **Nacional** | **Plaza-6** | Plaza-18, Plaza-13, Plaza-17 | 12.0 |

Reparto de una bolsa diaria de 1000 Lucas: Plaza-6 211, Plaza-18 158, Plaza-13 158, Plaza-17 105, Plaza-19 105.

_Tiempo de cómputo: 3.5 s._

## Dos pueblos que se tocan por el borde

Pueblos de 8 celulares, bordes a ~70 m. Las cartas cruzan solo con el ferry.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 2 (6 celulares · 7 celulares) |
| Cartas entregadas | **17 de 20** (85 %) |
| Entre islas distintas | 17 de 20 |
| Demora p50 / p95 | 50.5 s / 94.0 s |
| Viajes de ferry | 152 |
| Caramelos cobrados (recibos verificados) | 6 |
| Cartas en bolsillos al final | 388 |
| La libreta | 7 páginas, igual en 16 de 16 celulares |
| 👑 Rey de la libreta | Norte-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 1999 (los que más: Sur-3 1143, Norte-3 408, Norte-2 245) |
| La carta más avanzada que sigue en camino | a 110 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-3 | Norte-1, Norte-8 | 27.0 |
| Sur | Sur-3 | Sur-4, Sur-1 | 9.0 |
| **Nacional** | **Norte-3** | Norte-1, Sur-3, Norte-8 | 27.0 |

Reparto de una bolsa diaria de 1000 Lucas: Norte-3 360, Norte-1 280, Sur-3 120, Norte-8 80, Sur-4 40.

_Tiempo de cómputo: 1.8 s._

## Ruta de tres pueblos

Norte y Sur no se ven: todo pasa por el pueblo del medio, y su gente cobra por escasez.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 5 (6 celulares · 2 celulares · 1 celulares · 4 celulares · 1 celulares) |
| Cartas entregadas | **19 de 20** (95 %) |
| Entre islas distintas | 19 de 20 |
| Demora p50 / p95 | 348.2 s / 563.1 s |
| Viajes de ferry | 103 |
| Caramelos cobrados (recibos verificados) | 29 |
| Cartas en bolsillos al final | 381 |
| La libreta | 12 páginas, igual en 10 de 16 celulares |
| 👑 Rey de la libreta | Norte-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 2018 (los que más: Medio-1 1076, Norte-3 268, Medio-2 193) |
| La carta más avanzada que sigue en camino | a 200 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Norte | Norte-1 | Norte-3, Norte-2 | 30.0 |
| Medio | Medio-2 | Medio-3, Medio-4 | 36.0 |
| Sur | Sur-1 | Sur-4, Sur-2 | 30.0 |
| **Nacional** | **Medio-2** | Medio-3, Medio-4, Norte-1 | 36.0 |

Reparto de una bolsa diaria de 1000 Lucas: Medio-2 160, Medio-3 147, Medio-4 147, Norte-1 133, Sur-1 133.

_Tiempo de cómputo: 2.0 s._

## Pueblos con viajeros

Dos pueblos lejos (200 m); tres personas caminan entre ellos y llevan las cartas en el bolsillo.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 4 (1 celulares · 4 celulares · 1 celulares · 5 celulares) |
| Cartas entregadas | **20 de 20** (100 %) |
| Entre islas distintas | 16 de 16 |
| Demora p50 / p95 | 92.7 s / 153.0 s |
| Viajes de ferry | 89 |
| Caramelos cobrados (recibos verificados) | 23 |
| Cartas en bolsillos al final | 234 |
| La libreta | 11 páginas, igual en 12 de 12 celulares |
| 👑 Rey de la libreta | Este-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 2017 (los que más: Este-1 710, Este-2 650, Oeste-1 494) |
| La carta más avanzada que sigue en camino | a 218 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Este | Este-1 | Este-2, Este-6 | 21.0 |
| Oeste | Oeste-1 | Oeste-6 | 21.0 |
| **Nacional** | **Este-1** | Oeste-1, Este-2, Este-6 | 21.0 |

Reparto de una bolsa diaria de 1000 Lucas: Este-1 259, Oeste-1 259, Este-2 222, Este-6 185, Oeste-6 37.

_Tiempo de cómputo: 1.3 s._

## Ciudad de 1000

1000 celulares en ocho barrios de 125 que se tocan por el borde, con 40 personas caminando.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 141 (8 celulares · 4 celulares · 7 celulares · 6 celulares · 2 celulares · 4 celulares · 8 celulares · 7 celulares · 2 celulares · 5 celulares · 7 celulares · 4 celulares · 3 celulares · 2 celulares · 5 celulares · 7 celulares · 8 celulares · 3 celulares · 4 celulares · 7 celulares · 8 celulares · 7 celulares · 7 celulares · 8 celulares · 1 celulares · 7 celulares · 8 celulares · 8 celulares · 6 celulares · 7 celulares · 4 celulares · 7 celulares · 6 celulares · 5 celulares · 8 celulares · 5 celulares · 6 celulares · 6 celulares · 5 celulares · 7 celulares · 7 celulares · 7 celulares · 7 celulares · 3 celulares · 2 celulares · 4 celulares · 8 celulares · 6 celulares · 8 celulares · 5 celulares · 7 celulares · 6 celulares · 5 celulares · 3 celulares · 2 celulares · 3 celulares · 4 celulares · 3 celulares · 2 celulares · 4 celulares · 6 celulares · 8 celulares · 8 celulares · 8 celulares · 8 celulares · 8 celulares · 7 celulares · 6 celulares · 1 celulares · 6 celulares · 5 celulares · 2 celulares · 1 celulares · 8 celulares · 5 celulares · 7 celulares · 7 celulares · 2 celulares · 4 celulares · 6 celulares · 2 celulares · 7 celulares · 7 celulares · 8 celulares · 7 celulares · 6 celulares · 7 celulares · 8 celulares · 8 celulares · 7 celulares · 1 celulares · 8 celulares · 7 celulares · 3 celulares · 7 celulares · 6 celulares · 7 celulares · 8 celulares · 7 celulares · 8 celulares · 4 celulares · 8 celulares · 5 celulares · 6 celulares · 8 celulares · 1 celulares · 2 celulares · 5 celulares · 8 celulares · 3 celulares · 5 celulares · 6 celulares · 2 celulares · 2 celulares · 3 celulares · 8 celulares · 5 celulares · 5 celulares · 5 celulares · 3 celulares · 7 celulares · 8 celulares · 5 celulares · 7 celulares · 7 celulares · 7 celulares · 4 celulares · 8 celulares · 7 celulares · 7 celulares · 3 celulares · 6 celulares · 7 celulares · 2 celulares · 7 celulares · 1 celulares · 7 celulares · 8 celulares · 5 celulares · 6 celulares · 8 celulares) |
| Cartas entregadas | **78 de 120** (65 %) |
| Entre islas distintas | 74 de 115 |
| Demora p50 / p95 | 163.9 s / 384.9 s |
| Viajes de ferry | 14319 |
| Caramelos cobrados (recibos verificados) | 85 |
| Cartas en bolsillos al final | 32900 |
| La libreta | 24 páginas, igual en 1000 de 1000 celulares |
| 👑 Rey de la libreta | Barrio-6-85 y 20 nobles |
| Lucas pagadas por la libreta | 1995 (los que más: Barrio-8-56 166, Barrio-8-98 166, Barrio-8-97 166) |
| Copias soltadas, y por qué | eco local agotado: 6807, la tiene otro: 9928, promesa sin carta: 39 |
| La carta más avanzada que sigue en camino | a 795 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Barrio-8 | Barrio-8-3 | Barrio-8-98, Barrio-8-97 | 9.0 |
| Barrio-1 | Barrio-1-2 | Barrio-1-18, Barrio-1-36 | 7.0 |
| Barrio-5 | Barrio-5-105 | Barrio-5-70, Barrio-5-58 | 12.0 |
| Barrio-2 | Barrio-2-84 | Barrio-2-43, Barrio-2-68 | 12.0 |
| Barrio-7 | Barrio-7-65 | Barrio-7-46, Barrio-7-17 | 10.5 |
| Barrio-6 | Barrio-6-112 | Barrio-6-14, Barrio-6-99 | 12.0 |
| Barrio-3 | Barrio-3-19 | Barrio-3-18, Barrio-3-26 | 12.0 |
| Barrio-4 | Barrio-4-105 | Barrio-4-100, Barrio-4-54 | 12.0 |
| **Nacional** | **Barrio-2-84** | Barrio-5-105, Barrio-5-70, Barrio-3-19 | 12.0 |

Reparto de una bolsa diaria de 1000 Lucas: Barrio-2-84 6, Barrio-5-105 6, Barrio-5-70 6, Barrio-2-43 6, Barrio-3-19 6.

_Tiempo de cómputo: 220.1 s._

## Provincia: tres pueblos

Norte, Centro y Sur, a 2 km uno de otro y en barrios distintos, unidos por una ruta con un celular cada 55 m. En cada pueblo, algunos prestan Internet: el puente para el salto grande.

| Resultado | Valor |
|---|---|
| Islas formadas en 90 s | 47 (7 celulares · 3 celulares · 6 celulares · 3 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 2 celulares · 2 celulares · 2 celulares · 3 celulares · 2 celulares · 2 celulares · 2 celulares · 2 celulares · 1 celulares · 6 celulares · 5 celulares · 3 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 1 celulares · 2 celulares · 3 celulares · 2 celulares · 6 celulares · 7 celulares · 1 celulares · 2 celulares · 5 celulares · 7 celulares · 7 celulares) |
| Cartas entregadas | **29 de 30** (97 %) |
| Entre islas distintas | 29 de 30 |
| Demora p50 / p95 | 14.2 s / 49.5 s |
| Viajes de ferry | 5213 |
| Caramelos cobrados (recibos verificados) | 40 |
| Cartas en bolsillos al final | 1921 |
| La libreta | 24 páginas, igual en 29 de 147 celulares |
| 👑 Rey de la libreta | Norte-1 (génesis: escribe el fundador) |
| Lucas pagadas por la libreta | 2177 (los que más: Norte-25 849, Norte-23 402, Norte-24 326) |
| Por Internet (el puente) | 1110 KB, de 16 puentes |
| Lucas de los puentes (bolsa diaria de 1000) | 745 |
| Precio de la información | 0.0 Lucas por KB llevado (20075 KB entre aire e Internet) |
| Copias soltadas, y por qué | la tiene otro: 29, eco local agotado: 10, promesa sin carta: 2 |
| La carta más avanzada que sigue en camino | a 4034 m del oeste |

**La Corte** (puntaje = diversidad × escasez, spec 04):

| Pueblo | 👑 Rey | 🎩 Nobleza | Puntaje del Rey |
|---|---|---|---|
| Sur | Sur-20 | Sur-21, Sur-23 | 24.0 |
| Norte | Norte-25 | Norte-23, Norte-22 | 27.0 |
| **Nacional** | **Norte-25** | Sur-20, Sur-21, Sur-23 | 27.0 |

Reparto de una bolsa diaria de 1000 Lucas: Norte-25 164, Sur-20 145, Sur-21 127, Sur-23 73, Norte-23 73.

_Tiempo de cómputo: 26.0 s._

