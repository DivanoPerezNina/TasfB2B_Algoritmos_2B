# ✈️ Tasf.B2B — Motor de Planificación Logística

**Proyecto de Diseño y Desarrollo de Software (1INF54)**  
**Equipo:** 2B | **Semestre:** 2026-1

Sistema de enrutamiento de equipajes (~9.5 M envíos) a través de una red de aeropuertos de América, Asia y Europa. Implementa dos metaheurísticas comparables: **GVNS** (este repositorio) y ALNS (rama `feat/alns`).

> Documentación técnica del algoritmo: [`GVNS_ALGORITHM.md`](GVNS_ALGORITHM.md)

---

## Configuración inicial

**La carpeta `datos/` NO está versionada** (demasiado grande para Git).

1. Crear `datos/` en la raíz del proyecto (al mismo nivel que `src/`).
2. Colocar los archivos del profesor: `aeropuertos.txt`, `vuelos.txt` y el directorio `_envios_preliminar_/`.
3. Compilar y ejecutar:

```bash
javac -cp src src/pe/edu/pucp/tasf/gvns/Main.java
java  -Xmx4g -cp src pe.edu.pucp.tasf.gvns.Main
```

---

## Los 3 escenarios

El enunciado define tres modos de operación (cita §22-25). Se configuran en `Main.java` cambiando la constante `ESCENARIO`:

```java
// En Main.java — cambiar aquí:
private static final Escenario      ESCENARIO     = Escenario.PERIODO_3D;
private static final CriterioOrden  CRITERIO_ORDEN = CriterioOrden.EDF;
private static final int FECHA_INICIO_AAAAMMDD     = 20250818;
```

### Escenario 1 — Tiempo real (`TIEMPO_REAL`)

Procesa los envíos de **1 solo día** a partir de `FECHA_INICIO_AAAAMMDD`. Simula la operación diaria normal.

```
Ventana: [inicioUTC, inicioUTC + 1440)
```

### Escenario 2 — Simulación de período (`PERIODO_3D` / `PERIODO_5D` / `SEMANA`)

Ventana fija de 3, 5 o 7 días. **Debe ejecutarse en 30–90 minutos** según el enunciado.

```
Ventana: [inicioUTC, inicioUTC + N×1440)   N = 3, 5 ó 7
```

### Escenario 3 — Hasta el colapso (`COLAPSO`)

Avanza día a día. Si en un día la tasa de rechazo supera `UMBRAL_COLAPSO` (por defecto 50%), se declara colapso y se detiene.

```
Día 1: ventana [d0, d0+1440) → si tasa < 50% → continúa
Día 2: ventana [d0+1440, d0+2880) → ...
...
Día N: tasa ≥ 50% → COLAPSO DETECTADO
```

### Criterio de orden de envíos (`CRITERIO_ORDEN`)

Determina qué envíos "ganan" los asientos cuando la capacidad de la red es limitada:

| Constante | Comportamiento | Cuándo usar |
|---|---|---|
| `ALEATORIO` | Fisher-Yates con semilla fija (paper §3.4) | Reproducibilidad / comparación |
| `EDF` | Earliest Deadline First — más urgente primero | Minimizar incumplimientos |
| `FIFO` | First In First Out — más antiguo primero | Equidad por orden de llegada |

---

## Arquitectura del motor

El motor usa una **arquitectura lock-free con tipos primitivos** por la escala masiva de datos.

| Restricción | Razón |
|---|---|
| `int[]`/`long[]` para envíos y vuelos | Con 9.5 M objetos el GC colapsa el heap |
| `ConcurrentHashMap<Long, AtomicInteger>` + CAS | Reservas thread-safe sin `synchronized` en el hot path |
| Minutos UTC absolutos como unidad temporal | Evita 9.5 M allocaciones de `LocalDateTime` |
| `vuelosPorOrigen[][]` como índice de adyacencia | Reduce búsqueda de O(V²) a O(grado²) |

---

## Referencia de clases y métodos

### `GestorDatos.java`

Carga y almacena la red expandida en el tiempo usando arrays primitivos.

| Método | Qué hace |
|---|---|
| `cargarAeropuertos(String ruta)` | Lee `aeropuertos.txt` (UTF-16), asigna IDs 1-based, guarda GMT offset, capacidad y continente. |
| `cargarVuelos(String ruta)` | Lee `vuelos.txt`, convierte horas locales a minutos UTC del día (0–1439), ajusta cruces de medianoche. |
| `cargarTodosLosEnvios(String ruta)` | Carga todos los envíos sin filtro de tiempo. |
| `cargarTodosLosEnvios(String ruta, long inicioUTC, long finUTC)` | Carga solo envíos con `registroUTC ∈ [inicioUTC, finUTC)`. Usado por los escenarios con ventana de tiempo. |
| `resetEnvios()` | Pone `numEnvios = 0` para reutilizar los arrays en el escenario COLAPSO. |
| `calcularEpochMinutos(int a, int m, int d, int h, int min, int gmt)` | Convierte fecha/hora local a minutos UTC absolutos usando aritmética de enteros (Número de Día Juliano). Sin `LocalDateTime`. |

