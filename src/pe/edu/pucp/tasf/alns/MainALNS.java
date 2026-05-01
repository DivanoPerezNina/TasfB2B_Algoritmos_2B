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
        try {
            ejecutarExperimentoHastaColapsoALNS();
        } catch (Exception e) {
            System.err.println("Error en ALNS hasta colapso: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static final class ResumenEstadoALNS {
        int entregados;
        int pendientes;
        int sinRuta;
        int retrasados;
        int noFactibles;
    }

    public static void ejecutarExperimentoHastaColapsoALNS() throws Exception {
        System.out.println("======================================================");
        System.out.println(" ALNS — Experimento hasta colapso");
        System.out.println("   Condiciones compartidas con GVNS: semillas 42, 12345, 99999, 7777, 31415");
        System.out.println("   Horizonte: dataset completo, avance día a día");
        System.out.println("   Colapso  : primer día con un envío sin salida viable o fuera de SLA");
        System.out.println("======================================================");

        DatosEstaticos.cargarDatos();

        new File("resultados_colapso_alns").mkdirs();
        String csvResumen = "resultados_colapso_alns/resultados_alns_colapso.csv";

        try (PrintWriter pw = new PrintWriter(new FileWriter(csvResumen, false))) {
            pw.println("Replica,Semilla,DiaColapso,InicioDiaColapsoUTCMin,EnviosLeidosTotal,EntregadosFinales,PendientesFinales,SinRutaFinales,RetrasadosFinales,NoFactiblesFinales,RazonParada,TiempoMs");
        }

        long inicioGlobalUTC = Long.MAX_VALUE;
        try (ShipmentStreamManager manager = new ShipmentStreamManager()) {
            inicioGlobalUTC = manager.peekNextReleaseUTC();
        }

        if (inicioGlobalUTC == Long.MAX_VALUE) {
            System.out.println("No hay envíos disponibles para la simulación.");
            return;
        }

        long inicioDiaUTC = (inicioGlobalUTC / 1440L) * 1440L;

        for (int replica = 0; replica < NUM_REPLICAS; replica++) {
            long semilla = SEMILLAS[replica];
            System.out.printf("%n--- Réplica %d/%d (semilla=%d) ---%n", replica + 1, NUM_REPLICAS, semilla);

            ActiveShipmentPool pool = new ActiveShipmentPool(10000);
            RouteStore routes = new RouteStore(10000);
            FlightCapacityStore flights = new FlightCapacityStore();
            AirportCapacityTimeline airports = new AirportCapacityTimeline(DatosEstaticos.airportCode.length);

            long currentTime = inicioDiaUTC;
            long t0 = System.currentTimeMillis();
            int diaRelativo = 0;
            int enviosLeidosTotal = 0;
            int replanificacionesTotal = 0;
            int diaColapso = -1;
            long inicioDiaColapso = -1;
            String razonParada = "FIN_SIN_COLAPSO";
            List<String> diarioRows = new ArrayList<>();

            try (ShipmentStreamManager manager = new ShipmentStreamManager()) {
                while (true) {
                    long blockStart = currentTime;
                    long blockEnd = blockStart + 1440L;
                    int readsThisDay = 0;

                    while (manager.hasNextBefore(blockEnd)) {
                        ShipmentRecord rec = manager.pollNext();
                        if (rec == null) {
                            break;
                        }
                        if (rec.releaseUTC < blockStart) {
                            continue;
                        }

                        int activeIdx = pool.addShipment(rec);
                        routes.ensureCapacity(pool.getCapacity());
                        pool.setStatus(activeIdx, ActiveShipmentPool.PENDIENTE);
                        readsThisDay++;
                        enviosLeidosTotal++;
                    }

                    List<Integer> criticos = new ArrayList<>();
                    for (int i = 0; i < pool.getSize(); i++) {
                        int status = pool.getStatus(i);
                        if (status == ActiveShipmentPool.SIN_RUTA
                                || status == ActiveShipmentPool.PENDIENTE
                                || status == ActiveShipmentPool.RETRASADO) {
                            criticos.add(i);
                        }
                    }

                    ResultadoALNS resultadoALNS;
                    if (criticos.isEmpty()) {
                        resultadoALNS = new ResultadoALNS();
                    } else {
                        PlanificadorALNS alns = new PlanificadorALNS(pool, routes, flights, airports, semilla);
                        resultadoALNS = alns.ejecutarALNS(criticos, ConfigExperimentacion.TIEMPO_LIMITE_ALNS_MS);
                        replanificacionesTotal++;
                    }

                    ResumenEstadoALNS resumen = resumirEstado(pool, blockEnd);
                    boolean colapso = resumen.noFactibles > 0 || resumen.retrasados > 0;
                    String detalle = resumen.noFactibles > 0 ? "NO_FACTIBLE_ESTRUCTURAL"
                            : (resumen.retrasados > 0 ? "RETRASO_SLA" : "OK");

                        diarioRows.add(String.format("%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s",
                            replica + 1, semilla, diaRelativo + 1, blockStart, blockEnd,
                            readsThisDay, resumen.entregados, resumen.pendientes,
                            resumen.sinRuta, resumen.retrasados, resumen.noFactibles,
                            replanificacionesTotal, colapso ? "SI" : "NO", detalle));

                    System.out.printf("Día %d | leídos=%d | entregados=%d | pendientes=%d | sinRuta=%d | retrasados=%d | noFactibles=%d | %s%n",
                            diaRelativo + 1, readsThisDay, resumen.entregados, resumen.pendientes,
                            resumen.sinRuta, resumen.retrasados, resumen.noFactibles,
                            colapso ? ("COLAPSO " + detalle) : "OK");

                    if (colapso) {
                        diaColapso = diaRelativo + 1;
                        inicioDiaColapso = blockStart;
                        razonParada = detalle;
                        System.out.printf("*** COLAPSO en el día %d (UTC min %d) ***%n", diaColapso, inicioDiaColapso);
                        break;
                    }

                    if (!manager.hasNext() && !hayEnviosPorResolver(resumen)) {
                        break;
                    }

                    if (!manager.hasNext() && hayEnviosPorResolver(resumen)) {
                        currentTime = blockEnd;
                        diaRelativo++;
                        if (diaRelativo >= 3650) {
                            razonParada = "LIMITE_SEGURIDAD";
                            break;
                        }
                        continue;
                    }

                    if (readsThisDay == 0 && !hayEnviosPorResolver(resumen) && manager.hasNext()) {
                        long siguienteInicio = manager.peekNextReleaseUTC();
                        currentTime = (siguienteInicio / 1440L) * 1440L;
                        continue;
                    }

                    currentTime = blockEnd;
                    diaRelativo++;
                    if (diaRelativo >= 3650) {
                        razonParada = "LIMITE_SEGURIDAD";
                        break;
                    }
                }
            }

            ResumenEstadoALNS finalState = resumirEstado(pool, currentTime);
            long tiempoMs = System.currentTimeMillis() - t0;

            // Si ocurrió colapso, exportar 2 CSVs: (A) detalle envíos hasta colapso, (B) resumen por días hasta colapso
            if (diaColapso != -1) {
                String csvEnvios = String.format("resultados_colapso_alns/envios_hasta_colapso_replica_%d_semilla_%d.csv",
                        replica + 1, semilla);
                String csvResumenDias = String.format("resultados_colapso_alns/resumen_dias_hasta_colapso_replica_%d_semilla_%d.csv",
                        replica + 1, semilla);

                try (PrintWriter pw = new PrintWriter(new FileWriter(csvEnvios))) {
                    pw.println("Idx,ShipmentId,Origin,Destination,Quantity,ReleaseUTC,DeadlineUTC,Status,RouteLength,RouteDetail");
                    for (int i = 0; i < pool.getSize(); i++) {
                        long sid = pool.getShipmentId(i);
                        int origin = pool.getOrigin(i);
                        int dest = pool.getDestination(i);
                        int qty = pool.getQuantity(i);
                        long rel = pool.getReleaseUTC(i);
                        long dl = pool.getDeadlineUTC(i);
                        byte st = pool.getStatus(i);
                        int rlen = routes.getRouteLength(i);
                        StringBuilder rd = new StringBuilder();
                        for (int h = 0; h < rlen; h++) {
                            if (h > 0) rd.append(';');
                            int fl = routes.getFlightId(i, h);
                            long dep = routes.getDepartureUTC(i, h);
                            rd.append(fl).append('@').append(dep);
                        }
                        String statusStr;
                        switch (st) {
                            case ActiveShipmentPool.ENTREGADO: statusStr = "ENTREGADO"; break;
                            case ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL: statusStr = "NO_FACTIBLE_ESTRUCTURAL"; break;
                            case ActiveShipmentPool.RETRASADO: statusStr = "RETRASADO"; break;
                            case ActiveShipmentPool.SIN_RUTA: statusStr = "SIN_RUTA"; break;
                            case ActiveShipmentPool.PENDIENTE: statusStr = "PENDIENTE"; break;
                            default: statusStr = Byte.toString(st); break;
                        }
                        pw.printf("%d,%d,%d,%d,%d,%d,%d,%s,%d,%s%n",
                                i, sid, origin, dest, qty, rel, dl, statusStr, rlen, rd.toString());
                    }
                } catch (IOException e) {
                    System.err.println("Error escribiendo " + csvEnvios + ": " + e.getMessage());
                }

                try (PrintWriter pw = new PrintWriter(new FileWriter(csvResumenDias))) {
                    pw.println("Replica,Semilla,Dia,InicioUTCMin,FinUTCMin,EnviosLeidosDia,EntregadosAcum,PendientesAcum,SinRutaAcum,RetrasadosAcum,NoFactiblesAcum,ReplanificacionesAcum,Colapso,Detalle");
                    for (String row : diarioRows) pw.println(row);
                } catch (IOException e) {
                    System.err.println("Error escribiendo " + csvResumenDias + ": " + e.getMessage());
                }
            }

            try (PrintWriter pw = new PrintWriter(new FileWriter(csvResumen, true))) {
                pw.printf("%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%d%n",
                        replica + 1, semilla, diaColapso, inicioDiaColapso,
                        enviosLeidosTotal, finalState.entregados, finalState.pendientes,
                        finalState.sinRuta, finalState.retrasados, finalState.noFactibles,
                        razonParada, tiempoMs);
            }

            System.out.printf("Resumen réplica %d | diaColapso=%d | razon=%s | tiempo=%.1fs%n",
                    replica + 1, diaColapso, razonParada, tiempoMs / 1000.0);
        }

        System.out.println("\nCSV generados:");
        System.out.println("- " + csvResumen);
        System.out.println("- Detalle y resumen por días (por réplica) en resultados_colapso_alns/");
    }

    private static ResumenEstadoALNS resumirEstado(ActiveShipmentPool pool, long currentTime) {
        ResumenEstadoALNS resumen = new ResumenEstadoALNS();
        for (int i = 0; i < pool.getSize(); i++) {
            int status = pool.getStatus(i);
            if (status == ActiveShipmentPool.ENTREGADO) {
                resumen.entregados++;
            } else if (status == ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL) {
                resumen.noFactibles++;
            } else if (status == ActiveShipmentPool.RETRASADO) {
                resumen.retrasados++;
            } else if (status == ActiveShipmentPool.SIN_RUTA
                    || status == ActiveShipmentPool.PENDIENTE
                    || status == ActiveShipmentPool.EN_ALMACEN
                    || status == ActiveShipmentPool.PLANIFICADO
                    || status == ActiveShipmentPool.NUEVO) {
                if (pool.getDeadlineUTC(i) < currentTime) {
                    resumen.retrasados++;
                } else if (status == ActiveShipmentPool.SIN_RUTA) {
                    resumen.sinRuta++;
                } else {
                    resumen.pendientes++;
                }
            }
        }
        return resumen;
    }

    private static boolean hayEnviosPorResolver(ResumenEstadoALNS resumen) {
        return resumen.pendientes > 0 || resumen.sinRuta > 0 || resumen.retrasados > 0;
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
