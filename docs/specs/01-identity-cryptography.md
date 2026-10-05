# CHAMULLO — Identity & Cryptography Specification v0.1

| Campo | Valor |
|---|---|
| Estado | Borrador |
| Fecha | 2026-10-05 |
| Depende de | Documento Maestro v0.1 |
| Consumida por | Packet Format, Discovery & Routing, Proof of Relay / Proof of Delivery, Reward & Anti-Fraud |

Las palabras **DEBE**, **NO DEBE**, **DEBERÍA** y **PUEDE** se usan con el sentido de RFC 2119.

---

## 1. Propósito y alcance

Esta especificación define **quién es un nodo** en CHAMULLO y **cómo lo demuestra**:

- primitivas criptográficas;
- formato del identificador de nodo;
- generación, almacenamiento y recuperación de claves;
- firmas con separación de dominio;
- autenticación mutua de enlaces (handshake);
- protección contra replay;
- cifrado de extremo a extremo;
- rotación y revocación de claves.

**Fuera de alcance:** formato binario de paquetes, formato de las pruebas de retransmisión y entrega, resistencia a Sybil y economía. Estas piezas usan la identidad definida aquí, pero se especifican en sus propios documentos.

## 2. Principios

1. **La identidad es la clave.** Un nodo es su par de claves Ed25519. No existe registro central, autoridad emisora ni cadena de certificados hacia un fundador.
2. **El ID de ruteo es la identidad.** El identificador con el que un nodo aparece en la red es su clave pública. No hay alias de transporte (`ib-xxxx`, `net:...`) separados de la identidad.
3. **Todo lo que importa va firmado.** Ninguna afirmación con efecto en el ruteo o en las recompensas se acepta sin firma verificable.
4. **Reutilizar lo probado.** Las primitivas y formatos se toman de ICEBREAK cuando sirven, para que ICEBREAK pueda usar CHAMULLO como transporte sin migrar criptografía.
5. **Crear una identidad es gratis.** Esto es intencional. La resistencia a identidades múltiples (Sybil) **no** se resuelve en la capa de identidad, sino en la de recompensas (ver §11).

## 3. Primitivas criptográficas

| Uso | Primitiva | Referencia | Origen |
|---|---|---|---|
| Firma | Ed25519 (firma *detached*, 64 bytes) | RFC 8032 | ICEBREAK |
| Acuerdo de claves | X25519 derivado de las claves Ed25519 (conversión birracional) | RFC 7748 | ICEBREAK |
| Cifrado autenticado asimétrico | NaCl `box` (X25519 + XSalsa20-Poly1305), nonce aleatorio de 24 bytes | NaCl / libsodium | ICEBREAK |
| Cifrado autenticado simétrico | NaCl `secretbox` (XSalsa20-Poly1305) | NaCl / libsodium | ICEBREAK |
| Hash | `H(x)` = SHA-512(x) truncado a 32 bytes | FIPS 180-4 | ICEBREAK |
| Aleatoriedad | CSPRNG del sistema operativo | — | — |

Las implementaciones DEBEN ser compatibles byte a byte con tweetnacl/libsodium. La implementación de referencia de ICEBREAK usa `pinenacl` en Dart.

## 4. Identificador de nodo (NodeId)

- `NodeId` = clave pública Ed25519 del nodo, **32 bytes**.
- **Forma textual canónica:** 64 caracteres hexadecimales en minúscula. Es idéntica al ID de miembro de ICEBREAK.
- **Forma corta, solo para mostrar:** los primeros 8 caracteres hex. **NO DEBE** usarse para ruteo, firmas ni comparación de identidad.
- Un receptor DEBE rechazar un `NodeId` que no sea un punto válido de Ed25519.

## 5. Claves: generación, almacenamiento y recuperación

### 5.1 Generación

Un nodo nuevo genera su clave de una de estas dos formas:

- **Desde una frase de recuperación** (§5.3). Es el modo recomendado.
- **Aleatoria con el CSPRNG.** Es el modo para nodos dedicados o efímeros que no necesitan recuperación.

### 5.2 Almacenamiento

