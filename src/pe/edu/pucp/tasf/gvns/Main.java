package pe.edu.pucp.tasf.gvns;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Main — Punto de entrada del motor de planificación logística Tasf.B2B.
 *
 * <h2>Modos de ejecución</h2>
 * <ul>
 *   <li><b>MODO_EXPNUM = true</b> → lote automatizado de 7 ejecuciones para
 *       el experimento numérico (ver {@link #ejecutarExperimento3Dias()}).</li>
 *   <li><b>MODO_EXPNUM = false</b> → ejecución manual con los parámetros
 *       de la sección "PARÁMETROS" de abajo.</li>
 * </ul>
 *
 * <h2>Escenarios disponibles ({@link Escenario})</h2>
 * <ul>
 *   <li>TIEMPO_REAL  — Procesa 1 día. Útil para pruebas rápidas.</li>
 *   <li>PERIODO_3D   — Ventana de 3 días.</li>
 *   <li>PERIODO_5D   — Ventana de 5 días.</li>
 *   <li>SEMANA       — Ventana de 7 días.</li>
 *   <li>COLAPSO      — Avanza día a día hasta saturar la red.</li>
 * </ul>
 */
public class Main {

    // =========================================================================
    // PARÁMETROS — Modificar aquí para cambiar el comportamiento
    // =========================================================================

    /**
     * {@code true} → ejecuta el modo colapso (prueba de estrés con
     * estrangulamiento de red e inyección progresiva de carga).
     * Tiene prioridad sobre {@link #MODO_EXPNUM}.
     */
    private static final boolean MODO_COLAPSO = false;

    /**
     * {@code true} → ejecuta el lote automatizado del experimento numérico
     * (7 runs para PERIODO_3D, resultados en {@code resultados_3D/}).
     * {@code false} → ejecución manual con los parámetros de abajo.
     */
    private static final boolean MODO_EXPNUM = true;

    /** Capacidad máxima por vuelo durante el estrangulamiento de red. */
    private static final int CAP_ESTRANGULAMIENTO = 50;

    /** Escenario activo. Solo aplica cuando {@code MODO_EXPNUM = false}. */
    private static final Escenario ESCENARIO = Escenario.PERIODO_3D;

    /**
     * Criterio de ordenamiento de envíos en la Fase 2.
     * Solo aplica cuando {@code MODO_EXPNUM = false}.
     */
    private static final CriterioOrden CRITERIO_ORDEN = CriterioOrden.EDF;

    /** Fecha de inicio de la simulación en formato aaaammdd (ISO básico). */
    private static final int FECHA_INICIO_AAAAMMDD = 20260818;

    /**
     * Tasa de rechazo (0.0–1.0) que define el colapso operacional.
     * Si en un día más del UMBRAL_COLAPSO de envíos son rechazados, se para.
     */
    private static final double UMBRAL_COLAPSO = 0.50;

    /**
     * Semilla para la permutación aleatoria.
     * Solo aplica cuando {@code MODO_EXPNUM = false} y criterio == ALEATORIO.
     */
    private static final long SEMILLA = 12345L;

    private static final String RUTA_AEROPUERTOS = "datos/aeropuertos.txt";
    private static final String RUTA_VUELOS      = "datos/vuelos.txt";
    private static final String RUTA_ENVIOS      = "datos/_envios_preliminar_";

    // =========================================================================
    // PUNTO DE ENTRADA
    // =========================================================================

    public static void main(String[] args) {
        if (MODO_COLAPSO) {
            ejecutarModoColapso();
            return;
        }
        if (MODO_EXPNUM) {
            ejecutarExperimento3Dias();
            return;
        }

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
    // MODO COLAPSO — PRUEBA DE ESTRÉS
    // =========================================================================

    /**
     * Ejecuta la prueba de estrés de la red identificando el día pico,
     * estrangulando la capacidad de vuelos a 50 maletas y ejecutando el
     * algoritmo GVNS con carga progresiva (±30 % del volumen pico).
     *
     * <h3>Flujo completo</h3>
     * <ol>
     *   <li>Escanea los archivos de envíos para hallar el día con más demanda.</li>
     *   <li>Carga los envíos del día pico y los 2 días siguientes.</li>
     *   <li>Reduce la capacidad de todos los vuelos a 50 (cuello de botella).</li>
     *   <li>Itera sobre 5 niveles de volumen: −30 %, −15 %, 0 %, +15 %, +30 %.</li>
     *   <li>Para cada volumen ejecuta GVNS con criterios FIFO, EDF y ALEATORIO.</li>
     *   <li>Escribe en {@code datos_colapso_davila.csv} (modo append).</li>
     * </ol>
     *
     * <p><b>Nota sobre volúmenes mayores al pico real:</b> si el volumen objetivo
     * supera los envíos disponibles en el dataset, se usa el total cargado y se
     * registra una advertencia. Para superar el pico real se necesitaría datos
     * sintéticos adicionales.
     */
    public static void ejecutarModoColapso() {
        System.out.println("====================================================");
        System.out.println(" MODO COLAPSO — Prueba de Estrés de la Red");
        System.out.println("====================================================");

        // ── 1. Cargar red ─────────────────────────────────────────────────────
        GestorDatos datos = new GestorDatos();
        datos.cargarAeropuertos(RUTA_AEROPUERTOS);
        if (datos.numAeropuertos == 0) {
            System.err.println("No se cargaron aeropuertos.");
            return;
        }
        datos.cargarVuelos(RUTA_VUELOS);

        // ── 2. Detectar día pico ──────────────────────────────────────────────
        System.out.println("\n--- ANÁLISIS: DÍA PICO ---");
        String[] pico = GestorDatos.encontrarDiaPico(RUTA_ENVIOS);
        if (pico == null) {
            System.err.println("No se pudo determinar el día pico.");
            return;
        }
        String fechaPico    = pico[0];                     // "YYYYMMDD"
        long   maletasPico  = Long.parseLong(pico[1]);    // total maletas del día pico

        System.out.printf("Día pico : %s/%s/%s%n",
                fechaPico.substring(6), fechaPico.substring(4, 6), fechaPico.substring(0, 4));
        System.out.printf("Volumen  : %,d maletas%n", maletasPico);

        // ── 3. Cargar envíos del día pico + 2 días siguientes ─────────────────
        // Los 2 días extra proveen datos para los niveles +15% y +30%.
        int  anio      = Integer.parseInt(fechaPico.substring(0, 4));
        int  mes       = Integer.parseInt(fechaPico.substring(4, 6));
        int  dia       = Integer.parseInt(fechaPico.substring(6, 8));
        long inicioUTC = GestorDatos.calcularEpochMinutos(anio, mes, dia, 0, 0, 0);
        long finUTC    = inicioUTC + 3L * 1440L;

        System.out.printf("%nCargando envíos [UTC %d → %d] (3 días desde pico)...%n",
                inicioUTC, finUTC);
        datos.cargarTodosLosEnvios(RUTA_ENVIOS, inicioUTC, finUTC);

        if (datos.numEnvios == 0) {
            System.err.println("No se encontraron envíos en la ventana del día pico.");
            return;
        }
        int totalCargado = datos.numEnvios;

        // Contar los envíos que pertenecen únicamente al día pico (≤ 1440 min)
        // — este número es la referencia del 100% para la inyección progresiva.
        long finPicoDia    = inicioUTC + 1440L;
        int  enviosPicoDia = 0;
        for (int i = 0; i < totalCargado; i++) {
            if (datos.envioRegistroUTC[i] < finPicoDia) enviosPicoDia++;
        }
        System.out.printf("Envíos en el día pico (24 h)     : %,d%n", enviosPicoDia);
        System.out.printf("Envíos totales cargados (3 días) : %,d%n", totalCargado);

        // ── 4. Estrangular capacidad de vuelos ────────────────────────────────
        System.out.println("\n--- ESTRANGULAMIENTO DE RED ---");
        int[] backupCapacidades = datos.respaldarYEstrangularVuelos(CAP_ESTRANGULAMIENTO);

        // ── 5. Niveles de inyección (porcentaje del día pico) ─────────────────
        double[] multiplicadores = {0.70, 0.85, 1.00, 1.15, 1.30};
        String[] etiquetasNivel  = {"-30%", "-15%", "Pico", "+15%", "+30%"};

        // ── 6. Criterios a evaluar ────────────────────────────────────────────
        CriterioOrden[] criterios    = {CriterioOrden.FIFO, CriterioOrden.EDF, CriterioOrden.ALEATORIO};
        String[]        nombresCrit  = {"GVNS-FIFO", "GVNS-EDF", "GVNS-ALEATORIO"};
        long            semillaFija  = 42L;

        // ── 7. Carpeta de salida + CSV resumen ────────────────────────────────
        String carpeta = "resultados_colapso";
        new File(carpeta).mkdirs();
        String csvResumen = carpeta + "/datos_colapso_davila.csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvResumen, false))) {
            pw.println("Algoritmo,Volumen_Maletas,Porcentaje_Colapso");
        } catch (Exception ex) {
            System.err.println("Error creando CSV resumen: " + ex.getMessage());
            datos.restaurarCapacidadVuelos(backupCapacidades);
            return;
        }

        // ── 8. Bucle principal ────────────────────────────────────────────────
        System.out.println("\n--- INYECCIÓN DE CARGA PROGRESIVA ---");
        for (int ni = 0; ni < multiplicadores.length; ni++) {
            int volEfectivo = (int) Math.round(enviosPicoDia * multiplicadores[ni]);
            volEfectivo = Math.max(1, Math.min(volEfectivo, totalCargado));

            if (volEfectivo < (int)(enviosPicoDia * multiplicadores[ni])) {
                System.out.printf("%n[AVISO] Nivel %s: se usará el máximo disponible (%,d envíos).%n",
                        etiquetasNivel[ni], volEfectivo);
            }

            long malevasEfectivas = 0;
            for (int i = 0; i < volEfectivo; i++) malevasEfectivas += datos.envioMaletas[i];

            // Etiqueta numérica para nombres de archivo: 70, 85, 100, 115, 130
            int nivelPct = (int) Math.round(multiplicadores[ni] * 100);

            System.out.printf("%n══════ Nivel %s (%.0f%%) | %,d envíos | %,d maletas ══════%n",
                    etiquetasNivel[ni], multiplicadores[ni] * 100, volEfectivo, malevasEfectivas);

            for (int ci = 0; ci < criterios.length; ci++) {
                String nombreAlg = nombresCrit[ci];
                String sufijo    = criterios[ci].name() + "_" + nivelPct;

                datos.numEnvios = volEfectivo;

                long t0 = System.currentTimeMillis();
                PlanificadorGVNSConcurrente plan =
                        new PlanificadorGVNSConcurrente(datos, semillaFija, criterios[ci]);
                plan.construirSolucionInicial();
                long fGreedy     = plan.calcularTransitoTotal();
                double tGreedy   = (System.currentTimeMillis() - t0) / 1000.0;

                long t1 = System.currentTimeMillis();
                int salvados     = plan.ejecutarMejoraGVNS();
                double tGVNS     = (System.currentTimeMillis() - t1) / 1000.0;

                int    rechazados = plan.contarRechazadosActivos();
                double pctColapso = rechazados * 100.0 / volEfectivo;

                System.out.printf("  %-15s | rechazados=%,d | colapso=%.2f%%%n",
                        nombreAlg, rechazados, pctColapso);

                // Mismo formato que expnum: resultados detallados + convergencia
                plan.exportarResultadosCSV(
                        carpeta + "/resultados_colapso_" + sufijo + ".csv",
                        fGreedy, tGreedy, tGVNS, salvados);
                plan.exportarHistorialConvergenciaCSV(
                        carpeta + "/convergencia_colapso_" + sufijo + ".csv");

                // Append al resumen agregado
                try (PrintWriter pw = new PrintWriter(new FileWriter(csvResumen, true))) {
                    pw.printf("%s,%d,%.2f%n", nombreAlg, malevasEfectivas, pctColapso);
                } catch (Exception ex) {
                    System.err.println("Error escribiendo CSV resumen: " + ex.getMessage());
                }
            }
        }

        // ── 9. Restaurar estado original ──────────────────────────────────────
        datos.restaurarCapacidadVuelos(backupCapacidades);
        datos.numEnvios = totalCargado;

        System.out.printf("%n====================================================%n");
        System.out.printf(" Resultados en: %s/%n", new File(carpeta).getAbsolutePath());
        System.out.printf("   %-42s ← resumen R%n", "datos_colapso_davila.csv");
        System.out.printf("   resultados_colapso_CRITERIO_NIVEL.csv  ← detalle por run%n");
        System.out.printf("   convergencia_colapso_CRITERIO_NIVEL.csv ← curvas%n");
        System.out.printf("====================================================%n");
    }

    // =========================================================================
    // EXPERIMENTO NUMÉRICO — LOTE AUTOMATIZADO
    // =========================================================================

    /**
     * Ejecuta el lote completo de 7 ejecuciones del experimento numérico
     * para el escenario de 3 días ({@link Escenario#PERIODO_3D}).
     *
     * <p>Diseño factorial:
     * <ul>
     *   <li><b>FIFO</b>     — 1 ejecución (determinista, semilla 42).</li>
     *   <li><b>EDF</b>      — 1 ejecución (determinista, semilla 42).</li>
     *   <li><b>ALEATORIO</b> — 5 ejecuciones con semillas {42, 12345, 99999, 7777, 31415}.</li>
     * </ul>
     *
     * <p>Los CSV se escriben en {@code resultados_3D/} con el esquema:
     * <pre>
     *   resultados_3D/resultados_3D_{CRITERIO}_{semilla}.csv
     *   resultados_3D/convergencia_3D_{CRITERIO}_{semilla}.csv
     * </pre>
     */
    public static void ejecutarExperimento3Dias() {
        ejecutarLoteExpNum(Escenario.PERIODO_3D, "resultados_3D");
    }

    /**
     * Núcleo del lote de experimentación numérica para cualquier escenario
     * de ventana fija (no aplica a COLAPSO).
     *
     * <p>Los envíos se cargan una sola vez (todos los runs del lote usan
     * la misma ventana temporal) y el planificador se reinstancia por separado
     * en cada run, garantizando independencia entre ejecuciones.
     *
     * @param escenario Escenario de ventana fija a ejecutar.
     * @param carpeta   Carpeta de salida para todos los CSV del lote
     *                  (se crea si no existe).
     */
    private static void ejecutarLoteExpNum(Escenario escenario, String carpeta) {
        // ── Crear carpeta de resultados si no existe ──────────────────────────
        new File(carpeta).mkdirs();

        System.out.println("====================================================");
        System.out.printf (" EXPNUM — Lote: %s%n", escenario.nombre);
        System.out.printf ("   Carpeta destino : %s/%n", carpeta);
        System.out.printf ("   Fecha inicio    : %d%n", FECHA_INICIO_AAAAMMDD);
        System.out.println("====================================================");

        // ── Carga de red y envíos (una sola vez para todo el lote) ───────────
        GestorDatos datos = new GestorDatos();
        datos.cargarAeropuertos(RUTA_AEROPUERTOS);
        if (datos.numAeropuertos == 0) {
            System.err.println("No se cargaron aeropuertos. Revisa el archivo.");
            return;
        }
        datos.cargarVuelos(RUTA_VUELOS);

        int  anio      = FECHA_INICIO_AAAAMMDD / 10000;
        int  mes       = (FECHA_INICIO_AAAAMMDD / 100) % 100;
        int  dia       = FECHA_INICIO_AAAAMMDD % 100;
        long inicioUTC = GestorDatos.calcularEpochMinutos(anio, mes, dia, 0, 0, 0);
        long finUTC    = inicioUTC + (long) escenario.diasVentana * 1440L;

        System.out.printf("%nCargando envíos [UTC %d → %d] (%d días)...%n",
                inicioUTC, finUTC, escenario.diasVentana);
        datos.cargarTodosLosEnvios(RUTA_ENVIOS, inicioUTC, finUTC);

        if (datos.numEnvios == 0) {
            System.err.println("No se encontraron envíos en la ventana de tiempo indicada.");
            return;
        }
        System.out.printf("Envíos cargados: %,d%n", datos.numEnvios);

        System.out.println("\n--- ANÁLISIS DE RED ---");
        AnalizadorRed.analizarCobertura(datos);

        // ── Definición del lote: 7 configuraciones ────────────────────────────
        // FIFO×1 + EDF×1 + ALEATORIO×5 semillas
        // EDF y FIFO son deterministas: la semilla no altera el orden, se usa 42
        // como valor neutro (solo instancia el RNG sin que se invoque).
        long[] semillasAleatorio = {42L, 12345L, 99999L, 7777L, 31415L};

        CriterioOrden[] criterios = {
            CriterioOrden.FIFO,
            CriterioOrden.EDF,
            CriterioOrden.ALEATORIO,
            CriterioOrden.ALEATORIO,
            CriterioOrden.ALEATORIO,
            CriterioOrden.ALEATORIO,
            CriterioOrden.ALEATORIO
        };
        long[] semillas = {
            42L,
            42L,
            semillasAleatorio[0],
            semillasAleatorio[1],
            semillasAleatorio[2],
            semillasAleatorio[3],
            semillasAleatorio[4]
        };

        int total = criterios.length;

        // ── Iteración sobre el lote ───────────────────────────────────────────
        for (int i = 0; i < total; i++) {
            CriterioOrden criterio = criterios[i];
            long          semilla  = semillas[i];

            System.out.printf("%n══════════════════════════════════════════════════%n");
            System.out.printf(" Run %d/%d  |  criterio=%-9s  |  semilla=%d%n",
                    i + 1, total, criterio.name(), semilla);
            System.out.printf("══════════════════════════════════════════════════%n");

            ejecutarPlanificacionExpNum(datos, escenario, criterio, semilla, carpeta);
        }

        System.out.printf("%n====================================================%n");
        System.out.printf(" EXPNUM completado. Archivos en: %s/%n", carpeta);
        System.out.printf("====================================================%n");
    }

    /**
     * Ejecuta un run individual (Fase 2 + GVNS + auditoría) y exporta los CSV
     * con el esquema de nombres del experimento numérico.
     *
     * <p>Nombres de archivos generados:
     * <pre>
     *   {carpeta}/resultados_{tag}_{criterio}_{semilla}.csv
     *   {carpeta}/convergencia_{tag}_{criterio}_{semilla}.csv
     * </pre>
     * donde {@code tag} es la etiqueta del escenario extraída del nombre de la
     * carpeta (ej. {@code "3D"} si carpeta == {@code "resultados_3D"}).
     *
     * @param datos    Red con envíos ya cargados y filtrados.
     * @param escenario Escenario activo (para metadatos de consola).
     * @param criterio Criterio de ordenamiento de envíos en Fase 2.
     * @param semilla  Semilla del generador aleatorio (solo afecta a ALEATORIO).
     * @param carpeta  Carpeta destino de los CSV (debe existir previamente).
     */
    private static void ejecutarPlanificacionExpNum(GestorDatos datos,
                                                     Escenario escenario,
                                                     CriterioOrden criterio,
                                                     long semilla,
                                                     String carpeta) {
        // Derivar etiqueta del escenario del nombre de la carpeta
        // "resultados_3D" → "3D", "resultados_5D" → "5D", etc.
        String tag   = carpeta.replaceFirst("^resultados_", "");
        String sufijo = criterio.name() + "_" + semilla;

        String csvResultados   = carpeta + "/resultados_"  + tag + "_" + sufijo + ".csv";
        String csvConvergencia = carpeta + "/convergencia_" + tag + "_" + sufijo + ".csv";

        // ── Instanciar planificador (fresh por run) ───────────────────────────
        PlanificadorGVNSConcurrente plan =
                new PlanificadorGVNSConcurrente(datos, semilla, criterio);

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

        // ── Exportar CSV de resultados y convergencia ─────────────────────────
        plan.exportarResultadosCSV(csvResultados, fGreedy, tiempoGreedy, tiempoGVNS, salvados);
        plan.exportarHistorialConvergenciaCSV(csvConvergencia);
        System.out.printf("CSV escritos:%n  → %s%n  → %s%n", csvResultados, csvConvergencia);

        // ── Auditoría matemática ──────────────────────────────────────────────
        System.out.println("\n--- AUDITORÍA DE SOLUCIÓN ---");
        AuditorRutas.auditarSolucion(
                datos, plan.solucionVuelos, plan.solucionDias, plan.ocupacionVuelos);
    }

    // =========================================================================
    // ESCENARIOS DE PERÍODO Y TIEMPO REAL (modo manual)
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
    // ESCENARIO COLAPSO (modo manual)
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
    // PLANIFICACIÓN COMPLETA (modo manual — Fase 2 + Fase 3 + Auditoría + CSV)
    // =========================================================================

    /**
     * Ejecuta el ciclo completo de planificación sobre los envíos ya cargados:
     * Fase 2 → Fase 3 (GVNS) → auditoría → CSV → JSON.
     *
     * <p>Compartido por todos los escenarios de ventana fija en modo manual.
     * Usa las constantes {@link #CRITERIO_ORDEN} y {@link #SEMILLA}.
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
