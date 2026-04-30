# ALNS para Simulación Logística Tasf.B2B

## Descripción

Este programa implementa el algoritmo ALNS (Adaptive Large Neighborhood Search) para la simulación logística del proyecto Tasf.B2B. Es un motor experimental en consola que ejecuta ALNS sobre datos reales de envíos, aeropuertos y vuelos, exportando métricas comparables con el algoritmo GVNS existente.

## Diferencias con GVNS

- **GVNS**: Utiliza búsqueda de vecindad variable para optimización estática.
- **ALNS**: Emplea destroy/repair operators adaptativos para optimización dinámica en tiempo real, permitiendo replanificación continua durante la simulación.

## Cómo Ejecutar

1. Asegurarse de tener Java 17+ instalado.
2. Compilar: `javac -cp src src/pe/edu/pucp/tasf/alns/MainALNS.java`
3. Ejecutar: `java -cp src pe.edu.pucp/tasf.alns.MainALNS`

## Archivos de Salida

- `resultados_alns_resumen.csv`: Resumen por escenario (3D, 5D, 7D).
- `resultados_alns_bloques.csv`: Detalles por bloque de planificación.
- `convergencia_alns_3d.csv`, `convergencia_alns_5d.csv`, `convergencia_alns_7d.csv`: Historial de convergencia del ALNS.

## Métricas Comparables

- Envíos procesados y entregados.
- Tiempos de simulación.
- Fitness basado en penalizaciones por incumplimientos.
- Memoria utilizada.
- Número de replanificaciones.

Este código respeta las restricciones de memoria y utiliza streaming para procesar envíos sin cargar objetos masivos.