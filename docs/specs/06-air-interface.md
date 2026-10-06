# CHAMULLO — Air Interface "El Grito" Specification v0.1

| Campo | Valor |
|---|---|
| Estado | Borrador |
| Fecha | 2026-10-05 |
| Depende de | Identity v0.1, Packet Format v0.1, Discovery & Routing v0.1 |
| Consumida por | Simulator, implementaciones Android |

Las palabras **DEBE**, **NO DEBE**, **DEBERÍA** y **PUEDE** se usan con el sentido de RFC 2119.

---

## 1. Intuición

El Capitán pidió un modo de conexión **propio**, sin torres, sin internet y sin Wi-Fi, que organice la comunicación entre celulares de forma similar a 3GPP.

| Metáfora | Mecanismo |
|---|---|
| **El grito.** Nadie "se conecta" con nadie: cada celular grita sus cartas al aire y todos los que están cerca las escuchan. | Paquetes CHAMULLO dentro de *extended advertising* no conectable (§3) |
| **Hablar lento y claro para que se oiga lejos.** Es la filosofía de LoRa, sin el aparatito. | LE Coded PHY con codificación S=8 (§3.1) |
| **El turno para hablar.** Si todos gritan a la vez no se entiende nada. | Acceso al medio con escucha previa y reserva de turnos, inspirado en el *sidelink* de 3GPP (§5) |
| **Dormir con un ojo abierto.** | Ciclos de escucha y sueño, como el DRX de 3GPP (§7) |
| **Un grito, muchos oídos.** | Un solo envío llega a todos los vecinos a la vez (§6.3) |

## 2. Principios y límites

1. **Radio de fábrica, protocolo propio.** CHAMULLO usa la radio LE de 2,4 GHz que el teléfono ya trae, **en crudo**: sin emparejar, sin conexiones GATT y sin perfiles Bluetooth. Todo lo que va arriba de la radio es protocolo CHAMULLO.
2. **Legal por diseño.** 2,4 GHz es banda ISM de uso libre. Se usa la radio homologada del teléfono, con la potencia que el sistema operativo permite. No se modifica firmware ni módem (Documento Maestro §19).
3. **Física honesta.** El software no puede cambiar la frecuencia, la antena ni el chip de la radio. El alcance lo deciden el modo de modulación y el entorno (§3.2).
4. **El celular solo alcanza.** Ningún aparato extra es obligatorio.

## 3. Capa física (L1)

### 3.1 Modos

| Modo | Uso | Velocidad bruta |
|---|---|---|
| **Largo alcance:** LE Coded PHY, S=8, en canal primario y secundario | Por defecto, si el teléfono lo soporta | 125 kbit/s |
| **Normal:** LE 1M PHY | Respaldo para teléfonos sin Coded PHY | 1 Mbit/s |

- Al iniciar, el nodo DEBE consultar `isLeCodedPhySupported()` e `isLeExtendedAdvertisingSupported()` (Android 8.0, API 26).
- Un nodo con Coded PHY DEBERÍA escuchar también en 1M para no aislar a los teléfonos viejos.

### 3.2 Alcance esperado

- Coded PHY mejora la sensibilidad en **4 a 6 dB**, y eso equivale a **1,5 a 2 veces** el alcance del modo normal, según la documentación de Silicon Labs.
- Entre teléfonos, al aire libre: **del orden de cientos de metros**. Dentro de edificios, bastante menos.
- Los valores reales se miden en campo (§10) y alimentan al simulador.

## 4. El grito: la trama de radio

Cada grito es un *extended advertising* **no conectable y no escaneable**. Sus datos llevan un único campo *Service Data* con el UUID de 128 bits de CHAMULLO y, adentro, la trama:

```text
GRITO =
  chamullo_uuid   bytes[16]   identifica el grito como CHAMULLO
  l2_header       ver §4.1
  l2_payload      un paquete CHAMULLO (Packet Format), o un trozo de uno
```

### 4.1 Encabezado de enlace (L2)

