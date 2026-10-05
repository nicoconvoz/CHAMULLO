# CHAMULLO

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

- [Documento maestro v0.1](docs/CHAMULLO_Documento_Maestro_v0.1.docx): concepto y arquitectura.
- [Identity & Cryptography Specification v0.1](docs/specs/01-identity-cryptography.md): identidad de nodo, firmas, handshake, anti-replay, rotación y revocación.
- [Packet Format Specification v0.1](docs/specs/02-packet-format.md): prefijo universal, campos TLV con regla par/impar, sobre firmado, handshake y fragmentación.
- [Proof of Relay / Proof of Delivery Specification v0.1](docs/specs/03-proof-of-relay.md): cadena de saltos (semilla), compromiso previo de firma, recibo de entrega y principios de diversidad.
- [Economy & Governance Specification v0.1](docs/specs/04-economy-governance.md): ICE, escasez, carril gratis, pueblos, cuentas de operador, Rey y Nobleza, libretas y cámara compensadora.

## Licencia

- **Código**: [Apache License 2.0](LICENSE).
- **Especificaciones y documentación** (`docs/`): [Creative Commons Attribution 4.0 International](docs/LICENSE).
