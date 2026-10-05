# CHAMULLO — Proof of Relay / Proof of Delivery Specification v0.1

| Campo | Valor |
|---|---|
| Estado | Borrador |
| Fecha | 2026-10-05 |
| Depende de | Identity & Cryptography v0.1, Packet Format v0.1 |
| Consumida por | Reward & Anti-Fraud, Discovery & Routing, Simulator |

Las palabras **DEBE**, **NO DEBE**, **DEBERÍA** y **PUEDE** se usan con el sentido de RFC 2119.

---

## 1. Intuición

| Metáfora | Mecanismo |
|---|---|
| **La semilla que cambia en cada mano.** El origen planta una semilla; cada cartero la transforma con su sello. Al final se puede desarmar capa por capa hasta el origen. | Cadena de hashes `s_0 → s_1 → … → s_n` con registros firmados por salto (§4) |
| **"Se la di a FULANO."** Cada entrega la sellan los dos: el que da y el que recibe. | Registro de salto bilateral `HOP` + `ACPT` (§4.2) |
| **El papelito antes de recibir.** Antes de aceptar una carta, el receptor se compromete a firmar el recibo. | Feature obligatorio `receipts` y mensaje `ACCEPT` (§5) |
| **El recibo final.** El destinatario firma que recibió la carta y por qué camino llegó. | `RECEIPT` firmado por el destino (§6) |
| **Mil caminos con los mismos disfraces cuentan como uno.** | Ponderación por diversidad de identidades (§8) |

Las ideas de la semilla, del compromiso previo y de la diversidad son del Capitán del proyecto.

## 2. Objetivos

Un **Proof of Relay** debe permitir que cualquiera verifique, sin confiar en nadie:

1. que el mensaje existió y lo creó su origen (`origin_sig`, Packet Format §5);
2. que pasó por **exactamente** estos nodos, en **este** orden;
3. que cada entrega fue aceptada por quien la recibió;
4. que llegó a destino (Proof of Delivery).

Ataques que DEBE impedir:

| Ataque | Defensa |
|---|---|
| Agregar un relay que no participó | Firma bilateral por salto + identidad del enlace (§4.4) |
| Borrar a un relay que sí participó | Cadena de hashes y continuidad `to_i = giver_{i+1}` (§4.4) |
| Reordenar relays | Cadena de hashes |
| Reutilizar pruebas viejas | `msg_id` único y `exp` (Identity §8.2) |
| El destino se niega a firmar | Compromiso previo y reputación (§5) |
| Generar tráfico entre identidades propias | **No** se impide aquí; se vuelve poco rentable con la ponderación (§8) |

## 3. Notación

- `msg_id = H(src || nonce)` (Identity §8.2).
- `H` = SHA-512 truncado a 32 bytes (Identity §3).
- `sig_X(tag, ...)` = firma de X con separación de dominio (Identity §6).
- `|` = concatenación en el orden indicado, con la codificación canónica de Packet Format §4.2.

## 4. La semilla: cadena de saltos

### 4.1 Semilla inicial

```text
s_0 = H( "CHAMULLO/1/SEED" || 0x00 || msg_id || origin_sig )
```

La semilla queda atada a un único mensaje firmado por su origen. Nadie puede fabricar una semilla válida para un mensaje que no existe.

### 4.2 Registro de salto bilateral

Cada vez que un nodo *giver* entrega el sobre a un vecino *taker*, se produce el registro `HOP_i = { giver, taker, ts, accept_sig_i, hop_sig_i }`. Para el salto `i`, empezando en 1:

```text
accept_sig_i = sig_taker( "ACPT", msg_id | i | s_{i-1} | giver | taker | ts )
hop_sig_i    = sig_giver( "HOP",  msg_id | i | s_{i-1} | giver | taker | ts | accept_sig_i )
s_i          = H( s_{i-1} || giver || taker || ts || accept_sig_i || hop_sig_i )
```

- `accept_sig_i` es la firma del taker en el `ACCEPT` (§5).
- Un salto existe **solo si los dos lo firman**: el que da y el que recibe.
- El **primer** salto lo da el origen: `giver_1 = src`.

### 4.3 Dónde viaja

