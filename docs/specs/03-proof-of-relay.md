# CHAMULLO — Proof of Relay / Proof of Delivery Specification v0.2

| Campo | Valor |
|---|---|
| Estado | Borrador |
| Fecha | 2026-10-05 |
| Depende de | Identity & Cryptography v0.1, Packet Format v0.1 |
| Consumida por | Economy & Governance, Discovery & Routing, Simulator |

Las palabras **DEBE**, **NO DEBE**, **DEBERÍA** y **PUEDE** se usan con el sentido de RFC 2119.

**Cambios respecto de v0.1:** el viaje se escribe **cifrado** para que ningún cartero vea la ruta completa (§4); el destino entrega una copia sellada del viaje que **debe coincidir** con la confirmación del origen (§6); cada cartero cobra presentando solo su propio salto (§7).

---

## 1. Intuición

Las ideas de esta spec son del Capitán del proyecto.

| Metáfora | Mecanismo |
|---|---|
| **La semilla que cambia en cada mano.** Cada cartero la transforma; al final se puede desarmar hasta el origen. | Cadena de hashes `s_0 → … → s_k` (§4) |
| **"Se la di a FULANO."** Cada entrega la sellan los dos. | Registro de salto bilateral `HOP` + `ACPT` (§4.2) |
| **El papelito antes de recibir.** Aceptar es comprometerse a firmar. | Feature obligatorio `receipts` y mensaje `ACCEPT` (§5) |
| **Nadie ve la ruta completa.** Cada cartero escribe su renglón en un idioma que solo entienden Ana y Dani. | Registros cifrados con la llave del viaje (§4.3) |
| **El destino entrega una copia sellada del viaje, y tiene que coincidir con la del origen.** | Acuse del destino + confirmación del origen sobre el mismo viaje (§6) |
| **Se paga a todos los que pasaron.** | Cada cartero cobra con su propio renglón (§7) |
| **Mil caminos con los mismos disfraces cuentan como uno.** | Ponderación por diversidad (§8) |

## 2. Objetivos

| Debe garantizar | Cómo |
|---|---|
| El mensaje existió y lo creó su origen | `origin_sig` (Packet Format §5) |
| Pasó por exactamente estos nodos, en este orden | Cadena de saltos, verificada completa por el destino (§6.1) |
| Cada entrega fue aceptada por quien la recibió | Firma bilateral por salto (§4.2) |
| Llegó a destino y el origen lo confirma | Acuse + confirmación que coinciden (§6) |
| **Ningún cartero ni la libreta ven la ruta completa** | Registros cifrados (§4.3), cobro individual (§7) |

| Ataque | Defensa |
|---|---|
| Agregar un relay que no participó | Firma bilateral + el destino verifica que el último cartero es su vecino real (§6.1) |
| Borrar o reordenar relays | Cadena de hashes y continuidad (§6.1) |
| Cobrar por un viaje inventado | Hace falta el secreto de entrega del destino **y** la firma del origen (§6) |
| Reutilizar pruebas viejas | `msg_id` único y `exp` (Identity §8.2) |
| El destino se niega a firmar | Compromiso previo y reputación (§5) |
| Tráfico entre identidades propias | Se vuelve poco rentable con la ponderación (§8) |

## 3. Notación

- `msg_id = H(src || nonce)`, donde `src` es la clave **efímera** del origen (Discovery & Routing §7.1).
- `H` = SHA-512 truncado a 32 bytes (Identity §3).
- `sig_X(tag, ...)` = firma de X con separación de dominio (Identity §6).
- `box(a_sk, b_pk, n, m)` = NaCl box (Identity §3).
- `|` = concatenación canónica (Packet Format §4.2).

## 4. La cadena de saltos

### 4.1 Lo que el origen pone en el sobre

Al crear el mensaje, el origen genera:

| Elemento | Dónde va | Para qué |
|---|---|---|
| Par efímero X25519 `(J_pub, J_sec)`: la **llave del viaje** | `J_pub` en el TLV de origen `journey_key`; `J_sec` **dentro** del contenido cifrado | Que solo origen y destino puedan leer el viaje |
| Secreto aleatorio `r` (32 bytes): el **secreto de entrega** | `R = H(r)` en el TLV de origen `delivery_commit`; `r` **dentro** del contenido cifrado | Que solo el destino real pueda probar que la abrió |

