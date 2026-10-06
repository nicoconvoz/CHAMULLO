# CHAMULLO — Discovery & Routing Specification v0.2

| Campo | Valor |
|---|---|
| Estado | Borrador |
| Fecha | 2026-10-06 (v0.2: reglas exactas, islas Wi-Fi Direct y parámetros, §10) |
| Depende de | Identity v0.1, Packet Format v0.1, Proof of Relay v0.2 |
| Consumida por | Economy & Governance, Transport Abstraction, Simulator |

Las palabras **DEBE**, **NO DEBE**, **DEBERÍA** y **PUEDE** se usan con el sentido de RFC 2119.

---

## 1. Intuición

Las ideas de esta spec son del Capitán del proyecto.

| Metáfora | Mecanismo |
|---|---|
| **La tarjeta.** Para escribirle a alguien, primero te tiene que dar su tarjeta, por otro canal o porque se cruzaron. | Tarjeta de contacto firmada, intercambiada fuera de banda (§4) |
| **La brújula de los tesoros.** La carta solo dice "al norte"; en Santa Cruz, "al norte"; más arriba, "al este". | Reenvío geográfico por rumbo hacia el centro de una zona (§6), igual que `Hunt.bearing` de ICEBREAK |
| **El río con eco.** No se pasa a todos, solo a los suficientes para que siga el camino. | *Fanout* acotado hacia el destino (§6.2) |
| **El lago.** Si el río se traba, se ensancha o lo bordea. | Recuperación ante zonas vacías (§6.3) |
| **Nadie ve la ruta completa.** Cada cartero conoce solo al anterior y al siguiente. | Sello descartable, etiqueta de destino y viaje cifrado (§7) |
| **La mudanza.** Si Dani se muda, avisa solo a sus contactos. | Actualización de zona cifrada (§5) |

## 2. Zonas

El territorio se divide en una grilla jerárquica de celdas (estilo *geohash*):

| Nivel | Tamaño aproximado | Uso |
|---|---|---|
| Región | ~150 km | Rumbo de larga distancia |
| Pueblo | ~20 km | Economía (Economy §7), destino mínimo recomendado |
| Barrio | ~2 km | Destino por defecto en el sobre |
| Celda | ~100 m | Solo se comparte con vecinos directos, como en ICEBREAK |

- La grilla es fija y anidada, en grados (§10.1): cada celda de un nivel cae entera dentro de una del nivel superior.
- Un nodo NUNCA DEBE publicar coordenadas GPS exactas.
- El sobre lleva como destino una zona de nivel **barrio o mayor**. Cuanto más grande, más privacidad y más búsqueda final.

## 3. Descubrimiento de vecinos

1. Cada transporte anuncia la presencia del nodo a su manera (Transport Abstraction).
2. Al detectar un par, se ejecuta el handshake (Identity §7).
3. Ya autenticados, los vecinos intercambian por `LINK` su **celda** actual y métricas del enlace (latencia, pérdida, ancho de banda). La celda viaja firmada en el latido (`BEACON`, TLV `11`, Packet Format §6).
4. Cada nodo mantiene una **tabla de vecinos**: `NodeId`, celda, transporte, métricas y última vez visto.

**Verificación de plausibilidad:** si un vecino declara una celda incompatible con el alcance físico del transporte (por ejemplo, a 50 km por Bluetooth), su celda DEBE ignorarse y el vecino DEBERÍA perder prioridad de reenvío. Mentir con el GPS para atraer tráfico no paga.

## 4. Tarjeta de contacto

### 4.1 Contenido

```text
CARD {
  node_id     NodeId               identidad real
  zone        celda nivel barrio+  dónde anda, aproximado
  tag_secret  bytes[32]            secreto para reconocer cartas dirigidas a este nodo
  ts          u64
  sig         sig_node( "CARD", node_id | zone | tag_secret | ts )
}
```

### 4.2 Intercambio

- La tarjeta se entrega **fuera de banda**: QR cara a cara, ICEBREAK, otro CHAMULLO u otro canal; o bien por cercanía directa en un enlace autenticado.
- **No hay directorio público** de tarjetas. Quien no tiene tu tarjeta no puede escribirte, y eso evita el spam de desconocidos.

## 5. Mudanza: actualización de zona

