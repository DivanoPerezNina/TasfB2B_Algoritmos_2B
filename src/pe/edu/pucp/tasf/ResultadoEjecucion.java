package pe.edu.pucp.tasf;

/**
 * Resultado de una ejecución de ALNS.
 */
public class ResultadoEjecucion {
    public SolucionALNS solucion;
    public MetricasSolucion metricas;
    public double tiempoSeg;
    public String version; // "secuencial" o "concurrente"

    public ResultadoEjecucion(SolucionALNS solucion, MetricasSolucion metricas, double tiempoSeg, String version) {
        this.solucion = solucion;
        this.metricas = metricas;
        this.tiempoSeg = tiempoSeg;
        this.version = version;
    }
}