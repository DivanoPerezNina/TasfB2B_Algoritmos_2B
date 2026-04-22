package pe.edu.pucp.tasf;

import java.io.*;
import java.util.*;
import java.util.*;

/**
 * Main para ejecutar experimentos ALNS.
 */
public class MainALNS {
    public static void main(String[] args) throws IOException {
        ConfigExperimentacion config = new ConfigExperimentacion(args);

        System.out.println("=== ALNS Planificador Logistico Aereo ===");
        System.out.println("Parametros: maxEnvios=" + config.maxEnvios + ", iteraciones=" + config.iteraciones + ", hilos=" + config.threads);
        System.out.println("");
        System.out.println("Cargando datos...");
        
        // Cargar datos
        long startCarga = System.nanoTime();
        GestorDatos datos = new GestorDatos();
        datos.cargarDatos(config.pathAeropuertos, config.pathVuelos, config.pathEnviosDir, config.maxEnvios);
        double tiempoCarga = (System.nanoTime() - startCarga) / 1e9;

        // Solución inicial greedy
        long startGreedy = System.nanoTime();
        SolucionALNS inicial = construirGreedy(datos, config);
        double tiempoGreedy = (System.nanoTime() - startGreedy) / 1e9;

        // ALNS secuencial
        PlanificadorALNS plannerSeq = new PlanificadorALNS(datos, config);
        ResultadoEjecucion resSeq = plannerSeq.ejecutar(inicial);

        // ALNS concurrente
        PlanificadorALNSConcurrente plannerConc = new PlanificadorALNSConcurrente(datos, config);
        ResultadoEjecucion resConc = plannerConc.ejecutar(inicial);

        // Imprimir resúmenes
        imprimirResumen(resSeq);
        imprimirResumen(resConc);

        // Comparacion de resultados
        System.out.println("");
        System.out.println("==========================================");
        System.out.println("COMPARACION DE VERSIONES");
        System.out.println("==========================================");
        System.out.println("Costo secuencial: " + resSeq.metricas.bestObjective);
        System.out.println("Costo concurrente: " + resConc.metricas.bestObjective);
        System.out.println("Tiempo secuencial: " + String.format("%.3f", resSeq.tiempoSeg) + " s");
        System.out.println("Tiempo concurrente: " + String.format("%.3f", resConc.tiempoSeg) + " s");
        double speedup = resSeq.tiempoSeg / resConc.tiempoSeg;
        System.out.println("Aceleracion (speedup): " + String.format("%.2f", speedup) + "x");
        String mejor = (resSeq.metricas.bestObjective <= resConc.metricas.bestObjective ? "Secuencial" : "Concurrente");
        System.out.println("Mejor solucion: " + mejor + " (menor costo)");
        System.out.println("");
        System.out.println("Nota: El 'costo' mide penalizaciones por envios sin asignar y atrasados.");
        System.out.println("Un costo mas bajo significa una solucion mejor.");
        System.out.println("==========================================");

        // Exportar CSVs
        exportarCSVs(resSeq, "secuencial");
        exportarCSVs(resConc, "concurrente");
    }

    private static SolucionALNS construirGreedy(GestorDatos datos, ConfigExperimentacion config) {
        SolucionALNS sol = new SolucionALNS(datos.envios);
        
        // Para datasets muy grandes (>100K), usar muestreo estratégico
        int totalEnvios = sol.rutas.size();
        int muestra = Math.min(totalEnvios, 5000); // máximo 5K a procesar
        Random rand = new Random(config.seed);
        
        System.out.println("Construyendo solucion inicial...");
        if (totalEnvios > muestra) {
            System.out.println("  Dataset grande: " + totalEnvios + " envios. Usando muestra de " + muestra);
            java.util.Set<Integer> indices = new java.util.HashSet<>();
            while (indices.size() < muestra) {
                indices.add(rand.nextInt(totalEnvios));
            }
            for (Integer idx : indices) {
                RutaEnvio r = sol.rutas.get(idx);
                if (r.estado == RutaEnvio.Estado.UNASSIGNED) {
                    RutaEnvio mejor = GreedyInsertion.encontrarMejorRuta(r.envio, datos, config);
                    if (mejor != null) {
                        r.tramos.addAll(mejor.tramos);
                        r.calcularMetricas();
                    }
                }
            }
        } else {
            for (RutaEnvio r : sol.rutas) {
                if (r.estado == RutaEnvio.Estado.UNASSIGNED) {
                    RutaEnvio mejor = GreedyInsertion.encontrarMejorRuta(r.envio, datos, config);
                    if (mejor != null) {
                        r.tramos.addAll(mejor.tramos);
                        r.calcularMetricas();
                    }
                }
            }
        }
        EvaluadorSolucion eval = new EvaluadorSolucion(datos, config);
        eval.actualizarOcupacion(sol);
        sol.calcularObjective(config);
        return sol;
    }