- Cuando un nodo cambia de **barrio**, DEBERÍA enviar a sus contactos una tarjeta nueva dentro de un `ENVELOPE` cifrado (`SEALED`), uno por contacto: es una carta de mudanza, que solo lleva la tarjeta.
- Toda carta y todo acuse llevan la tarjeta actual del remitente dentro del contenido cifrado (TLV interno `21`). Así la zona se renueva sola con cada respuesta.
- Al recibirla, el destino reemplaza la tarjeta guardada solo si la firma es válida y el `node_id` es el del remitente.
- Si una carta llega a una zona vieja y no encuentra al destino, la búsqueda local (§6.4) agota su presupuesto y el origen recibe un aviso de no entrega por el tiempo de expiración.

## 6. Reenvío: la brújula y el río

### 6.1 Rumbo

Cada nodo calcula el rumbo desde su celda hasta el **centro** de la zona destino, con la misma geometría que `Hunt.bearing` de ICEBREAK. Un vecino tiene **progreso** si su celda está más cerca del centro de la zona que la propia.

### 6.2 Fanout acotado (el río)

- Entre los vecinos con progreso, se eligen hasta **`k = 2`** (el *fanout* de ECO), ordenados por progreso × calidad del enlace.
- Cada copia sigue su camino de forma independiente. Si un cartero se apaga, la otra copia continúa.
- Los duplicados se descartan por `msg_id` (Identity §8.2). Un nodo que ya vio un mensaje NO DEBE reenviarlo de nuevo.
- `k` se calibra con el simulador. Una prioridad paga (Economy §6) PUEDE usar `k = 3`.

### 6.3 El lago: zonas sin nodos

Si ningún vecino tiene progreso:

1. **Ensanchar:** se aceptan vecinos dentro de ±90° del rumbo, aunque no acerquen.
2. **Bordear:** si sigue trabado, se aplica la regla de la mano derecha (*perimeter routing*, como GPSR) con un presupuesto máximo de saltos sin progreso.
3. Si se agota el presupuesto, la copia se descarta.

### 6.4 Llegada al barrio

- Al entrar en la zona destino, el sobre pasa a un **eco local**: se reenvía a todos los vecinos dentro de la zona, con un TTL acotado.
- Cada nodo de la zona compara la **etiqueta de destino** del sobre (§7.2) con la suya. Solo el destinatario real la reconoce.

## 7. Privacidad: nadie ve la ruta completa

### 7.1 Sello descartable (oculta el origen)

- El `src` del sobre DEBE ser una **clave efímera**, nueva para cada mensaje. Es una clave Ed25519 válida, así que toda la validación de Identity §8.2 sigue funcionando.
- La identidad real del remitente viaja **dentro** del contenido cifrado. Solo la ve el destino.

### 7.2 Etiqueta de destino (oculta a quién va)

En lugar del `NodeId` del destino, el campo `dst` del sobre lleva:

```text
dst_tag = H( "CHAMULLO/1/TAG" || 0x00 || tag_secret_destino || nonce )
```

- Como incluye el `nonce` del mensaje, la etiqueta es **distinta en cada carta**. No se puede seguir a alguien juntando etiquetas.
- El destino calcula `H(... || su tag_secret || nonce)` y compara: un solo hash por sobre.
- **Limitación:** todos los contactos que tienen la tarjeta conocen el `tag_secret`. Si hacen de carteros, pueden reconocer las cartas dirigidas a ese destino. Ver §9.

### 7.3 Viaje cifrado (oculta la ruta)

Cada cartero escribe su registro de salto **cifrado** para que solo el origen y el destino lo lean (Proof of Relay v0.2 §4). Un cartero ve únicamente a quién se la dio y a quién se la pasa.

### 7.4 Quién ve qué

| Actor | Origen real | Destino real | Zona destino | Ruta completa |
|---|---|---|---|---|
| Cartero | ❌ | ❌ | ✅ (barrio o mayor) | ❌, solo sus vecinos |
| Cartero que es contacto del destino | ❌ | ⚠️ reconoce la etiqueta | ✅ | ❌ |
| Destino | ✅ | ✅ | ✅ | ✅ |
| Origen | ✅ | ✅ | ✅ | ✅ (por el acuse) |
| Libreta / Corte | ❌ | ❌ | ❌ | ❌, solo cobros sueltos |

## 8. Insumos para la economía

