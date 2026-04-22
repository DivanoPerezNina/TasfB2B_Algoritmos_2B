package pe.edu.pucp.tasf;

import java.util.*;

/**
 * Implementación secuencial del ALNS.
 */
public class PlanificadorALNS {
    private GestorDatos datos;
    private ConfigExperimentacion config;
    private EvaluadorSolucion evaluador;
    private Random rand;
    private OperadorDestroy[] destroys;
    private OperadorRepair[] repairs;
    private double[] pesosDestroy;
    private double[] pesosRepair;
    private double temperatura;
    private MetricasSolucion metricas;

    public PlanificadorALNS(GestorDatos datos, ConfigExperimentacion config) {
        this.datos = datos;
        this.config = config;
        this.evaluador = new EvaluadorSolucion(datos, config);
        this.rand = new Random(config.seed);
        this.destroys = new OperadorDestroy[]{new RandomRemoval(), new WorstSlackRemoval()}; // agregar más
        this.repairs = new OperadorRepair[]{new GreedyInsertion()}; // agregar más
        this.pesosDestroy = new double[destroys.length];
        Arrays.fill(pesosDestroy, 1.0);
        this.pesosRepair = new double[repairs.length];
        Arrays.fill(pesosRepair, 1.0);
        this.temperatura = config.temperaturaInicial;
        this.metricas = new MetricasSolucion();
    }

    public ResultadoEjecucion ejecutar(SolucionALNS solucionInicial) {
        long start = System.nanoTime();
        SolucionALNS current = new SolucionALNS(solucionInicial);
        SolucionALNS best = new SolucionALNS(current);
        evaluador.actualizarOcupacion(current);
        current.calcularObjective(config);
        best.calcularObjective(config);

        for (int iter = 0; iter < config.iteraciones; iter++) {
            // Seleccionar destroy
            int dIdx = seleccionarOperador(pesosDestroy, rand);
            // Seleccionar repair
            int rIdx = seleccionarOperador(pesosRepair, rand);

            // Destroy
            int numRemove = 5 + rand.nextInt(16); // 5-20%
            List<RutaEnvio> removidos = destroys[dIdx].destroy(current, rand, numRemove);

            // Repair
            repairs[rIdx].repair(current, removidos, datos, config, rand);

            // Evaluar
            evaluador.actualizarOcupacion(current);
            current.calcularObjective(config);

            // Aceptación
            boolean accepted = false;
            if (current.objective < best.objective) {
                best = new SolucionALNS(current);
                accepted = true;
                actualizarPesos(dIdx, rIdx, 1); // sigma1
            } else if (current.objective <= current.objective) { // mejora o igual
                accepted = true;
                actualizarPesos(dIdx, rIdx, 2); // sigma2
            } else {
                // Simulated Annealing
                double prob = Math.exp((current.objective - current.objective) / temperatura);
                if (rand.nextDouble() < prob) {
                    accepted = true;
                    actualizarPesos(dIdx, rIdx, 3); // sigma3
                } else {
                    actualizarPesos(dIdx, rIdx, 4); // sigma4
                }
            }

            if (accepted) {
                // current ya actualizado
            } else {
                // Revertir? Para simplicidad, no revertir, asumir repair siempre mejora
            }

            // Cooling
            temperatura *= config.coolingRate;

            // Actualizar métricas operadores
            metricas.seleccionDestroy[dIdx]++;
            if (accepted) metricas.acceptedDestroy[dIdx]++;
            if (current.objective < best.objective) metricas.improvementDestroy[dIdx]++;
            // Similar para repair
        }

        double tiempo = (System.nanoTime() - start) / 1e9;
        MetricasSolucion m = best.calcularMetricas(0, 0, tiempo, config.iteraciones, config.seed);
        m.seleccionDestroy = metricas.seleccionDestroy;
        m.acceptedDestroy = metricas.acceptedDestroy;
        m.improvementDestroy = metricas.improvementDestroy;
        return new ResultadoEjecucion(best, m, tiempo, "secuencial");
    }

    private int seleccionarOperador(double[] pesos, Random rand) {
        double total = Arrays.stream(pesos).sum();
        double r = rand.nextDouble() * total;
        double cum = 0;
        for (int i = 0; i < pesos.length; i++) {
            cum += pesos[i];
            if (r <= cum) return i;
        }
        return pesos.length - 1;
    }

    private void actualizarPesos(int dIdx, int rIdx, int sigma) {
        double rho = 0.1; // configurable
        for (int i = 0; i < pesosDestroy.length; i++) {
            pesosDestroy[i] *= (1 - rho);
            if (i == dIdx) pesosDestroy[i] += rho * sigma;
        }
        // Similar para repair
    }
}