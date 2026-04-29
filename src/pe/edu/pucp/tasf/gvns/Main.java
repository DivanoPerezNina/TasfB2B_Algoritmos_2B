package pe.edu.pucp.tasf.gvns;

/**
 * Main — Punto de entrada del motor de planificación logística Tasf.B2B.
 *
 * <h2>Cómo configurar el escenario</h2>
 * Cambiar las constantes de la sección "PARÁMETROS" para elegir el escenario
 * y el criterio de orden sin modificar ninguna otra clase.
 *
 * <h2>Escenarios disponibles ({@link Escenario})</h2>
 * <ul>
 *   <li>TIEMPO_REAL  — Procesa 1 día. Útil para pruebas rápidas.</li>
 *   <li>PERIODO_3D   — Ventana de 3 días (enunciado: 30–90 min de ejecución).</li>
 *   <li>PERIODO_5D   — Ventana de 5 días.</li>
 *   <li>SEMANA       — Ventana de 7 días.</li>
 *   <li>COLAPSO      — Avanza día a día hasta saturar la red.</li>
 * </ul>
 */
public class Main {

    // =========================================================================
    // PARÁMETROS — Modificar aquí para cambiar el comportamiento
    // =========================================================================

    /** Escenario activo. Ver {@link Escenario} para las opciones. */
    private static final Escenario ESCENARIO = Escenario.PERIODO_3D;

    /** Criterio de ordenamiento de envíos en la Fase 2. Ver {@link CriterioOrden}. */
    private static final CriterioOrden CRITERIO_ORDEN = CriterioOrden.EDF;

    /** Fecha de inicio de la simulación en formato aaaammdd (ISO básico). */
    private static final int FECHA_INICIO_AAAAMMDD = 20260818;

    /**
     * Tasa de rechazo (0.0–1.0) que define el colapso operacional.
     * Si en un día más del UMBRAL_COLAPSO de envíos son rechazados, se para.
     */
    private static final double UMBRAL_COLAPSO = 0.50;

    /** Semilla para la permutación (solo actúa con CriterioOrden.ALEATORIO). */
    private static final long SEMILLA = 12345L;

    private static final String RUTA_AEROPUERTOS = "datos/aeropuertos.txt";
    private static final String RUTA_VUELOS      = "datos/vuelos.txt";
    private static final String RUTA_ENVIOS      = "datos/_envios_preliminar_";

    // =========================================================================
    // PUNTO DE ENTRADA
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("====================================================");
        System.out.println(" TASF.B2B — Motor de Planificación Logística");
        System.out.printf ("  Escenario : %s%n", ESCENARIO.nombre);
        System.out.printf ("  Criterio  : %s%n", CRITERIO_ORDEN.descripcion);
        System.out.printf ("  Inicio    : %d%n", FECHA_INICIO_AAAAMMDD);
        System.out.println("====================================================");

        // ── Carga de red (igual para todos los escenarios) ────────────────────
        GestorDatos datos = new GestorDatos();
        datos.cargarAeropuertos(RUTA_AEROPUERTOS);
        if (datos.numAeropuertos == 0) {
            System.err.println("No se cargaron aeropuertos. Revisa el archivo.");
            return;
        }
        datos.cargarVuelos(RUTA_VUELOS);

        System.out.println("\n--- ANÁLISIS DE RED ---");
        AnalizadorRed.analizarCobertura(datos);

        // ── Convertir fecha de inicio a minutos UTC absolutos ─────────────────
        int  anio      = FECHA_INICIO_AAAAMMDD / 10000;
        int  mes       = (FECHA_INICIO_AAAAMMDD / 100) % 100;
        int  dia       = FECHA_INICIO_AAAAMMDD % 100;
        long inicioUTC = GestorDatos.calcularEpochMinutos(anio, mes, dia, 0, 0, 0);