- **Alternativas (escasez, Economy §5.2):** cada nodo cuenta, por cada envío, cuántos vecinos distintos con antigüedad suficiente ofrecían progreso hacia esa zona. Lo informa en el TLV de tránsito `path_metrics` (Packet Format §5.4), dentro de su registro cifrado.
- **Prioridad paga:** cambia `k` y el orden en la cola, nunca el carril gratis.

## 9. Preguntas abiertas

1. **Etiqueta por contacto:** usar un secreto distinto por contacto evitaría que los contactos reconozcan cartas ajenas, pero el destino tendría que probar con todos sus contactos en cada sobre. ¿Cuál es el punto de equilibrio?
2. **Triangulación:** varios carteros cómplices pueden cruzar rumbos y deducir la zona destino. Si la zona es grande, la fuga es aceptable; hay que cuantificarlo en el simulador.
3. **Tamaño de zona dinámico:** ¿zonas más grandes en ciudades densas y más chicas en el campo?
4. **Destinos móviles rápidos** (personas en auto o en colectivo): ¿la búsqueda local alcanza?
5. **Modo agente secreto:** ruta tipo mamushka (*onion*) elegida por el origen, con propinas por capa, para quien necesite que ni siquiera los extremos vean la ruta.
6. **El barrio grande** (acertijo abierto del Capitán): en una ciudad densa todo cae en el mismo barrio y la carta pasa enseguida al eco local (§6.4). ¿Cómo encuentra el cartero la casa dentro del barrio sin que nadie sepa dónde vive el destino?

## 10. Implementación v0.2: reglas exactas

Lo que sigue fija cada número y cada desempate, para que el núcleo de la app y el gemelo digital hagan exactamente lo mismo. Implementación de referencia: `android/core/.../Zone.kt` y `Node.kt`.

### 10.1 La grilla

| Nivel | Código | Lado de la celda | Aproximado en Argentina |
|---|---|---|---|
| Celda | `0` | 0,001° | ~110 m × ~90 m |
| Barrio | `1` | 0,02° | ~2,2 km × ~1,8 km |
| Pueblo | `2` | 0,2° | ~22 km × ~18 km |
| Región | `3` | 1,4° | ~155 km × ~127 km |

- Índices: `lat_i = ⌊lat / lado⌋`, `lon_i = ⌊lon / lado⌋`. El centro es `((lat_i + ½)·lado, (lon_i + ½)·lado)`.
- Codificación: `nivel u8 || zigzag-varint(lat_i) || zigzag-varint(lon_i)`.
- Distancia: haversine sobre la esfera de 6 371 km. Rumbo: el rumbo inicial del círculo máximo, en grados desde el norte (`Hunt.bearing`).

### 10.2 Vecinos y plausibilidad

- La tabla de vecinos guarda la celda firmada de cada latido.
- **Plausible:** la distancia entre el centro de mi celda y el de la suya es de `PLAUSIBLE_M = 400 m` o menos con Wi-Fi Direct (dos alcances más la diagonal de una celda). Una celda no plausible se ignora: ese vecino no ofrece progreso.
- **Estable:** se lo escuchó `STABLE_BEATS = 2` veces o más. Calidad del enlace: `1` si es estable, `½` si no.
- Al oír a un vecino nuevo, el nodo responde con su propio latido, como mucho una vez cada `BEACON_REPLY_MS = 5 s`. Así un ferry que llega conoce la isla en un instante y no en 15 s.

### 10.3 Presupuesto de saltos

El origen fija `max_hops = clamp(⌈d / HOP_M⌉ + LOCAL_TTL + 4, 8, 255)`, donde `d` es la distancia del centro de su celda al centro de la zona destino (`0` si ya está dentro) y `HOP_M = 40 m` con Wi-Fi Direct. Sin zona destino, `max_hops = 8`.

### 10.4 Qué hace cada cartero con una carta

Al tener una carta en el bolsillo (por haberla escrito o aceptado), el cartero decide, en este orden:

