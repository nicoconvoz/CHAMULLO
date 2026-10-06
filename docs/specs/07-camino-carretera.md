# CHAMULLO — Camino y Carretera v0.2

| Campo | Valor |
|---|---|
| Estado | Borrador, implementado en la app 0.2.0 |
| Fecha | 2026-10-06 (islas con brújula §6.1, llamadas §7) |
| Depende de | Air Interface v0.1, Discovery & Routing v0.1 |

Diseño del Capitán del proyecto.

## 1. Idea

| Metáfora | Mecanismo |
|---|---|
| **Camino** | El grito (Air Interface): descubrimiento, latidos y mensajes chicos. Anda en cualquier teléfono, sin conectarse. ≈ 50 bytes/s. |
| **Carretera** | Wi-Fi Direct de teléfono a teléfono, sin router ni internet. Pasa todo lo pesado. Megabits por segundo. |
| **El camino abre la carretera** | Cuando un vecino es **estable** (se escucharon al menos 2 latidos), recibe por el camino la **llave de mi carretera**, sellada para él. |
| **Cadena viva** | Cada teléfono es **dueño** de su carretera y, a la vez, **pasajero** de la de un vecino. Las carreteras se encadenan: A ← B ← C. |

## 2. La cadena viva

- Cada teléfono crea un grupo Wi-Fi Direct con nombre y clave fijos (`DIRECT-CH-<id>`, clave derivada de su semilla). Un vecino aprueba entrar una sola vez.
- Ese grupo acepta clientes Wi-Fi comunes. Un teléfono puede ser **dueño de su grupo y cliente de otro a la vez**: es la concurrencia "Wi-Fi Direct + cliente" que la mayoría de los teléfonos soporta.
- En Android 12+, con chips que lo permiten (*STA/STA concurrency*), un teléfono puede ser cliente de dos redes locales a la vez y hacer de cruce.
- Sobre cada carretera corre un caño TCP (puerto 47474). Las tramas CHAMULLO viajan **enteras**, hasta 60 KB, sin micros.

## 3. La llave (LINK tipo 14 `ROAD_INVITE`)

```text
ROAD_INVITE = { from, to, from_box, nonce, box( ssid | clave | ts | firma_from("ROAD", ssid | 0x00 | clave | ts | to) ) }
```

- Sellada con `box` para un solo vecino: nadie más en el aire ve la clave.
- Firmada por el dueño de la carretera.
- Se reenvía a cada vecino estable como mucho cada 5 minutos.

## 4. Reparto del tráfico

- Toda trama sale por todas las antenas activas. El receptor descarta duplicados.
- Por el camino (grito Bluetooth o Wi-Fi Aware) solo pasan tramas de hasta 4 KB. Lo más grande va únicamente por la carretera.

## 5. Límites conocidos

- Requiere **Android 10+** (grupos con nombre y clave elegidos) y el Wi-Fi prendido, sin red.
- La primera vez que un teléfono se sube a la carretera de otro, Android puede pedir confirmación.
- Un solo chip de radio: si dos carreteras quedan en canales distintos, se reparten el tiempo.
- Todo esto falta medirlo en campo. La app trae una **prueba de velocidad** de 4 MB en Diagnóstico.

## 6. Islas (v0.2)

Diseño del Capitán después de las pruebas de campo: **Wi-Fi Direct traza también los caminos.** El Bluetooth queda
como respaldo para teléfonos sin Wi-Fi Direct.

