package pe.edu.pucp.tasf;

import java.util.*;

/**
 * Clase para evaluar factibilidad y costo de soluciones.
 */
public class EvaluadorSolucion {
    private GestorDatos datos;
    private ConfigExperimentacion config;

    public EvaluadorSolucion(GestorDatos datos, ConfigExperimentacion config) {
        this.datos = datos;
        this.config = config;
    }

    public boolean esFactible(SolucionALNS solucion) {
        // Verificar capacidad de vuelos
        for (Map.Entry<String, Integer> entry : solucion.ocupacionVuelos.entrySet()) {
            // Buscar capacidad del vuelo
            String vueloId = entry.getKey().split("_")[0];
            int ocupado = entry.getValue();
            // Placeholder: asumir capacidad fija, en real buscar
            if (ocupado > 100) return false; // ejemplo
        }
        return true;
    }

    public void actualizarOcupacion(SolucionALNS solucion) {
        solucion.ocupacionVuelos.clear();
        solucion.ocupacionAlmacen.clear();
        for (RutaEnvio r : solucion.rutas) {
            for (TramoAsignado t : r.tramos) {
                String key = t.vuelo.origen + "-" + t.vuelo.destino + "_" + t.diaAbsoluto;
                solucion.ocupacionVuelos.put(key, solucion.ocupacionVuelos.getOrDefault(key, 0) + r.envio.maletas);
            }
            // Almacén: simplificar
            if (!r.tramos.isEmpty()) {
                String aero = r.tramos.get(0).vuelo.origen;
                solucion.ocupacionAlmacen.put(aero, solucion.ocupacionAlmacen.getOrDefault(aero, 0) + r.envio.maletas);
            }
        }
    }
}