La lista ordenada `[HOP_1, …, HOP_k]` viaja en la sección de tránsito del `ENVELOPE`, dentro del registro TLV `hop_attestation` (tipo `2`, Packet Format §5.4). Cada relay agrega su salto al final antes de reenviar. `hop_count` DEBE ser igual al largo de la lista.

### 4.4 Verificación al recibir

Un nodo que recibe un `ENVELOPE` de su vecino `p` (autenticado por el handshake, Identity §7) DEBE verificar, en este orden:

1. `origin_sig` es válida (Packet Format §5.1).
2. Recalcula `s_0` y, salto por salto, cada `accept_sig_i`, `hop_sig_i` y `s_i`.
3. **Continuidad:** `giver_1 = src` y `taker_i = giver_{i+1}` para todo `i`.
4. **El último salto es real:** `giver_k = p` (quien se lo dio en la mano) y `taker_k = self`.
5. **Sin ciclos:** ningún `NodeId` aparece dos veces como `giver`.
6. `k ≤ max_hops`.

Si algo falla, el sobre DEBE descartarse y NO DEBE reenviarse.

**Por qué funciona:**

- **Insertar a "Pepe"** exige su firma y la del siguiente. Además, el último salto lo verifica el receptor contra el vecino real del enlace, así que un salto inventado al final no pasa.
- **Borrar a un relay** rompe la continuidad: el anterior selló "se la di a X", y sin X el siguiente no encaja. Recalcular la cadena sin X requiere firmas que el tramposo no tiene.
- **Reordenar** cambia todos los `s_i` posteriores.

## 5. El compromiso previo: aceptar es comprometerse a firmar

### 5.1 Feature obligatorio

Se define el feature `receipts` con **bit 0 (par, obligatorio)** en el mapa de features del handshake (Packet Format §7.3). Un nodo que no lo anuncie NO puede establecer enlaces CHAMULLO. Firmar recibos es la ley del pueblo, no una opción.

### 5.2 Flujo de entrega en el enlace

```text
giver → taker : OFFER  { msg_id, i, s_{i-1}, size, exp }
taker → giver : ACCEPT { ts, accept_sig_i }          ← el papelito
giver → taker : ENVELOPE (con HOP_i agregado)
```

- `ACCEPT` es la **promesa firmada**: el taker reconoce que recibió la oferta y se compromete a cumplir el protocolo con ese mensaje (reenviarlo o, si es el destino, emitir el `RECEIPT`).
- Si el taker no quiere el mensaje, responde `CLOSE`/`ERROR` con un motivo (por ejemplo, sin espacio o sin batería). Rechazar **antes** de aceptar no tiene penalidad.
- Un giver NO DEBE enviar el sobre sin un `ACCEPT` válido.

### 5.3 Romper la promesa

La criptografía no puede obligar a nadie a firmar. Pero un `ACCEPT` firmado sin el `RECEIPT` correspondiente antes de `exp` es **evidencia verificable** de una promesa incumplida:

- El giver conserva el `ACCEPT` y PUEDE publicarlo como **reporte de incumplimiento**.
- La spec de Reward & Anti-Fraud define cómo afectan esos reportes a la reputación: menos prioridad de ruteo, menos tráfico entrante y menos recompensas.
- El efecto buscado: **a quien no firma, nadie le lleva cartas.**

## 6. Proof of Delivery: el recibo

Cuando el destino `dst` recibe un sobre válido (§4.4), DEBE emitir:

```text
RECEIPT = {
  msg_id,
  s_k,                     ← la semilla final: compromete el camino completo
  hops: [HOP_1 … HOP_k],   ← copia de la cadena, para que cualquiera la verifique
  ts,
  rcpt_sig = sig_dst( "RCPT", msg_id | s_k | ts )
}
```

- El `RECEIPT` viaja de vuelta al origen como un `ENVELOPE` con `payload_type = 3` (`RECEIPT`, Packet Format §5.5).
- Los relays del camino de vuelta PUEDEN guardar una copia: es su comprobante de participación.
- Un destino que mantiene el feature `receipts` DEBE emitir el `RECEIPT` aunque el contenido no le interese.

## 7. El comprobante de contribución

Un **Proof of Relay** para el nodo `R` en el mensaje `msg_id` es la tupla:

