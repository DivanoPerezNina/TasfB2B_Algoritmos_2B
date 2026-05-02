package pe.edu.pucp.tasf.alns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class PlanificadorALNS {
    private ActiveShipmentPool pool;
    private RouteStore routes;
    private FlightCapacityStore flights;
    private AirportCapacityTimeline airports;
    private final Random rand;
    private final CriterioOrden criterio;
    private final long semilla;

    // Pesos para operadores
    private double[] destroyWeights = {1,1,1,1,1};
    private double[] repairWeights = {1,1,1,1};
    private int[] destroyUses = {0,0,0,0,0};
    private int[] repairUses = {0,0,0,0};
    private double[] destroyScores = {0,0,0,0,0};
    private double[] repairScores = {0,0,0,0};

    /** Constructor reproducible con criterio de orden: pasa una semilla fija para experimentos. */
    public PlanificadorALNS(ActiveShipmentPool p, RouteStore r, FlightCapacityStore f,
                             AirportCapacityTimeline a, long seed, CriterioOrden crit) {
        pool = p; routes = r; flights = f; airports = a;
        semilla = seed;
        rand = new Random(seed);
        criterio = crit;
    }

    /** Constructor reproducible: pasa una semilla fija para experimentos. */
    public PlanificadorALNS(ActiveShipmentPool p, RouteStore r, FlightCapacityStore f,
                             AirportCapacityTimeline a, long seed) {
        this(p, r, f, a, seed, CriterioOrden.FIFO);
    }

    /** Constructor sin semilla (modo normal del compañero — no determinista). */
    public PlanificadorALNS(ActiveShipmentPool p, RouteStore r, FlightCapacityStore f, AirportCapacityTimeline a) {
        this(p, r, f, a, System.currentTimeMillis(), CriterioOrden.FIFO);
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

        // Ordenar críticos según el criterio
        List<Integer> criticosOrdenados = ordenarCriticos(criticos);

        resultado.llamadasALNS = 1;
        resultado.sinRutaAntes = 0;
        resultado.pendientesAntes = 0;
        resultado.retrasadosAntes = 0;
        for (int idx : criticosOrdenados) {
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

    public boolean intentarPlanificarConAlternativas(int idx) {
        RutaCandidata ruta = buscarRutaFactible(idx, false);
        if (ruta == null) {
            if (buscarRutaFactible(idx, true) == null) {
                pool.setStatus(idx, ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL);
            }
            return false;
        }

        reservarRuta(idx, ruta);
        return true;
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
        List<Integer> candidatos = new ArrayList<>(criticos);
        for (int i = 0; i < num && !candidatos.isEmpty(); i++) {
            int pos = rand.nextInt(candidatos.size());
            removidos.add(candidatos.remove(pos));
        }
        return removidos;
    }

    private void liberarReservas(int idx) {
        int length = routes.getRouteLength(idx);
        if (length <= 0) {
            return;
        }

        int quantity = pool.getQuantity(idx);
        int currentAirport = pool.getOrigin(idx);
        long previousArrivalUTC = pool.getReleaseUTC(idx);

        for (int hop = 0; hop < length; hop++) {
            int flightId = routes.getFlightId(idx, hop);
            long departureUTC = routes.getDepartureUTC(idx, hop);

            airports.releaseInterval(currentAirport, previousArrivalUTC, departureUTC, quantity);
            flights.release(flightId, departureUTC, quantity);

            previousArrivalUTC = calcularLlegadaUTC(departureUTC, flightId);
            currentAirport = DatosEstaticos.flightDestination[flightId];
        }

        routes.clearRoute(idx);
    }

    private boolean planificarRutaInicial(int idx, OperadorRepair repair) {
        boolean planificado = intentarPlanificarConAlternativas(idx);
        if (!planificado && pool.getStatus(idx) != ActiveShipmentPool.NO_FACTIBLE_ESTRUCTURAL) {
            pool.setStatus(idx, ActiveShipmentPool.SIN_RUTA);
        }
        return planificado;
    }

    private RutaCandidata buscarRutaFactible(int idx, boolean ignorarCapacidades) {
        int origen = pool.getOrigin(idx);
        int destino = pool.getDestination(idx);
        int quantity = pool.getQuantity(idx);
        long releaseUTC = pool.getReleaseUTC(idx);
        long deadlineUTC = pool.getDeadlineUTC(idx);

        boolean[] visitados = new boolean[DatosEstaticos.airportCode.length];
        visitados[origen] = true;

        return buscarRutaRecursiva(origen, destino, quantity, releaseUTC, deadlineUTC,
                ConfigExperimentacion.MAX_SALTOS, visitados,
                new int[ConfigExperimentacion.MAX_SALTOS],
                new long[ConfigExperimentacion.MAX_SALTOS],
                0, ignorarCapacidades);
    }

    private RutaCandidata buscarRutaRecursiva(int aeropuertoActual, int destinoFinal, int quantity,
                                              long tiempoMasTemprano, long deadlineUTC,
                                              int saltosRestantes, boolean[] visitados,
                                              int[] flightsPath, long[] departuresPath,
                                              int profundidad, boolean ignorarCapacidades) {
        if (saltosRestantes <= 0) {
            return null;
        }

        for (int flightId = 0; flightId < DatosEstaticos.numFlights; flightId++) {
            if (DatosEstaticos.flightOrigin[flightId] != aeropuertoActual) {
                continue;
            }

            int aeropuertoSiguiente = DatosEstaticos.flightDestination[flightId];
            if (visitados[aeropuertoSiguiente] && aeropuertoSiguiente != destinoFinal) {
                continue;
            }

            long departureUTC = calcularProximaSalidaUTC(tiempoMasTemprano,
                    DatosEstaticos.flightDepartureUTCMinuteOfDay[flightId]);
            if (departureUTC > deadlineUTC) {
                continue;
            }

            long arrivalUTC = calcularLlegadaUTC(departureUTC, flightId);
            if (arrivalUTC > deadlineUTC) {
                continue;
            }

            if (!ignorarCapacidades) {
                if (!flights.canReserve(flightId, departureUTC, quantity)) {
                    continue;
                }
                if (!airports.canReserveInterval(aeropuertoActual, tiempoMasTemprano, departureUTC, quantity)) {
                    continue;
                }
            }

            flightsPath[profundidad] = flightId;
            departuresPath[profundidad] = departureUTC;

            if (aeropuertoSiguiente == destinoFinal) {
                return construirRutaCandidata(flightsPath, departuresPath, profundidad + 1, arrivalUTC);
            }

            visitados[aeropuertoSiguiente] = true;
            RutaCandidata siguiente = buscarRutaRecursiva(aeropuertoSiguiente, destinoFinal, quantity,
                    arrivalUTC, deadlineUTC, saltosRestantes - 1, visitados,
                    flightsPath, departuresPath, profundidad + 1, ignorarCapacidades);
            visitados[aeropuertoSiguiente] = false;

            if (siguiente != null) {
                return siguiente;
            }
        }

        return null;
    }

    private RutaCandidata construirRutaCandidata(int[] flightsPath, long[] departuresPath, int length,
                                                 long arrivalUTC) {
        RutaCandidata ruta = new RutaCandidata(length, arrivalUTC);
        for (int i = 0; i < length; i++) {
            ruta.flightIds[i] = flightsPath[i];
            ruta.departures[i] = departuresPath[i];
        }
        return ruta;
    }

    private long calcularProximaSalidaUTC(long earliestUTC, int departureMinuteOfDay) {
        long dayStartUTC = (earliestUTC / 1440L) * 1440L;
        long departureUTC = dayStartUTC + departureMinuteOfDay;
        if (departureUTC < earliestUTC) {
            departureUTC += 1440L;
        }
        return departureUTC;
    }

    private long calcularLlegadaUTC(long departureUTC, int flightId) {
        return departureUTC + (DatosEstaticos.flightArrivalUTCMinuteOfDay[flightId]
                - DatosEstaticos.flightDepartureUTCMinuteOfDay[flightId]);
    }

    private void reservarRuta(int idx, RutaCandidata ruta) {
        int quantity = pool.getQuantity(idx);
        int currentAirport = pool.getOrigin(idx);
        long previousArrivalUTC = pool.getReleaseUTC(idx);

        for (int hop = 0; hop < ruta.length; hop++) {
            int flightId = ruta.flightIds[hop];
            long departureUTC = ruta.departures[hop];

            airports.reserveInterval(currentAirport, previousArrivalUTC, departureUTC, quantity);
            flights.reserve(flightId, departureUTC, quantity);

            previousArrivalUTC = calcularLlegadaUTC(departureUTC, flightId);
            currentAirport = DatosEstaticos.flightDestination[flightId];
        }

        int start = routes.allocateRoute(ruta.length);
        routes.setRoute(idx, start, ruta.length, ruta.flightIds, ruta.departures);
        pool.setStatus(idx, ruta.arrivalUTC <= pool.getDeadlineUTC(idx)
                ? ActiveShipmentPool.ENTREGADO
                : ActiveShipmentPool.RETRASADO);
    }

    private static final class RutaCandidata {
        final int[] flightIds;
        final long[] departures;
        final int length;
        final long arrivalUTC;

        RutaCandidata(int length, long arrivalUTC) {
            this.flightIds = new int[length];
            this.departures = new long[length];
            this.length = length;
            this.arrivalUTC = arrivalUTC;
        }
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

    /**
     * Ordena la lista de críticos según el criterio configurado.
     * 
     * <ul>
     *   <li>{@link CriterioOrden#FIFO}: por releaseUTC ascendente (más antiguo primero).</li>
     *   <li>{@link CriterioOrden#EDF}: por deadlineUTC ascendente (más urgente primero).</li>
     *   <li>{@link CriterioOrden#ALEATORIO}: Fisher-Yates con semilla (reproducible).</li>
     * </ul>
     */
    private List<Integer> ordenarCriticos(List<Integer> criticos) {
        if (criterio == CriterioOrden.FIFO) {
            // Ordenar por releaseUTC ascendente (orden de llegada)
            List<Integer> ordenados = new ArrayList<>(criticos);
            ordenados.sort((a, b) -> Long.compare(pool.getReleaseUTC(a), pool.getReleaseUTC(b)));
            return ordenados;
        } else if (criterio == CriterioOrden.EDF) {
            // Ordenar por deadlineUTC ascendente (más urgente primero)
            List<Integer> ordenados = new ArrayList<>(criticos);
            ordenados.sort((a, b) -> Long.compare(pool.getDeadlineUTC(a), pool.getDeadlineUTC(b)));
            return ordenados;
        } else { // ALEATORIO
            // Fisher-Yates shuffle con semilla reproducible
            List<Integer> ordenados = new ArrayList<>(criticos);
            Random rng = new Random(semilla);
            for (int i = ordenados.size() - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int temp = ordenados.get(i);
                ordenados.set(i, ordenados.get(j));
                ordenados.set(j, temp);
            }
            return ordenados;
        }
    }
}