    private static void imprimirResumen(ResultadoEjecucion res) {
        MetricasSolucion m = res.metricas;
        System.out.println("==========================================");
        System.out.println("VERSION: " + res.version.toUpperCase());
        System.out.println("==========================================");
        System.out.println("Envios totales: " + m.totalEnvios);
        System.out.println("Envios a tiempo: " + m.enviosOnTime);
        System.out.println("Envios atrasados: " + m.enviosLate);
        System.out.println("Envios sin asignar: " + m.enviosUnassigned);
        System.out.println("Minutos totales de atraso: " + m.tardanzaTotalMin);
        System.out.println("Tiempo de ejecucion: " + String.format("%.3f", res.tiempoSeg) + " segundos");
        System.out.println("Costo total (penalizaciones): " + m.bestObjective);
        System.out.println("==========================================");
    }

    private static void exportarCSVs(ResultadoEjecucion res, String suffix) throws IOException {
        // resumen_experimentos.csv
        try (PrintWriter pw = new PrintWriter("resumen_experimentos_" + suffix + ".csv")) {
            pw.println("version,seed,totalEnvios,totalMaletas,enviosOnTime,maletasOnTime,enviosLate,maletasLate,enviosUnassigned,maletasUnassigned,tardanzaTotalMin,tardanzaPromedioMin,tardanzaMaxMin,hopsPromedio,waitingPromedioMin,utilizacionPromedioVuelos,vuelosSaturados,sobrecapacidadAlmacen,tiempoCargaSeg,tiempoGreedySeg,tiempoALNSSeg,tiempoTotalSeg,iteraciones,bestObjective");
            MetricasSolucion m = res.metricas;
            pw.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%.2f,%.2f,%.2f,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d%n",
                res.version, m.seed, m.totalEnvios, m.totalMaletas, m.enviosOnTime, m.maletasOnTime, m.enviosLate, m.maletasLate,
                m.enviosUnassigned, m.maletasUnassigned, m.tardanzaTotalMin, m.tardanzaPromedioMin, m.tardanzaMaxMin,
                m.hopsPromedio, m.waitingPromedioMin, m.utilizacionPromedioVuelos, m.vuelosSaturados, m.sobrecapacidadAlmacen,
                m.tiempoCargaSeg, m.tiempoGreedySeg, m.tiempoALNSSeg, m.tiempoTotalSeg, m.iteraciones, m.bestObjective);
        }

        // detalle_rutas.csv
        try (PrintWriter pw = new PrintWriter("detalle_rutas_" + suffix + ".csv")) {
            pw.println("envioId,origen,destino,maletas,registroUTC,deadlineUTC,estado,tardanzaMin,hops,vuelo1,salida1,llegada1,vuelo2,salida2,llegada2,vuelo3,salida3,llegada3");
            for (RutaEnvio r : res.solucion.rutas) {
                pw.print(r.envio.id + "," + r.envio.origen + "," + r.envio.destino + "," + r.envio.maletas + "," +
                    r.envio.registroUTC + "," + r.envio.deadlineUTC + "," + r.estado + "," + r.tardanzaMin + "," + r.hops);
                for (int i = 0; i < 3; i++) {
                    if (i < r.tramos.size()) {
                        TramoAsignado t = r.tramos.get(i);
                        pw.print("," + t.vuelo.origen + "-" + t.vuelo.destino + "," + t.salidaReal + "," + t.llegadaReal);
                    } else {
                        pw.print(",,,");
                    }
                }
                pw.println();
            }
        }

        // ocupacion_vuelos.csv y estadisticas_operadores.csv: placeholders
    }
}