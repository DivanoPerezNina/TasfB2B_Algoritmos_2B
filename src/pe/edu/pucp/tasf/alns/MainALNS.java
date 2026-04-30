package pe.edu.pucp.tasf.alns;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainALNS {
    public static void main(String[] args) throws IOException {
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
}