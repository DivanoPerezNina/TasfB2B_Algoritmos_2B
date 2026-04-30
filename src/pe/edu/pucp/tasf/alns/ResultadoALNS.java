package pe.edu.pucp.tasf.alns;

import java.util.List;

public class ResultadoALNS {
    public int llamadasALNS;
    public int iteraciones;
    public int criticosAntes;
    public int criticosDespues;
    public int sinRutaAntes;
    public int sinRutaDespues;
    public int pendientesAntes;
    public int pendientesDespues;
    public int retrasadosAntes;
    public int retrasadosDespues;
    public int reparados;
    public int empeorados;
    public double fitnessAntesALNS;
    public double fitnessDespuesALNS;
    public boolean mejoro;
    /** Historial de convergencia: cada entrada es {iteracion, ms_transcurridos, mejor_fitness}. */
    public List<long[]> convergencia;
}