        // ── Despachar al método del escenario correspondiente ─────────────────
        if (ESCENARIO == Escenario.COLAPSO) {
            ejecutarEscenarioColapso(datos, inicioUTC);
        } else {
            ejecutarEscenarioPeriodo(datos, inicioUTC, ESCENARIO);
        }
    }

    // =========================================================================
    // ESCENARIOS DE PERÍODO Y TIEMPO REAL
    // =========================================================================

    /**
     * Ejecuta un escenario de ventana fija: carga los envíos del período,
     * construye la solución inicial (Fase 2), optimiza con GVNS (Fase 3),
     * audita y exporta resultados.
     *
     * <p>Aplica a TIEMPO_REAL, PERIODO_3D, PERIODO_5D y SEMANA.
     *
     * @param datos     Red de vuelos y aeropuertos ya cargada.
     * @param inicioUTC Minuto UTC absoluto del inicio de la ventana.
     * @param escenario Escenario con {@code diasVentana > 0}.
     */
    private static void ejecutarEscenarioPeriodo(GestorDatos datos,
                                                  long inicioUTC,
                                                  Escenario escenario) {
        long finUTC = inicioUTC + (long) escenario.diasVentana * 1440L;
        System.out.printf("%n--- CARGANDO ENVÍOS [UTC %d → %d] (%d días) ---%n",
                inicioUTC, finUTC, escenario.diasVentana);

        datos.cargarTodosLosEnvios(RUTA_ENVIOS, inicioUTC, finUTC);

        if (datos.numEnvios == 0) {
            System.err.println("No se encontraron envíos en la ventana de tiempo indicada.");
            return;
        }

        System.out.printf("Envíos cargados: %,d%n", datos.numEnvios);
        ejecutarPlanificacion(datos, escenario.nombre);
    }

    // =========================================================================
    // ESCENARIO COLAPSO
    // =========================================================================

    /**
     * Simula las operaciones día a día hasta que la red colapsa operacionalmente.
     *
     * <p>En cada iteración:
     * <ol>
     *   <li>Carga los envíos del día (ventana de 1440 min).</li>
     *   <li>Ejecuta Fase 2 + GVNS.</li>
     *   <li>Si la tasa de rechazo supera {@link #UMBRAL_COLAPSO}, declara colapso.</li>
     * </ol>
     *
     * <p>El colapso ocurre cuando el volumen de envíos supera la capacidad
     * combinada de todos los vuelos de la red.
     *
     * @param datos     Red de vuelos y aeropuertos ya cargada.
     * @param inicioUTC Minuto UTC absoluto del primer día.
     */
    private static void ejecutarEscenarioColapso(GestorDatos datos, long inicioUTC) {
        System.out.printf("%n--- ESCENARIO: COLAPSO (umbral=%.0f%%) ---%n",
                UMBRAL_COLAPSO * 100);

        int     diaActual        = 0;
        int     diasSinEnvios    = 0;
        boolean colapsoDetectado = false;

        while (!colapsoDetectado) {
            long ventanaIni = inicioUTC + (long) diaActual * 1440L;
            long ventanaFin = ventanaIni + 1440L;

            datos.resetEnvios();
            datos.cargarTodosLosEnvios(RUTA_ENVIOS, ventanaIni, ventanaFin);

            if (datos.numEnvios == 0) {
                diasSinEnvios++;
                System.out.printf("Día %2d: sin envíos en ventana.%n", diaActual + 1);
                if (diasSinEnvios >= 3) {
                    System.out.println("3 días consecutivos sin datos. Fin de la simulación.");
                    break;
                }
                diaActual++;
                continue;
            }
            diasSinEnvios = 0;
            System.out.printf("%n=== DÍA %d | %,d envíos ===%n", diaActual + 1, datos.numEnvios);

            PlanificadorGVNSConcurrente plan =
                    new PlanificadorGVNSConcurrente(datos, SEMILLA, CRITERIO_ORDEN);

            long t0 = System.currentTimeMillis();
            plan.construirSolucionInicial();
            plan.ejecutarMejoraGVNS();
            double tSeg = (System.currentTimeMillis() - t0) / 1000.0;

            int    exitosos   = plan.enviosExitosos.get();
            int    rechazados = datos.numEnvios - exitosos;
            double tasa       = (double) rechazados / datos.numEnvios;

            System.out.printf("Día %2d | Exitosos: %,d | Rechazados: %,d (%.1f%%) | t=%.1fs%n",
                    diaActual + 1, exitosos, rechazados, tasa * 100, tSeg);

            if (tasa >= UMBRAL_COLAPSO) {
                System.out.printf(
                        "%n*** COLAPSO en día %d: %.1f%% rechazados (umbral=%.0f%%) ***%n",
                        diaActual + 1, tasa * 100, UMBRAL_COLAPSO * 100);
                colapsoDetectado = true;
            }

            diaActual++;
            if (diaActual > 365) {
                System.out.println("Límite de seguridad: 365 días simulados sin colapso.");
                break;
            }
        }

        if (!colapsoDetectado)
            System.out.println("\nFin de datos sin detectar colapso.");
    }

    // =========================================================================
    // PLANIFICACIÓN COMPLETA (FASE 2 + FASE 3 + AUDITORÍA + EXPORTACIÓN)
    // =========================================================================

    /**
     * Ejecuta el ciclo completo de planificación sobre los envíos ya cargados:
     * Fase 2 → Fase 3 (GVNS) → auditoría → CSV → JSON.
     *
     * <p>Compartido por todos los escenarios de ventana fija.
     *
     * @param datos           Red con envíos ya cargados y filtrados.
     * @param nombreEscenario Nombre para los archivos de salida.
     */
    private static void ejecutarPlanificacion(GestorDatos datos, String nombreEscenario) {
        PlanificadorGVNSConcurrente plan =
                new PlanificadorGVNSConcurrente(datos, SEMILLA, CRITERIO_ORDEN);

        // ── FASE 2: Solución inicial ──────────────────────────────────────────
        System.out.println("\n--- FASE 2: SOLUCIÓN INICIAL ---");
        long t0 = System.currentTimeMillis();
        plan.construirSolucionInicial();
        double tiempoGreedy = (System.currentTimeMillis() - t0) / 1000.0;
        System.out.printf("Tiempo Fase 2: %.2f s%n", tiempoGreedy);

        long fGreedy = plan.calcularTransitoTotal();
        System.out.printf("Tránsito total Fase 2: %,d min%n", fGreedy);
        AnalizadorRed.analizarSolucion(datos, plan.solucionVuelos);

        // ── FASE 3: Optimización GVNS ─────────────────────────────────────────
        System.out.println("\n--- FASE 3: OPTIMIZACIÓN GVNS ---");
        long t1 = System.currentTimeMillis();
        int salvados = plan.ejecutarMejoraGVNS();
        double tiempoGVNS = (System.currentTimeMillis() - t1) / 1000.0;
        System.out.printf("Tiempo Fase 3: %.2f s%n", tiempoGVNS);

        long fGVNS = plan.calcularTransitoTotal();
        AnalizadorRed.compararFases(fGreedy, fGVNS, salvados);

        // ── Exportar CSV ──────────────────────────────────────────────────────
        String slug = nombreEscenario.replaceAll("[^a-zA-Z0-9]", "_");
        plan.exportarResultadosCSV(
                "resultados_" + slug + ".csv",
                fGreedy, tiempoGreedy, tiempoGVNS, salvados);
        plan.exportarHistorialConvergenciaCSV("convergencia_" + slug + ".csv");

        // ── Auditoría matemática ──────────────────────────────────────────────
        System.out.println("\n--- AUDITORÍA DE SOLUCIÓN ---");
        AuditorRutas.auditarSolucion(
                datos, plan.solucionVuelos, plan.solucionDias, plan.ocupacionVuelos);

        // ── [OPCIONAL] SIMULACIÓN DE CANCELACIÓN DE VUELO ────────────────────
        // Descomenta este bloque para probar la replanificación en tiempo real.
        // Cambia el índice (0) por el ID del vuelo que quieres cancelar.
        //
        // int vueloCancelado = 0;
        // System.out.println("\n--- SIMULACIÓN CANCELACIÓN ---");
        // plan.replanificarVueloCancelado(vueloCancelado);
        // AuditorRutas.auditarSolucion(datos, plan.solucionVuelos,
        //         plan.solucionDias, plan.ocupacionVuelos);

        // ── Exportación JSON para frontend ────────────────────────────────────
        System.out.println("\n--- EXPORTACIÓN VISUAL ---");
        int enviosRuteados   = plan.enviosExitosos.get();
        int enviosRechazados = datos.numEnvios - enviosRuteados;
        ExportadorVisual.exportarJSON(
                "visualizacion_" + slug + ".json",
                plan, datos, enviosRuteados, enviosRechazados,
                tiempoGreedy, tiempoGVNS, salvados);

        System.out.println("\n=== FIN ===");
    }
}
