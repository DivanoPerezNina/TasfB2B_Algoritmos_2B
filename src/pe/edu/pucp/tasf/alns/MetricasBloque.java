package pe.edu.pucp.tasf.alns;

public class MetricasBloque {
    public String escenario;
    public int bloqueDias;
    public int bloqueIndex;
    public long blockStartUTC;
    public long blockEndUTC;
    public long firstReleaseUTC;
    public long lastReleaseUTC;

    // Bloque
    public int enviosLeidosBloque;
    public int entregadosBloque;
    public int pendientesFinBloque; // Pedidos que quedan pendientes al cierre del bloque.
    public int sinRutaBloque; // Pedidos del bloque que no pudieron asignarse a una ruta factible.
    public int retrasadosBloque;
    public int noFactiblesBloque;
    public int criticosFinBloque; // Pedidos críticos al cierre del bloque.

    // ALNS por bloque
    public int llamadasALNS; // Cantidad de ejecuciones ALNS en el bloque.
    public int iteracionesALNS; // Iteraciones internas del ALNS en el bloque.
    public int criticosAntesALNS; // Críticos (SIN_RUTA + PENDIENTE + RETRASADO) antes de ejecutar ALNS.
    public int criticosDespuesALNS; // Críticos después de ejecutar ALNS.
    public int pedidosReparadosALNS; // Reducción de críticos: max(0, criticosAntes - criticosDespués). Métrica operativa principal.
    public int sinRutaAntesALNS;
    public int sinRutaDespuesALNS;
    public double fitnessAntesALNS; // Fitness calculado después de insertar pedidos del bloque, antes de ejecutar ALNS.
    public double fitnessDespuesALNS; // Fitness después de ejecutar ALNS. Comparación relativa, no métrica principal.
    public String mejoraOperativaALNS; // SI si pedidosReparadosALNS > 0, NO en caso contrario. Indica si ALNS redujo críticos.
    public long tiempoMs;
    public String validacionBalance;
}