| Pieza | Regla |
|---|---|
| Isla | Un grupo Wi-Fi Direct: un anfitrión y hasta 7 miembros. Dentro de la isla todo está a un salto, por el anfitrión |
| Cartel | Registro de servicio Wi-Fi Direct (DNS-SD, `_chamullo._tcp`) con `i` id, `n` nombre, `l` isla, `h` anfitrión, y si es anfitrión `s` red, `p` clave, `r` miembros (8 hex c/u). Cabe en ~255 bytes |
| Sumarse | Sin isla: unirse a la isla con lugar con más miembros; si no hay, fundar una |
| Fusión | Un anfitrión solo se une a una isla vecina más grande, o a otra sola de id menor |
| Ferry | Turnos de 60 s sobre la lista ordenada de miembros: el de turno va a una isla vecina, entrega y vuelve. La isla vecina rota con el turno (§6.1) |
| Lugar del ferry | El anfitrión deja libre `FERRY_SEATS = 1` lugar: un recién llegado se suma solo si quedan dos o más; el último es para ferrys |
| Puente | Un miembro que ve gente de otra isla pero no a su anfitrión funda una isla chica en el borde y no la abandona mientras vea a esos vecinos |
| Anfitrión = aire | El anfitrión repite **toda** trama de un miembro a los demás (latidos, ofertas y sobres). Cartero, solo si lo eligen |
| Puente fijo | Miembro de su isla y cliente Wi-Fi común de la otra a la vez (concurrencia), con sockets atados a cada red (§6.2) |

Implementación de referencia de las decisiones: `android/core/.../Islands.kt` (`Islands.decide`), con pruebas en
`IslandsTest.kt`.

### 6.1 Islas con brújula (Discovery & Routing v0.2 §10.6)

- El cartel del anfitrión lleva `c`, la celda de la isla (Discovery & Routing §10.1), para que el ferry sepa hacia dónde va. La celda solo la ven los que están al alcance del Wi-Fi Direct, igual que con un latido.
- El ferry del turno elige la isla vecina con lugar de índice `turno mod cantidad`, en orden de id.
- Embarque: anuncia ese rumbo en su latido y espera `FERRY_BOARDING_MS = 3 s`; los miembros le dan las cartas que acercan en esa dirección. Mientras viaja, lleva y no reparte.
- Al llegar: latido con su celda real, la isla le responde con los suyos (`BEACON_REPLY_MS`) y reparte con la brújula. A los 20 s vuelve a casa con lo que no pudo dejar.
- En la app: `Locator` le da la ubicación al nodo cada 15 s o 20 m (GPS o red); `WifiIslands` cuelga la celda en el cartel del anfitrión, le pasa al `decide` la celda y la carta trabada, y avisa el rumbo al nodo al embarcar y al llegar.

### 6.2 El puente fijo: un pie en cada isla

- **Quién:** un miembro (nunca un anfitrión) cuyo celular puede tener dos conexiones Wi-Fi a la vez y que ve al anfitrión de una isla vecina con lugar. En Android 10+ la segunda conexión es una red Wi-Fi común solo local, pedida con `WifiNetworkSpecifier`. El sistema le pregunta al dueño una vez.
- **Uno por par de islas:** el puente cuelga `b` (la isla a la que llega) en su cartel. Nadie más de su isla arma otro puente a esa misma isla. Si dos arrancan a la vez, el de id mayor suelta (`Unbridge`).
- **Qué hace:** el anfitrión de la otra isla lo cuenta como miembro (ocupa un lugar). Sus tramas salen por los dos caños y oye a las dos islas, así que la brújula ve a los vecinos de ambas. Un puente no hace de ferry.
- **Se cae:** si deja de ver a ese anfitrión, si deja su isla o si Android pierde la red.
- **En el gemelo:** la mitad de los celulares puede hacerlo (depende del modelo). Dos pueblos que se tocan por el borde cruzan una carta en menos de un minuto, sin ferry.

### 6.3 Buscar islas en un celular de verdad (lecciones de campo, 0.5.5)

