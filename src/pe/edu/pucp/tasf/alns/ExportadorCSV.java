package pe.edu.pucp.tasf.alns;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

public class ExportadorCSV {
    public static void exportarResumen(String archivo, List<MetricasSolucion> metricas) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(archivo))) {
            pw.println("escenario,tamanoBloqueDias,inicioSimulacionUTCMin,finSimulacionUTCMin,diasSimulados,enviosLeidosTotal,enviosEntregadosTotal,enviosPendientesFinal,enviosSinRutaTotal,enviosRetrasadosTotal,enviosNoFactiblesTotal,ejecucionesALNSTotal,fitnessFinal,tiempoEjecucionMs,memoriaUsadaMB,bloquesProcesados,validacionBalance,razonParada");

            for (MetricasSolucion m : metricas) {
                String fitness = String.format(Locale.US, "%.2f", m.fitnessFinal);
                String memoria = String.format(Locale.US, "%.2f", m.memoriaUsadaMB);

                pw.println(
                    m.escenario + "," +
                    m.bloqueDias + "," +
                    m.fechaInicio + "," +
                    m.fechaFinAlcanzada + "," +
                    ((m.fechaFinAlcanzada - m.fechaInicio) / 1440) + "," +
                    m.enviosLeidos + "," +
                    m.enviosEntregados + "," +
                    m.enviosPendientes + "," +
                    m.enviosSinRuta + "," +
                    m.enviosRetrasados + "," +
                    m.noFactiblesEstructurales + "," +
                    m.replanificaciones + "," +
                    fitness + "," +
                    m.tiempoMs + "," +
                    memoria + "," +
                    m.bloquesProcesados + "," +
                    m.validacionBalance + "," +
                    m.razonParada
                );
            }
        }
    }

    public static void exportarResumen(List<MetricasSolucion> metricas) throws IOException {
        exportarResumen("resultados_alns_resumen.csv", metricas);
    }

    public static void exportarBloques(String archivo, List<MetricasBloque> metricas) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(archivo))) {
            pw.println("escenario,tamanoBloqueDias,numeroBloque,inicioBloqueUTCMin,finBloqueUTCMin,primerEnvioBloqueUTCMin,ultimoEnvioBloqueUTCMin,enviosLeidosBloque,enviosEntregadosBloque,enviosPendientesAlCierre,enviosSinRutaBloque,enviosRetrasadosBloque,enviosNoFactiblesBloque,enviosCriticosAlCierre,ejecucionesALNSBloque,iteracionesALNSBloque,criticosAntesALNS,criticosDespuesALNS,pedidosReparadosALNS,sinRutaAntesALNS,sinRutaDespuesALNS,fitnessAntesALNS,fitnessDespuesALNS,mejoraOperativaALNS,tiempoBloqueMs,validacionBalance");

            for (MetricasBloque m : metricas) {
                String fitnessAntesALNS = String.format(Locale.US, "%.2f", m.fitnessAntesALNS);
                String fitnessDespuesALNS = String.format(Locale.US, "%.2f", m.fitnessDespuesALNS);

                pw.println(
                    m.escenario + "," +
                    m.bloqueDias + "," +
                    m.bloqueIndex + "," +
                    m.blockStartUTC + "," +
                    m.blockEndUTC + "," +
                    m.firstReleaseUTC + "," +
                    m.lastReleaseUTC + "," +
                    m.enviosLeidosBloque + "," +
                    m.entregadosBloque + "," +
                    m.pendientesFinBloque + "," +
                    m.sinRutaBloque + "," +
                    m.retrasadosBloque + "," +
                    m.noFactiblesBloque + "," +
                    m.criticosFinBloque + "," +
                    m.llamadasALNS + "," +
                    m.iteracionesALNS + "," +
                    m.criticosAntesALNS + "," +
                    m.criticosDespuesALNS + "," +
                    m.pedidosReparadosALNS + "," +
                    m.sinRutaAntesALNS + "," +
                    m.sinRutaDespuesALNS + "," +
                    fitnessAntesALNS + "," +
                    fitnessDespuesALNS + "," +
                    m.mejoraOperativaALNS + "," +
                    m.tiempoMs + "," +
                    m.validacionBalance
                );
            }
        }
    }

    public static void exportarBloquesPorEscenario(List<MetricasBloque> metricas) throws IOException {
        exportarBloquesPorEscenario("ALNS_3D", "resultados_alns_bloques_3d.csv", metricas);
        exportarBloquesPorEscenario("ALNS_5D", "resultados_alns_bloques_5d.csv", metricas);
        exportarBloquesPorEscenario("ALNS_7D", "resultados_alns_bloques_7d.csv", metricas);
    }

    private static void exportarBloquesPorEscenario(String escenario, String archivo, List<MetricasBloque> metricas) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(archivo))) {
            pw.println("escenario,tamanoBloqueDias,numeroBloque,inicioBloqueUTCMin,finBloqueUTCMin,primerEnvioBloqueUTCMin,ultimoEnvioBloqueUTCMin,enviosLeidosBloque,enviosEntregadosBloque,enviosPendientesAlCierre,enviosSinRutaBloque,enviosRetrasadosBloque,enviosNoFactiblesBloque,enviosCriticosAlCierre,ejecucionesALNSBloque,iteracionesALNSBloque,criticosAntesALNS,criticosDespuesALNS,pedidosReparadosALNS,sinRutaAntesALNS,sinRutaDespuesALNS,fitnessAntesALNS,fitnessDespuesALNS,mejoraOperativaALNS,tiempoBloqueMs,validacionBalance");
            for (MetricasBloque m : metricas) {
                if (!escenario.equals(m.escenario)) continue;
                String fitnessAntesALNS = String.format(Locale.US, "%.2f", m.fitnessAntesALNS);
                String fitnessDespuesALNS = String.format(Locale.US, "%.2f", m.fitnessDespuesALNS);

                pw.println(
                    m.escenario + "," +
                    m.bloqueDias + "," +
                    m.bloqueIndex + "," +
                    m.blockStartUTC + "," +
                    m.blockEndUTC + "," +
                    m.firstReleaseUTC + "," +
                    m.lastReleaseUTC + "," +
                    m.enviosLeidosBloque + "," +
                    m.entregadosBloque + "," +
                    m.pendientesFinBloque + "," +
                    m.sinRutaBloque + "," +
                    m.retrasadosBloque + "," +
                    m.noFactiblesBloque + "," +
                    m.criticosFinBloque + "," +
                    m.llamadasALNS + "," +
                    m.iteracionesALNS + "," +
                    m.criticosAntesALNS + "," +
                    m.criticosDespuesALNS + "," +
                    m.pedidosReparadosALNS + "," +
                    m.sinRutaAntesALNS + "," +
                    m.sinRutaDespuesALNS + "," +
                    fitnessAntesALNS + "," +
                    fitnessDespuesALNS + "," +
                    m.mejoraOperativaALNS + "," +
                    m.tiempoMs + "," +
                    m.validacionBalance
                );
            }
        }
    }

    public static void exportarConvergencia(String file, List<String> lines) throws IOException {
        FileWriter fw = new FileWriter(file);
        fw.write("iteracion,ms,fitnessActual,mejorFitness,criticos,sinRuta,retrasados,destroy,repair\n");
        for (String line : lines) {
            fw.write(line + "\n");
        }
        fw.close();
    }
}