**Arrays principales:**

| Array | Contenido |
|---|---|
| `envioOrigen[e]` | ID aeropuerto de origen del envío `e` |
| `envioDestino[e]` | ID aeropuerto de destino |
| `envioMaletas[e]` | Número de maletas |
| `envioRegistroUTC[e]` | Minuto UTC absoluto de disponibilidad |
| `envioDeadlineUTC[e]` | Minuto UTC absoluto límite (mismo continente +1440, distinto +2880) |
| `vueloOrigen[v]`, `vueloDestino[v]` | IDs de aeropuertos del vuelo `v` |
| `vueloSalidaUTC[v]`, `vueloLlegadaUTC[v]` | Minutos del día UTC (0–1439) |
| `vueloCapacidad[v]` | Maletas máximas por vuelo |

---

### `PlanificadorGVNSConcurrente.java`

Motor principal. Contiene la Fase 2 (construcción) y la Fase 3 (GVNS).

**Constructores:**

| Constructor | Comportamiento |
|---|---|
| `PlanificadorGVNSConcurrente(datos)` | Semilla 12345, criterio ALEATORIO. |
| `PlanificadorGVNSConcurrente(datos, semilla)` | Semilla explícita, criterio ALEATORIO. |
| `PlanificadorGVNSConcurrente(datos, semilla, criterio)` | Control total. Recomendado. |

**Métodos públicos:**

| Método | Qué hace |
|---|---|
| `construirSolucionInicial()` | **Fase 2.** Ordena los envíos según `CRITERIO_ORDEN`, luego los procesa en paralelo (`IntStream.parallel`). Para cada envío busca la primera ruta factible (directo / 1 escala / 2 escalas) y reserva capacidad atómicamente. |
| `ejecutarMejoraGVNS()` | **Fase 3.** Loop VNS hasta el tiempo límite (120 s). Cada iteración: Shaking (expulsa los `k×BATCH` envíos de mayor tránsito) → VND N1 (re-insertar con mínimo tránsito) → VND N2 (Exchange). Acepta si mejora `f(x) = rechazados×2880 + tránsito_total`. |
| `calcularTransitoTotal()` | Suma `(llegada_destino − registro)` de todos los envíos asignados. Métrica de calidad: menor es mejor. |
| `replanificarVueloCancelado(int vueloId)` | Marca el vuelo como inoperable (capacidad=0), libera todos los envíos afectados y los re-enruta. |
| `exportarResultadosCSV(...)` | Escribe métricas de la ejecución en un archivo CSV. |

**Métodos privados clave:**

| Método | Qué hace |
|---|---|
| `generarOrden(n)` | Devuelve el array de índices en el orden del criterio activo (Fisher-Yates / EDF / FIFO). |
| `buscarRutaGreedy(...)` | **Fase 2.** Retorna la primera ruta factible: Nivel 1 (directo) → Nivel 2 (1 escala) → Nivel 3 (2 escalas). |
| `buscarMejorRuta(...)` | **Fase 3 / VND N1.** Escanea todos los vuelos directos y elige el de menor tránsito. Usa índice de adyacencia. |
| `buscarRutaDFS(...)` | **Fase 3 / VND N2.** DFS con backtracking completo. Más lento pero encuentra rutas que el greedy pierde. |
| `ejecutarShaking(n, ...)` | Muestrea `n×50` candidatos y expulsa los `n` con mayor tránsito individual. |
| `revertirShaking(...)` | Si la iteración GVNS no mejoró, restaura los envíos expulsados a su estado anterior. |
| `intentarReservarEspacio(v, sal, maletas)` | CAS loop atómico sobre `ocupacionVuelos`. Retorna `false` si la capacidad del vuelo está agotada. |
| `liberarEspacio(v, sal, maletas)` | Decrementa la ocupación del vuelo (rollback de reserva). |
| `calcularMinutoSalidaReal(minActual, salidaUTC)` | Calcula el próximo horario de salida del vuelo dado el minuto actual (si ya pasó hoy, usa mañana). |
| `duracionVuelo(v)` | Duración del vuelo en minutos, corrigiendo cruces de medianoche. |
| `claveVueloDia(v, salidaAbs)` | Clave única `v × 100000 + día` para identificar un vuelo en un día específico en el mapa de ocupación. |
| `transitoEnvio(e)` | Tránsito individual del envío `e` (llegada − registro). |

---

### `AnalizadorRed.java`