- **Un paso por vez:** Wi-Fi Direct atiende un pedido a la vez. La búsqueda encadena "limpiar búsquedas", "agregar búsqueda de carteles", "buscar celulares" y "buscar carteles", y cada paso espera la respuesta del anterior. En la 0.5.4 iban todos juntos y cada 15 s, y la búsqueda (que tarda cerca de un minuto) contestaba `BUSY` (código 2). En el campo, cada celular fundaba su isla y nunca veía al otro.
- **Ocupado:** si contesta `BUSY`, se corta la búsqueda en curso y se reintenta a los 3, 6, 9 y 12 s. La búsqueda completa se relanza cada 60 s, y también después de fundar una isla o sumarse a una, porque muchos celulares la cortan al formar un grupo.
- **Mirar antes de fundar:** un celular sin isla espera de 30 a 60 s (según su id) antes de fundar la suya. Así uno funda y el otro, que todavía está mirando, se suma. La regla de `Islands.decide` no cambia: la espera la pone la app.

### 6.4 Islas en la lista de Wi-Fi (0.5.6)

En el campo, la búsqueda de carteles (DNS-SD) no funcionó entre dos celulares: "Carteles vistos: 0", y a veces "código 0" (error interno de Android). Por eso hay una segunda vía, que no depende de esa búsqueda:

- **Toda isla es también una red Wi-Fi común** llamada `DIRECT-CH-` más seis dígitos hex del id de su anfitrión. Cualquier celular la ve en su lista de redes.
- **La clave sale del nombre:** `clave = hex(H("CHAMULLO/1/ISLAND" 0x00 ssid))[0..20]`. Cualquier CHAMULLO puede sumarse sin cartel. No hace falta ocultarla: la isla es solo el aire, las cartas van selladas y firmadas, y el caño con el anfitrión tiene su propio saludo.
- **Una isla de la lista cuenta como el cartel de un anfitrión**, con su lista de miembros vacía. `Islands.decide` aplica la misma regla: dos anfitriones solos que se ven en la lista se juntan, y se suma el de id mayor.
- **Las islas se nombran con seis dígitos hex en todos lados** (cartel, lista de Wi-Fi, estado), así las dos vías hablan de la misma isla. Si hay cartel de esa isla, gana el cartel, que trae más datos.
- La app pide escanear cada minuto, aunque Android puede frenarla, y además lee los escaneos que hace el propio sistema.
- **Diagnóstico** muestra las dos vías por separado: "Carteles vistos" e "islas en la lista de Wi-Fi".

### 6.5 La causa de fondo: la ubicación con la app de fondo (0.5.7)

Con la depuración USB del Moto E7 Plus del Capitán (Android 10), Android lo dijo claro: `getScanResults not allowed … has no location permission`. La ubicación estaba dada "solo mientras se usa la app". Con la app en pantalla las islas se abrían al instante (al entrar a la Plaza), pero de fondo Android cortaba la ubicación, y con ella la búsqueda de Wi-Fi Direct y la lista de redes. De ahí venían "Carteles vistos: 0" y los códigos 0 y 2.

- **Arreglo:** el servicio declara el tipo `location` además de `connectedDevice` (manifiesto, `FOREGROUND_SERVICE_LOCATION` y `startForeground`). Así conserva la ubicación "mientras se usa" estando de fondo.
- **Comprobado en el celular:** de fondo, sin rechazos de permiso, con la búsqueda corriendo (`mDiscoveryStarted true`) y la isla en pie.

### 6.6 En la calle: la red propia necesita el Wi-Fi prendido (0.8.1)

Una isla **es** la red propia de CHAMULLO: un grupo Wi-Fi Direct (`DIRECT-CH-xxxxxx`) que arma un celular, sin router ni Internet. No hace falta estar conectado a ningún Wi-Fi, pero sí tener **el Wi-Fi prendido**. En la calle mucha gente lo apaga, y Android 10+ no deja que una app lo prenda sola.

- La isla escucha el estado del Wi-Fi Direct (`WIFI_P2P_STATE_CHANGED`, que además dice el estado actual al arrancar).
- **Se apaga:** cierra los caños y el puente fijo, se olvida de la isla y de los carteles, y no busca ni decide. Avisa con una notificación que abre el interruptor del sistema, y la app muestra arriba de todo una tarjeta roja con el botón **"Prender Wi-Fi"**: en Android 10+, el panel de Wi-Fi sobre la app, en un toque.
- **Vuelve:** cuelga el cartel y busca islas en el acto, sin esperar el minuto de la búsqueda.

