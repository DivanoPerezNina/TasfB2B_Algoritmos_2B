package pe.edu.pucp.tasf.gvns;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AuditorRutas — Validación matemática de la solución del planificador.
 *
 * Comprueba que ningún envío ruteado viole las reglas físicas de la red:
 *
 *   a) Continuidad Espacial : destino(Vᵢ) == origen(Vᵢ₊₁)
 *   b) Viabilidad Temporal  : llegada(Vᵢ) + 10 min ≤ salida(Vᵢ₊₁)
 *   c) Deadline             : llegada(V_final) + 10 min ≤ deadlineUTC
 *   d) Capacidad            : ocupación(vuelo-día) ≤ vueloCapacidad[v] en todo el mapa
 *
 * Uso:
 *   AuditorRutas.auditarSolucion(tablero, plan.solucionVuelos,
 *                                plan.solucionDias, plan.ocupacionVuelos);
 */
public final class AuditorRutas {

    private static final int TIEMPO_MINIMO_ESCALA = 10;   // minutos

    // Clase de utilidad: constructor privado
    private AuditorRutas() {}

    /**
     * Audita la solución completa e imprime un reporte en consola.
     *
     * @param tablero        datos de la red (vuelos, aeropuertos, envíos)
     * @param solucionVuelos solucionVuelos[e][s] = ID del vuelo en el salto s del envío e
     * @param solucionDias   solucionDias[e][s]   = salida absoluta (min UTC) del salto s
     * @param ocupacionVuelos mapa de ocupación real con clave vuelo·100_000 + diaAbsoluto
     */
    public static void auditarSolucion(
            GestorDatos tablero,
            int[][]     solucionVuelos,
            long[][]    solucionDias,
            ConcurrentHashMap<Long, AtomicInteger> ocupacionVuelos) {

        System.out.println("\n========== AUDITORÍA DE SOLUCIÓN ==========");

        // Contadores por tipo de violación
        int vContinuidad = 0;
        int vTemporal    = 0;
        int vDeadline    = 0;
        int enviosAuditados = 0;

        // ── a, b, c: Recorrer todos los envíos ruteados ───────────────────────
        for (int e = 0; e < tablero.numEnvios; e++) {
            if (solucionVuelos[e][0] == -1) continue;   // envío rechazado, omitir
            enviosAuditados++;

            long deadline = tablero.envioDeadlineUTC[e];

            // Contar saltos activos
            int numSaltos = 0;
            for (int s = 0; s < solucionVuelos[e].length; s++) {
                if (solucionVuelos[e][s] != -1) numSaltos++;
            }

            // Validar cada tramo
            for (int s = 0; s < numSaltos; s++) {
                int  v       = solucionVuelos[e][s];
                long salAbs  = solucionDias[e][s];
                long dur     = duracion(tablero, v);
                long lleAbs  = salAbs + dur;

                // ── a) Continuidad espacial ───────────────────────────────────
                if (s < numSaltos - 1) {
                    int vSig = solucionVuelos[e][s + 1];
                    if (tablero.vueloDestino[v] != tablero.vueloOrigen[vSig]) {
                        vContinuidad++;
                        if (vContinuidad <= 5)   // limitar spam en consola
                            System.err.printf(
                                "  [ESPACIAL] Envio %d: destino(V%d=%s) != origen(V%d=%s)%n",
                                e,
                                v,   iata(tablero, tablero.vueloDestino[v]),
                                vSig, iata(tablero, tablero.vueloOrigen[vSig]));
                    }
                }

                // ── b) Viabilidad temporal (conexión) ─────────────────────────
                if (s < numSaltos - 1) {
                    long salSig = solucionDias[e][s + 1];
                    if (lleAbs + TIEMPO_MINIMO_ESCALA > salSig) {
                        vTemporal++;
                        if (vTemporal <= 5)
                            System.err.printf(
                                "  [TEMPORAL] Envio %d salto %d: llegada=%d + 10 > salidaSig=%d%n",
                                e, s, lleAbs, salSig);
                    }
                }

                // ── c) Deadline (todos los tramos) ────────────────────────────
                if (lleAbs + TIEMPO_MINIMO_ESCALA > deadline) {
                    vDeadline++;
                    if (vDeadline <= 5)
                        System.err.printf(
                            "  [DEADLINE] Envio %d salto %d: llegada+10=%d > deadline=%d%n",
                            e, s, lleAbs + TIEMPO_MINIMO_ESCALA, deadline);
                }
            }
        }

        // ── d) Capacidad: recorrer el mapa de ocupación ───────────────────────
        int vCapacidad = 0;
        for (Map.Entry<Long, AtomicInteger> entrada : ocupacionVuelos.entrySet()) {
            long clave    = entrada.getKey();
            int  ocu      = entrada.getValue().get();
            int  v        = (int)(clave / 100_000L);

            if (v >= 0 && v < tablero.numVuelos && ocu > tablero.vueloCapacidad[v]) {
                vCapacidad++;
                if (vCapacidad <= 5)
                    System.err.printf(
                        "  [CAPACIDAD] Vuelo %d (%s→%s): ocupacion=%d > capacidad=%d%n",
                        v,
                        iata(tablero, tablero.vueloOrigen[v]),
                        iata(tablero, tablero.vueloDestino[v]),
                        ocu, tablero.vueloCapacidad[v]);
            }
        }

        // ── REPORTE FINAL ─────────────────────────────────────────────────────
        int totalViolaciones = vContinuidad + vTemporal + vDeadline + vCapacidad;

        System.out.println("------------------------------------------");
        System.out.printf("  Envios auditados          : %,d%n",   enviosAuditados);
        System.out.printf("  a) Continuidad espacial   : %d%n",    vContinuidad);
        System.out.printf("  b) Viabilidad temporal    : %d  (Llegada_Vi + 10 min > Salida_Vi+1)%n", vTemporal);
        System.out.printf("  c) Violaciones de deadline: %d  (Llegada + 10 min > DeadlineUTC)%n",    vDeadline);
        System.out.printf("  d) Violaciones de capacidad: %d (vuelo-dia sobre limite)%n", vCapacidad);
        System.out.println("------------------------------------------");
        if (totalViolaciones == 0) {
            System.out.println("  RESULTADO: 0 violaciones encontradas. Solucion 100% factible.");
        } else {
            System.out.printf("  RESULTADO: %d violaciones detectadas. Revisar solucion.%n",
                    totalViolaciones);
        }
        System.out.println("==========================================\n");
    }

    // ── Helpers internos ──────────────────────────────────────────────────────

    /** Duración de un vuelo en minutos (ajustada por cruce de medianoche). */
    private static long duracion(GestorDatos t, int v) {
        long d = t.vueloLlegadaUTC[v] - t.vueloSalidaUTC[v];
        return (d < 0) ? d + 1440 : d;
    }

    /** Código IATA de un aeropuerto por ID, con fallback seguro. */
    private static String iata(GestorDatos t, int id) {
        if (id <= 0 || id >= t.iataAeropuerto.length) return "???";
        String c = t.iataAeropuerto[id];
        return (c != null) ? c : "???";
    }
}
