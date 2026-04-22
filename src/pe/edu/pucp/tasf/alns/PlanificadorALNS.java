package pe.edu.pucp.tasf.alns;
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
        best = new SolucionALNS(current);

        final boolean fastMode = current.rutas.size() >= 200_000;
        final int evalStride = fastMode ? 25 : Math.max(1, config.iteraciones / 20); // Más agresivo: recalc cada 5%
        
        // Cache inicial de contador de asignados
        int numAsignados = 0;
        for (RutaEnvio r : current.rutas) {
            if (r.estado != RutaEnvio.Estado.UNASSIGNED) numAsignados++;
        }

        for (int iter = 0; iter < config.iteraciones; iter++) {
            // Seleccionar destroy
            int dIdx = seleccionarOperador(pesosDestroy, rand);
            // Seleccionar repair
            int rIdx = seleccionarOperador(pesosRepair, rand);
            long objectiveAntes = current.objective;

            // Destroy - adaptar porcentaje de remoción moderadamente según tamaño dataset
            int numRemove;
            if (numAsignados <= 100) {
                // Para datasets muy pequeños: 2-8% (rápido pero con exploración)
                numRemove = 2 + rand.nextInt(7);
            } else if (numAsignados <= 1000) {
                // Para datasets pequeños: 3-12% (exploración equilibrada)
                numRemove = 3 + rand.nextInt(10);
            } else {
                // Para datasets grandes: 5-20% (comportamiento original)
                numRemove = 5 + rand.nextInt(16);
            }
            List<RutaBackup> removidos = destroys[dIdx].destroy(current, rand, numRemove);
            List<RutaEnvio> rutasRemovidas = new ArrayList<>(removidos.size());
            for (RutaBackup rb : removidos) {
                rutasRemovidas.add(rb.ruta);
            }

            // Repair
            repairs[rIdx].repair(current, rutasRemovidas, datos, config, rand);

            // Evaluar
            boolean recalcOcupacion = (iter % evalStride == 0);
            if (recalcOcupacion) {
                evaluador.actualizarOcupacion(current);
            }
            current.calcularObjective(config);
            long delta = current.objective - objectiveAntes;

            // Aceptación
            boolean accepted = false;
            if (delta < 0) {
                accepted = true;
                actualizarPesos(dIdx, rIdx, 1); // sigma1
                if (current.objective < best.objective) {
                    best = new SolucionALNS(current);
                }
            } else if (delta == 0) {
                accepted = true;
                actualizarPesos(dIdx, rIdx, 2); // sigma2
            } else {
                // Simulated Annealing
                double prob = Math.exp(-((double) delta) / Math.max(temperatura, 1e-9));
                if (rand.nextDouble() < prob) {
                    accepted = true;
                    actualizarPesos(dIdx, rIdx, 3); // sigma3
                } else {
                    actualizarPesos(dIdx, rIdx, 4); // sigma4
                }
            }

            if (!accepted) {
                for (RutaBackup rb : removidos) {
                    rb.restore();
                }
                if (recalcOcupacion) {
                    evaluador.actualizarOcupacion(current);
                    current.calcularObjective(config);
                } else {
                    current.objective = objectiveAntes;
                }
            }

            // Actualizar contador de asignados solo si cambió el estado de alguna ruta
            numAsignados = 0;
            for (RutaEnvio r : current.rutas) {
                if (r.estado != RutaEnvio.Estado.UNASSIGNED) numAsignados++;
            }

            // Cooling
            temperatura *= config.coolingRate;

            // Actualizar métricas operadores
            metricas.seleccionDestroy[dIdx]++;
            if (accepted) metricas.acceptedDestroy[dIdx]++;
            if (current.objective == best.objective) metricas.improvementDestroy[dIdx]++;
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
        double total = 0.0;
        for (double peso : pesos) total += peso;
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