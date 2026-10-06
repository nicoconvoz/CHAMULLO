# CHAMULLO — Economy & Governance Specification v0.1

| Campo | Valor |
|---|---|
| Estado | Borrador |
| Fecha | 2026-10-05 |
| Depende de | Identity & Cryptography v0.1, Packet Format v0.1, Proof of Relay v0.1 |
| Consumida por | Simulator, Legal & Regulatory Deployment Guide |

Las palabras **DEBE**, **NO DEBE**, **DEBERÍA** y **PUEDE** se usan con el sentido de RFC 2119.

---

## 1. Intuición

Las ideas de esta spec son del Capitán del proyecto.

| Metáfora | Mecanismo |
|---|---|
| **Mandar cartas es gratis; llevarlas tiene premio.** | Mensajería básica sin costo; Lucas solo por contribución comprobada (§4) |
| **Los caramelos se ganan y se gastan.** | Emisión diaria acotada y consumo en prioridad y en la Tienda de Lucas (§4, §6) |
| **Donde sobran caminos se paga menos, donde faltan se paga más, siempre en equilibrio.** | Factor de escasez con piso y techo (§5.2) |
| **El jefe muta.** Manda el que más aporta. | Rey y Nobleza recalculados cada período por regla pública (§8) |
| **El Rey y la Nobleza.** | Rey escribe la libreta, Nobleza la sella con más de 2/3 (§8, §9) |
| **Un reino federal.** | Cortes por localidad y corte nacional; cámara compensadora (§7, §9.3) |
| **La corona no viaja.** | Los títulos valen solo en el pueblo de origen (§7.2) |
| **No hay corona fija; el Rey puede vivir en Tierra del Fuego.** | Mérito puro, nivelado por escasez (§8.1) |
| **Muchas antenas, una persona.** | Cuentas de operador (§7.3) |

## 2. Dos pisos

| Piso | Qué contiene | ¿Tiene jefe? |
|---|---|---|
| **Planta baja: protocolo** | Identidad, sobre, cadena de saltos, recibos | **No.** Cualquiera verifica cualquier firma sin consultar a nadie. |
| **Primer piso: economía** | Libretas de Lucas, ranking, títulos | **Sí:** la Corte, local y nacional, que muta según el aporte. |

La identidad sigue sin autoridad central (Identity §11). La Corte gobierna **solo** la economía: no puede impedir que dos nodos se comuniquen ni alterar un sobre.

## 3. Definiciones

| Término | Significado |
|---|---|
| **Lucas** | La moneda de CHAMULLO: lo que paga la billetera de la app como recompensa por llevar cartas. Unidad de cuenta única para todo el país. Es propia de CHAMULLO: no depende de ICEBREAK ni de ninguna otra app. |
| **Pueblo** | Localidad: unidad geográfica con su propia libreta y su propia Corte (§7). |
| **Período** | Ventana de cálculo de ranking y títulos. Valor inicial propuesto: **7 días**. |
| **Puntaje** | Medida ponderada de la contribución de un nodo en un período (§5). |
| **Corte** | El Rey y la Nobleza de un pueblo o del país. |
| **Libreta** | Registro público y encadenado de saldos y movimientos de Lucas (§9). |

## 4. De dónde sale y a dónde van las Lucas

### 4.1 Gratuidad

- Enviar y recibir mensajes en el **carril gratis** (§6.2) NO DEBE costar Lucas.
- Ningún nodo necesita Lucas para participar en la red.

### 4.2 Emisión: un reparto fijo, no una imprenta

- Cada día, cada pueblo reparte un **presupuesto fijo** de Lucas (`E_pueblo`) entre sus contribuyentes, en proporción a su puntaje:

  ```text
  ICE_día(R) = E_pueblo × puntaje(R) / Σ puntaje(todos)
  ```

- Como el total es fijo, inflar puntajes no imprime Lucas extra; solo cambia cómo se reparte. Ese es el **equilibrio**: si todos suben, nadie gana más que antes.
- `E_pueblo` se deriva de un presupuesto nacional (`E_nación`) según la actividad de cada pueblo. La fórmula de asignación queda abierta (§12).
- Solo cuentan contribuciones con **Proof of Relay** válido (Proof of Relay §7). Estar disponible sin trabajar no paga: es la lección de las antenas falsas de Helium.

