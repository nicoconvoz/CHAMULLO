# CHAMULLO

[![DOI](https://zenodo.org/badge/1406372843.svg)](https://zenodo.org/badge/latestdoi/1406372843)
[![Release](https://img.shields.io/github/v/release/nicoconvoz/CHAMULLO?label=Release&color=111111)](https://github.com/nicoconvoz/CHAMULLO/releases/latest)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Licencia comercial](https://img.shields.io/badge/Licencia-comercial-1F9D43.svg)](LICENCIA-COMERCIAL.md)

> *"La comunicación puede ser una infraestructura aportada por sus propios participantes."*

**CHAMULLO Protocol** es una red de comunicación gratuita, distribuida y participativa. Las personas pueden desplegar un nodo y aportar capacidad de retransmisión a otros participantes, sin depender de que cada intercambio atraviese un servicio centralizado.

## Idea central

Un mensaje puede avanzar de nodo en nodo: `A → B → C → D`. A no necesita alcance directo hasta D si B y C pueden retransmitir el tráfico. El alcance efectivo crece con la cantidad y distribución de participantes, no aumentando la potencia de un dispositivo.

## Principios

- **Protocolo abierto**: independiente de cualquier proveedor concreto.
- **Independencia del transporte**: la lógica de red (identidad, descubrimiento, encaminamiento, retransmisión, pruebas de entrega, economía) se separa del medio físico (Bluetooth, Wi-Fi, Wi-Fi Direct/Aware, WebRTC, Internet, etc.).
- **Contribución verificable** ("minería de tráfico"): se recompensa a quienes efectivamente transportan tráfico mediante *Proof of Relay* / *Proof of Delivery*, no mediante prueba de trabajo.
- **Identidad criptográfica**: cada nodo tiene su propia clave y firma sus operaciones.
- **Legalidad**: el protocolo es agnóstico del hardware y del espectro; no modifica módems ni firmware ni asume acceso a bandas no permitidas.

## Origen

CHAMULLO nace de la arquitectura P2P/ECO de **ICEBREAK**. Puede existir de forma independiente, y a futuro ICEBREAK podría utilizarlo como transporte.

## Estado

🧪 **Fase de definición conceptual.** Ya están definidos a nivel conceptual la idea central, el nodo, el modelo multi-hop, la abstracción de transportes y la minería de tráfico.

Pendiente de formalizar: formato de paquetes, descubrimiento, routing, Proof of Relay/Delivery, economía, anti-fraude, gobernanza y transportes concretos.

## Hoja de ruta inicial

1. Identidad
2. Formato de paquetes
3. Descubrimiento
4. Routing básico y relay
5. Delivery proof y registro de contribuciones
6. Economía experimental
7. Simulación con miles de nodos (escenarios normales, densos, dispersos y hostiles)
8. Estudio de hardware/radios adicionales

## Documentación

- **[Documentación técnica](docs/DOCUMENTACION_TECNICA.md)**: lo que está construido hoy, cómo compilar, probar y publicar.
- [Documento maestro v0.1](docs/CHAMULLO_Documento_Maestro_v0.1.docx): concepto y arquitectura.
- [Identity & Cryptography Specification v0.1](docs/specs/01-identity-cryptography.md): identidad de nodo, firmas, handshake, anti-replay, rotación y revocación.
- [Packet Format Specification v0.1](docs/specs/02-packet-format.md): prefijo universal, campos TLV con regla par/impar, sobre firmado, handshake y fragmentación.
- [Proof of Relay / Proof of Delivery Specification v0.2](docs/specs/03-proof-of-relay.md): cadena de saltos (semilla) cifrada, compromiso previo de firma, viaje sellado por destino y origen, cobro individual y diversidad.
- [Economy & Governance Specification v0.1](docs/specs/04-economy-governance.md): Lucas (la moneda), escasez, carril gratis, pueblos, cuentas de operador, Rey y Nobleza, libretas y cámara compensadora.
- [Discovery & Routing Specification v0.1](docs/specs/05-discovery-routing.md): zonas, tarjeta de contacto, brújula y río, búsqueda local y privacidad de ruta.
- [Air Interface "El Grito" Specification v0.1](docs/specs/06-air-interface.md): modo de conexión propio sobre la radio de largo alcance del celular, con turnos y enlace inspirados en el sidelink de 3GPP.
- [Camino y Carretera v0.1](docs/specs/07-camino-carretera.md): el grito descubre (camino) y abre Wi-Fi Direct de teléfono a teléfono (carretera) en una cadena viva.

## App Android

- **Descargar:** https://nicoconvoz.github.io/chamullo-web/ (Android 8+, 1,3 MB).
- Código: [android/](android/). Publicar una versión nueva: subir `versionName` en `android/app/build.gradle.kts` y correr `scripts/publish-web.sh`; la app avisa sola.

## Simulador

- [La maqueta](sim/README.md): modelo determinista de las reglas del protocolo, basado en el simulador P2P web de ICEBREAK. `cd sim && npm test`.

## Licencia

Copyright (C) 2026 **Jesús Nicolás Astorga** y **RESOURCES OPEN DOORS S.A.S**

- **Código**: doble licencia. Se elige una de dos:
  - [GNU AGPL-3.0-or-later](LICENSE): libre, con la obligación de publicar el código de toda obra derivada, también si se ofrece como servicio por red.
  - [Licencia comercial](LICENCIA-COMERCIAL.md): para integrar CHAMULLO en productos cerrados, por contrato con los titulares.
- **Especificaciones y documentación** (`docs/`): [Creative Commons Attribution 4.0 International](docs/LICENSE).
- Las versiones hasta la 0.8.2 se publicaron bajo Apache 2.0; desde la 0.9.0 rige la doble licencia. Detalles en [NOTICE](NOTICE).
- Para colaborar hay que aceptar el acuerdo de [CONTRIBUTING.md](CONTRIBUTING.md).
- Para citar el proyecto: [CITATION.cff](CITATION.cff).
