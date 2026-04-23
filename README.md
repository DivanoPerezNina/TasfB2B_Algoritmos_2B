# ✈️ Tasf.B2B - Motor de Planificación Logística

**Proyecto de Diseño y Desarrollo de Software (1INF54)**
**Equipo:** 2B

Este repositorio contiene el código fuente del componente planificador logístico para Tasf.B2B. El sistema enruta equipajes (~9.5 millones de envíos) a través de una red internacional de aeropuertos respetando capacidades dinámicas, husos horarios y ventanas de tiempo. El algoritmo de optimización implementado es **GVNS** (Polat, Kalayci & Topaloglu, 2026), minimizando el tránsito total de todos los envíos.

> Para la documentación técnica completa del algoritmo (pseudocódigo, parámetros, TODOs de integración), ver [`GVNS_ALGORITHM.md`](GVNS_ALGORITHM.md).

---

## 🛠️ Instrucciones de Configuración Inicial

**¡ATENCIÓN! La carpeta de datos NO está versionada en Git debido a su volumen.**

1. Crea una carpeta `datos/` en el directorio raíz (al mismo nivel que `src/`).
2. Coloca los archivos del profesor: `aeropuertos.txt`, `vuelos.txt` y los archivos de `_envios_preliminar_/`.
3. Compila y ejecuta:

```bash
javac -cp src src/pe/edu/pucp/tasf/gvns/Main.java
java  -Xmx4g -cp src pe.edu.pucp.tasf.gvns.Main
```

**Salidas generadas** (ignoradas por `.gitignore`):
- `resultados_escenario_concurrente.csv` — métricas de la ejecución
- `visualizacion_casos.json` — muestras de rutas para visualización

---

## 🏛️ Arquitectura del Motor

Debido a la escala masiva de los datos, el motor está construido con una **arquitectura lock-free de alto rendimiento basada en tipos primitivos**.

| Restricción | Razón |
|---|---|
| `int[]`/`long[]` para envíos y vuelos, nunca `ArrayList<Envio>` | Con 9.5M objetos el GC colapsa el heap |
| `ConcurrentHashMap<Long, AtomicInteger>` + CAS para capacidad | Reservas thread-safe sin `synchronized` en el hot path |
| Minutos UTC absolutos como unidad temporal | Evita 9.5M allocaciones de `LocalDateTime` |
| `vuelosPorOrigen[][]` como índice de adyacencia | Reduce búsqueda de ruta de O(V²) a O(grado²) |

### Componentes Principales

| Clase | Responsabilidad |
|---|---|
| `GestorDatos.java` | Carga hiper-rápida desde archivos `.txt` a arrays primitivos |
| `PlanificadorGVNSConcurrente.java` | Motor GVNS: Fase 2 (solución inicial paralela) + Fase 3 (VNS de tránsito) |
| `AnalizadorRed.java` | Análisis estático de la red y comparación de fases (cobertura, distribución de tramos) |
| `AuditorRutas.java` | Verificación matemática de la solución: conectividad, ventanas de tiempo, capacidades |
| `ExportadorVisual.java` | Exporta muestras de rutas a JSON para visualización |
| `Main.java` | Orquesta las fases: carga → Fase 2 → Fase 3 → auditoría → CSV + JSON |

### Flujo de Ejecución

```
Fase 2 — Solución inicial
  Fisher-Yates shuffle (semilla fija) → orden aleatorio de envíos
  IntStream.parallel() → buscarMejorRuta por cada envío
    Nivel 1: vuelo directo O(grado)      ← 99.8% de los envíos
    Nivel 2: 1 escala    O(grado²)
    Nivel 3: 2 escalas   O(grado³)

Fase 3 — GVNS (120 s)
  f(x) = Σ tránsito_individual (minimizar)
  k ← 1
  mientras tiempo disponible:
    Shaking: expulsar k×BATCH envíos de mayor tránsito
    VND N1 (Relocate): reinsertar con buscarMejorRuta
    VND N2 (Exchange): liberar vuelo lleno, reintentar
    si mejora: k ← 1
    si no:     revertir; k ← (k % K_MAX) + 1
```

### Replanificación por Cancelación de Vuelo

`PlanificadorGVNSConcurrente.replanificarVueloCancelado(int vueloId)` permite simular la cancelación de un vuelo en tiempo real:
1. Marca el vuelo como sin capacidad (`tablero.vueloCapacidad[vueloId] = 0`).
2. Libera todos los envíos que usaban ese vuelo.
3. Los re-enruta automáticamente a alternativas disponibles.

Para activar la simulación, descomentar el bloque en `Main.java` marcado con `[OPCIONAL] SIMULACIÓN DE CANCELACIÓN DE VUELO`.

---

## 🌿 Flujo de Trabajo Git

* **`main`**: Rama estable con la implementación GVNS completa y validada.
* **`feat/alns`**: Rama para el desarrollo del algoritmo ALNS (equipo 2).
* **`exp/comparacion`**: Rama de convergencia para comparación numérica GVNS vs ALNS.

> [!IMPORTANT]
> No modificar `PlanificadorGVNSConcurrente.java` salvo mejoras de calidad de solución. El algoritmo está validado matemáticamente por `AuditorRutas`. Ver `GVNS_ALGORITHM.md` §7 para restricciones de arquitectura.