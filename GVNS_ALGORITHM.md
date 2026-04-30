# GVNS Backend — Guía Técnica del Algoritmo

**Proyecto:** Tasf.B2B — Motor de Planificación Logística de Equipajes  
**Algoritmo:** Variable Neighborhood Search (VNS) — Polat, Kalayci & Topaloglu (2026)  
**Paper base:** *"An enhanced variable neighborhood search algorithm for rich multi-compartment vehicle routing problems"*, Soft Computing 30, 327–352.  
**Equipo:** 2B — Curso 1INF54

---

## 1. Qué hace este proyecto

Este backend Java enruta ~9.5 millones de envíos de equipaje a través de una red de aeropuertos internacionales. Para cada envío (maletas de un pasajero), el sistema encuentra la mejor secuencia de vuelos que lleve las maletas desde su aeropuerto de origen hasta el destino **dentro de una ventana de tiempo** y **respetando la capacidad de cada vuelo**.

El resultado es una matriz de asignación: `envío → secuencia de vuelos`, que luego puede exportarse para una simulación visual o procesarse en otro sistema.

### Analogía con el paper

El paper resuelve el MCVRP (Multi-Compartment Vehicle Routing Problem). La adaptación al problema de equipajes es:

| Concepto MCVRP (paper) | Equivalente en equipajes       |
|------------------------|-------------------------------|
| Cliente / pedido       | Envío (origen, destino, maletas, deadline) |
| Vehículo               | Vuelo (capacidad en maletas)   |
| Ruta de vehículo       | Secuencia de vuelos (hasta 3 tramos) |
| Compartimento          | No aplica (1 tipo de carga)   |
| Ventana de tiempo      | Registro UTC → Deadline UTC   |

---

## 2. Estructura del problema

```
Entrada:
  aeropuertos[]   → 50 aeropuertos con zona horaria (GMT offset)
  vuelos[]        → ~5000 vuelos con origen, destino, salida UTC, llegada UTC, capacidad
  envios[]        → 9.5M envíos con origen, destino, maletas, registro UTC, deadline UTC

Salida:
  solucionVuelos[e][s]  → índice del vuelo en el tramo s del envío e  (-1 si no existe)
  solucionDias[e][s]    → minuto UTC absoluto de salida del tramo s
```

Todos los tiempos se manejan como **minutos UTC absolutos** (entero desde el epoch del problema) para evitar conversiones de zona horaria en caliente.

---

## 3. El algoritmo GVNS

El algoritmo opera en dos fases:

### Fase 2 — Construcción de Solución Inicial (§3.4 del paper)

El paper prescribe un proceso de **3 pasos**:

1. **Permutación aleatoria de pedidos** (`generarPermutacionAleatoria`):  
   Se genera un orden aleatorio de todos los envíos usando Fisher-Yates con una semilla fija. Esto evita el sesgo sistemático que ocurriría si siempre se procesaran los envíos en orden 0..N-1 (los IDs bajos siempre "ganarían" los asientos disponibles).

2. **Determinación de clientes a visitar**: en nuestro caso, se procesan todos los envíos en el orden aleatorio.

3. **Asignación de vehículos a rutas** (`buscarRutaGreedy`):  
   Para cada envío en el orden aleatorio, se busca la primera ruta factible evaluando:
   - **Nivel 1**: Vuelo directo (O(V))
   - **Nivel 2**: 1 escala (O(V²))
   - **Nivel 3**: 2 escalas (O(V³))
   
   La capacidad se reserva atómicamente (CAS loop) sin bloqueos. Si no se encuentra ruta, el envío va al **pool de rechazados** (equivalente a los "placeholder vehicles incurring penalties" del paper §3.4).

### Fase 3 — Mejora GVNS (§3.2 del paper)

Implementa el ciclo VNS estándar con tiempo límite de 120 segundos:

```
k ← 1
mientras tiempo_disponible:
    Shaking: expulsar k×BATCH envíos asignados → aumentar espacio de búsqueda
    VND N1 (Relocate): re-insertar rechazados con DFS
    VND N2 (Exchange):  liberar un vuelo lleno temporalmente, reintentar DFS
    si mejora: k ← 1
    si no:     revertir shaking; k ← (k % K_MAX) + 1
```

**Función objetivo:**

```
f(x) = rechazados_activos × 2880 + tránsito_total
```

| Término | Descripción |
|---|---|
| `rechazados_activos` | Envíos en el pool sin ruta (sin contar posiciones `-1` ya salvadas) |
| `tránsito_total` | Suma de `(llegada_destino_UTC − registroUTC)` de todos los envíos asignados |
| `2880` | Penalización = deadline máximo posible (distinto continente, 48 h) |

**Por qué la penalización es 2880:** garantiza que cualquier reducción de rechazados mejora `f`, sin importar el tránsito. Sin ella, si la Fase 2 ruteaba el 100 % de envíos (`rechazados = 0 → f = 0`), el GVNS arrancaba desde `f = 0` y rechazaba cualquier movimiento —incluso uno que redujera el tránsito— porque todo movimiento sube `f` desde cero. La penalización hace que `f` nunca empiece en 0 salvo cuando no hay envíos.

---

## 4. Clases principales

### `GestorDatos.java`
Carga los datos desde archivos de texto plano usando **arrays primitivos** (`int[]`, `long[]`) para evitar el colapso de heap con 9.5M objetos.

| Array | Contenido |
|---|---|
| `envioOrigen[e]` | ID del aeropuerto de origen del envío `e` |
| `envioDestino[e]` | ID del aeropuerto de destino |
| `envioMaletas[e]` | Número de maletas |
| `envioRegistroUTC[e]` | Minuto UTC absoluto de disponibilidad |
| `envioDeadlineUTC[e]` | Minuto UTC absoluto límite de entrega |
| `vueloOrigen[v]`, `vueloDestino[v]` | IDs de aeropuertos del vuelo `v` |
| `vueloSalidaUTC[v]`, `vueloLlegadaUTC[v]` | Minutos del día UTC (0–1439) |
| `vueloCapacidad[v]` | Maletas máximas por vuelo |

### `PlanificadorGVNSConcurrente.java`
Motor principal. Contiene:
- `construirSolucionInicial()` — Fase 2 (permutación aleatoria + greedy paralelo)
- `ejecutarMejoraGVNS()` — Fase 3 (VNS loop)
- `ocupacionVuelos` — `ConcurrentHashMap<Long, AtomicInteger>` para reservas lock-free

### `AuditorRutas.java`
Verifica matemáticamente la validez de la solución: conectividad de tramos, ventanas de tiempo, capacidades.

### `ExportadorVisual.java`
Exporta un JSON con muestras de rutas exitosas y salvadas. Es el único punto de salida de datos actualmente.

### `Main.java`
Orquesta las fases: carga datos → Fase 2 → Fase 3 → auditoría → exportar CSV + JSON.

---

## 5. Parámetros configurables

| Parámetro | Valor actual | Descripción |
|---|---|---|
| `semilla` | `12345L` | Semilla para la permutación inicial. Cambiar para explorar distintas soluciones. |
| `MAX_SALTOS` | `3` | Máximo de tramos por ruta (directo / 1 escala / 2 escalas). |
| `TIEMPO_MINIMO_ESCALA` | `10 min` | Tiempo mínimo entre llegada y siguiente salida en una escala. |
| `TIEMPO_LIMITE_MS` | `120 000 ms` | Tiempo máximo de la Fase 3 (GVNS). |
| `K_MAX` | `3` | Número de estructuras de vecindad en el VNS. |
| `BATCH_FACTOR` | `20` | Envíos expulsados por iteración de shaking en k=1. |

---

## 6. Cómo ejecutar

