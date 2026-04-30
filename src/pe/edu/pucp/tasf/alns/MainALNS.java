package pe.edu.pucp.tasf.alns;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainALNS {

    /**
     * {@code true} → ejecuta el experimento numérico base (3 días, capacidades reales,
     * 120 s de ALNS) exportando a {@code resultados_3D/}.
     * Tiene prioridad sobre {@link #MODO_COLAPSO_ALNS}.
     */
    private static final boolean MODO_EXPNUM_ALNS = false;

    /** {@code true} → ejecuta la prueba de estrés (colapso) en lugar del modo normal. */
    private static final boolean MODO_COLAPSO_ALNS = false;

    /** Capacidad forzada por vuelo en el modo colapso (maletas). */
    private static final int CAP_ESTRANGULAMIENTO = 2;

    /** Tiempo límite del ALNS en el modo colapso (ms). */
    private static final long TIEMPO_LIMITE_COLAPSO_MS = 30_000L;

    /** Semillas fijas para las réplicas independientes. */
    private static final long[] SEMILLAS = {42L, 12345L, 99999L, 7777L, 31415L};

    /** Número de réplicas independientes por escenario. */
    private static final int NUM_REPLICAS = SEMILLAS.length;

    public static void main(String[] args) throws IOException {
        if (MODO_EXPNUM_ALNS) {
            try {
                ejecutarModoNormalALNS();
            } catch (Exception e) {
                System.err.println("Error en modo expnum ALNS: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }
        if (MODO_COLAPSO_ALNS) {
            try {
                ejecutarModoColapsoALNS();
            } catch (Exception e) {
                System.err.println("Error en modo colapso ALNS: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }
        // Cargar datos
        DatosEstaticos.cargarDatos();

        // Calcular fecha de inicio correctamente
        int fecha = ConfigExperimentacion.FECHA_INICIO_AAAAMMDD;
        int anio = fecha / 10000;
        int mes = (fecha / 100) % 100;
        int dia = fecha % 100;
        long inicioUTCMin = DatosEstaticos.calcularEpochMinutos(anio, mes, dia, 0, 0, 0);

        System.out.println("\n=== INICIALIZACIÓN ALNS ===");
        System.out.println("Inicio AAAAMMDD=" + ConfigExperimentacion.FECHA_INICIO_AAAAMMDD);
        System.out.println("Inicio UTC min=" + inicioUTCMin);

        System.out.println("\n=== CONFIGURACIÓN ALNS ===");
        System.out.println("EJECUTAR_3D = " + ConfigExperimentacion.EJECUTAR_3D);
        System.out.println("EJECUTAR_5D = " + ConfigExperimentacion.EJECUTAR_5D);
        System.out.println("EJECUTAR_7D = " + ConfigExperimentacion.EJECUTAR_7D);
        System.out.println("MAX_ITERACIONES_ALNS = " + ConfigExperimentacion.MAX_ITERACIONES_ALNS);
        System.out.println("MAX_ENVIOS_DEBUG = " + ConfigExperimentacion.MAX_ENVIOS_DEBUG);
        System.out.println("MAX_BLOQUES_SEGURIDAD = " + ConfigExperimentacion.MAX_BLOQUES_SEGURIDAD);
        System.out.println("MAX_TIEMPO_ESCENARIO_MS = " + ConfigExperimentacion.MAX_TIEMPO_ESCENARIO_MS);
        System.out.println("DETENER_POR_COLAPSO = " + ConfigExperimentacion.DETENER_POR_COLAPSO);
        System.out.println("MODO_STRESS_COLAPSO = " + ConfigExperimentacion.MODO_STRESS_COLAPSO);
        System.out.println("FACTOR_CAPACIDAD_AEROPUERTO = " + ConfigExperimentacion.FACTOR_CAPACIDAD_AEROPUERTO);
        System.out.println("FACTOR_CAPACIDAD_VUELO = " + ConfigExperimentacion.FACTOR_CAPACIDAD_VUELO);

        List<Integer> escenariosActivos = new ArrayList<>();
        List<String> escenariosActivosNombres = new ArrayList<>();
        if (ConfigExperimentacion.EJECUTAR_3D) {
            escenariosActivos.add(3);
            escenariosActivosNombres.add("ALNS_3D");
        }
        if (ConfigExperimentacion.EJECUTAR_5D) {
            escenariosActivos.add(5);
            escenariosActivosNombres.add("ALNS_5D");
        }
        if (ConfigExperimentacion.EJECUTAR_7D) {
            escenariosActivos.add(7);
            escenariosActivosNombres.add("ALNS_7D");
        }

        System.out.println("Escenarios activos = " + escenariosActivosNombres);
        if (escenariosActivos.isEmpty()) {
            System.out.println("No hay escenarios activos. Ajusta EJECUTAR_3D/EJECUTAR_5D/EJECUTAR_7D en ConfigExperimentacion.");
            return;
        }

        String escenariosActivosTexto = String.join(";", escenariosActivosNombres);

        List<MetricasSolucion> resumen = new ArrayList<>();
        List<MetricasBloque> bloques = new ArrayList<>();

        for (int bloque : escenariosActivos) {
            System.out.println("\n=== ESCENARIO ALNS_" + bloque + "D ===");
            MetricasSolucion met = new MetricasSolucion();
            met.escenario = "ALNS_" + bloque + "D";
            met.bloqueDias = bloque;
            met.fechaInicio = inicioUTCMin;
            long currentTime = inicioUTCMin;
            int bloqueIndex = 0;
            int bloquesConColapso = 0;
            String razonParada = "FinData";
            long t0 = System.currentTimeMillis();
            int totalEnviosLeidosBloques = 0;
            long primerReleaseUTC = Long.MAX_VALUE;
            long ultimoReleaseUTC = -1;

            ActiveShipmentPool pool = new ActiveShipmentPool(10000);
            RouteStore routes = new RouteStore(10000);
            FlightCapacityStore flights = new FlightCapacityStore();
            AirportCapacityTimeline airports = new AirportCapacityTimeline(DatosEstaticos.airportCode.length);

            try (ShipmentStreamManager manager = new ShipmentStreamManager()) {
                System.out.println("Streams abiertos=" + manager.getStreamsAbiertos());

                long minReleaseInManager = manager.peekNextReleaseUTC();
                System.out.println("Min release encontrado en streams=" + minReleaseInManager);

                long inicioEscenarioMs = System.currentTimeMillis();
                while (manager.hasNext()) {
                    if (ConfigExperimentacion.MAX_TIEMPO_ESCENARIO_MS > 0
                            && System.currentTimeMillis() - inicioEscenarioMs >= ConfigExperimentacion.MAX_TIEMPO_ESCENARIO_MS) {
                        razonParada = "MAX_TIEMPO_ESCENARIO_MS";
                        break;
                    }

                    if (bloqueIndex >= ConfigExperimentacion.MAX_BLOQUES_SEGURIDAD) {
                        razonParada = "MAX_BLOQUES_SEGURIDAD";
                        break;
                    }

                    if (ConfigExperimentacion.MAX_ENVIOS_DEBUG > 0 && met.enviosLeidos >= ConfigExperimentacion.MAX_ENVIOS_DEBUG) {
                        razonParada = "MAX_ENVIOS_DEBUG";
                        break;
                    }

                    long blockStart = currentTime;
                    long blockEnd = currentTime + bloque * 1440L;
                    long firstRelease = -1;
                    long lastRelease = -1;
                    int readsThisBlock = 0;

                    while (manager.hasNextBefore(blockEnd)) {
                        ShipmentRecord rec = manager.pollNext();
                        if (rec == null) break;

                        if (rec.releaseUTC < blockStart) {
                            continue;
                        }
                        readsThisBlock++;
                        totalEnviosLeidosBloques++;
                        met.enviosLeidos++;

                        if (rec.releaseUTC < primerReleaseUTC) {
                            primerReleaseUTC = rec.releaseUTC;
                        }
                        if (rec.releaseUTC > ultimoReleaseUTC) {
                            ultimoReleaseUTC = rec.releaseUTC;
                        }

                        if (firstRelease < 0) firstRelease = rec.releaseUTC;
                        lastRelease = rec.releaseUTC;

                        int activeIdx = pool.addShipment(rec);
                        routes.ensureCapacity(pool.getCapacity());
                        // Intentar aceptar en origen
                        if (airports.canReserveInterval(rec.origin, rec.releaseUTC, blockEnd, rec.quantity)) {
                            airports.reserveInterval(rec.origin, rec.releaseUTC, blockEnd, rec.quantity);
                            pool.setStatus(activeIdx, ActiveShipmentPool.PENDIENTE);
                            met.enviosAceptadosEnOrigen++;
                        } else {
                            pool.setStatus(activeIdx, ActiveShipmentPool.PENDIENTE);
                        }

                        if (ConfigExperimentacion.MAX_ENVIOS_DEBUG > 0 && met.enviosLeidos >= ConfigExperimentacion.MAX_ENVIOS_DEBUG) {
                            System.out.println("Límite de debug alcanzado: " + ConfigExperimentacion.MAX_ENVIOS_DEBUG);
                            break;
                        }
                    }

                    if (ConfigExperimentacion.MAX_ENVIOS_DEBUG > 0 && met.enviosLeidos >= ConfigExperimentacion.MAX_ENVIOS_DEBUG) {
                        razonParada = "MAX_ENVIOS_DEBUG";
                        break;
                    }

                    System.out.println("  Bloque " + bloqueIndex + ": blockStart=" + blockStart + " blockEnd=" + blockEnd +
                            " firstRelease=" + firstRelease + " lastRelease=" + lastRelease + " reads=" + readsThisBlock);

                    if (readsThisBlock == 0 && manager.hasNext()) {
                        long nextRelease = manager.peekNextReleaseUTC();
                        if (nextRelease >= blockEnd && nextRelease < Long.MAX_VALUE) {
                            long nextBlockStart = ((nextRelease - inicioUTCMin) / (bloque * 1440L)) * (bloque * 1440L) + inicioUTCMin;
                            System.out.println("  Bloque vacío, saltando a " + nextBlockStart);
                            currentTime = nextBlockStart;
                            bloqueIndex++;
                            continue;
                        }
                    }

                    int entregadosAntes = 0;
                    int pendientesAntes = 0;
                    int sinRutaAntes = 0;
                    int retrasadosAntes = 0;
                    int noFactiblesAntes = 0;
                    int replanificacionesAntes = met.replanificaciones;
                    for (int i = 0; i < pool.getSize(); i++) {
                        int status = pool.getStatus(i);
                        if (status == ActiveShipmentPool.ENTREGADO) {
                            entregadosAntes++;
                        } else if (status == ActiveShipmentPool.RETRASADO) {
                            retrasadosAntes++;
                        } else if (status == ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL) {
                            noFactiblesAntes++;
                        } else if (status == ActiveShipmentPool.SIN_RUTA) {
                            sinRutaAntes++;
                        } else if (status == ActiveShipmentPool.PENDIENTE || status == ActiveShipmentPool.EN_ALMACEN || status == ActiveShipmentPool.PLANIFICADO) {
                            pendientesAntes++;
                        }
                    }
                    double fitnessAntesALNS = EvaluadorSolucion.calcularFitness(pool, routes, 0);
                    // Identificar críticos antes de ALNS
                    List<Integer> criticos = new ArrayList<>();
                    for (int i = 0; i < pool.getSize(); i++) {
                        int status = pool.getStatus(i);
                        if (status == ActiveShipmentPool.SIN_RUTA || status == ActiveShipmentPool.PENDIENTE || status == ActiveShipmentPool.RETRASADO) {
                            criticos.add(i);
                        }
                    }
                    ResultadoALNS resultadoALNS;
                    if (criticos.isEmpty()) {
                        resultadoALNS = new ResultadoALNS();
                    } else {
                        PlanificadorALNS alns = new PlanificadorALNS(pool, routes, flights, airports);
                        resultadoALNS = alns.ejecutarALNS(criticos, ConfigExperimentacion.TIEMPO_LIMITE_ALNS_MS);
                        met.replanificaciones++;
                    }

                    int entregadosDespues = 0;
                    int pendientesDespues = 0;
                    int sinRutaDespues = 0;
                    int retrasadosDespues = 0;
                    int noFactiblesDespues = 0;
                    for (int i = 0; i < pool.getSize(); i++) {
                        int status = pool.getStatus(i);
                        if (status == ActiveShipmentPool.ENTREGADO) {
                            entregadosDespues++;
                        } else if (status == ActiveShipmentPool.RETRASADO) {
                            retrasadosDespues++;
                        } else if (status == ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL) {
                            noFactiblesDespues++;
                        } else if (status == ActiveShipmentPool.SIN_RUTA) {
                            sinRutaDespues++;
                        } else if (status == ActiveShipmentPool.PENDIENTE || status == ActiveShipmentPool.EN_ALMACEN || status == ActiveShipmentPool.PLANIFICADO) {
                            pendientesDespues++;
                        }
                    }
                    int entregadosBloque = entregadosDespues - entregadosAntes;
                    int sinRutaBloque = sinRutaDespues - sinRutaAntes;
                    int retrasadosBloque = retrasadosDespues - retrasadosAntes;
                    int noFactiblesBloque = noFactiblesDespues - noFactiblesAntes;
                    int criticosFinBloque = pendientesDespues + sinRutaDespues;
                    String validacionBalanceBloque = (readsThisBlock >= entregadosBloque + sinRutaBloque + noFactiblesBloque)
                            ? "OK" : "ERROR";
                    MetricasBloque bloqueMetric = new MetricasBloque();
                    bloqueMetric.escenario = met.escenario;
                    bloqueMetric.bloqueDias = met.bloqueDias;
                    bloqueMetric.bloqueIndex = bloqueIndex;
                    bloqueMetric.blockStartUTC = blockStart;
                    bloqueMetric.blockEndUTC = blockEnd;
                    bloqueMetric.firstReleaseUTC = firstRelease;
                    bloqueMetric.lastReleaseUTC = lastRelease;
                    bloqueMetric.enviosLeidosBloque = readsThisBlock;
                    bloqueMetric.entregadosBloque = entregadosBloque;
                    bloqueMetric.pendientesFinBloque = pendientesDespues;
                    bloqueMetric.sinRutaBloque = sinRutaBloque;
                    bloqueMetric.retrasadosBloque = retrasadosBloque;
                    int retrasadosAcumulados = calcularRetrasadosSLA(pool, blockEnd);
                    double tasaColapsoSLABloque = met.enviosLeidos > 0 ? retrasadosAcumulados / (double) met.enviosLeidos : 0.0;
                    bloqueMetric.retrasadosAcumulados = retrasadosAcumulados;
                    bloqueMetric.tasaColapsoSLABloque = tasaColapsoSLABloque;
                    bloqueMetric.noFactiblesBloque = noFactiblesBloque;
                    bloqueMetric.criticosFinBloque = criticosFinBloque;
                    bloqueMetric.llamadasALNS = resultadoALNS.llamadasALNS;
                    bloqueMetric.iteracionesALNS = resultadoALNS.iteraciones;
                    bloqueMetric.criticosAntesALNS = resultadoALNS.criticosAntes;
                    bloqueMetric.criticosDespuesALNS = resultadoALNS.criticosDespues;
                    bloqueMetric.pedidosReparadosALNS = resultadoALNS.reparados;
                    bloqueMetric.sinRutaAntesALNS = resultadoALNS.sinRutaAntes;
                    bloqueMetric.sinRutaDespuesALNS = resultadoALNS.sinRutaDespues;
                    bloqueMetric.fitnessAntesALNS = resultadoALNS.fitnessAntesALNS;
                    bloqueMetric.fitnessDespuesALNS = resultadoALNS.fitnessDespuesALNS;
                    // Métrica operativa: ALNS mejoró si redujo críticos
                    bloqueMetric.mejoraOperativaALNS = resultadoALNS.reparados > 0 ? "SI" : "NO";
                    bloqueMetric.tiempoMs = System.currentTimeMillis() - t0;
                    bloqueMetric.validacionBalance = validacionBalanceBloque;
                    bloques.add(bloqueMetric);

                    if (ConfigExperimentacion.DETENER_POR_COLAPSO_SLA
                        && ConfigExperimentacion.MAX_ENVIOS_DEBUG == 0
                        && met.enviosLeidos >= ConfigExperimentacion.MIN_ENVIOS_ANTES_COLAPSO_SLA
                        && bloqueIndex >= ConfigExperimentacion.MIN_BLOQUES_ANTES_COLAPSO_SLA) {

                        int retrasadosAcumuladosParaColapso = calcularRetrasadosSLA(pool, blockEnd);
                        double tasaColapsoSLA = met.enviosLeidos > 0
                                ? retrasadosAcumuladosParaColapso / (double) met.enviosLeidos
                                : 0.0;

                        if (tasaColapsoSLA >= ConfigExperimentacion.UMBRAL_COLAPSO_SLA) {
                            bloquesConColapso++;
                        } else {
                            bloquesConColapso = 0;
                        }

                        if (bloquesConColapso >= ConfigExperimentacion.BLOQUES_CONSECUTIVOS_COLAPSO_SLA) {
                            razonParada = "ColapsoSLA";
                            break;
                        }
                    }

                    currentTime = blockEnd;
                    bloqueIndex++;
                }
            }

            met.bloquesProcesados = bloqueIndex;
            met.razonParada = razonParada;
            System.out.println("Total leidos por escenario = " + met.enviosLeidos);
            System.out.println("Bloques procesados = " + bloqueIndex);
            System.out.println("Razón de parada = " + razonParada);

            met.fechaFinAlcanzada = currentTime;
            met.enviosEntregados = 0;
            met.enviosPendientes = 0;
            met.enviosSinRuta = 0;
            met.enviosRetrasados = 0;
            met.noFactiblesEstructurales = 0;
            for (int i = 0; i < pool.getSize(); i++) {
                int status = pool.getStatus(i);
                if (status == ActiveShipmentPool.ENTREGADO) {
                    met.enviosEntregados++;
                } else if (status == ActiveShipmentPool.RETRASADO) {
                    met.enviosRetrasados++;
                } else if (status == ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL) {
                    met.noFactiblesEstructurales++;
                } else if (status == ActiveShipmentPool.SIN_RUTA
                        || status == ActiveShipmentPool.PENDIENTE
                        || status == ActiveShipmentPool.EN_ALMACEN
                        || status == ActiveShipmentPool.PLANIFICADO) {
                    if (pool.getDeadlineUTC(i) < currentTime) {
                        met.enviosRetrasados++;
                    } else if (status == ActiveShipmentPool.SIN_RUTA) {
                        met.enviosSinRuta++;
                    } else {
                        met.enviosPendientes++;
                    }
                }
            }
            met.fitnessFinal = EvaluadorSolucion.calcularFitness(pool, routes, 0);
            int balanceSum = met.enviosEntregados + met.enviosPendientes + met.enviosSinRuta + met.noFactiblesEstructurales;
            met.validacionBalance = (met.enviosEntregados <= met.enviosLeidos && balanceSum <= met.enviosLeidos) ? "OK" : "ERROR";
            met.tasaCriticaFinal = met.enviosLeidos > 0
                    ? (met.enviosPendientes + met.enviosSinRuta + met.enviosRetrasados) / (double) met.enviosLeidos
                    : 0.0;
            met.tasaColapsoSLAFinal = met.enviosLeidos > 0
                    ? met.enviosRetrasados / (double) met.enviosLeidos
                    : 0.0;
            met.umbralColapsoSLA = ConfigExperimentacion.UMBRAL_COLAPSO_SLA;
            met.detenerPorColapsoSLA = ConfigExperimentacion.DETENER_POR_COLAPSO_SLA;
            met.escenariosActivos = escenariosActivosTexto;
            met.maxIteracionesALNS = ConfigExperimentacion.MAX_ITERACIONES_ALNS;
            met.modoStressColapso = ConfigExperimentacion.MODO_STRESS_COLAPSO;
            met.factorCapacidadAeropuerto = ConfigExperimentacion.FACTOR_CAPACIDAD_AEROPUERTO;
            met.factorCapacidadVuelo = ConfigExperimentacion.FACTOR_CAPACIDAD_VUELO;
            met.tiempoMs = System.currentTimeMillis() - t0;
            Runtime rt = Runtime.getRuntime();
            met.memoriaUsadaMB = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
            resumen.add(met);

            System.out.println("\nEscenario " + met.escenario + " completado:");
            System.out.println("  Total leidos=" + met.enviosLeidos + " (acum=" + totalEnviosLeidosBloques + ")");
            System.out.println("  Entregados=" + met.enviosEntregados + " pendientes=" + met.enviosPendientes +
                " sinRuta=" + met.enviosSinRuta + " retrasados=" + met.enviosRetrasados + " noFactibles=" + met.noFactiblesEstructurales);
            System.out.println("  Fitness=" + met.fitnessFinal + " balance=" + met.validacionBalance);
            System.out.println("  Primer release=" + (primerReleaseUTC == Long.MAX_VALUE ? "N/A" : primerReleaseUTC) +
                " último release=" + ultimoReleaseUTC);
            System.out.println("  Tasa colapso SLA final = " + String.format("%.2f", met.tasaColapsoSLAFinal * 100.0) + "%");
            System.out.println("  Umbral colapso SLA = " + String.format("%.2f", ConfigExperimentacion.UMBRAL_COLAPSO_SLA * 100.0) + "%");
            System.out.println("  Razón de parada = " + razonParada);
            System.out.println("  Tiempo=" + (met.tiempoMs / 1000.0) + "s memoria=" + String.format("%.2f", met.memoriaUsadaMB) + "MB");
        }

        // Exportar
        if (ConfigExperimentacion.EXPORTAR_CSV) {
            try {
                ExportadorCSV.exportarBloques("resultados_alns_bloques.csv", bloques);
            } catch (Exception e) {
                System.err.println("Error exportando bloques: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                ExportadorCSV.exportarBloquesPorEscenario(bloques);
            } catch (Exception e) {
                System.err.println("Error exportando bloques por escenario: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                ExportadorCSV.exportarResumen("resultados_alns_resumen.csv", resumen);
            } catch (Exception e) {
                System.err.println("Error exportando resumen: " + e.getMessage());
                e.printStackTrace();
            }

            System.out.println("\n=== ARCHIVOS CSV EXPORTADOS ===");
            System.out.println("- resultados_alns_resumen.csv");
            System.out.println("- resultados_alns_bloques.csv");
            System.out.println("- resultados_alns_bloques_3d.csv");
            System.out.println("- resultados_alns_bloques_5d.csv");
            System.out.println("- resultados_alns_bloques_7d.csv");
        } else {
            System.out.println("EXPORTAR_CSV está deshabilitado. No se generaron CSV.");
        }

        System.out.println("\n=== RESULTADOS EXPORTADOS ===");
        System.out.println("Archivos: resultados_alns_resumen.csv, resultados_alns_bloques.csv, resultados_alns_bloques_3d.csv, resultados_alns_bloques_5d.csv, resultados_alns_bloques_7d.csv");
    }

    private static int calcularRetrasadosSLA(ActiveShipmentPool pool, long currentTime) {
        int retrasados = 0;
        for (int i = 0; i < pool.getSize(); i++) {
            int status = pool.getStatus(i);
            if (status == ActiveShipmentPool.RETRASADO) {
                retrasados++;
            } else if (status == ActiveShipmentPool.PENDIENTE
                    || status == ActiveShipmentPool.EN_ALMACEN
                    || status == ActiveShipmentPool.PLANIFICADO
                    || status == ActiveShipmentPool.SIN_RUTA) {
                if (pool.getDeadlineUTC(i) < currentTime) {
                    retrasados++;
                }
            }
        }
        return retrasados;
    }

    // =========================================================================
    // MODO EXPNUM ALNS — Experimento Numérico Base (3 días, 15 réplicas)
    // =========================================================================

    /**
     * Ejecuta el experimento numérico base del ALNS: 15 réplicas con semillas
     * 1-15, capacidades reales, ventana de 3 días desde
     * {@code FECHA_INICIO_AAAAMMDD}, 120 s por réplica (mismo límite GVNS Fase 3).
     *
     * <p>Exporta a {@code resultados_3D/}:
     * <ul>
     *   <li>{@code resultados_3dias_ALNS.csv} — una fila por réplica (columnar).</li>
     *   <li>{@code convergencia_3dias_ALNS.csv} — curva de mejora de las 15 réplicas.</li>
     * </ul>
     *
     * <p>Para activar: {@code MODO_EXPNUM_ALNS = true}.
     */
    public static void ejecutarModoNormalALNS() throws Exception {
        final long TIEMPO_LIMITE_NORMAL_MS = 120_000L;
        final int  MAX_ITER_NORMAL         = Integer.MAX_VALUE;

        System.out.println("======================================================");
        System.out.println(" EXPNUM ALNS — Experimento Numérico Base (3 días, 15 réplicas)");
        System.out.printf ("   Fecha inicio : %d%n", ConfigExperimentacion.FECHA_INICIO_AAAAMMDD);
        System.out.printf ("   Tiempo ALNS  : %.0f s | Réplicas: %d%n",
                TIEMPO_LIMITE_NORMAL_MS / 1000.0, NUM_REPLICAS);
        System.out.println("======================================================");

        // 1. Cargar red con capacidades reales
        DatosEstaticos.cargarDatos();

        // 2. Ventana de 3 días
        int fecha   = ConfigExperimentacion.FECHA_INICIO_AAAAMMDD;
        int anio    = fecha / 10000;
        int mes     = (fecha / 100) % 100;
        int dia     = fecha % 100;
        long inicioUTC = DatosEstaticos.calcularEpochMinutos(anio, mes, dia, 0, 0, 0);
        long finUTC    = inicioUTC + 3L * 1440L;

        System.out.printf("%nCargando envíos [UTC %d → %d]...%n", inicioUTC, finUTC);

        // 3. Materializar envíos una sola vez (streaming → lista)
        List<ShipmentRecord> envios = cargarEnviosEnLista(inicioUTC, finUTC, 0);
        System.out.printf("Envíos cargados: %,d%n", envios.size());

        if (envios.isEmpty()) {
            System.err.println("No se encontraron envíos en la ventana configurada.");
            return;
        }

        // 4. Preparar archivos de salida
        new File("resultados_3D").mkdirs();
        String archivoDatos = "resultados_3D/resultados_3dias_ALNS.csv";
        String archivoConv  = "resultados_3D/convergencia_3dias_ALNS.csv";

        try (PrintWriter pw = new PrintWriter(new FileWriter(archivoDatos))) {
            pw.println("Replica,Total Envios,Exitosos Total,Salvados ALNS,Rechazados Finales,"
                     + "Transito Final (min),Tiempo ALNS (s),Iteraciones ALNS,Mejor FO,Tasa Exito Final (%)");
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(archivoConv))) {
            pw.println("Replica,iteracion,ms_transcurridos,mejor_fitness");
        }

        // 5. Bucle de réplicas con semillas 1-15
        for (int rep = 1; rep <= NUM_REPLICAS; rep++) {
            long semilla = SEMILLAS[rep - 1];
            System.out.printf("%n--- Réplica %d/%d (semilla=%d) ---%n", rep, NUM_REPLICAS, semilla);

            ActiveShipmentPool pool = new ActiveShipmentPool(envios.size() + 1000);
            RouteStore routes = new RouteStore(envios.size() + 1000);
            FlightCapacityStore flights = new FlightCapacityStore();
            AirportCapacityTimeline airports =
                    new AirportCapacityTimeline(DatosEstaticos.airportCode.length);

            poblarPool(envios, pool, routes);

            List<Integer> criticos = new ArrayList<>();
            for (int i = 0; i < pool.getSize(); i++) criticos.add(i);

            long t0 = System.currentTimeMillis();
            PlanificadorALNS alns = new PlanificadorALNS(pool, routes, flights, airports, semilla);
            ResultadoALNS resultado = alns.ejecutarALNS(criticos, TIEMPO_LIMITE_NORMAL_MS, MAX_ITER_NORMAL);
            long tiempoMs = System.currentTimeMillis() - t0;

            long[] metricas = calcularMetricasPool(pool, routes);
            long exitosos    = metricas[0];
            long rechazados  = metricas[1];
            long transitoMin = metricas[2];
            double tasa = envios.size() > 0 ? exitosos * 100.0 / envios.size() : 0;

            System.out.printf("  Exitosos: %,d | Rechazados: %,d | Iter: %d | %.1f s%n",
                    exitosos, rechazados, resultado.iteraciones, tiempoMs / 1000.0);

            // Fila de datos (append)
            try (PrintWriter pw = new PrintWriter(new FileWriter(archivoDatos, true))) {
                pw.printf("%d,%d,%d,%d,%d,%d,%.3f,%d,%d,%.4f%n",
                        rep, envios.size(), exitosos, resultado.reparados, rechazados,
                        transitoMin, tiempoMs / 1000.0, resultado.iteraciones,
                        (long) resultado.fitnessDespuesALNS, tasa);
            }
            // Filas de convergencia (append)
            try (PrintWriter pw = new PrintWriter(new FileWriter(archivoConv, true))) {
                if (resultado.convergencia != null) {
                    for (long[] row : resultado.convergencia) {
                        pw.printf("%d,%d,%d,%d%n", rep, row[0], row[1], row[2]);
                    }
                }
            }
        }

        System.out.println("\n====== EXPNUM ALNS COMPLETADO ======");
        System.out.println("  " + archivoDatos);
        System.out.println("  " + archivoConv);
    }

    // =========================================================================
    // MODO COLAPSO ALNS — Prueba de Estrés (15 réplicas)
    // =========================================================================

    /**
     * Ejecuta la prueba de estrés del ALNS con los mismos 5 niveles de carga
     * que el GVNS, 15 réplicas por nivel con semillas 1-15.
     *
     * <p>Para activar: {@code MODO_COLAPSO_ALNS = true}.</p>
     */
    public static void ejecutarModoColapsoALNS() throws Exception {
        System.out.println("======================================================");
        System.out.println(" MODO COLAPSO ALNS — Prueba de Estrés (15 réplicas)");
        System.out.printf ("   Cap. vuelo   : %d maletas | Tiempo: %.0f s | Réplicas: %d%n",
                CAP_ESTRANGULAMIENTO, TIEMPO_LIMITE_COLAPSO_MS / 1000.0, NUM_REPLICAS);
        System.out.println("======================================================");

        // 1. Cargar red
        DatosEstaticos.cargarDatos();

        // 2. Estrangular capacidad de vuelos
        for (int f = 0; f < DatosEstaticos.numFlights; f++) {
            DatosEstaticos.flightCapacity[f] = CAP_ESTRANGULAMIENTO;
        }
        System.out.printf("Red estrangulada: %d vuelos × cap=%d%n",
                DatosEstaticos.numFlights, CAP_ESTRANGULAMIENTO);

        // 3. Día pico y ventana de 3 días
        long[] pico = encontrarDiaPicoALNS();
        long inicioPico  = pico[0];
        long maletasPico = pico[1];
        System.out.printf("Día pico: UTC %d | Maletas: %,d%n", inicioPico, maletasPico);

        long enviosPicoDia = contarEnviosPicoDia(inicioPico, inicioPico + 1440L);
        System.out.printf("Envíos (registros) en día pico: %,d%n", enviosPicoDia);

        long fin3Dias = inicioPico + 3L * 1440L;

        // 4. Niveles de carga
        double[] factores  = {1.00, 1.15, 1.30, 1.50, 2.00};
        String[] etiquetas = {"100", "115", "130", "150", "200"};

        // 5. Archivos de salida
        new File("resultados_colapso").mkdirs();
        String archivoDatosSummary = "resultados_colapso/datos_colapso_alns.csv";

        // Header del resumen global
        try (PrintWriter pw = new PrintWriter(new FileWriter(archivoDatosSummary))) {
            pw.println("Replica,Algoritmo,Volumen_Maletas,Porcentaje_Colapso");
        }

        // 6. Bucle por nivel de carga
        for (int lv = 0; lv < factores.length; lv++) {
            int targetEnvios = (int) Math.round(enviosPicoDia * factores[lv]);
            String et = etiquetas[lv];

            System.out.printf("%n=== Nivel %s (+%.0f%%) | target: %,d envíos ===%n",
                    et, (factores[lv] - 1.0) * 100, targetEnvios);

            // Materializar envíos del nivel una sola vez
            List<ShipmentRecord> envios = cargarEnviosEnLista(inicioPico, fin3Dias, targetEnvios);
            System.out.printf("  Cargados: %,d envíos%n", envios.size());

            String archivoNivel = "resultados_colapso/resultados_colapso_ALNS_" + et + ".csv";
            String archivoConv  = "resultados_colapso/convergencia_colapso_ALNS_" + et + ".csv";

            // Headers por nivel
            try (PrintWriter pw = new PrintWriter(new FileWriter(archivoNivel))) {
                pw.println("Replica,Total Envios,Exitosos Total,Salvados ALNS,Rechazados Finales,"
                         + "Transito Final (min),Tiempo ALNS (s),Iteraciones ALNS,Mejor FO,Tasa Exito Final (%)");
            }
            try (PrintWriter pw = new PrintWriter(new FileWriter(archivoConv))) {
                pw.println("Replica,iteracion,ms_transcurridos,mejor_fitness");
            }

            // 15 réplicas para este nivel
            for (int rep = 1; rep <= NUM_REPLICAS; rep++) {
                long semilla = SEMILLAS[rep - 1];
                System.out.printf("  Réplica %d/%d (semilla=%d)...%n", rep, NUM_REPLICAS, semilla);

                ActiveShipmentPool pool = new ActiveShipmentPool(envios.size() + 1000);
                RouteStore routes = new RouteStore(envios.size() + 1000);
                FlightCapacityStore flights = new FlightCapacityStore();
                AirportCapacityTimeline airports =
                        new AirportCapacityTimeline(DatosEstaticos.airportCode.length);

                poblarPool(envios, pool, routes);

                List<Integer> criticos = new ArrayList<>();
                for (int i = 0; i < pool.getSize(); i++) criticos.add(i);

                long t0 = System.currentTimeMillis();
                PlanificadorALNS alns = new PlanificadorALNS(pool, routes, flights, airports, semilla);
                ResultadoALNS resultado = alns.ejecutarALNS(criticos, TIEMPO_LIMITE_COLAPSO_MS);
                long tiempoMs = System.currentTimeMillis() - t0;

                long[] metricas = calcularMetricasPool(pool, routes);
                long exitosos    = metricas[0];
                long rechazados  = metricas[1];
                long transitoMin = metricas[2];
                double pctColapso = envios.size() > 0 ? rechazados * 100.0 / envios.size() : 0;
                double tasa       = envios.size() > 0 ? exitosos   * 100.0 / envios.size() : 0;

                System.out.printf("    Exitosos: %,d | Rechazados: %,d | Colapso: %.2f%%%n",
                        exitosos, rechazados, pctColapso);

                // Resumen global (append)
                try (PrintWriter pw = new PrintWriter(new FileWriter(archivoDatosSummary, true))) {
                    pw.printf("%d,ALNS,%d,%.2f%n", rep, envios.size(), pctColapso);
                }
                // Detalle por nivel (append)
                try (PrintWriter pw = new PrintWriter(new FileWriter(archivoNivel, true))) {
                    pw.printf("%d,%d,%d,%d,%d,%d,%.3f,%d,%d,%.4f%n",
                            rep, envios.size(), exitosos, resultado.reparados, rechazados,
                            transitoMin, tiempoMs / 1000.0, resultado.iteraciones,
                            (long) resultado.fitnessDespuesALNS, tasa);
                }
                // Convergencia por nivel (append)
                try (PrintWriter pw = new PrintWriter(new FileWriter(archivoConv, true))) {
                    if (resultado.convergencia != null) {
                        for (long[] row : resultado.convergencia) {
                            pw.printf("%d,%d,%d,%d%n", rep, row[0], row[1], row[2]);
                        }
                    }
                }
            }

            System.out.printf("  Nivel %s completado (%d réplicas).%n", et, NUM_REPLICAS);
        }

        System.out.println("\n====== MODO COLAPSO ALNS COMPLETADO ======");
        System.out.println("Archivos en: resultados_colapso/");
        System.out.println("  datos_colapso_alns.csv  (resumen " + NUM_REPLICAS + " réplicas × 5 niveles)");
        System.out.println("  resultados_colapso_ALNS_{nivel}.csv  (detalle por nivel)");
        System.out.println("  convergencia_colapso_ALNS_{nivel}.csv  (convergencia por nivel)");
    }

    // =========================================================================
    // Helpers compartidos
    // =========================================================================

    /**
     * Materializa los envíos del rango UTC dado desde los streams en una lista.
     * {@code maxEnvios} ≤ 0 significa sin límite de volumen.
     */
    private static List<ShipmentRecord> cargarEnviosEnLista(
            long inicioUTC, long finUTC, int maxEnvios) throws IOException {
        List<ShipmentRecord> lista = new ArrayList<>();
        try (ShipmentStreamManager manager = new ShipmentStreamManager()) {
            while (manager.hasNextBefore(finUTC)) {
                ShipmentRecord rec = manager.pollNext();
                if (rec == null) break;
                if (rec.releaseUTC < inicioUTC) continue;
                lista.add(rec);
                if (maxEnvios > 0 && lista.size() >= maxEnvios) break;
            }
        }
        return lista;
    }

    /** Puebla un pool vacío a partir de una lista pre-cargada. */
    private static void poblarPool(List<ShipmentRecord> envios,
            ActiveShipmentPool pool, RouteStore routes) {
        for (ShipmentRecord rec : envios) {
            int idx = pool.addShipment(rec);
            routes.ensureCapacity(pool.getCapacity());
            pool.setStatus(idx, ActiveShipmentPool.PENDIENTE);
        }
    }

    /**
     * Calcula métricas del pool tras ejecutar el ALNS.
     *
     * @return {@code long[]{exitosos, rechazados, transitoMin}}
     */
    private static long[] calcularMetricasPool(ActiveShipmentPool pool, RouteStore routes) {
        long exitosos = 0, rechazados = 0, transitoMin = 0;
        for (int i = 0; i < pool.getSize(); i++) {
            byte s = pool.getStatus(i);
            if (s == ActiveShipmentPool.ENTREGADO) {
                exitosos++;
                int rLen = routes.getRouteLength(i);
                if (rLen > 0) {
                    int  fl    = routes.getFlightId(i, rLen - 1);
                    long dep   = routes.getDepartureUTC(i, rLen - 1);
                    int durMin = DatosEstaticos.flightArrivalUTCMinuteOfDay[fl]
                               - DatosEstaticos.flightDepartureUTCMinuteOfDay[fl];
                    if (durMin <= 0) durMin += 1440;
                    transitoMin += (dep + durMin) - pool.getReleaseUTC(i);
                }
            } else {
                rechazados++;
            }
        }
        return new long[]{exitosos, rechazados, transitoMin};
    }

    /**
     * Escanea todos los streams de envíos y devuelve el inicio UTC del día
     * con mayor volumen de maletas, junto con dicho volumen.
     *
     * @return {@code long[]{inicioDiaPicoUTC, maletasPico}}
     */
    private static long[] encontrarDiaPicoALNS() throws IOException {
        System.out.println("Buscando día pico (scan streaming — puede tardar)...");
        Map<Long, Long> maletasPorDia = new HashMap<>();
        try (ShipmentStreamManager manager = new ShipmentStreamManager()) {
            while (manager.hasNext()) {
                ShipmentRecord rec = manager.pollNext();
                if (rec == null) continue;
                long dia = rec.releaseUTC / 1440L;
                maletasPorDia.merge(dia, (long) rec.quantity, Long::sum);
            }
        }
        if (maletasPorDia.isEmpty()) {
            throw new RuntimeException("No se encontraron envíos en los archivos de streaming.");
        }
        long peakDay   = maletasPorDia.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow().getKey();
        long maletasPico = maletasPorDia.get(peakDay);
        System.out.printf("Día pico: día %d desde epoch | Maletas: %,d%n", peakDay, maletasPico);
        return new long[]{peakDay * 1440L, maletasPico};
    }

    /**
     * Cuenta cuántos registros (envíos individuales) tienen releaseUTC en
     * {@code [inicioUTC, finUTC)}, usando streaming sin cargar en memoria.
     */
    private static long contarEnviosPicoDia(long inicioUTC, long finUTC) throws IOException {
        long count = 0;
        try (ShipmentStreamManager manager = new ShipmentStreamManager()) {
            while (manager.hasNextBefore(finUTC)) {
                ShipmentRecord rec = manager.pollNext();
                if (rec == null) break;
                if (rec.releaseUTC >= inicioUTC) count++;
            }
        }
        return count;
    }
}
