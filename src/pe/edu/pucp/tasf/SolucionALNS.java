package pe.edu.pucp.tasf;

import java.util.*;

/**
 * Representa una solución completa del ALNS.
 */
public class SolucionALNS {
    public List<RutaEnvio> rutas = new ArrayList<>();
    public Map<String, Integer> ocupacionVuelos = new HashMap<>(); // clave: vueloId + dia
    public Map<String, Integer> ocupacionAlmacen = new HashMap<>(); // por aeropuerto
    public long objective;

    public SolucionALNS(List<GestorDatos.Envio> envios) {
        for (GestorDatos.Envio e : envios) {
            rutas.add(new RutaEnvio(e));
        }
    }

    public SolucionALNS(SolucionALNS other) {
        // Copia profunda
        for (RutaEnvio r : other.rutas) {
            RutaEnvio nr = new RutaEnvio(r.envio);
            nr.tramos.addAll(r.tramos);
            nr.estado = r.estado;
            nr.tardanzaMin = r.tardanzaMin;
            nr.hops = r.hops;
            nr.waitingTimeMin = r.waitingTimeMin;
            this.rutas.add(nr);
        }
        this.ocupacionVuelos = new HashMap<>(other.ocupacionVuelos);
        this.ocupacionAlmacen = new HashMap<>(other.ocupacionAlmacen);
        this.objective = other.objective;
    }

    public void calcularObjective(ConfigExperimentacion config) {
        int unassigned = 0, lateRequests = 0, lateBags = 0;
        long totalLateness = 0, totalHops = 0;
        for (RutaEnvio r : rutas) {
            if (r.estado == RutaEnvio.Estado.UNASSIGNED) {
                unassigned++;
                lateBags += r.envio.maletas;
            } else if (r.estado == RutaEnvio.Estado.LATE) {
                lateRequests++;
                lateBags += r.envio.maletas;
                totalLateness += r.tardanzaMin;
            }
            totalHops += r.hops;
        }
        long storageOveruse = calcularStorageOveruse();
        objective = config.pesoUnassigned * unassigned +
                    config.pesoLateRequests * lateRequests +
                    config.pesoLateBags * lateBags +
                    config.pesoTotalLateness * totalLateness +
                    config.pesoTotalHops * totalHops +
                    config.pesoStorageOveruse * storageOveruse;
    }

    private long calcularStorageOveruse() {
        long overuse = 0;
        for (Map.Entry<String, Integer> entry : ocupacionAlmacen.entrySet()) {
            // Asumir capacidad del aeropuerto, pero por simplicidad, penalizar si > 0 (placeholder)
            // En implementación real, comparar con capacidad
            if (entry.getValue() > 0) overuse += entry.getValue();
        }
        return overuse;
    }

    public MetricasSolucion calcularMetricas(double tiempoCarga, double tiempoGreedy, double tiempoALNS, int iter, long seed) {
        MetricasSolucion m = new MetricasSolucion();
        m.totalEnvios = rutas.size();
        m.totalMaletas = rutas.stream().mapToInt(r -> r.envio.maletas).sum();
        for (RutaEnvio r : rutas) {
            if (r.estado == RutaEnvio.Estado.ON_TIME) {
                m.enviosOnTime++;
                m.maletasOnTime += r.envio.maletas;
            } else if (r.estado == RutaEnvio.Estado.LATE) {
                m.enviosLate++;
                m.maletasLate += r.envio.maletas;
                m.tardanzaTotalMin += r.tardanzaMin;
            } else {
                m.enviosUnassigned++;
                m.maletasUnassigned += r.envio.maletas;
            }
            m.hopsPromedio += r.hops;
            m.waitingPromedioMin += r.waitingTimeMin;
        }
        m.tardanzaPromedioMin = m.enviosLate > 0 ? (double) m.tardanzaTotalMin / m.enviosLate : 0;
        m.tardanzaMaxMin = rutas.stream().mapToLong(r -> r.tardanzaMin).max().orElse(0);
        m.hopsPromedio /= m.totalEnvios;
        m.waitingPromedioMin /= m.totalEnvios;
        // Utilización vuelos: placeholder
        m.utilizacionPromedioVuelos = 0.5; // calcular real
        m.vuelosSaturados = 0; // calcular
        m.sobrecapacidadAlmacen = calcularStorageOveruse();
        m.tiempoCargaSeg = tiempoCarga;
        m.tiempoGreedySeg = tiempoGreedy;
        m.tiempoALNSSeg = tiempoALNS;
        m.tiempoTotalSeg = tiempoCarga + tiempoGreedy + tiempoALNS;
        m.iteraciones = iter;
        m.bestObjective = objective;
        m.seed = seed;
        return m;
    }
}