Análisis estático de la red y comparación de fases. Todos los métodos son estáticos.

| Método | Qué hace |
|---|---|
| `analizarCobertura(GestorDatos datos)` | Imprime estadísticas de la red: aeropuertos con/sin vuelos, pares origen-destino cubiertos. |
| `analizarSolucion(GestorDatos datos, int[][] solucionVuelos)` | Distribución de tramos en la solución (% directos, % 1 escala, % 2 escalas). |
| `compararFases(long fGreedy, long fGVNS, int salvados)` | Tabla comparativa Fase 2 vs Fase 3: mejora absoluta y porcentual del tránsito. |

---

### `AuditorRutas.java`

Verificación matemática independiente de la solución. No usa el mapa de ocupación del planificador — reconstruye la ocupación desde `solucionVuelos[]` para detectar race conditions.

| Regla | Condición verificada |
|---|---|
| a) Continuidad espacial | `vueloDestino[Vᵢ] == vueloOrigen[Vᵢ₊₁]` |
| b) Viabilidad temporal | `llegada(Vᵢ) + 10 min ≤ salida(Vᵢ₊₁)` |
| c) Deadline | `llegada(V_final) + 10 min ≤ envioDeadlineUTC[e]` |
| d) Destino correcto | `vueloDestino[último tramo] == envioDestino[e]` |
| e) Capacidad (rebuild) | `Σ maletas por (vuelo, día) ≤ vueloCapacidad[v]` |

---

### `ExportadorVisual.java`

| Método | Qué hace |
|---|---|
| `exportarJSON(archivo, plan, datos, ...)` | Exporta muestras de rutas (primeros 100 exitosos de Fase 2 + 10 salvados por GVNS) a JSON para visualización en frontend. |

---

### `Escenario.java`

Enum con los 3 escenarios del enunciado. Cada valor define `nombre` (legible) y `diasVentana` (tamaño de la ventana; `-1` = incremental).

### `CriterioOrden.java`

Enum con los criterios de ordenamiento de envíos: `ALEATORIO` (Fisher-Yates), `EDF` (menor deadline primero), `FIFO` (registro más antiguo primero).

---

## Parámetros configurables

| Parámetro | Clase | Valor por defecto | Descripción |
|---|---|---|---|
| `ESCENARIO` | `Main` | `PERIODO_3D` | Escenario activo |
| `CRITERIO_ORDEN` | `Main` | `EDF` | Orden de procesamiento de envíos |
| `FECHA_INICIO_AAAAMMDD` | `Main` | `20250818` | Fecha de inicio (aaaammdd) |
| `UMBRAL_COLAPSO` | `Main` | `0.50` | Tasa de rechazo que declara colapso |
| `SEMILLA` | `Main` | `12345L` | Semilla (solo para ALEATORIO) |
| `TIEMPO_LIMITE_MS` | `PlanificadorGVNSConcurrente` | `120000` | Tiempo máximo Fase 3 (ms) |
| `K_MAX` | `PlanificadorGVNSConcurrente` | `3` | Vecindades en el VNS |
| `BATCH_FACTOR` | `PlanificadorGVNSConcurrente` | `20` | Base de envíos expulsados por Shaking |
| `TIEMPO_MINIMO_ESCALA` | `PlanificadorGVNSConcurrente` | `10` | Tiempo mínimo entre llegada y siguiente salida (min) |
| `MAX_SALTOS` | `PlanificadorGVNSConcurrente` | `3` | Máximo de tramos por ruta |

> **Nota sobre `PENALIZACION = 2880`:** este valor no es configurable. Debe ser ≥ al tránsito máximo posible de un envío. El máximo tránsito es el deadline de distinto continente (48 h = 2880 min), por lo que la penalización está fijada a ese valor para garantizar el ordenamiento lexicográfico `rechazados ≻ tránsito`.

---

## Salidas generadas

| Archivo | Contenido |
|---|---|
| `resultados_<escenario>.csv` | Métricas: total envíos, exitosos, rechazados, tránsito Fase2/3, tiempos de CPU, tasa de éxito |
| `visualizacion_<escenario>.json` | Muestras de rutas para el frontend de visualización |

Ambos archivos están ignorados por `.gitignore`.

---

## Flujo de trabajo Git

| Rama | Propósito |
|---|---|
| `main` | Estable. GVNS completo y validado. |
| `feat/alns` | Algoritmo ALNS (equipo 2). |
| `exp/comparacion` | Fusión de ambos algoritmos para experimentación numérica. |

> **No modificar** `PlanificadorGVNSConcurrente.java` salvo mejoras de calidad.  
> El algoritmo está validado por `AuditorRutas`. Ver `GVNS_ALGORITHM.md` §7 para restricciones de arquitectura y TODOs de integración con otros backends.
