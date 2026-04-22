package pe.edu.pucp.tasf.alns;
import java.util.*;

/**
 * Clase para evaluar factibilidad y costo de soluciones.
 */
public class EvaluadorSolucion {
    private GestorDatos datos;
    private ConfigExperimentacion config;
    private Map<String, Integer> capacidadPorVueloId = new HashMap<>();
    private Map<String, Integer> capacidadPorRuta = new HashMap<>();

    public EvaluadorSolucion(GestorDatos datos, ConfigExperimentacion config) {
        this.datos = datos;
        this.config = config;
        inicializarCapacidades();
    }

    public boolean esFactible(SolucionALNS solucion) {
        // Verificar capacidad de vuelos
        for (Map.Entry<String, Integer> entry : solucion.ocupacionVuelos.entrySet()) {
            String vueloId = entry.getKey().split("_")[0];
            int ocupado = entry.getValue();
            int capacidad = obtenerCapacidad(vueloId);
            if (ocupado > capacidad) return false;
        }
        return true;
    }

    public void actualizarOcupacion(SolucionALNS solucion) {
        solucion.ocupacionVuelos.clear();
        solucion.ocupacionAlmacen.clear();
        for (RutaEnvio r : solucion.rutas) {
            for (TramoAsignado t : r.tramos) {
                String key = construirVueloId(t.vuelo) + "_" + t.diaAbsoluto;
                solucion.ocupacionVuelos.put(key, solucion.ocupacionVuelos.getOrDefault(key, 0) + r.envio.maletas);
            }
            // Almacén: simplificar
            if (!r.tramos.isEmpty()) {
                String aero = r.tramos.get(0).vuelo.origen;
                solucion.ocupacionAlmacen.put(aero, solucion.ocupacionAlmacen.getOrDefault(aero, 0) + r.envio.maletas);
            }
        }
    }

    private void inicializarCapacidades() {
        for (GestorDatos.Vuelo vuelo : datos.vuelos) {
            String vueloId = construirVueloId(vuelo);
            capacidadPorVueloId.put(vueloId, vuelo.capacidad);

            String rutaId = vuelo.origen + "-" + vuelo.destino;
            int capacidadMax = Math.max(capacidadPorRuta.getOrDefault(rutaId, 0), vuelo.capacidad);
            capacidadPorRuta.put(rutaId, capacidadMax);
        }
    }

    private int obtenerCapacidad(String vueloId) {
        Integer capacidad = capacidadPorVueloId.get(vueloId);
        if (capacidad != null) return capacidad;

        String[] partes = vueloId.split("-");
        if (partes.length >= 2) {
            String rutaId = partes[0] + "-" + partes[1];
            Integer capacidadRuta = capacidadPorRuta.get(rutaId);
            if (capacidadRuta != null) return capacidadRuta;
        }

        // Fallback conservador para casos no reconocidos.
        return 100;
    }

    private String construirVueloId(GestorDatos.Vuelo vuelo) {
        return vuelo.origen + "-" + vuelo.destino + "-" + vuelo.salidaMinutos + "-" + vuelo.llegadaMinutos;
    }
}