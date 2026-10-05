# CHAMULLO — Packet Format Specification v0.1

| Campo | Valor |
|---|---|
| Estado | Borrador |
| Fecha | 2026-10-05 |
| Depende de | Identity & Cryptography v0.1 |
| Consumida por | Discovery & Routing, Proof of Relay / Proof of Delivery, Transport Abstraction |

Las palabras **DEBE**, **NO DEBE**, **DEBERÍA** y **PUEDE** se usan con el sentido de RFC 2119.

---

## 1. Intuición: el idioma universal de gestos

Va a haber muchas implementaciones y versiones de CHAMULLO, que en la práctica hablan "idiomas" distintos. Igual que las personas, que hablan lenguas diferentes pero entienden todas una sonrisa o un llanto, los nodos comparten un **conjunto mínimo de gestos universales** y, sobre esa base, **se alinean en los gestos que tienen en común**.

En términos técnicos:

| Metáfora | Mecanismo |
|---|---|
| Gestos que todos entienden | Prefijo universal congelado y el mensaje `HELLO` (§3, §7) |
| Cada gesto se reconoce y se sabe cuándo termina | Campos TLV: tipo, largo, valor (§4) |
| Buscar los gestos que comparten las partes | Negociación de *features* en el handshake (§7) |
| Gestos de cortesía y gestos de ¡alto! | Regla par/impar (§4.3) |
| Los gestos siempre en el mismo orden | Codificación canónica para firmar (§4.2) |

## 2. Convenciones de codificación

- **Orden de bytes:** *big-endian* (orden de red).
- **Enteros de tamaño fijo:** `u8`, `u16`, `u32`, `u64`, sin signo.
- **`varint`:** entero variable de QUIC (RFC 9000 §16). Los 2 bits altos del primer byte indican el largo (1, 2, 4 u 8 bytes) y permiten valores hasta 2⁶² − 1. Un `varint` **DEBE** usar la codificación más corta posible; un receptor DEBE rechazar codificaciones no mínimas.
- **`bytes[n]`:** secuencia de exactamente `n` bytes.
- **`NodeId`:** `bytes[32]` según Identity §4.
- **Tiempo:** `u64` en milisegundos Unix (UTC).

## 3. Prefijo universal (congelado)

Todo paquete CHAMULLO, de cualquier versión, empieza con estos 4 bytes:

| Campo | Tipo | Valor |
|---|---|---|
| `magic` | `bytes[2]` | `0x43 0x48` (ASCII `"CH"`) |
| `version` | `u8` | versión del formato de cable; esta spec define `1` |
| `kind` | `u8` | clase de paquete (§3.1) |

Este prefijo **NUNCA cambia** entre versiones. Ante un `magic` incorrecto, el paquete DEBE descartarse en silencio. Ante una `version` desconocida, el paquete DEBE descartarse; la compatibilidad entre versiones se resuelve antes, en el `HELLO` (§7).

### 3.1 Clases de paquete (`kind`)

| `kind` | Nombre | Alcance |
|---|---|---|
| `0x01` | `LINK` | Entre dos vecinos directos. Nunca se retransmite. |
| `0x02` | `ENVELOPE` | De extremo a extremo; viaja por múltiples saltos. |
| `0x03` | `FRAGMENT` | Trozo de un paquete más grande que la MTU del transporte. |
| `0x00`, `0x04`–`0xFF` | Reservado | Un receptor DEBE descartar clases que no conoce. |

## 4. Campos TLV (los gestos)

Un **flujo TLV** es una secuencia de registros:

```text
tlv_record = type:varint  length:varint  value:bytes[length]
tlv_stream = tlv_record*
```

Todo flujo TLV va precedido por su largo total en bytes (`varint`), así que un receptor siempre puede saltarlo completo.

### 4.1 Rangos de tipos

| Rango | Uso |
|---|---|
| `0` | Reservado; NO DEBE usarse. |
| `1` – `1023` | Núcleo: definido por las specs de CHAMULLO. |
| `1024` – `65535` | Extensiones registradas en un registro público del proyecto. |
| `65536` en adelante | Experimental o privado; sin coordinación. |

### 4.2 Reglas canónicas

1. Los registros DEBEN aparecer en **orden estrictamente creciente** de `type`.
2. NO DEBE haber `type` duplicados.
3. `type` y `length` DEBEN usar `varint` mínimo.
4. Un flujo que viole alguna de estas reglas DEBE rechazarse completo.

Con estas reglas, un mismo mensaje lógico tiene **una sola** representación en bytes. Esa es la "codificación canónica" que exige Identity §6 para calcular firmas.

### 4.3 Regla par/impar

Cuando un receptor encuentra un `type` que no conoce:

- **Impar (cortesía):** lo DEBE ignorar al interpretar el mensaje, y si el mensaje se retransmite, lo DEBE **conservar intacto**.
- **Par (obligatorio):** DEBE rechazar el mensaje completo, porque procesarlo sin entender ese campo podría ser incorrecto.