| Campo | Tamaño | Uso |
|---|---|---|
| `l2_ver` | 1 B | Versión del encabezado de enlace |
| `sender` | 2 B | **ID corto** del emisor en el vecindario (§6.1) |
| `seq` | 2 B | Número de secuencia del emisor, para ACK y duplicados |
| `receivers` | 1 B + 2 B × n | Lista de IDs cortos destinatarios; vacía = todos los vecinos |
| `reserve` | 2 B | Turno que el emisor reserva para su próximo grito (§5.2) |
| `acks` | 2 B + mapa de bits | ACK de gritos recibidos de un vecino (§6.2) |
| `seg` | 0 o 3 B | Índice y total si es un trozo |

### 4.2 Tamaño

- Android admite hasta **1650 bytes** de datos en *extended advertising* no conectable. Se encadenan varios paquetes de radio (`AUX_CHAIN`).
- A 125 kbit/s, 1650 bytes ocupan el aire más de 100 ms y aumentan las colisiones. El grito DEBERÍA limitarse a **≈ 250 bytes por paquete de radio**. Lo que no entra se trocea (§6.2).

### 4.3 Grito universal y comodines (v0.1.8)

La primera prueba de campo mostró que no todos los teléfonos pueden enviar *extended advertising*. Por eso toda trama viaja también en **micros** dentro de anuncios clásicos de 31 bytes, que cualquier teléfono envía y escucha (Manufacturer Specific Data, compañía `0xFFFF`):

```text
MICRO = MARK(0xC5) | id(2) | índice(1) | n(1) | g(1) | pedazo(16)
```

- La trama, precedida por su largo de 2 bytes, se corta en `n` pedazos de datos.
- Se agregan `g = ⌈n/4⌉` **comodines** de paridad: el comodín `j` es el XOR de todos los pedazos `i` con `i mod g = j`.
- El receptor reconstruye **un pedazo perdido por grupo**. La radio ya descarta por CRC los paquetes dañados, así que lo que hay que reparar son **pérdidas**, no bits cambiados.
- Costo: ≈ 25 % más de aire. Las cartas importantes, además, se repiten hasta recibir confirmación (mensajes de la plaza y tarjetas).

## 5. Acceso al medio (MAC): los turnos, estilo *sidelink* 3GPP

El *sidelink* de 3GPP en modo autónomo resuelve el mismo problema que CHAMULLO: equipos sin torre que comparten el aire. CHAMULLO toma su lógica y la adapta a los tiempos gruesos que permite una app.

### 5.1 Supertrama

- El tiempo se divide en **supertramas** de 1 s con **10 turnos** de 100 ms.
- 100 ms es la granularidad mínima realista: el intervalo de anuncio más corto que una app pide en Android es de 100 ms, y el controlador agrega un retardo aleatorio de hasta 10 ms.

### 5.2 Escuchar, elegir y reservar

Inspirado en la selección de recursos por sensado del modo 2 del *sidelink*:

1. **Escuchar:** el nodo escucha al menos una supertrama completa y anota qué turnos están ocupados según el campo `reserve` de los gritos de sus vecinos.
2. **Elegir:** elige al azar un turno **libre**.
3. **Reservar:** en cada grito anuncia en `reserve` el turno que va a usar en la próxima supertrama.
4. **Liberar:** si deja de tener tráfico, deja de anunciar la reserva.
5. **Colisión:** si detecta otro nodo con su mismo turno, el de `NodeId` mayor vuelve al paso 2.

### 5.3 Sincronización: el latido

- Los nodos se alinean con el **latido** de un vecino de referencia: el de `NodeId` menor entre los que escucha, como la elección de fuente de sincronización del *sidelink*.
- Cada grito lleva implícita su posición en la supertrama. Un nodo que escucha un latido ajusta su reloj de turnos.
- Cuando dos grupos con latidos distintos se encuentran, gana el de referencia con `NodeId` menor. El cambio se propaga en pocas supertramas.

## 6. Enlace (RLC/PDCP), estilo 3GPP

### 6.1 IDs cortos