### 4.3 Consumo: los desagües

Las Lucas se gastan en:

1. **Prioridad de ruteo** (§6.1).
2. **Tienda de Lucas:** apps y servicios del ecosistema.
3. **Pagos entre personas.**

Una fracción de las Lucas gastadas en prioridad DEBERÍA **quemarse** (salir de circulación) para compensar la emisión diaria. El porcentaje queda abierto (§12).

## 5. Puntaje de contribución

### 5.1 Base: diversidad (Proof of Relay §8)

```text
base(R) = Σ  peso(c) · f( n_c )
          c ∈ identidades distintas con las que R trabajó en el período
```

- `peso(c)` crece con la antigüedad de `c` y con su historial de recibos honestos con terceros. Una identidad nueva pesa ≈ 0.
- `f(n) = 1 + log2(n)`: la repetición rinde cada vez menos.
- Mil caminos con los mismos disfraces cuentan como uno.

### 5.2 Escasez: donde faltan caminos se paga más

Para cada salto que `R` realizó, se estima cuántos **caminos alternativos** existían: cuántas otras identidades distintas, con antigüedad suficiente, sirvieron esa misma zona o ese mismo tramo en el período.

```text
escasez(salto) = clamp( k / (1 + alternativas), PISO, TECHO )

PISO  = 0,5   (en la ciudad más densa, el aporte nunca vale cero)
TECHO = 3,0   (en el lugar más aislado, el aporte no se dispara al infinito)
k     = constante de calibración (se fija con el simulador)
```

```text
puntaje(R) = Σ  base_salto · escasez(salto)
             saltos de R
```

- **Más caminos, menos paga; menos caminos, más paga.** El piso y el techo mantienen el equilibrio.
- Así, un nodo que conecta una zona aislada, como un pueblito en la estepa fueguina, puede tener más puntaje que uno en el centro de una gran ciudad.
- Además, empuja a instalar nodos donde **no hay cobertura**.

### 5.3 Reparto por entrega

La recompensa de una entrega se **reparte** entre los relays del camino. No se paga entera a cada uno. Alargar un camino con saltos inútiles no aumenta el total, solo lo divide (Proof of Relay §10.2).

## 6. Prioridad y carril gratis

### 6.1 Prioridad paga

- Un origen PUEDE adjuntar una oferta de Lucas para que su sobre tenga prioridad. El campo TLV de origen se registrará en Packet Format.
- Los relays que lo transportan cobran esa oferta, repartida según §5.3 y liquidada por la libreta al presentar el Proof of Relay.

### 6.2 Carril gratis garantizado

- Cada relay DEBE reservar al menos **30 %** de su capacidad (ancho de banda y cola) para tráfico **sin** prioridad paga. El valor final se calibra con el simulador.
- La prioridad hace llegar antes, pero NUNCA deja afuera a quien no paga.
- Si el carril prioritario no se usa, el carril gratis PUEDE ocupar toda la capacidad.

## 7. Pueblos

### 7.1 Qué es un pueblo

Una unidad geográfica con su propia libreta y su propia Corte. La forma de delimitarlo queda abierta (§12). Las alternativas son una cuadrícula fija, como las celdas de ICEBREAK, o los límites administrativos (municipios).

### 7.2 Pueblo de origen y la corona que no viaja

- El **pueblo de origen** de un nodo es aquel donde acumuló **más puntaje en los últimos 90 días**. No hay que registrarse: si alguien se muda de verdad, su pueblo cambia solo con el tiempo, y un viaje corto no lo cambia.
- Los **títulos valen solo en el pueblo de origen**. Fuera de él, el nodo es pueblo llano.
- El trabajo hecho en otro pueblo se anota en la libreta de **ese** pueblo y suma al ranking **de ese** pueblo. Las Lucas llegan al nodo por la cámara compensadora (§9.3).

