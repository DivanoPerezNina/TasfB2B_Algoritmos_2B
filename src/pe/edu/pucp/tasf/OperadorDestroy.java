package pe.edu.pucp.tasf;

import java.util.List;
import java.util.Random;

/**
 * Interfaz para operadores destroy.
 */
public interface OperadorDestroy {
    List<RutaEnvio> destroy(SolucionALNS solucion, Random rand, int numRemove);
}

// Implementaciones
class RandomRemoval implements OperadorDestroy {
    @Override
    public List<RutaEnvio> destroy(SolucionALNS solucion, Random rand, int numRemove) {
        List<RutaEnvio> removidos = new java.util.ArrayList<>();
        List<RutaEnvio> asignados = new java.util.ArrayList<>();
        for (RutaEnvio r : solucion.rutas) {
            if (r.estado != RutaEnvio.Estado.UNASSIGNED) asignados.add(r);
        }
        for (int i = 0; i < numRemove && !asignados.isEmpty(); i++) {
            int idx = rand.nextInt(asignados.size());
            RutaEnvio r = asignados.remove(idx);
            r.tramos.clear();
            r.estado = RutaEnvio.Estado.UNASSIGNED;
            r.calcularMetricas();
            removidos.add(r);
        }
        return removidos;
    }
}

class WorstSlackRemoval implements OperadorDestroy {
    @Override
    public List<RutaEnvio> destroy(SolucionALNS solucion, Random rand, int numRemove) {
        // Remover con menor slack (más riesgo de late)
        List<RutaEnvio> asignados = new java.util.ArrayList<>();
        for (RutaEnvio r : solucion.rutas) {
            if (r.estado != RutaEnvio.Estado.UNASSIGNED) asignados.add(r);
        }
        asignados.sort((a, b) -> Long.compare(a.tardanzaMin, b.tardanzaMin)); // menor tardanza primero? Wait, slack is deadline - arrival
        // Placeholder: remover los con menor slack
        List<RutaEnvio> removidos = new java.util.ArrayList<>();
        for (int i = 0; i < numRemove && !asignados.isEmpty(); i++) {
            RutaEnvio r = asignados.remove(0);
            r.tramos.clear();
            r.estado = RutaEnvio.Estado.UNASSIGNED;
            r.calcularMetricas();
            removidos.add(r);
        }
        return removidos;
    }
}

// Agregar otros similares: CriticalFlightRemoval, RelatedRemoval, LateOrLongRouteRemoval