package pe.edu.pucp.tasf.gvns;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * ExportadorVisual — Genera visualizacion_casos.json para el frontend React/Vue.
 *
 * Exporta SOLO una muestra representativa de la solución:
 *   • 100 envíos enrutados en la Fase 2 (solución inicial golosa)
 *   •  10 envíos "salvados" por la Fase 3 (GVNS)
 *
 * Nunca exporta los 9.5 M de envíos completos.
 *
 * Formato de salida:
 * {
 *   "resumen": { "enviosRuteados": N, "enviosRechazados": M, ... },
 *   "fase2": [ { "id": e, "origen": "OAKB", "destino": "EBCI",
 *                "tramos": [ { "vuelo": v, "desde": "OAKB",
 *                              "hasta": "EBCI",
 *                              "salida": "2025-08-18 10:30 UTC",
 *                              "llegada": "2025-08-18 14:45 UTC" } ] }, ... ],
 *   "gvns":  [ { ... mismo esquema ... } ]
 * }
 *
 * Uso:
 *   ExportadorVisual.exportarJSON("visualizacion_casos.json", plan, datos,
 *                                 enviosRuteados, enviosRechazados,
 *                                 tiempoGreedy, tiempoGVNS, salvados);
 */
public final class ExportadorVisual {

    private ExportadorVisual() {}  // clase de utilidad

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Genera el archivo JSON de visualización.
     *
     * @param rutaSalida     path del archivo JSON a crear (ej. "visualizacion_casos.json")
     * @param plan           planificador con las muestras y la solución
     * @param datos          red (aeropuertos, vuelos, envíos)
     * @param enviosRuteados total de envíos ruteados en la solución final
     * @param enviosRechazados total de envíos rechazados
     * @param tiempoGreedy   segundos de CPU — Fase 2
     * @param tiempoGVNS     segundos de CPU — Fase 3
     * @param salvados       envíos salvados por GVNS
     */
    public static void exportarJSON(
            String rutaSalida,
            PlanificadorGVNSConcurrente plan,
            GestorDatos datos,
            int     enviosRuteados,
            int     enviosRechazados,
            double  tiempoGreedy,
            double  tiempoGVNS,
            int     salvados) {

        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(rutaSalida), StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder(1 << 17);  // 128 KB inicial

            sb.append("{\n");

            // ── Sección resumen ───────────────────────────────────────────────
            sb.append("  \"resumen\": {\n");
            sb.append("    \"totalEnvios\":       ").append(datos.numEnvios).append(",\n");
            sb.append("    \"enviosRuteados\":    ").append(enviosRuteados).append(",\n");
            sb.append("    \"enviosRechazados\":  ").append(enviosRechazados).append(",\n");
            sb.append("    \"salvadosPorGVNS\":   ").append(salvados).append(",\n");
            sb.append("    \"tiempoFase2Seg\":    ").append(String.format("%.3f", tiempoGreedy)).append(",\n");
            sb.append("    \"tiempoFase3Seg\":    ").append(String.format("%.3f", tiempoGVNS)).append(",\n");
            sb.append("    \"aeropuertos\":       ").append(datos.numAeropuertos).append(",\n");
            sb.append("    \"vuelos\":            ").append(datos.numVuelos).append("\n");
            sb.append("  },\n");

            // ── Sección fase2 (100 muestras) ─────────────────────────────────
            int n2 = plan.cantMuestraFase2();
            sb.append("  \"fase2\": [\n");
            for (int i = 0; i < n2; i++) {
                int e = plan.muestraFase2[i];
                appendEnvio(sb, e, plan, datos);
                if (i < n2 - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("  ],\n");

            // ── Sección gvns (10 muestras) ────────────────────────────────────
            int ng = plan.cantMuestraGVNS();
            sb.append("  \"gvns\": [\n");
            for (int i = 0; i < ng; i++) {
                int e = plan.muestraGVNS[i];
                appendEnvio(sb, e, plan, datos);
                if (i < ng - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("  ]\n");

            sb.append("}\n");

            pw.print(sb);
            System.out.println("✓ Exportado: " + rutaSalida +
                    "  (" + n2 + " muestras Fase2, " + ng + " muestras GVNS)");

        } catch (Exception ex) {
            System.err.println("Error al exportar JSON: " + ex.getMessage());
        }
    }

    // ── Helpers internos ──────────────────────────────────────────────────────

    /**
     * Serializa un envío como objeto JSON con sus tramos.
     * Escribe directamente en el StringBuilder (sin crear objetos intermedios).
     */
    private static void appendEnvio(
            StringBuilder sb,
            int e,
            PlanificadorGVNSConcurrente plan,
            GestorDatos datos) {

        int[]  ruta = plan.solucionVuelos[e];
        long[] dias = plan.solucionDias[e];

        // Contar saltos activos
        int numSaltos = 0;
        for (int s = 0; s < ruta.length; s++) {
            if (ruta[s] != -1) numSaltos++;
            else break;
        }

        String iataOri = iata(datos, datos.envioOrigen[e]);
        String iataDes = iata(datos, datos.envioDestino[e]);

        sb.append("    {\n");
        sb.append("      \"id\":      ").append(e).append(",\n");
        sb.append("      \"origen\":  \"").append(iataOri).append("\",\n");
        sb.append("      \"destino\": \"").append(iataDes).append("\",\n");
        sb.append("      \"maletas\": ").append(datos.envioMaletas[e]).append(",\n");

        // Registro y deadline en UTC legible
        sb.append("      \"registroUTC\": \"")
          .append(minutosAFechaHora(datos.envioRegistroUTC[e]))
          .append("\",\n");
        sb.append("      \"deadlineUTC\": \"")
          .append(minutosAFechaHora(datos.envioDeadlineUTC[e]))
          .append("\",\n");

        // Tramos
        sb.append("      \"tramos\": [\n");
        for (int s = 0; s < numSaltos; s++) {
            int  v      = ruta[s];
            long salAbs = dias[s];
            long dur    = duracion(datos, v);
            long lleAbs = salAbs + dur;

            String desde  = iata(datos, datos.vueloOrigen[v]);
            String hasta  = iata(datos, datos.vueloDestino[v]);
            String salida = minutosAFechaHora(salAbs);
            String llegada= minutosAFechaHora(lleAbs);

            sb.append("        {\n");
            sb.append("          \"vuelo\":   ").append(v).append(",\n");
            sb.append("          \"desde\":   \"").append(desde).append("\",\n");
            sb.append("          \"hasta\":   \"").append(hasta).append("\",\n");
            sb.append("          \"salida\":  \"").append(salida).append("\",\n");
            sb.append("          \"llegada\": \"").append(llegada).append("\"\n");
            sb.append("        }");
            if (s < numSaltos - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("      ]\n");
        sb.append("    }");
    }

    /**
     * Convierte minutos absolutos UTC (desde Epoch 1970-01-01 00:00)
     * a cadena legible "YYYY-MM-DD HH:MM UTC".
     *
     * Usa aritmética gregoriana inversa al JDN para evitar import java.time
     * en el path crítico; aquí solo se invocan ~110 veces, por lo que
     * la legibilidad prima sobre el micro-rendimiento.
     */
    static String minutosAFechaHora(long minutosUTC) {
        long totalMin = minutosUTC;

        // Separar días y minutos del día
        long diasEpoch = totalMin / 1440L;
        int  minDia    = (int)(totalMin % 1440L);
        if (minDia < 0) { minDia += 1440; diasEpoch--; }

        int hora = minDia / 60;
        int min  = minDia % 60;

        // JDN del Epoch (1970-01-01) = 2 440 588
        long jdn = diasEpoch + 2_440_588L;

        // Conversión JDN → fecha gregoriana (algoritmo Richards, IERS)
        long a = jdn + 32044L;
        long b = (4 * a + 3) / 146097L;
        long c = a - (146097L * b) / 4;
        long d = (4 * c + 3) / 1461L;
        long e = c - (1461L * d) / 4;
        long m = (5 * e + 2) / 153L;

        int dia  = (int)(e - (153L * m + 2) / 5 + 1);
        int mes  = (int)(m + 3 - 12 * (m / 10));
        int anio = (int)(100L * b + d - 4800 + m / 10);

        return String.format("%04d-%02d-%02d %02d:%02d UTC", anio, mes, dia, hora, min);
    }

    /** Duración de un vuelo en minutos (con ajuste de cruce de medianoche). */
    private static long duracion(GestorDatos t, int v) {
        long d = t.vueloLlegadaUTC[v] - t.vueloSalidaUTC[v];
        return (d < 0) ? d + 1440 : d;
    }

    /** Código IATA de un aeropuerto, con fallback seguro. */
    private static String iata(GestorDatos t, int id) {
        if (id <= 0 || id >= t.iataAeropuerto.length) return "???";
        String c = t.iataAeropuerto[id];
        return (c != null) ? c : "???";
    }
}
