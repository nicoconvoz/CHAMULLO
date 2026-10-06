# CHAMULLO — Camino y Carretera v0.1

| Campo | Valor |
|---|---|
| Estado | Borrador, implementado en la app 0.2.0 |
| Fecha | 2026-10-05 |
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
