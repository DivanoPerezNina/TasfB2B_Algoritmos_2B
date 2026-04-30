package pe.edu.pucp.tasf.alns;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PlanificadorALNS {
    private ActiveShipmentPool pool;
    private RouteStore routes;
    private FlightCapacityStore flights;
    private AirportCapacityTimeline airports;
    private final Random rand;

    // Pesos para operadores
    private double[] destroyWeights = {1,1,1,1,1};
    private double[] repairWeights = {1,1,1,1};
    private int[] destroyUses = {0,0,0,0,0};
    private int[] repairUses = {0,0,0,0};
    private double[] destroyScores = {0,0,0,0,0};
    private double[] repairScores = {0,0,0,0};

    /** Constructor reproducible: pasa una semilla fija para experimentos. */
    public PlanificadorALNS(ActiveShipmentPool p, RouteStore r, FlightCapacityStore f,
                             AirportCapacityTimeline a, long seed) {
        pool = p; routes = r; flights = f; airports = a;
        rand = new Random(seed);
    }

    /** Constructor sin semilla (modo normal del compañero — no determinista). */
    public PlanificadorALNS(ActiveShipmentPool p, RouteStore r, FlightCapacityStore f, AirportCapacityTimeline a) {
        this(p, r, f, a, System.currentTimeMillis());
    }

    public ResultadoALNS ejecutarALNS(List<Integer> criticos, long timeLimitMs) {
        return ejecutarALNS(criticos, timeLimitMs, ConfigExperimentacion.MAX_ITERACIONES_ALNS);
    }

    public ResultadoALNS ejecutarALNS(List<Integer> criticos, long timeLimitMs, int maxIteraciones) {
        ResultadoALNS resultado = new ResultadoALNS();
        if (criticos == null || criticos.isEmpty()) {
            resultado.llamadasALNS = 0;
            resultado.iteraciones = 0;
            resultado.criticosAntes = 0;
            resultado.criticosDespues = 0;
            resultado.sinRutaAntes = 0;
            resultado.sinRutaDespues = 0;
            resultado.pendientesAntes = 0;
            resultado.pendientesDespues = 0;
            resultado.reparados = 0;
            resultado.empeorados = 0;
            resultado.fitnessAntesALNS = EvaluadorSolucion.calcularFitness(pool, routes, 0);
            resultado.fitnessDespuesALNS = resultado.fitnessAntesALNS;
            resultado.mejoro = false;
            return resultado;
        }

        resultado.llamadasALNS = 1;
        resultado.sinRutaAntes = 0;
        resultado.pendientesAntes = 0;
        resultado.retrasadosAntes = 0;
        for (int idx : criticos) {
            int status = pool.getStatus(idx);
            if (status == ActiveShipmentPool.SIN_RUTA) {
                resultado.sinRutaAntes++;
            } else if (status == ActiveShipmentPool.PENDIENTE || status == ActiveShipmentPool.EN_ALMACEN || status == ActiveShipmentPool.PLANIFICADO) {
                resultado.pendientesAntes++;
            } else if (status == ActiveShipmentPool.RETRASADO) {
                resultado.retrasadosAntes++;
            }
        }
        resultado.criticosAntes = resultado.sinRutaAntes + resultado.pendientesAntes + resultado.retrasadosAntes;
        resultado.fitnessAntesALNS = EvaluadorSolucion.calcularFitness(pool, routes, 0);
        resultado.convergencia = new ArrayList<>();

        long start = System.currentTimeMillis();
        double bestFitness = resultado.fitnessAntesALNS;
        int iter = 0;
        while (System.currentTimeMillis() - start < timeLimitMs && iter < maxIteraciones) {
            OperadorDestroy destroy = seleccionarDestroy();
            OperadorRepair repair = seleccionarRepair();
            List<Integer> removidos = destroyOperacion(destroy, criticos);
            for (int idx : removidos) {
                liberarReservas(idx);
                routes.clearRoute(idx);
                pool.setStatus(idx, ActiveShipmentPool.SIN_RUTA);
            }
            for (int idx : removidos) {
                planificarRutaInicial(idx, repair);
            }
            double fitness = EvaluadorSolucion.calcularFitness(pool, routes, 0);
            if (fitness < bestFitness) {
                bestFitness = fitness;
                actualizarPesos(destroy, repair, 10);
            } else {
                actualizarPesos(destroy, repair, -1);
            }
            long msActual = System.currentTimeMillis() - start;
            resultado.convergencia.add(new long[]{iter + 1, msActual, (long) bestFitness});
            iter++;
        }

        resultado.iteraciones = iter;
        resultado.fitnessDespuesALNS = EvaluadorSolucion.calcularFitness(pool, routes, 0);
        resultado.sinRutaDespues = 0;
        resultado.pendientesDespues = 0;
        resultado.retrasadosDespues = 0;
        for (int i = 0; i < pool.getSize(); i++) {
            int status = pool.getStatus(i);
            if (status == ActiveShipmentPool.SIN_RUTA) {
                resultado.sinRutaDespues++;
            } else if (status == ActiveShipmentPool.PENDIENTE || status == ActiveShipmentPool.EN_ALMACEN || status == ActiveShipmentPool.PLANIFICADO) {
                resultado.pendientesDespues++;
            } else if (status == ActiveShipmentPool.RETRASADO) {
                resultado.retrasadosDespues++;
            }
        }
        resultado.criticosDespues = resultado.sinRutaDespues + resultado.pendientesDespues + resultado.retrasadosDespues;
        resultado.reparados = Math.max(0, resultado.criticosAntes - resultado.criticosDespues);
        resultado.empeorados = Math.max(0, resultado.criticosDespues - resultado.criticosAntes);
        resultado.mejoro = resultado.fitnessDespuesALNS < resultado.fitnessAntesALNS;
        return resultado;
    }

    private OperadorDestroy seleccionarDestroy() {
        double total = 0;
        for (double w : destroyWeights) total += w;
        double r = rand.nextDouble() * total;
        double cum = 0;
        for (int i = 0; i < destroyWeights.length; i++) {
            cum += destroyWeights[i];
            if (r <= cum) return OperadorDestroy.values()[i];
        }
        return OperadorDestroy.RANDOM_REMOVAL;
    }

    private OperadorRepair seleccionarRepair() {
        double total = 0;
        for (double w : repairWeights) total += w;
        double r = rand.nextDouble() * total;
        double cum = 0;
        for (int i = 0; i < repairWeights.length; i++) {
            cum += repairWeights[i];
            if (r <= cum) return OperadorRepair.values()[i];
        }
        return OperadorRepair.EARLIEST_ARRIVAL_INSERTION;
    }

    private List<Integer> destroyOperacion(OperadorDestroy op, List<Integer> criticos) {
        // Implementar lógica de destroy, ej random
        List<Integer> removidos = new ArrayList<>();
        int num = Math.min(10, criticos.size());
        for (int i = 0; i < num; i++) {
            removidos.add(criticos.get(rand.nextInt(criticos.size())));
        }
        return removidos;
    }

    private void liberarReservas(int idx) {
        // Implementar liberar
    }

    private boolean planificarRutaInicial(int idx, OperadorRepair repair) {
        // Implementar búsqueda de ruta
        // Para brevedad, buscar directo
        int orig = pool.getOrigin(idx);
        int dest = pool.getDestination(idx);
        int qty = pool.getQuantity(idx);
        long release = pool.getReleaseUTC(idx);
        // Buscar vuelo directo
        boolean foundCandidate = false;
        for (int f = 0; f < DatosEstaticos.numFlights; f++) {
            if (DatosEstaticos.flightOrigin[f] == orig && DatosEstaticos.flightDestination[f] == dest) {
                long dep = release / 1440 * 1440 + DatosEstaticos.flightDepartureUTCMinuteOfDay[f];
                if (dep >= release) {
                    foundCandidate = true;
                    if (flights.canReserve(f, dep, qty) && airports.canReserveInterval(orig, release, dep, qty)) {
                        flights.reserve(f, dep, qty);
                        airports.reserveInterval(orig, release, dep, qty);
                        int start = routes.allocateRoute(1);
                        routes.setRoute(idx, start, 1, new int[]{f}, new long[]{dep});
                        long arrivalUTC = dep + (DatosEstaticos.flightArrivalUTCMinuteOfDay[f] - DatosEstaticos.flightDepartureUTCMinuteOfDay[f]);
                        if (arrivalUTC <= pool.getDeadlineUTC(idx)) {
                            pool.setStatus(idx, ActiveShipmentPool.ENTREGADO);
                        } else {
                            pool.setStatus(idx, ActiveShipmentPool.RETRASADO);
                        }
                        return true;
                    }
                }
            }
        }
        if (foundCandidate) {
            pool.setStatus(idx, ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL);
        } else {
            pool.setStatus(idx, ActiveShipmentPool.SIN_RUTA);
        }
        return false;
    }

    private void actualizarPesos(OperadorDestroy d, OperadorRepair r, int score) {
        int di = d.ordinal();
        int ri = r.ordinal();
        destroyUses[di]++;
        repairUses[ri]++;
        destroyScores[di] += score;
        repairScores[ri] += score;
        destroyWeights[di] = Math.max(0.1, destroyScores[di] / destroyUses[di]);
        repairWeights[ri] = Math.max(0.1, repairScores[ri] / repairUses[ri]);
    }
}