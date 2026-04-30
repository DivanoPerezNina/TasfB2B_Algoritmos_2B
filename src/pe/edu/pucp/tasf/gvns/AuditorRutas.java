package pe.edu.pucp.tasf.gvns;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AuditorRutas — Validación matemática de la solución del planificador.
 *
 * Reglas verificadas:
 *   a) Continuidad Espacial : destino(Vᵢ) == origen(Vᵢ₊₁)
 *   b) Viabilidad Temporal  : llegada(Vᵢ) + 10 min ≤ salida(Vᵢ₊₁)
 *   c) Deadline             : llegada(V_final) + 10 min ≤ deadlineUTC
 *   d) Destino Final        : vueloDestino[último tramo] == envioDestino[e]
 *   e) Capacidad (rebuild)  : reconstruye la suma de maletas por (vuelo, día)
 *                             desde solucionVuelos[] — independiente del mapa
 *                             de ocupación del planificador.
 *
 * La regla (e) reemplaza el chequeo anterior que leía ocupacionVuelos
 * (podría contener estado residual de cancelaciones). El rebuild desde cero
 * es la única forma de verificar la ausencia de race conditions de capacidad.
 */
public final class AuditorRutas {

    private static final int TIEMPO_MINIMO_ESCALA = 10;

    private AuditorRutas() {}

    public static void auditarSolucion(
            GestorDatos tablero,
            int[][]     solucionVuelos,
            long[][]    solucionDias,
            ConcurrentHashMap<Long, AtomicInteger> ocupacionVuelos) {

        System.out.println("\n========== AUDITORÍA DE SOLUCIÓN ==========");

        int vContinuidad  = 0;
        int vTemporal     = 0;
        int vDeadline     = 0;
        int vDestino      = 0;
        int enviosAudit   = 0;

        // ── Reconstrucción de capacidad desde cero ────────────────────────────
        // Clave: v * 100_000L + (salidaAbsoluta / 1440)  — igual que el planificador
        Map<Long, Integer> ocupRebuilt = new HashMap<>();

        // ── a, b, c, d: recorrer todos los envíos ruteados ───────────────────
        for (int e = 0; e < tablero.numEnvios; e++) {
            if (solucionVuelos[e][0] == -1) continue;
            enviosAudit++;

            long deadline = tablero.envioDeadlineUTC[e];

            // Contar saltos activos (break en primer -1: rutas son contiguas)
            int numSaltos = 0;
            for (int s = 0; s < solucionVuelos[e].length; s++) {
                if (solucionVuelos[e][s] == -1) break;
                numSaltos++;
            }

            for (int s = 0; s < numSaltos; s++) {
                int  v      = solucionVuelos[e][s];
                long salAbs = solucionDias[e][s];
                long dur    = duracion(tablero, v);
                long lleAbs = salAbs + dur;

                // ── e) Acumulación para rebuild de capacidad ──────────────────
                long clave = (long) v * 100_000L + (salAbs / 1440);
                ocupRebuilt.merge(clave, tablero.envioMaletas[e], Integer::sum);

                // ── a) Continuidad espacial ───────────────────────────────────
                if (s < numSaltos - 1) {
                    int vSig = solucionVuelos[e][s + 1];
                    if (tablero.vueloDestino[v] != tablero.vueloOrigen[vSig]) {
                        vContinuidad++;
                        if (vContinuidad <= 5)
                            System.err.printf(
                                "  [ESPACIAL] Envio %d: destino(V%d=%s) != origen(V%d=%s)%n",
                                e, v, iata(tablero, tablero.vueloDestino[v]),
                                vSig, iata(tablero, tablero.vueloOrigen[vSig]));
                    }
                }

                // ── b) Viabilidad temporal ────────────────────────────────────
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

                // ── c) Deadline ───────────────────────────────────────────────
                if (lleAbs + TIEMPO_MINIMO_ESCALA > deadline) {
                    vDeadline++;
                    if (vDeadline <= 5)
                        System.err.printf(
                            "  [DEADLINE] Envio %d salto %d: llegada+10=%d > deadline=%d%n",
                            e, s, lleAbs + TIEMPO_MINIMO_ESCALA, deadline);
                }

                // ── d) Destino final ──────────────────────────────────────────
                if (s == numSaltos - 1) {
                    if (tablero.vueloDestino[v] != tablero.envioDestino[e]) {
                        vDestino++;
                        if (vDestino <= 5)
                            System.err.printf(
                                "  [DESTINO] Envio %d: ultimo tramo llega a %s, esperado %s%n",
                                e,
                                iata(tablero, tablero.vueloDestino[v]),
                                iata(tablero, tablero.envioDestino[e]));
                    }
                }
            }
        }

        // ── e) Capacidad: comparar rebuild vs capacidad física ────────────────
        // Este chequeo es independiente del ConcurrentHashMap del planificador.
        // Si hay race condition o reserva indebida, la suma reconstruida lo detecta.
        int vCapacidad = 0;
        for (Map.Entry<Long, Integer> entrada : ocupRebuilt.entrySet()) {
            long clave = entrada.getKey();
            int  ocu   = entrada.getValue();
            int  v     = (int)(clave / 100_000L);

            if (v >= 0 && v < tablero.numVuelos && ocu > tablero.vueloCapacidad[v]) {
                vCapacidad++;
                if (vCapacidad <= 5)
                    System.err.printf(
                        "  [CAPACIDAD] Vuelo %d (%s->%s): suma_real=%d > cap=%d%n",
                        v,
                        iata(tablero, tablero.vueloOrigen[v]),
                        iata(tablero, tablero.vueloDestino[v]),
                        ocu, tablero.vueloCapacidad[v]);
            }
        }

        // ── REPORTE FINAL ─────────────────────────────────────────────────────
        int total = vContinuidad + vTemporal + vDeadline + vDestino + vCapacidad;

        System.out.println("------------------------------------------");
        System.out.printf("  Envios auditados            : %,d%n",  enviosAudit);
        System.out.printf("  a) Continuidad espacial     : %d%n",   vContinuidad);
        System.out.printf("  b) Viabilidad temporal      : %d  (llegada+10 > siguiente salida)%n", vTemporal);
        System.out.printf("  c) Violaciones de deadline  : %d  (llegada+10 > deadlineUTC)%n",      vDeadline);
        System.out.printf("  d) Destino final incorrecto : %d  (ultimo tramo != envioDestino)%n",  vDestino);
        System.out.printf("  e) Capacidad (rebuild)      : %d  (suma_maletas > capacidad fisica)%n", vCapacidad);
        System.out.println("------------------------------------------");
        if (total == 0) {
            System.out.println("  RESULTADO: 0 violaciones. Solucion 100% factible.");
        } else {
            System.out.printf("  RESULTADO: %d violaciones detectadas. Revisar solucion.%n", total);
        }
        System.out.println("==========================================\n");
    }

    private static long duracion(GestorDatos t, int v) {
        long d = t.vueloLlegadaUTC[v] - t.vueloSalidaUTC[v];
        return (d < 0) ? d + 1440 : d;
    }

    private static String iata(GestorDatos t, int id) {
        if (id <= 0 || id >= t.iataAeropuerto.length) return "???";
        String c = t.iataAeropuerto[id];
        return (c != null) ? c : "???";
    }
}