```bash
# Compilar (desde la raíz del proyecto)
javac -cp src src/pe/edu/pucp/tasf/gvns/Main.java

# Ejecutar (requiere carpeta datos/ con aeropuertos.txt, vuelos.txt, _envios_preliminar_/)
java -cp src pe.edu.pucp.tasf.gvns.Main
```

**Salidas generadas:**
- `resultados_escenario_concurrente.csv` — métricas de la ejecución
- `visualizacion_casos.json` — muestras de rutas para visualización

---

## 7. Decisiones de arquitectura no negociables

- **Arrays primitivos** (`int[]`, `long[]`) para datos de envíos y vuelos. **Nunca** usar `ArrayList<Envio>` o `List<Vuelo>` para el dataset principal: con 9.5M objetos, el GC colapsa el heap.
- **CAS loop** (`compareAndSet`) para reservar capacidad en vuelos. Sin `synchronized` en el hot path de Fase 2.
- **Parallel streams** en Fase 2 y VND. El Shaking es secuencial porque necesita snapshot coherente.
- **Minutos UTC absolutos** como unidad temporal universal. No usar `LocalDateTime` en el hot path (9.5M allocaciones).

---

## TODO — Próxima iteración

> **Para la IA que continúe este trabajo:** el algoritmo GVNS está implementado y validado. Las dos tareas pendientes son **integraciones con otros sistemas**. El código del algoritmo en sí **no debe modificarse** salvo mejoras de calidad de solución.

---

### TODO-1: Ingesta de datos desde backend externo (reemplazar carga de archivos)

**Estado actual:** `GestorDatos.java` carga los datos desde archivos `.txt` en disco (`datos/aeropuertos.txt`, `datos/vuelos.txt`, directorio `datos/_envios_preliminar_/`).

**Objetivo:** Reemplazar (o complementar) esa carga con una ingesta desde una API REST o base de datos, ya que en producción los datos vendrán de otro sistema backend.

**Lo que hay que hacer:**

1. **Crear una interfaz `FuenteDatos`** que abstraiga el origen de los datos:
   ```java
   interface FuenteDatos {
       void cargarAeropuertos(GestorDatos destino);
       void cargarVuelos(GestorDatos destino);
       void cargarEnvios(GestorDatos destino, int maxEnvios);
   }
   ```
   Implementaciones: `FuenteArchivos` (actual), `FuenteApiRest` (nueva), `FuenteBaseDatos` (opcional).

2. **`FuenteApiRest`**: consumir endpoints HTTP que devuelvan los datos en JSON/Protobuf. Usar `java.net.http.HttpClient` (Java 11+) o `OkHttp`. Los arrays primitivos deben llenarse igual que hoy; solo cambia *de dónde* se leen.

3. **Consideraciones de escala:** con 9.5M envíos, la API externa debe soportar **streaming/paginación** (no un solo JSON de 500MB). Opciones recomendadas:
   - **HTTP chunked transfer + NDJSON**: cada línea es un envío en JSON, parsear en streaming.
   - **gRPC + Protocol Buffers**: eficiente para datos binarios a escala.
   - **Shared database**: si el otro backend y este comparten BD (PostgreSQL/MySQL), leer directamente con JDBC usando `ResultSet` en modo streaming (`setFetchSize(1000)`).

4. **No cambiar** la estructura interna de `GestorDatos` (arrays primitivos). Solo agregar el mecanismo de llenado alternativo.

**Archivos a crear/modificar:**
- `src/pe/edu/pucp/tasf/gvns/FuenteDatos.java` (nueva interfaz)
- `src/pe/edu/pucp/tasf/gvns/FuenteArchivos.java` (extraer lógica actual de GestorDatos)
- `src/pe/edu/pucp/tasf/gvns/FuenteApiRest.java` (nueva implementación)
- `GestorDatos.java` — refactorizar para recibir un `FuenteDatos` en lugar de leer directamente

---

### TODO-2: Envío de resultados al backend de simulación (tiempo real o batch)