```text
PoR = { ENVELOPE_origin_section, RECEIPT }
```

Un verificador lo acepta si:

1. La sección de origen y su `origin_sig` son válidas.
2. La cadena `hops` del `RECEIPT` cumple todas las reglas del §4.4, recalculada desde `s_0`.
3. El `s_k` recalculado coincide con el firmado en `rcpt_sig`.
4. `R` aparece como `giver` en algún salto `i ≥ 2` (los relays; el origen no cobra por enviar su propia carta).
5. El `RECEIPT` se emitió antes de `exp`.

## 8. Diversidad: mil caminos con los mismos disfraces cuentan como uno

La cadena prueba que un camino es **real**, no que sus participantes sean **personas distintas**. Un operador con muchas identidades puede generar caminos reales entre ellas. Esta sección fija los **principios** con que la spec de Reward & Anti-Fraud debe ponderar las contribuciones. La fórmula exacta se define allí.

### 8.1 Principios

1. **Lo que cuenta son las identidades distintas, no los caminos.** En un período, la contribución de un relay se mide por el conjunto de identidades **distintas** con las que trabajó (orígenes, destinos y demás relays), no por la cantidad de mensajes ni de caminos. Mil caminos armados con las mismas identidades valen lo mismo que uno.
2. **Rendimientos decrecientes por repetición.** Cada repetición del mismo par origen-destino aporta menos que la anterior (por ejemplo, de forma logarítmica).
3. **Identidades nuevas valen poco.** El peso de cada identidad crece con su antigüedad y con su historial de recibos honestos con terceros. Una identidad creada ayer pesa casi cero.
4. **Clientes reales por sobre carteros.** Pesa más la diversidad de orígenes y destinos, que son quienes usan la red, que la de relays. Es fácil disfrazarse de 100 carteros; es difícil conseguir 100 clientes reales.
5. **No castigar al honesto estable.** Una antena doméstica que todos los días lleva el tráfico de su barrio por el mismo camino tiene muchos clientes distintos, así que puntúa alto aunque repita el camino.

### 8.2 Esquema orientativo (no normativo)

```text
contribución(R, período) = Σ  peso(c) · f( n_c )
                           c ∈ identidades distintas con las que R trabajó

peso(c) = confianza de c según antigüedad e historial  (≈ 0 si es nueva)
f(n)    = 1 + log2(n)   (rendimiento decreciente por repetición)
```

### 8.3 Lo que queda abierto

Con estos principios, la única salida del tramposo es **fabricar más identidades y envejecerlas**. Encarecer eso es tarea de Reward & Anti-Fraud: prueba de trabajo al nacer, tiempo mínimo de maduración, aval de otros nodos u otras opciones.

## 9. Costo

| Elemento | Bytes aproximados |
|---|---|
| Registro `HOP` (2 `NodeId`, `ts`, 2 firmas) | 32 + 32 + 8 + 64 + 64 = **200** |
| Sobre con 5 saltos | 174 + 5 × 200 ≈ **1,2 KB** de overhead |
| `ACCEPT` en el enlace | ≈ 80 |

Es el precio de poder demostrar quién llevó la carta. En §10 hay ideas de compresión.

## 10. Preguntas abiertas

1. **Privacidad del camino:** el destino y los relays posteriores ven quién llevó la carta antes que ellos. ¿Hace falta ocultar el camino con una variante tipo cebolla (*onion*) que solo se revele al reclamar la recompensa?
2. **Compresión:** `taker_i` es redundante con `giver_{i+1}`. ¿Se omite en el cable y se reconstruye? ¿Conviene usar firmas agregadas para reducir los 128 bytes por salto?
3. **Recibos perdidos:** si el `RECEIPT` no llega de vuelta, los relays trabajaron sin comprobante. ¿Se reintenta por otro camino? ¿Se publica en un lugar común?
4. **Relleno de camino:** dos cómplices adyacentes pueden alargar el camino con saltos reales pero inútiles. Si la recompensa por entrega es fija y se **reparte** entre los saltos, alargar no da ganancia; lo define Reward.
5. **Destinos de difusión:** con `dst` = difusión no hay un único destino que firme. ¿Recibos por muestreo?