### 7.3 Cuentas de operador: muchas antenas, una persona

Una persona PUEDE asociar varios nodos (teléfonos, antenas) a una **cuenta de operador**. Así, quien vive en Ushuaia puede tener antenas en Ushuaia, Mendoza, Córdoba y Santa Fe, y todas suman para la misma cuenta.

- **Asociación bilateral:** la cuenta es un par de claves Ed25519. Cada nodo firma `JOIN { account, node, ts }` con su clave y la cuenta firma la aceptación. Una asociación sin las dos firmas no vale. Para separar un nodo de la cuenta, cualquiera de las dos partes firma un `LEAVE`.
- **Puntaje nacional:** suma el de todos los nodos de la cuenta, cada uno con la escasez de su zona.
- **Puntaje local:** en cada pueblo cuentan solo los nodos de la cuenta que están **en** ese pueblo.
- **Títulos locales:** una cuenta PUEDE tener título local **solo** en su pueblo de origen, que es donde suma más puntaje. La corona no viaja, ni siquiera con muchas antenas.
- **Una cuenta, un asiento:** una cuenta ocupa como máximo **un** lugar en cada Nobleza. Esto impide que un operador con muchas antenas junte más de 2/3 de los sellos.
- **Las antenas propias no se cuentan como diversidad:** a efectos del §5.1, todos los nodos de una cuenta son **una sola identidad**. Una carta que pasa solo por antenas de la misma cuenta no suma diversidad.
- **Incentivo para declararse:** el operador honesto agrupa sus antenas porque así suma puntaje nacional. El tramposo que no las agrupa pierde ese beneficio, y sus antenas siguen sujetas a las reglas de antigüedad y diversidad.

## 8. La Corte

### 8.1 Títulos

| Título | Ámbito | Quién | Cantidad inicial |
|---|---|---|---|
| 👑 **Rey local** | Pueblo | Mayor puntaje del período entre los residentes del pueblo | 1 |
| 🎩 **Nobleza local** | Pueblo | Los siguientes en el ranking del pueblo | 20 |
| 👑 **Rey nacional** | País | Mayor puntaje nacional, sin importar dónde viva | 1 |
| 🎩 **Nobleza nacional** | País | Los siguientes en el ranking nacional | 20 |
| 🏘️ **Pueblo** | — | Todos los contribuyentes | — |

- **No hay corona fija:** el Rey nacional puede vivir en Tierra del Fuego. Lo decide solo el puntaje, que la escasez (§5.2) nivela para que vivir lejos no sea una desventaja.
- Una misma identidad PUEDE tener títulos locales y nacionales a la vez.

### 8.2 Reglas de la monarquía

1. **Nadie nombra a nadie.** Los títulos salen de una **regla determinista** aplicada a las libretas públicas. Cualquier nodo puede recalcularlos y obtener el mismo resultado.
2. **Los títulos vencen.** Se recalculan cada período y no se heredan. Quien deja de aportar vuelve al pueblo llano.
3. **Los beneficios no multiplican las Lucas.** Un título NO DEBE dar más Lucas por el mismo trabajo, porque eso congelaría la Corte. Beneficios permitidos:
   - prestigio, insignias y voz en propuestas de cambio de parámetros;
   - una cuota de prioridad gratis;
   - una **remuneración fija por el trabajo** de mantener y sellar la libreta.

### 8.3 Funciones

- **Rey:** arma y firma las **páginas** nuevas de la libreta (§9).
- **Nobleza:** verifica cada página; una página es válida solo con la firma de **más de 2/3** de la Nobleza.
- **Destronamiento:** si más de 2/3 de la Nobleza firma una declaración de destitución, por ejemplo porque el Rey firmó una página inválida o dejó de producirlas, el Rey pierde el título y lo asume el primer noble del ranking hasta el próximo período.
- **Partición:** si un pueblo se divide, la mitad que no reúna más de 2/3 de la Nobleza **no puede** validar páginas. Se evita tener dos libretas en conflicto; la parte aislada sigue funcionando en la planta baja (protocolo) y sus recibos se anotan cuando se reconecta.

## 9. Las libretas