- La clave secreta DEBE guardarse en el almacén seguro de la plataforma cuando exista (Android Keystore, iOS Keychain, TPM o DPAPI).
- La clave secreta NUNCA DEBE transmitirse por la red ni incluirse en logs.

### 5.3 Frase de recuperación

CHAMULLO reutiliza el esquema de ICEBREAK sin cambios en la parte de palabras:

- **Formato:** 16 palabras de una lista de 256 palabras en castellano. Son 15 bytes de entropía más 1 byte de checksum, igual a `SHA-512(entropía)[0]`. La lista y la normalización (minúsculas, sin acentos) son las de `icebreak_core/lib/src/domain/recovery.dart`.
- **Derivación de la semilla con etiqueta propia de CHAMULLO:**

  ```text
  seed = SHA-512( UTF8("chamullo-recovery-v1 " || palabras_normalizadas_separadas_por_espacio) )[0..32]
  NodeKey = Ed25519.fromSeed(seed)
  ```

**Decisión: etiqueta propia y no `icebreak-recovery-v1`.** La misma frase genera **claves distintas** en ICEBREAK y en CHAMULLO. Así el nodo que retransmite tráfico no queda vinculado públicamente con la identidad social de la persona. Si alguien quiere unirlas a propósito, puede importar su clave de ICEBREAK como clave de nodo (§5.4).

- **Backup cifrado de datos locales:** igual que en ICEBREAK, con `secretbox` y una clave derivada de la frase con la etiqueta `chamullo-backup-v1`. El formato es `CB1 <nonce b64> <ciphertext b64>`.

### 5.4 Importar una identidad de ICEBREAK

Un nodo PUEDE usar directamente la clave Ed25519 de un miembro de ICEBREAK como `NodeKey`. Es seguro porque todas las firmas de CHAMULLO llevan separación de dominio (§6): una firma de CHAMULLO no es válida como mensaje de ICEBREAK, y al revés tampoco.

## 6. Firmas con separación de dominio

Toda firma de CHAMULLO se calcula sobre:

```text
signing_input = "CHAMULLO/1/" || tag || 0x00 || body
sig           = Ed25519.sign(NodeKey, signing_input)
```

- `tag` es un identificador ASCII del tipo de mensaje (`HS`, `ENV`, `HOP`, `ACPT`, `CONF`, `ROT`, `REV`, ...). Cada spec que defina un mensaje firmado DEBE registrar su `tag` en la tabla del §12.
- `body` es la **codificación canónica** del mensaje. La define la *Packet Format Specification* y DEBE ser determinista: dos implementaciones que serialicen el mismo mensaje producen exactamente los mismos bytes.
- Un verificador DEBE reconstruir `signing_input` por su cuenta y NO DEBE aceptar firmas sobre bytes enviados "ya armados" por el emisor.

> **Diferencia con ICEBREAK.** ICEBREAK firma texto UTF-8 separado por `|` sin prefijo de dominio. CHAMULLO agrega el prefijo `CHAMULLO/1/` para que una misma clave pueda vivir en ambos protocolos sin que una firma de uno sirva en el otro.

## 7. Autenticación de enlaces (handshake)

En ICEBREAK los enlaces se aceptan sin probar identidad y la confianza llega después, en los payloads. Eso no alcanza para demostrar retransmisiones: si no sé con certeza quién es mi vecino, no puedo firmar que le entregué tráfico.

Por eso, todo enlace CHAMULLO, sea de cualquier transporte, DEBE completar este handshake antes de intercambiar tráfico:

```text
A → B : HELLO     { id_A, n_A, caps_A }
B → A : CHALLENGE { id_B, n_B, caps_B, sig_B( "HS", "R" | id_A | id_B | n_A | n_B | caps_A | caps_B | cb ) }
A → B : CONFIRM   { sig_A( "HS", "I" | id_A | id_B | n_A | n_B | caps_A | caps_B | cb ) }
```