Semilla inicial:

```text
s_0 = H( "CHAMULLO/1/SEED" || 0x00 || msg_id || origin_sig )
```

### 4.2 Registro de salto bilateral

Para el salto `i` (desde 1), del *giver* al *taker*:

```text
accept_sig_i = sig_taker( "ACPT", msg_id | i | s_{i-1} | giver | taker | ts )
hop_sig_i    = sig_giver( "HOP",  msg_id | i | s_{i-1} | giver | taker | ts | accept_sig_i )

HOP_i = { giver, taker, ts, accept_sig_i, hop_sig_i, path_metrics }
```

- Un salto existe **solo si los dos lo firman**.
- `giver_1 = src` (la clave efímera del origen).
- `path_metrics` lleva las alternativas observadas, para el cálculo de escasez (Discovery & Routing §8).

### 4.3 El renglón cifrado

El giver cifra su registro con una clave efímera propia `e_i`:

```text
blob_i = e_i_pub || n_i || box( e_i_sec, J_pub, n_i, HOP_i )
s_i    = H( s_{i-1} || H(blob_i) )
```

- `blob_i` se agrega al final de la lista del TLV de tránsito `hop_attestation` (Packet Format §5.4).
- El giver DEBE conservar `e_i_sec` hasta cobrar (§7).
- Solo quien tenga `J_sec` (el origen y el destino) puede leer los renglones. Un cartero ve hashes y bytes cifrados.

### 4.4 Lo que verifica cada cartero

Un cartero no puede leer los renglones anteriores. Al recibir del vecino `p`, DEBE verificar:

1. `origin_sig` es válida.
2. La cadena `s_0 … s_{i-1}` es consistente con los `H(blob)` recibidos.
3. En el enlace, `p` le entregó `HOP_i` **en claro**, y en él:
   - `giver = p`, el vecino autenticado por el handshake;
   - `taker = self`;
   - `accept_sig_i` es su propia firma del `ACCEPT`;
   - `hop_sig_i` es válida.
4. `hop_count ≤ max_hops`.

La verificación de la cadena **completa** la hace el destino (§6.1).

## 5. El compromiso previo: aceptar es comprometerse a firmar

### 5.1 Feature obligatorio

El feature `receipts`, **bit 0 (par, obligatorio)** en el handshake (Packet Format §7.3). Sin él no hay enlace. Firmar recibos es la ley del pueblo.

### 5.2 Flujo en el enlace

```text
giver → taker : OFFER    { msg_id, i, s_{i-1}, size, exp }
taker → giver : ACCEPT   { ts, accept_sig_i }              ← el papelito
giver → taker : ENVELOPE (con blob_i agregado)  +  HOP_i en claro (solo en este enlace)
```

- Rechazar **antes** de aceptar no tiene penalidad.
- Un giver NO DEBE enviar el sobre sin un `ACCEPT` válido.

### 5.3 Romper la promesa

Un `ACCEPT` firmado sin acuse antes de `exp` es evidencia verificable de una promesa incumplida. El giver PUEDE publicarla. Las consecuencias en reputación las define Economy & Governance: **a quien no firma, nadie le lleva cartas.**

## 6. Proof of Delivery: el viaje sellado por los dos extremos

### 6.1 El destino verifica todo el viaje

Al reconocer su etiqueta (Discovery & Routing §7.2), el destino abre el contenido, obtiene `J_sec` y `r`, descifra **todos** los renglones y DEBE verificar:

1. Cada `accept_sig_i` y `hop_sig_i`.
2. **Continuidad:** `giver_1 = src` y `taker_i = giver_{i+1}`.
3. **El último salto es real:** `giver_k` es el vecino autenticado que se la entregó, y `taker_k` es él mismo.
4. **Sin ciclos:** ningún `NodeId` aparece dos veces como giver.
5. La cadena `s_0 … s_k` recalculada coincide.

Si un renglón no descifra o no verifica, el destino marca ese salto como **inválido**. Los saltos válidos conservan su derecho a cobro y el giver del salto inválido queda reportado.

### 6.2 La copia sellada del destino