### 6.7 El que ve, se mueve (prueba de campo 0.8.1)

**Lo que pasó:** dos celulares, uno conectado a la red de la casa y otro con el Wi-Fi prendido sin red. Cada uno fundó su isla. El de la casa (`6e6375`) no veía a nadie; el otro (`48ab23`) veía al primero. La regla "entre dos islas solas se muda el de id más grande" le pedía moverse al que no veía, y el que veía se quedaba esperando: un abrazo de oso.

| Pieza | Regla |
|---|---|
| El cartel dice qué ve | Un anfitrión cuelga en `v` las islas que ve (hasta 3). Sin `v`: no se sabe (isla de la lista de Wi-Fi, cartel viejo) |
| Desde cuándo | Cada celular anota desde cuándo ve cada isla. Un cartel puede parpadear: se olvida recién después de 3 min sin verlo |
| El que ve, se mueve | Si la otra isla sola **no me ve** según su cartel, después de `ASYM_WAIT_MS = 90 s` me mudo yo, aunque mi id sea más chico. Los 90 s dejan que los dos carteles se pongan al día, así no se mudan los dos a la vez |
| Sin cartel | Si solo la veo en la lista de Wi-Fi y en `SCAN_WAIT_MS = 120 s` no vino, voy yo |
| Si los dos se ven | Sigue la regla de siempre: se muda solo el de id más grande |

## 7. Llamadas y videollamadas en la isla (0.8.0)

Una llamada va **en vivo**: no puede esperar a que una carta salte de celular en celular. Por eso anda **solo dentro de la isla** (o por un puente fijo), donde todo está a uno o dos saltos por Wi-Fi Direct.

| Pieza | Regla |
|---|---|
| Con quién | Solo con contactos. Todo pedazo va sellado con las claves de caja de las dos tarjetas: llamar es probar que tengo mi tarjeta y la suya |
| Por dónde | LINK 19 `CALL` por los caños de la isla, nunca por el Bluetooth. El anfitrión pasa cada pedazo solo al miembro al que va y no puede escuchar |
| Sonar | `RING` (dice si es video) cada 2 s hasta que vuelve `RINGING`. Sin `RINGING` en 12 s: "no está en tu isla". Sonando 45 s sin atender: "no contesta" y llamada perdida del otro lado |
| Atender | `ANSWER`, `REJECT`, `BUSY` (ya estoy en otra), `HANGUP` |
| Voz | `AUDIO`: 20 ms de PCM 16 kHz mono de 16 bits (640 B, 256 kbit/s), sin códec, con el cancelador de eco y el supresor de ruido del celular. Del otro lado, a lo sumo 160 ms en cola: si se junta más, se tira lo viejo |
| Video | `VIDEO`: H.264 640×480, 15 cuadros por segundo, 800 kbit/s, con el codificador y el decodificador del propio celular. Cada pedazo lleva banderas (configuración, cuadro clave, cámara apagada, "mandame un cuadro clave") y el giro de la cámara. La configuración viaja antes de cada cuadro clave |
| Vida | `PING` cada 2 s. Sin voz ni señales en 15 s: "se cortó" |

- La pantalla es oscura como la de ICEBREAK: atender o rechazar; durante la llamada, micrófono, altavoz, cámara, girar y colgar. Suena aunque el celular esté bloqueado.
- Las reglas viven en el núcleo (`CallSession`, `CallMsg` en `Call.kt`, con pruebas en `CallTest.kt`); la voz y el video, en la app (`AudioEngine`, `VideoEngine`).
- Límites conocidos: no salta entre islas por el ferry (tarda demasiado); falta medir en campo la calidad con dos celulares.