- `n_A` y `n_B` son nonces aleatorios de 16 bytes, nuevos en cada handshake.
- `caps_X` son las versiones y *features* que anuncia cada lado (Packet Format §7). Van firmados por ambos para que nadie en el medio pueda borrarlos y forzar una versión más débil.
- `"I"` / `"R"` marcan el rol de quien firma (*initiator* / *responder*). Así una firma no se puede reflejar y hacerla pasar por la del otro rol.
- `cb` (*channel binding*) son bytes que identifican el canal de transporte concreto. Por ejemplo, el fingerprint DTLS de ambos extremos en WebRTC. Si el transporte no ofrece ninguno, `cb` es vacío y se registra el enlace como *unbound*.
- Si la verificación falla, el enlace DEBE cerrarse.
- El handshake autentica y **no** cifra el canal. La confidencialidad del contenido viaja de extremo a extremo (§9).

**Alternativa evaluada:** Noise XX, que autentica y además da cifrado por enlace con *forward secrecy*. Es más robusta, pero suma complejidad y una dependencia nueva. Para v0.1 alcanza con el desafío-respuesta; Noise queda como pregunta abierta (§13).

## 8. Protección contra replay

### 8.1 Mensajes de enlace (handshake y control entre vecinos)

- Los nonces del handshake no se reutilizan.
- Los mensajes de control llevan `ts` (milisegundos Unix) y se rechazan si `|ahora − ts| > 5 min`, la misma tolerancia de reloj que ICEBREAK.

### 8.2 Sobres de extremo a extremo (tráfico multi-hop)

El tráfico multi-hop puede quedar demorado en nodos intermedios (*store-and-forward*), así que una ventana fija de 5 minutos no sirve. Cada sobre DEBE llevar, firmado por el origen:

| Campo | Tamaño | Uso |
|---|---|---|
| `src` | 32 B | Clave pública del origen; en tráfico privado es una clave **efímera** por mensaje (Discovery & Routing §7.1) |
| `nonce` | 16 B | aleatorio, único por mensaje |
| `ts` | 8 B | hora de creación (ms) |
| `exp` | 8 B | hora de expiración (ms), con `exp − ts ≤ 24 h` |

- Un nodo DEBE rechazar sobres con `ahora > exp` o con `ts > ahora + 5 min`.
- El identificador del mensaje es `H(src || nonce)`. Cada nodo DEBE mantener un caché de IDs vistos hasta su `exp`. Si llega un duplicado, lo descarta y no lo retransmite.

### 8.3 Declaraciones con estado (rotación, revocación, configuración)

Siguen el patrón `A2` de ICEBREAK: un `seq` por firmante que **solo avanza**. Una declaración con `seq` menor o igual al último aceptado de ese firmante se descarta.

## 9. Cifrado de extremo a extremo

- El contenido dirigido a un destinatario se cifra con `box(src_sk, dst_pk)`, igual que `Identity.seal` de ICEBREAK. Como es autenticado, el destinatario sabe además quién lo cifró.
- Los nodos intermedios ven solo los metadatos de ruteo, nunca el contenido.
- **Limitación conocida:** `box` con claves estáticas **no** tiene *forward secrecy*. Si una clave se compromete, el tráfico viejo capturado se puede descifrar. Ver §13.

## 10. Rotación y revocación

ICEBREAK no tiene ninguna de las dos para miembros. CHAMULLO las agrega.

### 10.1 Rotación

Un nodo cambia a una clave nueva publicando:

```text
ROT { old_id, new_id, seq, ts, sig_old, sig_new }
sig_old = sig_old_key( "ROT", old_id | new_id | seq | ts )
sig_new = sig_new_key( "ROT", old_id | new_id | seq | ts )
```

- La doble firma prueba que el nodo controla **ambas** claves.
- Quien acepta un `ROT` DEBE trasladar al `new_id` el historial asociado al `old_id`, como la reputación y las contribuciones registradas.

### 10.2 Revocación

```text
REV { id, seq, ts, reason, sig }   sig = sig_id( "REV", id | seq | ts | reason )
```

- Un `REV` válido es **definitivo**: toda firma posterior de esa clave se rechaza.
- `reason`: `compromised` | `retired`.

### 10.3 Limitación de v0.1

