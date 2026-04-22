package pe.edu.pucp.tasf.alns;
import java.util.List;
import java.util.Random;

/**
 * Interfaz para operadores destroy.
 */
public interface OperadorDestroy {
    List<RutaBackup> destroy(SolucionALNS solucion, Random rand, int numRemove);
}

class RutaBackup {
    public final RutaEnvio ruta;
    public final List<TramoAsignado> tramos;
    public final RutaEnvio.Estado estado;
    public final long tardanzaMin;
    public final int hops;
    public final long waitingTimeMin;

    RutaBackup(RutaEnvio ruta) {
        this.ruta = ruta;
        this.tramos = new java.util.ArrayList<>(ruta.tramos);
        this.estado = ruta.estado;
        this.tardanzaMin = ruta.tardanzaMin;
        this.hops = ruta.hops;
        this.waitingTimeMin = ruta.waitingTimeMin;
    }

    public void restore() {
        ruta.tramos.clear();
        ruta.tramos.addAll(tramos);
        ruta.estado = estado;
        ruta.tardanzaMin = tardanzaMin;
        ruta.hops = hops;
        ruta.waitingTimeMin = waitingTimeMin;
    }
}

// Implementaciones
class RandomRemoval implements OperadorDestroy {
    @Override
    public List<RutaBackup> destroy(SolucionALNS solucion, Random rand, int numRemove) {
        List<RutaBackup> removidos = new java.util.ArrayList<>();
        List<RutaEnvio> asignados = new java.util.ArrayList<>();
        for (RutaEnvio r : solucion.rutas) {
            if (r.estado != RutaEnvio.Estado.UNASSIGNED) asignados.add(r);
        }
        for (int i = 0; i < numRemove && !asignados.isEmpty(); i++) {
            int idx = rand.nextInt(asignados.size());
            RutaEnvio r = asignados.remove(idx);
            removidos.add(new RutaBackup(r));
            r.tramos.clear();
            r.estado = RutaEnvio.Estado.UNASSIGNED;
            r.calcularMetricas();
        }
        return removidos;
    }
}

class WorstSlackRemoval implements OperadorDestroy {
    @Override
    public List<RutaBackup> destroy(SolucionALNS solucion, Random rand, int numRemove) {
        // Remover con menor slack (más riesgo de late)
        List<RutaEnvio> asignados = new java.util.ArrayList<>();
        for (RutaEnvio r : solucion.rutas) {
            if (r.estado != RutaEnvio.Estado.UNASSIGNED) asignados.add(r);
        }
        asignados.sort((a, b) -> Long.compare(a.tardanzaMin, b.tardanzaMin)); // menor tardanza primero? Wait, slack is deadline - arrival
        // Placeholder: remover los con menor slack
        List<RutaBackup> removidos = new java.util.ArrayList<>();
        for (int i = 0; i < numRemove && !asignados.isEmpty(); i++) {
            RutaEnvio r = asignados.remove(0);
            removidos.add(new RutaBackup(r));
            r.tramos.clear();
            r.estado = RutaEnvio.Estado.UNASSIGNED;
            r.calcularMetricas();
        }
        return removidos;
    }
}

// Agregar otros similares: CriticalFlightRemoval, RelatedRemoval, LateOrLongRouteRemoval