1. **Sin zona destino** (tarjeta vieja): eco acotado de v0.1. Grito a todos (`receivers` vacío), una vez por carta, y la guarda para repetirla ante vecinos nuevos (hasta `MAX_RESHOUTS = 5`).
2. **Ya está en el barrio destino** (su celda cae dentro): eco local (§6.4). El primer cartero del barrio pone `local_ttl = LOCAL_TTL = 4` (TLV de tránsito `7`); cada salto dentro del barrio lo baja en 1, y con `0` ya no se reenvía.
3. **Fuera del barrio, con vecinos que acercan:** el río (§6.2). Progreso de un vecino = `dist(mi celda, zona) − dist(su celda, zona)`; cuenta si es de 10 m o más. Se ordena por progreso × calidad, con desempate por `node_id` menor. El origen ofrece a los primeros `k = 2` y cada cartero a **uno**: son `k` corrientes que siguen cada una su camino (§6.2), sin duplicarse en cada salto. Los vecinos que no aceptaron esta carta quedan afuera en los siguientes intentos.
4. **Fuera del barrio, nadie acerca:** el lago (§6.3).
   - Ensanchar: vecinos plausibles cuyo rumbo esté a ±90° del rumbo a la zona, excepto quien me la dio.
   - Bordear: si no hay, el primero en sentido horario desde el rumbo a la zona (regla de la mano derecha), excepto quien me la dio.
   - Cada salto sin progreso suma 1 al TLV de tránsito `9` (`detour`). Un salto con progreso lo vuelve a `0`.
   - Con `LAKE_BUDGET = 3` agotado, la copia **no se descarta: se lleva** (punto 5). Cambio respecto de §6.3: en las islas, que nadie acerque suele ser pasajero, porque el progreso llega en ferry.
   - Vecinos en mi misma celda no cuentan: no mueven la carta.
   - En el lago se ofrece a **uno** solo.
5. **Sin ningún vecino:** la guarda (llevar y traer). La vuelve a decidir cuando aparece un vecino nuevo, cada `RETRY_MS = 10 s`, hasta `MAX_RESHOUTS` veces.

### 10.5 La entrega en mano: ofrecer, aceptar, soltar

- El giver ofrece (`OFFER`, Proof of Relay §5.2) a cada elegido. El taker acepta si no la vio, no venció y tiene lugar (`MAX_POCKETS = 200`).
- Con cada `ACCEPT` válido, el giver arma la copia de ese taker: agrega su renglón con `taker` y `accept_sig` y grita el sobre con `receivers = [taker]` (TLV de tránsito `5`).
- **Recibo de la copia:** cuando la copia llega, el taker responde `ACCEPT` con `already` ("la tengo"). Recién ahí el giver la cuenta como entregada. El gemelo lo pidió: un taker que se iba (un ferry que zarpaba) dejaba la copia perdida en el camino y el giver ya había soltado la suya.
- **Soltar:** el giver borra su copia cuando todos los elegidos dijeron "la tengo", o a los `OFFER_TIMEOUT_MS = 2 s` si al menos uno lo dijo. Si nadie lo dijo, la guarda y reintenta (punto 5); en el peor caso queda una copia de más, nunca una carta de menos.
- **Nunca se devuelve** una carta a quien me la dio, ni por el río ni por el lago.
- **"La tengo":** quien recibe una oferta de una carta que **tiene ahora en el bolsillo** (o ya aceptó) responde `ACCEPT` con `already` (TLV `13`) y sin firma. El giver la da por entregada a ese vecino: otra copia sigue viva ahí. Así la segunda corriente del río no queda dando vueltas para siempre.
- Haberla visto **no cierra el camino**: si se la ofrecen en mano otra vez, la acepta (si no la tiene ahora). No hay rulos: nunca vuelve a quien la dio, espera antes de retroceder y `max_hops` es el tope. Cambio respecto de §6.2, que el gemelo pidió: con "nunca de nuevo", una carta que retrocedía quedaba encerrada.
- Haberla **visto** no alcanza para decirlo: quien la vio pudo pasarla y esa copia pudo perderse. El gemelo lo mostró: con "ya la vi" se perdían cartas. En ese caso, y para las cartas propias del origen (para no delatarse), se calla, como en cualquier rechazo.
- Quien escucha un sobre con `receivers` en el que no figura, no lo guarda ni lo reenvía. Solo lo abre si es el destino (la etiqueta lo reconoce, §7.2).
- El eco local y el eco acotado no ofrecen: gritan a todos (`receivers` vacío) y el renglón lleva `taker` vacío.

### 10.6 Islas Wi-Fi Direct (Camino y Carretera §6)