- Después del handshake (Identity §7, transportado en gritos), los dos vecinos acuerdan un **ID corto de 2 bytes** para el enlace, en lugar de los 32 bytes del `NodeId`.
- Es la compresión de encabezados que dejó pendiente Packet Format §11.1.

### 6.2 Troceo y confirmación

- Los paquetes CHAMULLO más grandes que un grito se trocean (`seg`), como la capa RLC de 3GPP.
- Confirmación **sin conexión:** cada nodo incluye en sus gritos un mapa de bits `acks` con los `seq` recibidos de cada vecino. El emisor repite solo los trozos sin confirmar.
- Un trozo sin confirmar después de 3 supertramas se reintenta; tras 5 intentos, el enlace se considera roto para ese paquete.

### 6.3 Un grito, muchos oídos

- El aire es compartido: un grito lo escuchan **todos** los vecinos en alcance.
- Por eso, el *fanout* del río (Discovery & Routing §6.2) **no** cuesta k gritos. Cuesta **uno** con k destinatarios en `receivers`.
- El eco local del barrio se hace con un grito con `receivers` vacío.

## 7. Batería: dormir con un ojo abierto (DRX)

| Estado | Escucha | Grita | Cuándo |
|---|---|---|---|
| **Activo** | Continua | En su turno | Hay cartas en cola o tráfico reciente |
| **Atento** | 1 de cada 4 supertramas | Solo latido | Sin tráfico durante 30 s |
| **Dormido** | 1 de cada 16 supertramas | Latido cada 16 s | Batería baja o sin vecinos |

- Al recibir un grito dirigido a él, el nodo pasa a **Activo**.
- Una carta para un nodo en **Atento** o **Dormido** espera en el emisor hasta su ventana de escucha.

## 8. Capacidad esperada

| Supuesto | Valor |
|---|---|
| Velocidad bruta Coded S=8 | 125 kbit/s |
| Fracción útil tras encabezados, turnos y repeticiones | ≈ 20–30 % |
| **Capacidad útil compartida por vecindario** | **≈ 25–40 kbit/s** |

Alcanza para mensajes de texto, recibos, tarjetas y fotos chicas. **No** alcanza para voz en tiempo real ni video. Las cifras se ajustan con mediciones (§10).

## 9. Plataformas

| Plataforma | Estado |
|---|---|
| **Android 8+** con Coded PHY y *extended advertising* | Participante completo |
| **Android 8+** sin Coded PHY | Participante en modo normal (1M), con menor alcance |
| **iOS** | **Limitado.** CoreBluetooth no permite a las apps elegir el PHY de anuncio ni gritar datos libres en segundo plano. Queda como pregunta abierta (§11). |

## 10. Plan de medición en campo

Antes de fijar parámetros, se mide con teléfonos reales:

1. Alcance Coded S=8 vs 1M entre pares de teléfonos, en campo abierto, en la calle y dentro de casas.
2. Tasa de pérdida según la distancia.
3. Fluctuación real del intervalo de anuncio, para validar los turnos de 100 ms.
4. Consumo de batería en cada estado de DRX.
5. Colisiones con 5, 20 y 50 teléfonos en el mismo lugar.

Los resultados reemplazan los supuestos del simulador.

## 11. Preguntas abiertas

1. **iOS:** ¿participación limitada, con el iPhone solo escuchando o como hoja del río, o un rol distinto?
2. **Wi-Fi Aware/NAN como segundo grito:** el Capitán descartó Wi-Fi Direct. Wi-Fi Aware es otra radio sin infraestructura, con más velocidad y alcance similar. ¿Se evalúa como modo rápido opcional?
3. **Turnos más finos:** con una supertrama de 1 s, un salto cuesta de media unos 500 ms de espera. ¿Hay forma confiable de bajar la granularidad?
4. **Coexistencia:** los gritos comparten 2,4 GHz con Bluetooth y Wi-Fi de todo el entorno. Hay que medir el impacto en ciudades densas.
5. **UUID:** registrar un UUID de servicio de 16 bits ante el Bluetooth SIG ahorraría 14 bytes por grito.
