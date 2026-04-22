package pe.edu.pucp.tasf.alns;
/**
 * Métricas calculadas para una solución.
 */
public class MetricasSolucion {
    public int totalEnvios;
    public int totalMaletas;
    public int enviosOnTime;
    public int maletasOnTime;
    public int enviosLate;
    public int maletasLate;
    public int enviosUnassigned;
    public int maletasUnassigned;
    public long tardanzaTotalMin;
    public double tardanzaPromedioMin;
    public long tardanzaMaxMin;
    public double hopsPromedio;
    public double waitingPromedioMin;
    public double utilizacionPromedioVuelos;
    public int vuelosSaturados;
    public long sobrecapacidadAlmacen;
    public double tiempoCargaSeg;
    public double tiempoGreedySeg;
    public double tiempoALNSSeg;
    public double tiempoTotalSeg;
    public int iteraciones;
    public long bestObjective;
    public long seed;

    // Para operadores
    public int[] seleccionDestroy = new int[5]; // índices para cada operador
    public int[] acceptedDestroy = new int[5];
    public int[] improvementDestroy = new int[5];
    public int[] seleccionRepair = new int[4];
    public int[] acceptedRepair = new int[4];
    public int[] improvementRepair = new int[4];
}