- **El anfitrión es el aire de la isla:** repite cada trama de un miembro a los demás. Es cartero solo si lo eligen, como cualquiera.
- Los vecinos de un teléfono son los de su isla y los ferrys que la visitan.
- **El ferry con brújula:** al empezar su turno, anuncia en su latido la celda del anfitrión de la isla a la que va (su **rumbo**) y espera `FERRY_BOARDING_MS = 3 s` antes de irse. Los miembros le ofrecen las cartas para las que ese rumbo acerca. Mientras tiene rumbo, no reparte: lleva. Al llegar, vuelve a anunciar su celda real y reparte con la brújula entre los de la isla nueva.
- El ferry va, por turnos, a cada isla vecina con lugar: la del índice `turno mod cantidad`, en orden de id. Así ninguna dirección queda sin barco.
- **El ferry de necesidad:** quien guarda una carta que nadie de su isla acerca, viaja él mismo (una vez por turno) a la isla vecina cuyo anfitrión la acerca más. Un anfitrión solo lo hace si está solo: nunca deja a sus miembros sin aire.
- **Antes del lago, esperar:** sin progreso, la carta espera `LAKE_WAIT_MS = 2 turnos de ferry` a que la lleve el ferry de necesidad. Recién después ensancha o bordea. Sin esta espera, el lago la alejaba del borde de la isla justo antes de que llegara el barco.

### 10.7 Los pagos mientras no exista la libreta

Hasta que la libreta del pueblo exista (Proof of Relay §6.4, §7), la confirmación del origen (`PAYMENT`) viaja como una carta hacia la zona destino del sobre que paga: río `k = 2` sin ofrecer, eco local al llegar y un tope de `max_hops` del sobre. Cada teléfono que la escucha y encuentra su renglón cobra su caramelo.
- Quien la reenvía la guarda `PAY_KEEP_MS = 30 min` y la repite ante cada vecino nuevo (hasta `MAX_RESHOUTS`), igual que una carta. Así sube al ferry y llega a los carteros de otras islas.
- Si nada acerca, la confirmación sale en un grito a todos con a lo sumo 2 saltos.
- Al escuchar la confirmación de una carta, el cartero suelta su copia: ya llegó.

## 11. El puente por Internet (diseño del Capitán)

Donde hay un **salto grande** (dos zonas sin celulares que las unan), la carta toma un atajo por Internet y vuelve a la red de islas del otro lado:

```text
red local → isla → ferry → pueblo → 🕳️ salto grande → enrutamiento auxiliar por Internet → otra zona → isla → ferry → destino
```

- Es **auxiliar**, y entra en dos casos:
  - **Salto grande:** la zona destino está a más de `BIG_JUMP_M = 2,5 km`, o sea más allá del barrio vecino. Por islas Wi-Fi eso es media hora o más. Si hay un puente a mano (yo, o un vecino de mi isla), la carta sube aunque la ruta avance. El gemelo lo mostró: sin esta regla, la ruta lenta siempre ofrecía "algo" de progreso y el puente no se usaba nunca.
  - **Trabada:** sin progreso y pasados `LAKE_WAIT_MS`, va a un vecino puente antes que al lago. Un puente trabado sube enseguida.
- **Quién es puente:** un teléfono con Internet que lo presta. Lo anuncia en su latido (TLV `13`) y se anota con su barrio en el directorio de la nube.
- **Bajada diversificada:** la nube la baja por `BRIDGE_PEERS = 3` puentes distintos del barrio destino, y desde ahí sigue con el eco local. El directorio rota el orden para repartir la carga.
- **Cobro:** quien la sube escribe su renglón como cualquier cartero, y los que la bajan también, al seguir el eco. Cobran con los mismos recibos. Los pagos también cruzan por el puente.
- **Diversificado**: la entrega se reparte entre varios caminos por Internet a la vez, para no depender de uno solo.
- La carta sigue sellada de punta a punta (§7): el tramo por Internet ve lo mismo que un cartero, nada más.
- Implementado en el núcleo (`Bridge`, `Node`) y en el gemelo (la nube de `World`). Pendiente en la app: la nube real (un servidor o un directorio entre pares) y medir los bytes prestados.
- El puente cobra en **Lucas** por un servicio real: eso le da a la moneda su respaldo (Economy & Governance §13).

