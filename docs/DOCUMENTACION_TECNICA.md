# CHAMULLO — Documentación técnica

Versión del documento: 1 · Estado del código: app 0.2.0 (octubre 2026)

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
| Chips viejos | Sin anuncios extendidos se usa el anunciante clásico de Android (start/stop por micro) |
| Escucha | Filtro por fabricante `0xFFFF` + prefijo `0xC5`; se reinicia cada 10 min (Android corta las de más de 30 min) |
| Ritmo | 250 ms por micro, cola por **cartas enteras** (máx. 12); latido cada 15 s |
| Límite | Tramas de hasta 4 KB; lo más grande va solo por la carretera |

**Wi-Fi Aware** (`WifiRadio.kt`): si el teléfono lo trae, publica y se suscribe al servicio `chamullo` y manda
cada trama como mensaje (≤ 255 bytes) a cada teléfono encontrado. Sin red ni internet.

> **Permisos de escucha:** Android filtra los anuncios tipo baliza a las apps que declaran `neverForLocation`.
> Por eso CHAMULLO **pide ubicación** (no la lee) y necesita la **Ubicación del sistema prendida**.

### 6.2 Carretera (lo pesado y rápido)

`WifiRoad.kt`, diseño en [spec 07](specs/07-camino-carretera.md).

1. Cada teléfono abre **su carretera**: un grupo Wi-Fi Direct con nombre fijo `DIRECT-CH-<id>` y clave derivada de su semilla.
2. Cuando un vecino es **estable** (2 latidos escuchados), el nodo le manda la llave con `ROAD_INVITE`, sellada y firmada. Se repite como mucho cada 5 min.
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
| Bolsillos y vistos | Memoria del nodo: se pierden al cerrar el servicio |
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
- La app lee `version.txt` al abrirse, al volver y cada 6 h. Si hay una versión nueva, muestra un aviso y una notificación (una por versión) que llevan a la página.
- Mismo criterio que ICEBREAK 0.10.13: **sin** `REQUEST_INSTALL_PACKAGES`.
- Publicar: subir `versionCode` y `versionName` en `android/app/build.gradle.kts` y correr `scripts/publish-web.sh`.
- Firma: por ahora, la clave de depuración local. Falta una clave de release propia.

## 11. Pruebas

| Conjunto | Cantidad | Qué cubre |
|---|---|---|
| `android/core` (JUnit 5) | 57 | Vectores tweetnacl, identidad y frase, TLV y varints, sobres, tarjetas, plaza, llaves de carretera, micros con comodines, nodo en un "pueblo de prueba" (saltos, bolsillos, reintentos, carretera) |
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

## 13. Pendientes conocidos

- [ ] **Medir la carretera en campo**: conexión, prueba de velocidad, posible choque de direcciones (todas las carreteras usan `192.168.49.x`).
- [ ] Confirmar si Android pide aprobación cada vez que se sube a una carretera.
- [ ] Brújula y río (spec 05) en la app: hoy el ruteo es eco acotado.
- [ ] Recibos con cadena de saltos y cobro (spec 03) y economía (spec 04).
- [ ] Bolsillos persistentes: hoy se pierden si el servicio se cierra.
- [ ] Clave de firma de release propia.
- [ ] Rol del iPhone.

## 14. Próximo paso

Probar la 0.2.0 con dos teléfonos Android 10+: Wi-Fi, Bluetooth y Ubicación prendidos → Diagnóstico →
"Caños abiertos: 1" → **Prueba de velocidad**. Si algo falla, compartir la caja negra.