### 9.1 Libreta local

- Una secuencia de **páginas** encadenadas por hash. Cada página contiene:
  - los Proof of Relay presentados;
  - los movimientos de Lucas: reparto diario, gastos, pagos y quemas;
  - el hash de la página anterior.
- Cada página va firmada por el Rey y por más de 2/3 de la Nobleza local.
- La libreta es **pública**: cualquiera puede auditarla y recalcular saldos, puntajes y títulos.

### 9.2 Libreta nacional

- Registra el presupuesto `E_nación`, su asignación a los pueblos y la compensación entre pueblos.
- Sus páginas las firman el Rey nacional y más de 2/3 de la Nobleza nacional.

### 9.3 Cámara compensadora

Cuando una carta cruza pueblos (Ana en Mendoza, Caro de Junín que la lleva):

1. Cada libreta local anota el trabajo hecho **en** su pueblo.
2. Periódicamente, la libreta nacional **neta** los saldos entre pueblos: "Mendoza debe 500 a Junín, Junín debe 300 a Mendoza, entonces Mendoza transfiere 200".
3. Como las Lucas son las mismas en todo el país, no hay tipos de cambio.

## 10. Arranque (génesis)

Al principio no hay recibos, así que no hay ranking.

- El **fundador** opera como Rey y Nobleza provisorios de la libreta nacional y de cada pueblo nuevo.
- Desde el primer período con al menos **21 contribuyentes con puntaje > 0**, la Corte se elige por la regla del §8 y el fundador pasa a ser un nodo más.
- La regla de transición DEBE estar escrita en la página génesis, para que nadie pueda postergarla.

## 11. Fraude: qué cubre y qué no

| Amenaza | Defensa |
|---|---|
| Inventar o borrar carteros | Cadena de saltos (Proof of Relay §4) |
| Prometer y no firmar | Reportes de incumplimiento (Proof of Relay §5.3) |
| Tráfico entre disfraces propios | Diversidad, antigüedad y rendimiento decreciente (§5.1) |
| Cobrar por solo estar disponible | Solo paga el Proof of Relay (§4.2) |
| Alargar caminos | Reparto por entrega (§5.3) |
| Rey tramposo | Más de 2/3 de la Nobleza no sella y lo destrona (§8.3) |
| Nobleza capturada (más de 2/3 en manos de un mismo operador) | **Abierto.** Lo mitigan la diversidad, la antigüedad y la transparencia, pero no lo resuelven. |
| Fabricar escasez (nodos que se apagan para que la zona "parezca" aislada) | **Abierto.** El techo de 3× acota la ganancia. |
| Fabricar y envejecer muchas identidades | **Abierto.** Se necesita un costo de creación (§12). |
| Un operador con mucho capital compra antenas en todo el país | Es aporte real y se premia. "Una cuenta, un asiento" (§7.3) impide que capture la Nobleza. Si conviene un rendimiento decreciente por antena extra queda **abierto** (§12). |

## 12. Preguntas abiertas

1. **Parámetros:** `E_nación`, el reparto a los pueblos, `k`, piso y techo de escasez, tamaño de la Nobleza, período, porcentaje del carril gratis y porcentaje de quema. Se calibran con el simulador.
2. **Límites de los pueblos:** ¿cuadrícula fija o límites administrativos?
3. **Costo de identidad:** ¿prueba de trabajo, maduración mínima o aval de nodos existentes?
4. **Consenso formal:** elegir un algoritmo BFT concreto para la firma de páginas por más de 2/3 de la Nobleza.
5. **Varios países:** ¿cómo se compensan Lucas entre cortes nacionales?
6. **Marco legal:** un token con valor económico puede quedar alcanzado por regulación financiera. Se trata en la *Legal & Regulatory Deployment Guide*.
7. **Concentración de antenas:** ¿el puntaje nacional de una cuenta suma lineal o con rendimiento decreciente por cada antena extra?
8. **ICEBREAK:** es un servicio más sobre la red y tiene su propia moneda (ICE). ¿Acepta Lucas, hay cambio entre ICE y Lucas, o no se tocan?
