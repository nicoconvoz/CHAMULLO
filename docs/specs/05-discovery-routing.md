# CHAMULLO — Discovery & Routing Specification v0.1

| Campo | Valor |
|---|---|
| Estado | Borrador |
| Fecha | 2026-10-05 |
| Depende de | Identity v0.1, Packet Format v0.1, Proof of Relay v0.2 |
| Consumida por | Economy & Governance, Transport Abstraction, Simulator |

Las palabras **DEBE**, **NO DEBE**, **DEBERÍA** y **PUEDE** se usan con el sentido de RFC 2119.

---

## 1. Intuición

Las ideas de esta spec son del Capitán del proyecto.

| Metáfora | Mecanismo |
|---|---|
| **La tarjeta.** Para escribirle a alguien, primero te tiene que dar su tarjeta, por otro canal o porque se cruzaron. | Tarjeta de contacto firmada, intercambiada fuera de banda (§4) |
| **La brújula de los tesoros.** La carta solo dice "al norte"; en Santa Cruz, "al norte"; más arriba, "al este". | Reenvío geográfico por rumbo hacia el centro de una zona (§6), igual que `Hunt.bearing` de ICEBREAK |
| **El río con eco.** No se pasa a todos, solo a los suficientes para que siga el camino. | *Fanout* acotado hacia el destino (§6.2) |
| **El lago.** Si el río se traba, se ensancha o lo bordea. | Recuperación ante zonas vacías (§6.3) |
| **Nadie ve la ruta completa.** Cada cartero conoce solo al anterior y al siguiente. | Sello descartable, etiqueta de destino y viaje cifrado (§7) |
| **La mudanza.** Si Dani se muda, avisa solo a sus contactos. | Actualización de zona cifrada (§5) |

## 2. Zonas

El territorio se divide en una grilla jerárquica de celdas (estilo *geohash*):

| Nivel | Tamaño aproximado | Uso |
|---|---|---|
| Región | ~150 km | Rumbo de larga distancia |
| Pueblo | ~20 km | Economía (Economy §7), destino mínimo recomendado |
| Barrio | ~2 km | Destino por defecto en el sobre |
| Celda | ~100 m | Solo se comparte con vecinos directos, como en ICEBREAK |

- Un nodo NUNCA DEBE publicar coordenadas GPS exactas.
- El sobre lleva como destino una zona de nivel **barrio o mayor**. Cuanto más grande, más privacidad y más búsqueda final.

## 3. Descubrimiento de vecinos

1. Cada transporte anuncia la presencia del nodo a su manera (Transport Abstraction).
2. Al detectar un par, se ejecuta el handshake (Identity §7).
3. Ya autenticados, los vecinos intercambian por `LINK` su **celda** actual y métricas del enlace (latencia, pérdida, ancho de banda).
4. Cada nodo mantiene una **tabla de vecinos**: `NodeId`, celda, transporte, métricas y última vez visto.

**Verificación de plausibilidad:** si un vecino declara una celda incompatible con el alcance físico del transporte (por ejemplo, a 50 km por Bluetooth), su celda DEBE ignorarse y el vecino DEBERÍA perder prioridad de reenvío. Mentir con el GPS para atraer tráfico no paga.

## 4. Tarjeta de contacto

### 4.1 Contenido

```text
CARD {
  node_id     NodeId               identidad real
  zone        celda nivel barrio+  dónde anda, aproximado
  tag_secret  bytes[32]            secreto para reconocer cartas dirigidas a este nodo
  ts          u64
  sig         sig_node( "CARD", node_id | zone | tag_secret | ts )
}
```

### 4.2 Intercambio

- La tarjeta se entrega **fuera de banda**: QR cara a cara, ICEBREAK, otro CHAMULLO u otro canal; o bien por cercanía directa en un enlace autenticado.
- **No hay directorio público** de tarjetas. Quien no tiene tu tarjeta no puede escribirte, y eso evita el spam de desconocidos.

## 5. Mudanza: actualización de zona

- Cuando un nodo cambia de zona, DEBERÍA enviar a sus contactos una tarjeta nueva dentro de un `ENVELOPE` cifrado (`SEALED`), uno por contacto.
- Toda respuesta lleva la zona actual del remitente dentro del contenido cifrado.
- Si una carta llega a una zona vieja y no encuentra al destino, la búsqueda local (§6.4) agota su presupuesto y el origen recibe un aviso de no entrega por el tiempo de expiración.

## 6. Reenvío: la brújula y el río

### 6.1 Rumbo

Cada nodo calcula el rumbo desde su celda hasta el **centro** de la zona destino, con la misma geometría que `Hunt.bearing` de ICEBREAK. Un vecino tiene **progreso** si su celda está más cerca del centro de la zona que la propia.

