# ✈️ Tasf.B2B - Motor de Planificación Logística

**Proyecto de Diseño y Desarrollo de Software (1INF54)**
**Equipo:** 2B

Este repositorio contiene el código fuente del componente planificador logístico para Tasf.B2B. El sistema enruta equipajes (aprox. 9.5 millones de envíos) a través de una red internacional de aeropuertos respetando capacidades dinámicas, husos horarios y ventanas de tiempo (*Time-Expanded Network*).

---

## 🛠️ Instrucciones de Configuración Inicial (Para el Equipo)

**¡ATENCIÓN! La carpeta de datos NO está versionada en Git debido a su volumen.**

Para que el proyecto compile y ejecute correctamente en tu máquina local:
1. Crea una carpeta llamada `datos/` en el directorio raíz del proyecto (al mismo nivel que `src/`).
2. Descarga los archivos de prueba (`aeropuertos.txt`, `vuelos.txt` y los archivos de `_envios_XXX_.txt`) provistos por el profesor.
3. Coloca estos archivos dentro de tu carpeta local `datos/`.

---

## 🏛️ Arquitectura del Motor (Contexto para IA / Developers)

Debido a la escala masiva de los datos, el motor está construido con una **Arquitectura Lock-Free de Alto Rendimiento basada en Tipos Primitivos**.
* **NO usar Colecciones de Objetos:** Se prohíbe el uso de `ArrayList<Envio>` para el modelo de datos principal para evitar el colapso de la memoria *Heap* (*Out Of Memory*).
* **Control de Capacidad:** Se utiliza `ConcurrentHashMap<Long, AtomicInteger>` para permitir que múltiples hilos reserven asientos simultáneamente sin bloqueos (*Deadlocks*).
* **Reloj Maestro:** Todas las horas locales se convierten y operan en "Minutos Absolutos UTC" ($O(1)$) para simplificar las validaciones de las ventanas de tiempo.

### Componentes Principales:
* `GestorDatos.java`: (Rama `main`) Encargado de la lectura hiper-rápida y el parseo a memoria primitiva.
* `PlanificadorGVNSConcurrente.java`: (Rama `feat/gvns`) Implementación de la Solución Inicial Golosa (DFS Multihilo) y el algoritmo GVNS para la rama del equipo 1.
* `PlanificadorALNS.java`: (Rama `feat/alns`) (Pendiente) Implementación de las heurísticas de destrucción y reconstrucción para la rama del equipo 2.

---

## 🌿 Flujo de Trabajo Git (GitFlow)

Estamos utilizando una estrategia de ramas aislada para evitar conflictos durante el desarrollo de las metaheurísticas:

* **`main`**: Rama estable. Solo contiene la base compartida (`GestorDatos` y modelo base).
* **`feat/gvns`**: Rama para el desarrollo exclusivo del algoritmo GVNS.
* **`feat/alns`**: Rama para el desarrollo exclusivo del algoritmo ALNS.
* **`exp/comparacion`**: Rama de convergencia donde fusionaremos ambos algoritmos para realizar la Experimentación Numérica (comparación de tiempos de CPU y tasas de éxito).

**Regla de Oro:** ¡Nunca hagas commit directamente en `main` si estás tocando lógica de los algoritmos!