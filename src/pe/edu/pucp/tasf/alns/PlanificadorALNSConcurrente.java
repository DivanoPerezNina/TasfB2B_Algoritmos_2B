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
                configHilo.iteraciones = iterPorHilo;
                configHilo.seed = seedHilo;
                // Copiar otros
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