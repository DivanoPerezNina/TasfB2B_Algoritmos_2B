package pe.edu.pucp.tasf.alns;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Interfaz para operadores repair.
 */
public interface OperadorRepair {
    void repair(SolucionALNS solucion, List<RutaEnvio> removidos, GestorDatos datos, ConfigExperimentacion config, Random rand);
}

// Implementaciones
class GreedyInsertion implements OperadorRepair {
    @Override
    public void repair(SolucionALNS solucion, List<RutaEnvio> removidos, GestorDatos datos, ConfigExperimentacion config, Random rand) {
        for (RutaEnvio r : removidos) {
            // Encontrar mejor ruta factible
            RutaEnvio mejor = encontrarMejorRuta(r.envio, datos, config);
            if (mejor != null) {
                r.tramos.addAll(mejor.tramos);
                r.calcularMetricas();
            }
        }
    }

    public static RutaEnvio encontrarMejorRuta(GestorDatos.Envio envio, GestorDatos datos, ConfigExperimentacion config) {
        List<TramoAsignado> rutaActual = new ArrayList<>();
        Set<String> visitados = new HashSet<>();
        RutaBusqueda mejor = new RutaBusqueda();

        visitados.add(envio.origen);
        buscarRutaOnTime(
            envio,
            datos,
            config,
            envio.origen,
            envio.registroUTC,
            0,
            rutaActual,
            visitados,
            mejor
        );

        if (mejor.tramos == null) return null;

        RutaEnvio ruta = new RutaEnvio(envio);
        ruta.tramos.addAll(mejor.tramos);
        ruta.calcularMetricas();
        return ruta.estado == RutaEnvio.Estado.ON_TIME ? ruta : null;
    }

    private static void buscarRutaOnTime(
        GestorDatos.Envio envio,
        GestorDatos datos,
        ConfigExperimentacion config,
        String aeropuertoActual,
        ZonedDateTime disponibleDesde,
        int saltos,
        List<TramoAsignado> rutaActual,
        Set<String> visitados,
        RutaBusqueda mejor
    ) {
        if (saltos >= Math.max(1, config.maxSaltos)) return;

        List<GestorDatos.Vuelo> vuelos = datos.vuelosPorOrigen.get(aeropuertoActual);
        if (vuelos == null || vuelos.isEmpty()) return;

        int minEscala = Math.max(0, config.tiempoMinEscalaMin);
        for (GestorDatos.Vuelo vuelo : vuelos) {
            GestorDatos.Aeropuerto aeroOrigen = datos.aeropuertos.get(vuelo.origen);
            GestorDatos.Aeropuerto aeroDestino = datos.aeropuertos.get(vuelo.destino);
            if (aeroOrigen == null || aeroDestino == null) continue;
            if (visitados.contains(vuelo.destino) && !vuelo.destino.equals(envio.destino)) continue;

            ZonedDateTime salida = UtilTiempo.calcularSalidaReal(vuelo, disponibleDesde, aeroOrigen);
            if (salida.isAfter(envio.deadlineUTC)) continue;

            ZonedDateTime llegada = UtilTiempo.calcularLlegadaReal(salida, vuelo, aeroDestino);
            if (llegada.isAfter(envio.deadlineUTC)) continue;

            if (mejor.llegadaFinal != null && !llegada.isBefore(mejor.llegadaFinal) && vuelo.destino.equals(envio.destino)) {
                continue;
            }

            TramoAsignado tramo = new TramoAsignado(vuelo, salida, llegada, 0);
            rutaActual.add(tramo);

            if (vuelo.destino.equals(envio.destino)) {
                mejor.actualizarSiMejor(rutaActual, llegada);
            } else {
                visitados.add(vuelo.destino);
                buscarRutaOnTime(
                    envio,
                    datos,
                    config,
                    vuelo.destino,
                    llegada.plusMinutes(minEscala),
                    saltos + 1,
                    rutaActual,
                    visitados,
                    mejor
                );
                visitados.remove(vuelo.destino);
            }

            rutaActual.remove(rutaActual.size() - 1);
        }
    }

    private static class RutaBusqueda {
        private List<TramoAsignado> tramos;
        private ZonedDateTime llegadaFinal;

        private void actualizarSiMejor(List<TramoAsignado> candidata, ZonedDateTime llegadaCandidata) {
            if (llegadaFinal == null || llegadaCandidata.isBefore(llegadaFinal) ||
                (llegadaCandidata.isEqual(llegadaFinal) && candidata.size() < tramos.size())) {
                tramos = new ArrayList<>(candidata);
                llegadaFinal = llegadaCandidata;
            }
        }
    }
}

// Agregar Regret2Insertion, UrgencyInsertion, CapacityAwareInsertion