Como la clave de nodo sale directamente de la frase de recuperación, quien robe la clave tiene el mismo poder que el dueño y también podría emitir un `ROT`. Ante un compromiso, lo único seguro en v0.1 es **revocar y empezar con una identidad nueva**. Una jerarquía con raíz offline y claves de dispositivo resolvería esto; ver §13.

## 11. Relación con la resistencia a Sybil

La capa de identidad garantiza **autenticidad**: esta firma es de esta clave. **No** garantiza **unicidad**: esta clave es una persona o un dispositivo real. Crear 10.000 identidades cuesta casi nada.

Las defensas van en la *Reward & Anti-Fraud Specification*. Algunas candidatas:

- prueba de trabajo al crear la identidad (similar al `C2` de ICEBREAK);
- reputación que solo se gana con entregas firmadas por terceros;
- recompensas con maduración (*vesting*);
- detección de rutas artificiales entre identidades del mismo operador.

**Decisión: no** se hereda la cadena de certificados `G1`/`C1` de ICEBREAK. Haría que toda la red dependiera de la firma de un fundador, lo que contradice el principio de independencia del Documento Maestro.

## 12. Registro de etiquetas de firma

| Tag | Mensaje | Definido en |
|---|---|---|
| `HS` | Handshake de enlace | Esta spec, §7 |
| `ENV` | Sobre de extremo a extremo | Packet Format (campos mínimos en §8.2) |
| `ROT` | Rotación de clave | Esta spec, §10.1 |
| `REV` | Revocación de clave | Esta spec, §10.2 |
| `SEED` | Semilla inicial de la cadena de saltos (hash, no firma) | Proof of Relay §4.1 |
| `ACPT` | Aceptación y compromiso de firmar | Proof of Relay §5 |
| `HOP` | Registro de salto firmado por quien entrega | Proof of Relay §4.2 |
| `JRNY` | Hash del viaje sellado (hash, no firma) | Proof of Relay §6.2 |
| `CONF` | Confirmación del origen sobre el viaje | Proof of Relay §6.3 |
| `CARD` | Tarjeta de contacto | Discovery & Routing §4 |
| `TAG` | Etiqueta de destino (hash, no firma) | Discovery & Routing §7.2 |

## 13. Preguntas abiertas

1. **Forward secrecy:** ¿adoptar Noise XX en los enlaces y/o un *double ratchet* de extremo a extremo?
2. **Jerarquía de claves:** ¿raíz offline derivada de la frase más claves de dispositivo rotables, para que un `ROT` siga siendo seguro tras perder un dispositivo?
3. **Criptografía post-cuántica:** ¿reservar un byte de versión de algoritmo en el `NodeId` para migrar en el futuro?
4. **Frase de recuperación:** 120 bits de entropía son suficientes hoy. ¿Conviene subir a 24 palabras para nodos de infraestructura?

## 14. Matriz de reutilización de ICEBREAK

| Elemento de ICEBREAK | En CHAMULLO |
|---|---|
| Ed25519 + X25519 + NaCl box/secretbox (`crypto/identity.dart`) | Se reutiliza tal cual |
| ID = clave pública en hex de 64 caracteres | Se reutiliza; además pasa a ser el ID de ruteo |
| Hash SHA-512 truncado a 256 bits | Se reutiliza |
| Frase de 16 palabras (`domain/recovery.dart`) | Se reutilizan la lista y el formato; cambia la etiqueta de derivación |
| Patrón `seq` monotónico + ventana de validez (`A2`) | Se reutiliza para declaraciones con estado |
| Firmas sobre texto `\|` sin dominio | Se reemplaza por firmas con separación de dominio |
| IDs de ruteo `ib-xxxx` / `net:` / `qr:` | Se descartan; el ID de ruteo es el `NodeId` |
| Enlaces sin autenticar | Se reemplaza por el handshake obligatorio (§7) |
| `mid` y `ttl` sin firmar | Se reemplazan por un sobre firmado por el origen (§8.2) |
| Cadena de membresía `G1`/`C1`/`OD1` | No se toma (§11) |
| Sin rotación ni revocación de miembros | Se agregan `ROT` y `REV` (§10) |