### 6.2 Fanout acotado (el río)

- Entre los vecinos con progreso, se eligen hasta **`k = 2`** (el *fanout* de ECO), ordenados por progreso × calidad del enlace.
- Cada copia sigue su camino de forma independiente. Si un cartero se apaga, la otra copia continúa.
- Los duplicados se descartan por `msg_id` (Identity §8.2). Un nodo que ya vio un mensaje NO DEBE reenviarlo de nuevo.
- `k` se calibra con el simulador. Una prioridad paga (Economy §6) PUEDE usar `k = 3`.

### 6.3 El lago: zonas sin nodos

Si ningún vecino tiene progreso:

1. **Ensanchar:** se aceptan vecinos dentro de ±90° del rumbo, aunque no acerquen.
2. **Bordear:** si sigue trabado, se aplica la regla de la mano derecha (*perimeter routing*, como GPSR) con un presupuesto máximo de saltos sin progreso.
3. Si se agota el presupuesto, la copia se descarta.

### 6.4 Llegada al barrio

- Al entrar en la zona destino, el sobre pasa a un **eco local**: se reenvía a todos los vecinos dentro de la zona, con un TTL acotado.
- Cada nodo de la zona compara la **etiqueta de destino** del sobre (§7.2) con la suya. Solo el destinatario real la reconoce.

## 7. Privacidad: nadie ve la ruta completa

### 7.1 Sello descartable (oculta el origen)

- El `src` del sobre DEBE ser una **clave efímera**, nueva para cada mensaje. Es una clave Ed25519 válida, así que toda la validación de Identity §8.2 sigue funcionando.
- La identidad real del remitente viaja **dentro** del contenido cifrado. Solo la ve el destino.

### 7.2 Etiqueta de destino (oculta a quién va)

En lugar del `NodeId` del destino, el campo `dst` del sobre lleva:

```text
dst_tag = H( "CHAMULLO/1/TAG" || 0x00 || tag_secret_destino || nonce )
```

- Como incluye el `nonce` del mensaje, la etiqueta es **distinta en cada carta**. No se puede seguir a alguien juntando etiquetas.
- El destino calcula `H(... || su tag_secret || nonce)` y compara: un solo hash por sobre.
- **Limitación:** todos los contactos que tienen la tarjeta conocen el `tag_secret`. Si hacen de carteros, pueden reconocer las cartas dirigidas a ese destino. Ver §9.

### 7.3 Viaje cifrado (oculta la ruta)

Cada cartero escribe su registro de salto **cifrado** para que solo el origen y el destino lo lean (Proof of Relay v0.2 §4). Un cartero ve únicamente a quién se la dio y a quién se la pasa.

### 7.4 Quién ve qué

| Actor | Origen real | Destino real | Zona destino | Ruta completa |
|---|---|---|---|---|
| Cartero | ❌ | ❌ | ✅ (barrio o mayor) | ❌, solo sus vecinos |
| Cartero que es contacto del destino | ❌ | ⚠️ reconoce la etiqueta | ✅ | ❌ |
| Destino | ✅ | ✅ | ✅ | ✅ |
| Origen | ✅ | ✅ | ✅ | ✅ (por el acuse) |
| Libreta / Corte | ❌ | ❌ | ❌ | ❌, solo cobros sueltos |

## 8. Insumos para la economía

- **Alternativas (escasez, Economy §5.2):** cada nodo cuenta, por cada envío, cuántos vecinos distintos con antigüedad suficiente ofrecían progreso hacia esa zona. Lo informa en el TLV de tránsito `path_metrics` (Packet Format §5.4), dentro de su registro cifrado.
- **Prioridad paga:** cambia `k` y el orden en la cola, nunca el carril gratis.

## 9. Preguntas abiertas

1. **Etiqueta por contacto:** usar un secreto distinto por contacto evitaría que los contactos reconozcan cartas ajenas, pero el destino tendría que probar con todos sus contactos en cada sobre. ¿Cuál es el punto de equilibrio?
2. **Triangulación:** varios carteros cómplices pueden cruzar rumbos y deducir la zona destino. Si la zona es grande, la fuga es aceptable; hay que cuantificarlo en el simulador.
3. **Tamaño de zona dinámico:** ¿zonas más grandes en ciudades densas y más chicas en el campo?
4. **Destinos móviles rápidos** (personas en auto o en colectivo): ¿la búsqueda local alcanza?
5. **Modo agente secreto:** ruta tipo mamushka (*onion*) elegida por el origen, con propinas por capa, para quien necesite que ni siquiera los extremos vean la ruta.