```text
L         = [ H(blob_1), …, H(blob_k) ]          ← solo hashes: no revela nombres
j         = H( "CHAMULLO/1/JRNY" || 0x00 || msg_id || L || validez )
DELIVERY  = { msg_id, L, validez, r, ts }
```

- `validez` es un mapa de bits que dice qué saltos verificaron (§6.1).
- **Revelar `r` es el sello del destino:** solo quien abrió la carta conoce `r`, y cualquiera puede comprobarlo con `H(r) = R`. El destino no necesita revelar su identidad.
- El destino envía al origen, cifrado y por la red, el viaje **completo** en claro (`JOURNEY = [HOP_1 … HOP_k]`) junto con `DELIVERY`.

### 6.3 La confirmación del origen

El origen descifra el `JOURNEY` (tiene `J_sec`), lo verifica con las mismas reglas del §6.1, comprueba que el primer salto sea el que él entregó y recalcula `L` y `j`. **Solo si coincide**, firma:

```text
CONFIRM = sig_src( "CONF", msg_id | j )          ← con la misma clave efímera del sobre
```

### 6.4 Publicación

`DELIVERY` + `CONFIRM` se publican en la libreta del pueblo. La libreta acepta el viaje si:

1. `H(r) = R`, según el `delivery_commit` firmado por el origen;
2. `CONFIRM` es válida con la clave `src` del sobre;
3. ambos se refieren al **mismo** `j`.

**Si no coinciden, no se paga a nadie** y el caso queda para disputa.

## 7. El cobro: cada cartero con su renglón

Para cobrar el salto `i`, el giver presenta a la libreta:

```text
CLAIM = { sección de origen del sobre, i, e_i_sec, blob_i, HOP_i }
```

La libreta verifica:

1. Hay un viaje aceptado (§6.4) para ese `msg_id`.
2. `H(blob_i) = L[i]` y el bit `i` de `validez` está encendido.
3. Volviendo a cifrar `HOP_i` con `e_i_sec`, `n_i` y `J_pub` se obtiene exactamente `blob_i`. Así se prueba que el renglón es ese y no otro.
4. `hop_sig_i` y `accept_sig_i` son válidas, y el reclamante es `giver_i`.
5. `i ≥ 2`: el origen no cobra por mandar su propia carta.

**Qué aprende la libreta:** el reclamante, a quién se la dio y de quién la recibió. Nunca la ruta completa, ni el origen, ni el destino reales.

## 8. Diversidad: mil caminos con los mismos disfraces cuentan como uno

Principios que Economy & Governance §5 aplica:

1. Cuentan las **identidades distintas**, no los caminos.
2. La repetición rinde cada vez menos.
3. Las identidades nuevas pesan casi cero; pesan la antigüedad y el historial honesto.
4. No se castiga al honesto estable.

**Efecto de la privacidad:** como origen y destino son anónimos, la libreta ya **no** puede medir la diversidad de clientes. La diversidad se mide con lo que el cobro sí revela: los carteros vecinos de cada salto, su antigüedad y su historial. Ver §10.

## 9. Costo

| Elemento | Bytes aproximados |
|---|---|
| `HOP_i` en claro | ≈ 210 |
| `blob_i` (clave efímera + nonce + MAC + `HOP_i`) | ≈ 32 + 24 + 16 + 210 = **282** |
| Sobre con 5 saltos | 174 + 64 (`journey_key` + `delivery_commit`) + 5 × 282 ≈ **1,6 KB** |

## 10. Preguntas abiertas

1. **Diversidad de clientes sin revelarlos:** ¿algún token ciego que pruebe "son clientes distintos y viejos" sin decir quiénes son?
2. **Relleno de camino:** dos cómplices pueden alargar el camino con saltos reales pero inútiles. La recompensa por entrega se reparte (Economy §5.3), así que no ganan más.
3. **Destino y origen cómplices:** si son la misma persona, pueden confirmar viajes entre sus propios carteros. Los frenan la diversidad y la antigüedad, no la criptografía.
4. **Acuses perdidos:** si `DELIVERY` o `CONFIRM` no llegan, nadie cobra. ¿Se reintenta por otro camino?
5. **Difusión:** sin un único destino no hay quién revele `r`. ¿Recibos por muestreo?
6. **Compresión:** firmas agregadas para bajar los ≈ 282 bytes por salto.