Quien define un campo nuevo elige la paridad según sea seguro ignorarlo o no. La regla viene del formato TLV de Lightning (BOLT #1, "*it's ok to be odd*").

## 5. `ENVELOPE`: el sobre de extremo a extremo

```text
ENVELOPE =
  prefix           bytes[4]   magic, version=1, kind=0x02
  ── sección de origen (inmutable, firmada por src) ──
  src              NodeId
  dst              NodeId
  nonce            bytes[16]
  ts               u64
  exp              u64
  origin_tlv_len   varint
  origin_tlv       tlv_stream
  origin_sig       bytes[64]
  ── sección de tránsito (la modifican los relays) ──
  hop_count        u8
  transit_tlv_len  varint
  transit_tlv      tlv_stream
```

### 5.1 Sección de origen

- `src`, `nonce`, `ts`, `exp` y el identificador del mensaje `H(src || nonce)` se definen y validan según Identity §8.2.
- `dst` = `NodeId` del destinatario. El valor de 32 bytes en cero significa **difusión** dentro del alcance que indique el campo `scope` (§5.3). Su semántica la define la spec de Routing.
- **Firma de origen** (Identity §6, tag `ENV`):

  ```text
  body       = version || kind || src || dst || nonce || ts || exp || origin_tlv_len || origin_tlv
  origin_sig = Ed25519.sign(src_key, "CHAMULLO/1/ENV" || 0x00 || body)
  ```

  Incluir `version` y `kind` impide reinterpretar los mismos bytes como otra versión u otra clase.
- Un relay NO DEBE modificar ningún byte de la sección de origen. Cualquier cambio invalida `origin_sig` y el paquete DEBE descartarse.

### 5.2 Sección de tránsito

- `hop_count`: el origen lo pone en `0` y cada relay lo incrementa en 1 antes de reenviar. Si `hop_count ≥ max_hops` (§5.3), el paquete NO DEBE reenviarse.
- `transit_tlv`: aquí cada relay agrega su **atestación de retransmisión** (tag `HOP`), firmada con su propia clave. El formato de esa atestación lo define la spec de *Proof of Relay / Proof of Delivery*; los tipos reservados están en §5.4.
- La sección de tránsito **no** está cubierta por `origin_sig`. Su integridad depende de las firmas individuales de cada relay.

> **Nota de seguridad.** Un relay malicioso puede mentir en `hop_count` o borrar atestaciones previas. `max_hops` está firmado por el origen y pone un tope duro. La defensa contra el borrado de atestaciones corresponde a Proof of Relay; por ejemplo, encadenando cada atestación con el hash de la anterior.

### 5.3 TLV de origen del núcleo

| Tipo | Paridad | Nombre | Valor | Descripción |
|---|---|---|---|---|
| `2` | par | `payload_type` | `varint` | Qué contiene `payload` (§5.5). Obligatorio. |
| `4` | par | `max_hops` | `u8` | Límite de saltos fijado por el origen. Obligatorio. |
| `6` | par | `payload` | `bytes` | Contenido; cifrado o en claro según `payload_type`. |
| `7` | impar | `scope` | `bytes` | Alcance de una difusión (zona, radio). Lo define Routing. |
| `9` | impar | `route_hint` | `bytes` | Pista de ruta opaca. La define Routing. |
| `11` | impar | `content_hash` | `bytes[32]` | `H(contenido en claro)`, para deduplicar contenido idéntico. |

### 5.4 TLV de tránsito reservados

| Tipo | Paridad | Nombre | Definido en |
|---|---|---|---|
| `2` | par | `hop_attestation` | Proof of Relay / Delivery |
| `3` | impar | `path_metrics` | Discovery & Routing |

Como un flujo TLV no admite tipos duplicados (§4.2), las múltiples atestaciones de una ruta van **todas dentro** de un único registro `hop_attestation`, cuyo formato interno es una lista definida por Proof of Relay.

### 5.5 Tipos de payload

| `payload_type` | Nombre | Contenido de `payload` |
|---|---|---|
| `1` | `SEALED` | `box(src_sk, dst_pk)` según Identity §9: `nonce[24] || ciphertext` |
| `2` | `PLAIN` | Bytes en claro, autenticados solo por `origin_sig`. Para difusiones públicas. |
| `3` | `RECEIPT` | Recibo de entrega firmado por el receptor (tag `RCPT`). Lo define Proof of Delivery. |
| `4`+ | — | Reservados para specs futuras. |

## 6. `LINK`: mensajes entre vecinos

```text
LINK =
  prefix     bytes[4]   magic, version, kind=0x01
  msg_type   varint
  tlv_len    varint
  tlv        tlv_stream
```

Los mensajes `LINK` nunca salen del enlace en el que se crearon.

| `msg_type` | Nombre | Uso |
|---|---|---|
| `1` | `HELLO` | Paso 1 del handshake (§7) |
| `2` | `CHALLENGE` | Paso 2 del handshake |
| `3` | `CONFIRM` | Paso 3 del handshake |
| `4` | `PING` / `5` `PONG` | Mantener vivo el enlace y medir latencia |
| `6` | `CLOSE` | Cierre ordenado, con motivo |
| `7` | `ERROR` | Error de protocolo, con código y texto opcional |

## 7. Handshake y negociación de gestos

### 7.1 `HELLO` es el gesto universal

`HELLO` se codifica **siempre** con `version = 1`, aunque el nodo hable versiones más nuevas. Así cualquier par puede entender el saludo y descubrir qué versiones tienen en común.

### 7.2 Campos del handshake

| Tipo | Paridad | Nombre | Valor | Presente en |
|---|---|---|---|---|
| `2` | par | `node_id` | `NodeId` | `HELLO`, `CHALLENGE` |
| `4` | par | `nonce` | `bytes[16]` | `HELLO`, `CHALLENGE` |
| `6` | par | `versions` | lista de `u8` | `HELLO`, `CHALLENGE` |
| `8` | par | `features` | mapa de bits | `HELLO`, `CHALLENGE` |
| `10` | par | `signature` | `bytes[64]` | `CHALLENGE`, `CONFIRM` |
| `11` | impar | `agent` | texto UTF-8 | Opcional: nombre y versión de la implementación |

### 7.3 Alinearse en los gestos comunes

- **Versión:** se usa la **más alta** presente en `versions` de ambos lados. Si no hay ninguna en común, se envía `ERROR` y se cierra el enlace.
- **Features:** cada bit `i` del mapa indica que el nodo conoce el feature `i`. Los bits van en pares, igual que en Lightning:
  - **bit par** = "lo **necesito**; sin esto no hablo";
  - **bit impar** = "lo **conozco** y lo uso si el otro también".
- El conjunto efectivo del enlace es la **intersección** de ambos mapas.
- Si un nodo marca como necesario un feature que el otro no conoce, el enlace DEBE cerrarse con `ERROR`.
- **Protección contra degradación:** las firmas del handshake (Identity §7) DEBEN cubrir `versions` y `features` de **ambos** lados, para que nadie en el medio pueda borrar gestos y forzar una versión más débil.

## 8. `FRAGMENT`: cartas más grandes que el buzón

Cada transporte tiene un tamaño máximo de paquete (MTU) distinto; Bluetooth LE, por ejemplo, es mucho más chico que WebRTC. Si un paquete supera la MTU del enlace, el emisor lo parte:

```text
FRAGMENT =
  prefix    bytes[4]   magic, version, kind=0x03
  frag_id   bytes[8]   aleatorio, igual en todos los trozos
  index     varint     desde 0
  count     varint     total de trozos
  data      bytes      el resto del frame
```

- La fragmentación es **por enlace**: el vecino rearma el paquete y lo vuelve a fragmentar según la MTU de su siguiente enlace.
- El receptor DEBERÍA limitar los rearmados pendientes (por ejemplo, 64 por vecino, como el `Fragmenter` de ICEBREAK) y descartar los incompletos tras un tiempo.
- El tamaño máximo de un `ENVELOPE` rearmado es **64 KiB**. Los contenidos más grandes se dividen en la capa de aplicación.

## 9. Tamaño y overhead

Overhead mínimo de un `ENVELOPE` sin atestaciones:

| Parte | Bytes |
|---|---|
| Prefijo | 4 |
| `src` + `dst` + `nonce` + `ts` + `exp` | 32 + 32 + 16 + 8 + 8 = 96 |
| TLV obligatorios (`payload_type`, `max_hops`, encabezado de `payload`) | ≈ 8 |
| `origin_sig` | 64 |
| Tránsito vacío | 2 |
| **Total** | **≈ 174 bytes** |

Cada atestación de relay suma aproximadamente `NodeId` + firma + metadatos ≈ 100+ bytes. Se cuantificará en Proof of Relay; es el precio de poder demostrar quién llevó la carta.

## 10. Diferencias con ICEBREAK

| ICEBREAK | CHAMULLO |
|---|---|
| JSON de texto | Binario: prefijo fijo + TLV |
| Campos de ruteo `from`, `mid`, `ttl` sin firmar | Sección de origen firmada; solo `hop_count` y las atestaciones son mutables |
| `mid` derivado del ID de radio | ID de mensaje = `H(src \|\| nonce)` |
| Versiones implícitas en cada formato (`DM2`, `SG2`, `D3`) | Versión de cable en el prefijo y features negociados |
| Fragmentación JSON de 30/60 KB | Fragmentación binaria por enlace, adaptada a la MTU |

## 11. Preguntas abiertas

1. **Compresión de encabezados:** en Bluetooth LE, 174 bytes por paquete pesan. ¿Conviene un modo compacto con IDs cortos negociados por enlace?
2. **Herramienta de depuración:** definir un volcado de texto canónico (por ejemplo, CBOR-diag o JSON) para logs y pruebas, sin que sea el formato de cable.
3. **Registro de extensiones:** ¿dónde vive y quién aprueba los tipos `1024`–`65535`? Lo resuelve la spec de Gobernanza.
4. **Vectores de prueba:** publicar paquetes de ejemplo con sus bytes exactos, para que implementaciones distintas verifiquen que codifican igual.
