# CHAMULLO — Documentación técnica

Versión del documento: 10 · Estado del código: app 0.4.2 (octubre 2026)

> Este documento describe **lo que está construido y funcionando hoy**. El diseño completo de la red
> (identidad, sobre, recibos, economía, routing, interfaz de aire) está en [`docs/specs/`](specs/). Cuando el
> código todavía no implementa algo de una spec, se indica en [§13 Pendientes](#13-pendientes-conocidos).

---

## 1. Qué es CHAMULLO

Red de comunicación **de teléfono a teléfono**: sin torres celulares, sin internet y sin routers. Los mensajes
saltan de celular en celular hasta llegar. Cada teléfono es un nodo que envía, recibe y lleva cartas de otros.

| Hoy funciona | Cómo |
|---|---|
| Identidad propia | 16 palabras → clave Ed25519; sin registro ni servidor |
| Descubrir quién está cerca | **Camino**: el grito por Bluetooth (y Wi-Fi Aware si el teléfono lo tiene) |
| Mandar mucho y rápido | **Carretera**: Wi-Fi Direct de teléfono a teléfono, en cadena |
| Contactos | Intercambio de tarjetas selladas por el camino |
| Chat cifrado de punta a punta | Sobres con remitente efímero y etiqueta de destino; ✓✓ al llegar |
| Chat de prueba abierto | **La Plaza**: lo leen los cercanos y responden "lo escuché" |
| Recibos y caramelos | Cada cartero deja su huella cifrada; el destino confirma y el origen paga. Cobra solo quien puede probarlo |
| Actualizaciones | Página web con `version.txt`; la app avisa sola |

Plataforma: **Android 8+**. La carretera requiere **Android 10+**. iPhone no está soportado: iOS no permite
usar la radio como lo necesita el grito.

## 2. Inicio rápido

```bash
cd android
./gradlew :core:test
```

```bash
./gradlew :app:assembleRelease
```

```bash
bash scripts/publish-web.sh
```

1. `:core:test` corre las pruebas del núcleo (todo lo que no es radio ni pantallas).
2. `:app:assembleRelease` genera `android/app/build/outputs/apk/release/app-release.apk` (≈ 1,35 MB).
3. `publish-web.sh` prueba, compila y publica el APK y `version.txt` en la página de descarga.

Requisitos de la PC: JDK 17+, Android SDK (`local.properties` → `sdk.dir`), `GRADLE_USER_HOME` opcional.

## 3. Arquitectura

### 3.1 Principios

| Principio | Consecuencia en el código |
|---|---|
| El protocolo no depende del medio | `core` no sabe nada de radios; cada antena implementa la interfaz `Radio` |
| Liviano | Kotlin nativo, **sin AndroidX**, pantallas armadas en código, R8 en release |
| Compatible con ICEBREAK | Criptografía byte a byte igual a tweetnacl (vectores en las pruebas) |
| Todo lo verificable se prueba | La lógica vive en `core` y se prueba en la PC con JUnit; sin emuladores |
| Nada se pierde en silencio | Toda carta llega, queda en un bolsillo o se descarta con motivo registrado |

### 3.2 Estructura del repositorio

| Ruta | Contenido |
|---|---|
| `docs/CHAMULLO_Documento_Maestro_v0.1.docx` | Visión y concepto |
| `docs/specs/01…07` | Especificaciones del protocolo |
| `android/core` | Núcleo en Kotlin puro: cripto, identidad, formato, paquetes, nodo |
| `android/app` | App Android: radios, servicio, almacenamiento, pantallas |
| `sim/` | Simulador determinista y laboratorio visual (`lab.html`) |
| `scripts/publish-web.sh` | Publicación en la página de descarga |
| Repo aparte `chamullo-web` | GitHub Pages: `index.html`, APK y `version.txt` |

### 3.3 Capas

```text
Pantallas (Main, Plaza, Chat, Diagnóstico, Bienvenida)
        │  Hub: hilo del nodo + avisos a las pantallas
GritoService (servicio en primer plano)
        │
     Node (core) ── Store (FileStore: contactos, chats, plaza)
        │
  Radio ×3: WifiRoad (carretera) · WifiRadio (Wi-Fi Aware) · GritoRadio (Bluetooth)
```

- El nodo corre en **un solo hilo** (`Hub.worker`). Las pantallas le preguntan con `Hub.ask` y escuchan eventos.
- Cada trama sale por **todas las antenas activas**. Al llegar, el servicio descarta duplicados por hash (10 s).

## 4. Identidad

| Elemento | Detalle | Archivo |
|---|---|---|
| Frase | 16 palabras de la lista de ICEBREAK (15 bytes + checksum) | `Phrase.kt` |
| Semilla | `SHA-512("chamullo-recovery-v1 " + palabras)[0..32]` | `Phrase.kt` |
| Clave de nodo | Ed25519 desde la semilla; el `NodeId` es la clave pública | `Identity.kt` |
| Clave de caja | X25519 = `clamp(SHA-512(semilla)[0..32])`, igual que tweetnacl/pinenacl | `Crypto.kt` |
| Secreto de etiqueta | `H("CHAMULLO/1/TAGSECRET" ‖ 0 ‖ semilla)`: se recupera con la frase | `Identity.kt` |
| Firmas | `"CHAMULLO/1/" + tag + 0x00 + cuerpo` (separación de dominio) | `Identity.kt` |
| Guardado | La frase cifrada con AES-GCM y una clave del Android Keystore | `Vault.kt` |

La misma frase da claves **distintas** en ICEBREAK y en CHAMULLO, porque cada red deriva con su propia etiqueta.

## 5. Formato de cable

### 5.1 Prefijo y gestos

Todo paquete empieza con `"CH" | versión=1 | clase`. Clases: `0x01 LINK` (entre vecinos), `0x02 ENVELOPE`
(de punta a punta), `0x03 FRAGMENT`. Los campos van en **TLV** con varints de QUIC, tipos estrictamente
crecientes y la **regla par/impar**: un campo desconocido impar se ignora, uno par se rechaza (`Wire.kt`).

### 5.2 Mensajes de enlace (LINK)

| Tipo | Nombre | Para qué | Firmado |
|---|---|---|---|
| 10 | `BEACON` | Latido: id, clave de caja, nombre, largo alcance | Sí |
| 11 | `CARD_OFFER` | Tarjeta de contacto **sellada** para un vecino | Tarjeta firmada |
| 12 | `PLAZA` | Mensaje abierto de La Plaza | Sí |
| 13 | `HEARD` | "Lo escuché" automático | Sí |
| 14 | `ROAD_INVITE` | Llave de mi carretera, **sellada** para un vecino | Sí |

### 5.3 El sobre (ENVELOPE)

- **Remitente efímero:** una clave Ed25519 nueva por carta, que firma el sobre.
- **Destino oculto:** `dst = H("CHAMULLO/1/TAG" ‖ 0 ‖ tagSecret ‖ nonce)`, distinto en cada carta. Solo el destinatario lo reconoce.
- **Contenido:** `box` de NaCl con clave efímera hacia la clave de caja del destino. Adentro van el remitente real, la hora, el texto (o el acuse) y su firma.
- **Acuse (✓✓):** el destinatario responde con otro sobre que nombra el `msgId` recibido.
- **Tránsito:** solo `hop_count` es modificable; el resto está bajo la firma del origen. Vence a las 6 h y admite hasta 8 saltos.

## 6. La red: camino y carretera

### 6.1 Camino (descubrimiento y mensajes chicos)

**Bluetooth: el grito** (`GritoRadio.kt`)

| Pieza | Detalle |
|---|---|
| Grito universal | Cada trama viaja en **micros** de 22 bytes en anuncios clásicos (Manufacturer Data, compañía `0xFFFF`), que cualquier teléfono envía y escucha |
| Micro | `0xC5 | id(2) | índice | n | g | pedazo(16)` (`Micro.kt`) |
| Comodines | `g = ⌈n/4⌉` pedazos de paridad XOR; se reconstruye **un pedazo perdido por grupo** |
| Largo alcance | Si el chip tiene Coded PHY, la trama entera sale además en un anuncio extendido |
| Chips viejos | Sin anuncios extendidos: **varios megáfonos a la vez** con el anunciante clásico (hasta 4, 1 s cada micro), así cada micro sale al aire ≈ 10 veces |
| Escucha | Filtro por fabricante `0xFFFF` + prefijo `0xC5`; se reinicia cada 10 min (Android corta las de más de 30 min) |
| Ritmo | 250 ms por micro, cola por **cartas enteras** (máx. 12); latido firmado cada 15 s |
| Saludo | **"¡Hola!" en un solo grito** (`0xC6 | id corto(8) | largo alcance | nombre ≤ 12 B`) cada 3 s, adelante de la cola. Muestra quién está cerca; no está firmado |
| Límite | Tramas de hasta 4 KB; lo más grande va solo por la carretera |

**Wi-Fi Aware** (`WifiRadio.kt`): si el teléfono lo trae, publica y se suscribe al servicio `chamullo` y manda
cada trama como mensaje (≤ 255 bytes) a cada teléfono encontrado. Sin red ni internet.

> **Permisos de escucha:** Android filtra los anuncios tipo baliza a las apps que declaran `neverForLocation`.
> Por eso CHAMULLO **pide ubicación** (no la lee) y necesita la **Ubicación del sistema prendida**.

### 6.2 Carretera (lo pesado y rápido)

`WifiRoad.kt`, diseño en [spec 07](specs/07-camino-carretera.md).

1. **Entre dos vecinos estables abre la carretera uno solo: el de id más chico**; el otro se sube. Un teléfono que ya viaja en una carretera y recibe otra invitación abre la suya para ese vecino ("pasajero ocupado"), y así la cadena se arma eslabón por eslabón. Una carretera vacía durante 2 minutos se cierra. El teléfono abre **su carretera**: un grupo Wi-Fi Direct con nombre fijo `DIRECT-CH-<id>` y clave derivada de su semilla. No antes: Bluetooth y Wi-Fi comparten antena y una carretera abierta de más le quita oído al grito.
2. Cuando un vecino es **estable** (2 latidos escuchados) y le toca subirse, el nodo le manda la llave con `ROAD_INVITE`, sellada y firmada. Se repite como mucho cada 5 min.
3. El vecino **se sube como cliente Wi-Fi común** (`WifiNetworkSpecifier`, red local sin internet) **sin cerrar su propia carretera**: así se forma la **cadena viva**.
4. Por cada carretera corre un caño TCP (puerto **47474**) con tramas `[largo][bytes]` de hasta 1 MB. El nodo arma tramas de hasta **60 KB enteras**.
5. Cada teléfono viaja en **una** carretera ajena a la vez y recibe a varios en la suya.

### 6.3 Ruteo actual (v0.x)

- **Eco acotado:** cada cartero reenvía una sola vez la carta que no es para él, hasta su límite de saltos.
- **Bolsillos:** el remitente y los carteros guardan la carta y la vuelven a gritar cuando aparece un vecino nuevo (máx. 5 veces).
- **Deduplicación** por `msgId` (últimos 4.000).
- La brújula y el río de la [spec 05](specs/05-discovery-routing.md) están probados en el simulador y **todavía no** están en la app.

### 6.4 Velocidades esperadas

| Medio | Velocidad útil aproximada | Estado |
|---|---|---|
| Grito universal | ≈ 50 bytes/s | Funciona en campo (recepción confirmada) |
| Grito de largo alcance | ≈ 1 KB/s | Solo entre chips con Coded PHY |
| Wi-Fi Aware (mensajes) | ≈ 6 KB/s | Sin medir |
| **Carretera** | **1 a decenas de Mbit/s** | **Sin medir:** usar la prueba de velocidad |

## 7. Funciones de la app

| Pantalla | Qué hace |
|---|---|
| Bienvenida | Crear identidad (muestra las 16 palabras) o recuperarla |
| Inicio | Estado de cada antena, **Cerca tuyo**, Contactos, botón de La Plaza y enlace a ICEBREAK |
| La Plaza | Chat abierto de un salto; cada mensaje propio muestra **quién lo escuchó**. Se reintenta hasta 3 veces si nadie lo escuchó |
| Chat | Cartas cifradas a un contacto; "en camino" → **✓✓** |
| Diagnóstico | Estado de cada antena, contadores, prueba de velocidad de la carretera y **caja negra** con botón para compartir |

**Tarjetas:** "Intercambiar tarjeta" manda mi tarjeta sellada; el otro acepta y la suya vuelve **sin necesidad
de escuchar su latido** (se arma con la tarjeta recibida). Las ofertas se repiten hasta 5 veces hasta recibir la
del otro, y se muestra un solo aviso por persona.

**ICEBREAK** es un servicio más: la app abre su web, que sí necesita internet. CHAMULLO no depende de ICEBREAK.

## 8. Almacenamiento

| Dato | Dónde |
|---|---|
| Frase (identidad) | SharedPreferences cifrado con Keystore (`Vault`) |
| Contactos, chats, plaza | `files/store.json` (`FileStore`), escritura atómica |
| Bolsillos | `files/store.json`: sobreviven al reinicio |
| Vistos | Memoria del nodo |
| Caja negra | Memoria (últimas 800 líneas), exportable desde Diagnóstico |

## 9. Permisos

| Permiso | Por qué |
|---|---|
| `BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT` (12+) | El grito |
| `ACCESS_FINE_LOCATION` | Android entrega los gritos solo con este permiso; también lo pide Wi-Fi Aware/Direct hasta Android 12. **No se lee la ubicación** |
| `NEARBY_WIFI_DEVICES` (13+) | Wi-Fi Aware y Wi-Fi Direct |
| `ACCESS_/CHANGE_WIFI_STATE`, `CHANGE_/ACCESS_NETWORK_STATE` | Carretera y Wi-Fi Aware |
| `INTERNET` | Leer `version.txt` y los caños TCP de la carretera (red local) |
| `FOREGROUND_SERVICE(_CONNECTED_DEVICE)`, `POST_NOTIFICATIONS` | Mantener la red viva y avisar cartas nuevas |

## 10. Actualizaciones y publicación

- Página: **https://nicoconvoz.github.io/chamullo-web/** (repo `nicoconvoz/chamullo-web`, GitHub Pages).
- La app lee `version.txt` al abrirse, al volver y cada 30 min. Si hay una versión nueva, muestra un aviso y una notificación (una por versión) que llevan a la página.
- Mismo criterio que ICEBREAK 0.10.13: **sin** `REQUEST_INSTALL_PACKAGES`.
- Publicar: subir `versionCode` y `versionName` en `android/app/build.gradle.kts` y correr `scripts/publish-web.sh`.
- Firma: clave propia RSA 4096 en `C:/Users/Nico/.android/chamullo-release.jks`. La contraseña está en `GRADLE_USER_HOME/gradle.properties` (`CHAMULLO_STORE_*`), **nunca en el repo**. Se usa con `CHAMULLO_RELEASE_KEY=true` (`android/gradle.properties`).
- **Si se pierde el `.jks` no se pueden publicar más actualizaciones**: hay que guardar una copia de respaldo fuera de la PC.

## 11. Pruebas

| Conjunto | Cantidad | Qué cubre |
|---|---|---|
| `android/core` (JUnit 5) | 79 | Vectores tweetnacl, identidad y frase, TLV y varints, sobres, tarjetas, plaza, llaves de carretera, micros con comodines, nodo en un "pueblo de prueba" (saltos, bolsillos, reintentos, una carretera por par, pasajero ocupado), saludo, **decisiones de islas y turnos del ferry** |
| `android/simulator` (gemelo digital) | 7 | El núcleo real sobre islas Wi-Fi simuladas: formación de islas, ferry entre islas, recibos y caramelos; economía (escasez, diversidad, Corte, bolsa diaria) |
| `sim/` (`node --test`) | 28 | Reglas de ruteo de la spec 05: río, lago, eco de barrio, privacidad, bolsillos, ninguna pérdida silenciosa |

La radio y las pantallas no tienen pruebas automáticas: se verifican **en campo con la caja negra**.

## 12. Bitácora de versiones

| Versión | Cambio | Motivo |
|---|---|---|
| 0.1.0 | Primer Grito: identidad, tarjetas, chat, diagnóstico | — |
| 0.1.1 | Aviso de actualización por `version.txt` | Pedido del Capitán |
| 0.1.2 | Grito universal en micros clásicos | Un teléfono sin anuncios extendidos no escuchaba ni gritaba |
| 0.1.3 | La Plaza | Chat de prueba de alcance |
| 0.1.4 | Wi-Fi Aware y antenas intercambiables | "El Wi-Fi conecta mejor" |
| 0.1.5 | Sin `neverForLocation`; pide ubicación | Android filtraba los gritos |
| 0.1.6 | Anunciante clásico en chips viejos; reinicio de escucha | Los gritos de un teléfono viejo no salían |
| 0.1.7 | La Plaza se guarda | Se borraba al reiniciar |
| 0.1.8 | Comodines (FEC) y reintentos; devolución de tarjeta | Se perdían pedazos y tarjetas |
| 0.1.9 | Caja negra, cola por cartas enteras, latido cada 15 s | La cola cortaba cartas por la mitad |
| 0.2.0 | **Carretera**: Wi-Fi Direct en cadena viva | Transmitir mucho y rápido |
| 0.2.1 | Saludo de un solo grito; la carretera abre solo con un vecino estable | "Vecinos: 0": la carretera abierta de entrada le robaba antena al grito |
| 0.2.2 | Varios megáfonos a la vez en chips viejos | Lo que gritaba un chip viejo llegaba "a veces": cada micro salía una vez o ninguna |
| 0.2.3 | Una sola carretera por par de vecinos; pasajero ocupado; cierre de carreteras vacías; aviso de versión cada 30 min | El Capitán: "si uno abre, el otro se conecta; ¿para qué abrir las dos?" |
| 0.4.2 | Islas puente, asiento libre para el ferry, el anfitrión controla la puerta, un viaje de ferry por turno; el anfitrión cobra como cartero | Hallazgos del **gemelo digital** |
| 0.4.1 | **Clave de firma propia** (`CN=CHAMULLO, O=nicoconvoz`) | Dejar la clave de depuración. Requiere desinstalar una vez y recuperar con las 16 palabras |
| 0.4.0 | **Recibos con la semilla y caramelos**; ICEBREAK como servicio | Proof of Relay: cobrar solo lo que se puede probar |
| 0.3.1 | Saludo secreto con firma en cada caño de la isla; bolsillos que sobreviven al reinicio | Intrusos con la llave del cartel; cartas perdidas al cerrar la app |
| 0.3.0 | **Islas**: todo por Wi-Fi Direct, carteles, anfitrión que reparte, ferry que rota; Bluetooth de respaldo apagado por defecto; Wi-Fi Aware apagado | El Capitán: "¿para qué caminos si podemos trazarlos con la carretera?" |

## 12 bis. Recibos y caramelos (0.4.0)

Implementación de la [spec 03](specs/03-proof-of-relay.md), adaptada a islas.

| Paso | Qué pasa | Código |
|---|---|---|
| 1. Sellar | El origen genera una **llave del viaje** y un **secreto de entrega**. En el sobre van `J_pub` (TLV 8) y `H(r)` (TLV 10); `J_sec` y `r` van adentro, para el destino | `Envelope.seal` |
| 2. Llevar | Cada cartero agrega su **registro cifrado** con `J_pub` (quién, posición, hora, firma sobre la semilla anterior). La **semilla** cambia con cada registro | `Envelope.withHop(carrier, now)` |
| 3. Leer el viaje | El destino abre los registros con `J_sec` y ve **quién llevó la carta, en orden** | `Envelope.journey` |
| 4. Recibo | El destino responde con las **huellas** (hashes) de los registros y revela `r`: solo pudo saberlo quien abrió la carta | `Letter.Ack(journey, revealed)` |
| 5. Pago | El origen comprueba `r`, firma el viaje con su clave efímera y **difunde el pago** (LINK 15, hasta 8 saltos) | `Payment.confirm` |
| 6. Cobrar | Cada cartero que encuentra **su propia huella** en un pago válido suma **1 caramelo** | `Node.onPayment` |

**Diferencias con la spec 03 (honestas):**
- El registro de salto lo firma **solo quien entrega**. La aceptación del que recibe (`ACCEPT`) necesita entregas uno a uno, y hoy las islas reparten a todos.
- Los caramelos son un **contador local verificado** (`Store.saveCandies`). La libreta pública de la Corte (spec 04) todavía no existe.
- El origen no cobra por su propia carta, ni el destino por recibirla.

## 12 ter. Servicios (0.4.0)

CHAMULLO es la red; los servicios viajan por ella. Cada carta lleva su **servicio** (TLV 19 dentro del sobre; por defecto `chat`).
En la pantalla de inicio, **Servicios** muestra La Plaza e **ICEBREAK** como uno más. ICEBREAK hoy se abre en su web; el
siguiente paso es que hable por cartas CHAMULLO con su propio nombre de servicio.

## 12 quater. El gemelo digital (`android/simulator`)

Simulador que corre **el núcleo real de la app** (`Node`, sobres, semilla, recibos, pagos, `Islands.decide`) sobre una radio
Wi-Fi simulada: alcance 80 m, 3 s para sumarse a una isla, 30 ms por trama, celulares que caminan. Encima calcula la
**economía de la spec 04**: escasez entre ×0,5 y ×3, diversidad con rendimiento decreciente, Rey y Nobleza por pueblo y
nacional, y una bolsa diaria fija repartida por puntaje.

```bash
cd android
./gradlew :simulator:run
```

Escribe el informe en [`docs/SIMULACION.md`](SIMULACION.md).

**En vivo**, con la cara del laboratorio (`sim/lab.html`) y el gemelo como motor:

```bash
./gradlew :simulator:live
```

Abre `http://localhost:8787`: mapa de islas con su alcance Wi-Fi, caños, ferrys, caramelos, la Corte coronándose y el bus de eventos. Trae cuatro escenarios: plaza llena, dos pueblos, ruta de tres pueblos y pueblos con viajeros.

| Escenario | Cartas | Hallazgo |
|---|---|---|
| Plaza llena (20) | 30/30 | Más de 7 no entran en una isla: se forman varias y el ferry las une |
| Dos pueblos de 8 | 20/20 | Antes 0/20: islas llenas no dejaban entrar al ferry → **asiento libre** y el anfitrión controla la puerta |
| Ruta de tres pueblos | 20/20 | Antes 0/20: nadie veía al anfitrión ajeno → **islas puente**. El **Rey nacional sale del pueblo del medio**, por escasez |

Límite: el gemelo prueba **el diseño**. Las mañas reales de Wi-Fi Direct en cada celular solo se ven en campo.

## 13. Islas (0.3.0)

Rediseño del Capitán después de las pruebas de campo: **el Bluetooth complicaba todo y transmitía muy poco**. En
0.3.0 Wi-Fi Direct hace de camino **y** de carretera. Diseño en [spec 07 §6](specs/07-camino-carretera.md#6-islas-v02).

### 13.1 La idea en una tabla

| Pieza | Qué es | Estado |
|---|---|---|
| **Isla** | Un grupo Wi-Fi Direct: un anfitrión y hasta 7 miembros que se hablan en un salto; el anfitrión repite cada trama a los demás | En la app (`WifiIslands.kt`) |
| **Cartel** | Registro de servicio Wi-Fi Direct (DNS-SD) con id, nombre, isla, rol, llave y lista de miembros. Se ve **sin conectarse**; se busca cada 15 s | En la app |
| **Sumarse, no fundar** | Sin isla: me sumo a la más grande con lugar; si no hay, fundo una. Se decide cada 5 s | En la app |
| **Fusión** | Un anfitrión solo se suma a una isla vecina (a una más grande, o a otra sola de id menor) | En la app |
| **Ferry** | Por turnos de 60 s, un miembro sale, entra a la isla vecina 20 s, entrega lo que lleva y vuelve | En la app |
| **Bluetooth y Wi-Fi Aware** | Apagados por defecto; el Bluetooth se prende desde Diagnóstico como respaldo | En la app |

### 13.2 Reglas de decisión (`Islands.decide`)

1. **Sin isla:** me sumo a la isla con lugar que tenga más miembros (desempate: id menor). Si no hay, **fundo** la mía.
2. **Anfitrión con miembros:** me quedo; la isla depende de mí.
3. **Anfitrión solo:** me sumo a una isla vecina si es más grande o si es otra isla sola con id menor que el mío. Así dos anfitriones solos se fusionan y nunca se cruzan.
4. **Puente:** un miembro que ve gente de otra isla pero no a su anfitrión **funda una isla puente** en el borde; un anfitrión puente solo no se fusiona de vuelta.
5. **Asiento libre:** los nuevos se suman hasta 6 miembros; el 7.º lugar es para ferrys. El anfitrión controla la puerta: al que entra de más le avisa "llena".
6. **Miembro:** si hay otra isla a la vista, el turno de ferry rota por la lista ordenada de miembros (`(hora / 60 s) mod miembros`). Al de turno le toca **Ferry**, **un viaje por turno**; los demás se quedan.

### 13.3 Cómo viaja una carta entre islas

1. En la isla, la carta llega en un salto al anfitrión, que la reparte a todos.
2. Todos la guardan en el **bolsillo** (ya existe).
3. El ferry de turno se cambia a la isla vecina. Allí aparecen vecinos nuevos y el bolsillo **se vuelve a gritar solo**, que es la regla de bolsillos de 0.1.x.
4. El ferry vuelve a su isla.

### 13.4 Límites honestos

- Conectarse a otra isla tarda **de 1 a 5 s**: el ferry lleva lotes, no paquetes sueltos.
- Todas las islas usan `192.168.49.x`: el ferry cambia de isla, así que no hay choque. Un puente fijo en las dos a la vez queda para más adelante.
- Unirse sin carteles de confirmación requiere **Android 10+**, y el celular del Capitán tiene Android 10.
- El cartel publica la llave de la isla: cualquiera cerca puede entrar al Wi-Fi, pero **cada caño exige el saludo secreto** (spec 01 §7): ambos lados firman los dos ids y dos nonces frescos. Sin firma válida en 10 s, el caño se cierra.

## 14. Pendientes conocidos

- [ ] **Probar Islas en campo** (§13): fundar, sumarse, prueba de velocidad.
- [ ] **Medir la carretera en campo**: conexión, prueba de velocidad, posible choque de direcciones (todas las carreteras usan `192.168.49.x`).
- [ ] Confirmar si Android pide aprobación cada vez que se sube a una carretera.
- [ ] Brújula y río (spec 05) en la app: hoy el ruteo es eco acotado.
- [ ] Economía completa (spec 04): hoy los caramelos son un contador local verificado; falta la libreta de la Corte, la escasez y la diversidad.
- [ ] Aceptación bilateral por salto (`ACCEPT`, spec 03 §5): con islas y eco, el registro de salto lo firma solo quien entrega.
- [ ] Brújula entre islas lejanas (spec 05): dentro de una isla todo está a un salto; la brújula vuelve cuando haya varias islas en campo.
- [ ] Rol del iPhone.

## 15. Próximo paso

Probar 0.3.0 con dos teléfonos Android 10+ con el Wi-Fi y la Ubicación prendidos: Diagnóstico → "Isla: …" →
**Prueba de velocidad en la isla**. Si algo falla, compartir la caja negra.


## Decisión: la moneda se llama Lucas

La billetera de la app paga la recompensa en **Lucas**, la moneda propia de CHAMULLO (spec 04). Reemplaza a ICE, que queda como moneda de ICEBREAK: un servicio más sobre la red, del que CHAMULLO no depende. Los caramelos siguen siendo los recibos verificados; el reparto diario los convierte en Lucas.

## La brújula sobre islas (Discovery & Routing v0.2)

**Qué cambió:** las cartas dejaron de repartirse como volante. Cada carta va de mano en mano hacia el barrio del destino y, al llegar, se reparte con un eco local acotado. Las reglas exactas están en el spec 05 §10; el núcleo de la app y el gemelo usan el mismo código.

| Pieza | Dónde | Qué hace |
|---|---|---|
| Grilla | `Zone.kt` | Celda de ~50 m, barrio de ~2 km, pueblo, región. Distancia y rumbo |
| Brújula | `Compass.kt` | Decide río, lago, eco local, llevar o parar. Funciones puras |
| Mano en mano | `Node.kt` + `Offer` / `Accept` | Ofrecer, aceptar, copia firmada por los dos, "la tengo" y soltar |
| Islas | `Islands.kt` | El ferry rota entre islas vecinas y existe el ferry de necesidad |
| Gemelo | `World.kt` | Mapa en latitud y longitud, el anfitrión es el aire, embarque de 3 s |

**Lo que el gemelo obligó a corregir** (todo anotado en el spec 05 §10):

- La celda de 100 m era más grande que el alcance del Wi-Fi, así que se pasó a 50 m.
- El lago alejaba las cartas del borde de la isla: ahora espera dos turnos de ferry.
- Un rechazo ya no dura para siempre, sino 30 s.
- Haber visto una carta ya no cierra el camino para volver a llevarla.
- Se suelta la copia solo con el "la tengo" del que la recibe.
- Los pagos se guardan y suben al ferry como las cartas.

**Resultados del informe** (`docs/SIMULACION.md`):

| Escenario | Entregadas |
|---|---|
| Plaza | 100 % |
| Dos pueblos | 70–85 % |
| Ruta de tres pueblos | 100 % |
| Pueblos con viajeros | 100 % |
| Ciudad de 1000 | 43 % (antes ~30 %) |
| Provincia, 4 km, solo islas | 0 %: llega a ~3 km y se frena |
| Provincia con el puente por Internet | **100 %**, 11 s de mediana |

**Acertijos abiertos** (son del Capitán):

1. ~~El barrio grande~~: resuelto (spec 05 §2.1). En medio de una multitud, la tarjeta apunta a la manzana (~400 m), porque tanta gente ya esconde. La ciudad pasó del 45 % al 72 %.
2. ~~El puente por Internet~~: ya está programado (`Bridge.kt`, la nube del gemelo). Entra en un **salto grande** (más de 2,5 km) o cuando la carta queda trabada, y baja por 3 puentes del barrio destino. En la provincia, los puentes cobraron 697 de las 1000 Lucas del día, y la información costó 0,1 Lucas por KB: es la economía con respaldo real (spec 04 §13).

**Pendiente en la app:** leer el GPS y llamar a `Node.locate`. Mientras no lo haga, las cartas de la app siguen con el eco de v0.1 (sin zona), igual que hoy.

## Versión 0.5.0: vamos por todo

| Pieza | Qué es | Dónde |
|---|---|---|
| 🏘️ El barrio grande | En medio de una multitud la tarjeta apunta a la manzana (~400 m) | spec 05 §2.1, `Node` |
| 🧭 GPS en la app | `Locator` le da la celda al nodo; ferry con rumbo y celdas en los carteles | `Locator`, `WifiIslands` |
| ☁️ El relé | Nube que cualquiera aloja: directorio de puentes por manzana y buzones firmados | `android/relay`, `RelayBridge`, spec 05 §11.1 |
| 🧾 Cobrar | El cartero guarda la llave de su renglón; `Journey` y `Claim` según Proof of Relay §6-§7 | `Receipts.kt` |
| 📒 La libreta | Páginas encadenadas; Rey y más de 2/3 de la Nobleza; génesis, destronamiento, reparto por escasez y diversidad, sueldo de la Corte, cámara compensadora | `Ledger.kt`, spec 04 §9.4 |
| 🗣️ La libreta en los celulares | Asientos de boca en boca, el Rey escribe, la Nobleza endosa, el que llega tarde se pone al día, las páginas cruzan por los puentes y se guardan en disco | `Node` |
| 🛒 Gastar Lucas | Prioridad paga (mitad se quema, mitad para los carteros) con carril gratis de un tercio; Tienda de Lucas | `Node`, `StoreActivity`, spec 04 §6 |
| 🌉 Puente fijo | Un miembro queda en dos islas a la vez (Wi-Fi Direct + Wi-Fi común solo local) | `Islands`, `WifiIslands`, spec 07 §6.2 |

**Resultados del gemelo** (`docs/SIMULACION.md`):

| Escenario | Entregadas |
|---|---|
| Plaza | 100 % |
| Dos pueblos | 100 % |
| Ruta de tres pueblos | 100 % |
| Pueblos con viajeros | 100 % |
| Ciudad de 1000 | 81 %, libreta igual en 997 de 1000 celulares, Corte por regla |
| Provincia | 100 % por el puente |

**Lo que queda fuera de este repo:**
- Probar en el campo con celulares de verdad.
- La app de iPhone (necesita Mac y Xcode).
- Alojar un relé, que cualquiera puede levantar con `./gradlew :relay:run`.
- Que el Capitán cargue su clave de fundador en Diagnóstico.

En el puente fijo, Android le pide permiso al dueño la primera vez, y eso todavía no se probó en celulares reales.

## Bitácora 0.5.1 a 0.5.5

| Versión | Qué cambió | Por qué |
|---|---|---|
| 0.5.1 | Los períodos de la libreta cuentan desde la página génesis. La clave del fundador (`6e63751b…`) quedó escrita en el protocolo y se sacó el botón "Soy el fundador". Un libro guardado que rompe las reglas se descarta al arrancar | En el campo, la libreta contaba desde 1970 y cerraba un período cada 10 s: 31 páginas y 620 Lucas falsas en minutos. Con varios fundadores, el pueblo se partía en libretas que nunca se juntan |
| 0.5.2 | La app busca el relé sola en `relays.txt` de la página. Queda un solo botón, sin direcciones | El Capitán: "tiene que ser todo fácil" |
| 0.5.3 | El puente por Internet va por **Nostr** (spec 05 §11.2), con firma BIP-340, eventos NIP-01, un WebSocket propio y 6 relés públicos. Sin servidor propio | Idea del Capitán: una red P2P que se arma sola |
| 0.5.4 | "Prestar Internet y ganar Lucas" aparece en la presentación y en la pantalla principal | Estaba escondido en Diagnóstico |
| 0.5.5 | La búsqueda de islas va un paso por vez, reintenta si el Wi-Fi Direct está ocupado y relanza después de formar un grupo. Antes de fundar, el celular mira 30 a 60 s (spec 07 §6.3) | En el campo, cada celular fundó su isla y la búsqueda contestaba `BUSY` (código 2): nunca se vieron |

| 0.5.6 | Segunda vía para encontrar islas: la lista de redes Wi-Fi (`DIRECT-CH-xxxxxx`), con la clave sacada del nombre. Las islas se nombran con 6 dígitos hex. Diagnóstico muestra las dos vías (spec 07 §6.4) | En el campo, con la 0.5.5: "Carteles vistos: 0". La búsqueda DNS-SD no se veía entre esos dos celulares |

| 0.5.7 | El servicio declara el tipo `location` además de `connectedDevice` (spec 07 §6.5) | Depuración USB en el Moto E7 Plus (Android 10): de fondo, Android negaba la ubicación, y sin ella no hay búsqueda Wi-Fi Direct ni lista de redes. Comprobado en el celular, con la app de fondo |

**Cómo se documenta:** cada cambio va a su spec (la regla) y a esta bitácora (qué y por qué). El informe del gemelo se regenera con `./gradlew :simulator:run`.

