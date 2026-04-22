package pe.edu.pucp.tasf.alns;
import java.util.concurrent.*;

/**
 * Versión concurrente del ALNS usando multi-start.
 */
public class PlanificadorALNSConcurrente {
    private GestorDatos datos;
    private ConfigExperimentacion config;

    public PlanificadorALNSConcurrente(GestorDatos datos, ConfigExperimentacion config) {
        this.datos = datos;
        this.config = config;
    }

    public ResultadoEjecucion ejecutar(SolucionALNS solucionInicial) {
        ExecutorService executor = Executors.newFixedThreadPool(config.threads);
        CompletionService<ResultadoEjecucion> completionService = new ExecutorCompletionService<>(executor);

        int iterPorHilo = config.iteraciones / config.threads;

        for (int i = 0; i < config.threads; i++) {
            long seedHilo = config.seed + i;
            completionService.submit(() -> {
                ConfigExperimentacion configHilo = new ConfigExperimentacion(new String[]{});
                configHilo.pathAeropuertos = config.pathAeropuertos;
                configHilo.pathVuelos = config.pathVuelos;
                configHilo.pathEnviosDir = config.pathEnviosDir;
                configHilo.maxEnvios = config.maxEnvios;
                configHilo.iteraciones = iterPorHilo;
                configHilo.threads = config.threads;
                configHilo.seed = seedHilo;
                configHilo.temperaturaInicial = config.temperaturaInicial;
                configHilo.coolingRate = config.coolingRate;
                configHilo.maxSaltos = config.maxSaltos;
                configHilo.tiempoMinEscalaMin = config.tiempoMinEscalaMin;
                configHilo.pesoUnassigned = config.pesoUnassigned;
                configHilo.pesoLateRequests = config.pesoLateRequests;
                configHilo.pesoLateBags = config.pesoLateBags;
                configHilo.pesoTotalLateness = config.pesoTotalLateness;
                configHilo.pesoTotalHops = config.pesoTotalHops;
                configHilo.pesoStorageOveruse = config.pesoStorageOveruse;

                PlanificadorALNS planner = new PlanificadorALNS(datos, configHilo);
                return planner.ejecutar(new SolucionALNS(solucionInicial));
            });
        }

        ResultadoEjecucion mejor = null;
        double tiempoTotal = 0;
        for (int i = 0; i < config.threads; i++) {
            try {
                Future<ResultadoEjecucion> future = completionService.take();
                ResultadoEjecucion res = future.get();
                tiempoTotal = Math.max(tiempoTotal, res.tiempoSeg); // tiempo del más lento
                if (mejor == null || res.metricas.bestObjective < mejor.metricas.bestObjective) {
                    mejor = res;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        executor.shutdown();
        mejor.version = "concurrente";
        mejor.tiempoSeg = tiempoTotal;
        return mejor;
    }
}