**Estado actual:** `ExportadorVisual.java` escribe un JSON local con muestras de ~100 rutas. Esto **no es suficiente** para una simulación completa en mapa.

**Objetivo:** Enviar el resultado completo de la simulación (todas las rutas asignadas) a un backend de visualización que renderice el movimiento de maletas en un mapa de aeropuertos en tiempo real.

**Hay dos enfoques posibles; elegir según la arquitectura del frontend:**

#### Opción A — Envío batch al finalizar (más simple)

Al terminar las Fases 2 y 3, serializar toda la solución y hacer un `HTTP POST` al backend de visualización.

```
POST /api/simulacion/resultado
Content-Type: application/json

{
  "semilla": 12345,
  "totalEnvios": 9500000,
  "rutas": [
    { "envio": 0, "tramos": [{"vuelo": 42, "salidaUTC": 720}, ...] },
    ...
  ]
}
```

- La solución completa son ~9.5M entradas; comprimir con **gzip** antes de enviar.
- El backend receptor puede procesarla y almacenarla para que el frontend la consuma por páginas.
- Implementar en una clase `ExportadorApiRest.java` que reuse la lógica de `ExportadorVisual`.

#### Opción B — Streaming en tiempo real durante la Fase 2 (más complejo, mejor UX)

Emitir cada ruta asignada en el momento en que se resuelve, para que la simulación "arranque" mientras el algoritmo todavía corre.

```
WebSocket / Server-Sent Events:
  ws://backend-simulacion/stream/ruteo
  → { "envio": 1234, "tramos": [...] }   (cuando se asigna)
  → { "envio": 5678, "tramos": [...] }   (cuando se asigna)
  ...
```

- En `construirSolucionInicial()`, dentro del `forEach` paralelo, llamar a un `EmisoreventoRuteo.emitir(e, ruta)` después de cada asignación exitosa.
- `EmisorEventoRuteo` debe ser thread-safe (el forEach es paralelo). Usar una `BlockingQueue<RutaEvento>` con un hilo dedicado que consuma y envíe por WebSocket.
- Librería recomendada: `Java-WebSocket` (org.java-websocket) o integrarse en un framework como **Spring Boot** con `@SendTo` de STOMP/SockJS.

**Archivos a crear:**
- `src/pe/edu/pucp/tasf/gvns/ExportadorApiRest.java` (Opción A)
- `src/pe/edu/pucp/tasf/gvns/EmisorEventoRuteo.java` (Opción B — productor de eventos)
- `src/pe/edu/pucp/tasf/gvns/ConectorWebSocket.java` (Opción B — cliente WS)

**Formato de datos para el mapa:**
El frontend necesita, por cada evento de ruteo:
```json
{
  "envioId": 1234,
  "maletas": 3,
  "tramos": [
    {
      "vueloId": 42,
      "origen": "LIM",
      "destino": "MIA",
      "salidaUTC": 720,
      "llegadaUTC": 960
    }
  ]
}
```
Los campos `"origen"` y `"destino"` en IATA requieren la lookup `iataAeropuerto[id]` de `GestorDatos`.

---

### Notas para la próxima IA

- **No tocar** `PlanificadorGVNSConcurrente.java` más allá de los TODO. El algoritmo es correcto y validado.
- La **semilla** en el constructor es el único parámetro que cambia la solución sin modificar código: `new PlanificadorGVNSConcurrente(datos, 99999L)` da otra solución válida distinta.
- Los archivos `*.class` en `src/pe/edu/pucp/tasf/gvns/` son artefactos de compilación local; no pertenecen al repo (deberían estar en `.gitignore`).
- El proyecto no tiene build tool (Maven/Gradle). Si se añaden dependencias externas (OkHttp, Java-WebSocket, etc.), **agregar un `pom.xml` o `build.gradle`** es el primer paso.
- Para Spring Boot (integración recomendada para Opción B): crear un proyecto Spring Boot separado que importe el motor GVNS como librería JAR, o integrar las clases directamente en el contexto Spring.
