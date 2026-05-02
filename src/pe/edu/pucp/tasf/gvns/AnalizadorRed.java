package pe.edu.pucp.tasf.gvns;

/**
 * Análisis estructural de la red de vuelos y de la calidad de la solución.
 *
 * <p>Provee tres tipos de análisis independientes:</p>
 * <ol>
 *   <li>{@link #analizarCobertura} — para cada par O-D de los envíos, determina
 *       si existe ruta en 1, 2 o 3 saltos en el grafo de vuelos (sin restricciones
 *       de capacidad ni tiempo). Revela si el problema es factible en teoría.</li>
 *   <li>{@link #analizarSolucion} — distribuye los envíos ruteados según la
 *       cantidad de tramos usados (directo / 1 escala / 2 escalas).</li>
 *   <li>{@link #compararFases} — tabla comparativa antes/después del GVNS.</li>
 * </ol>
 *
 * <p>Todas las operaciones trabajan sobre arrays primitivos para no presionar
 * el GC con datos a escala de 9.5 M de envíos.</p>
 */
public final class AnalizadorRed {

    private AnalizadorRed() {}

    // =========================================================================
    // 1. COBERTURA DEL GRAFO DE VUELOS
    //
    // Construye una matriz booleana de alcanzabilidad por número de saltos,
    // usando el conjunto de aeropuertos (id 1..numAeropuertos-1) y de vuelos.
    // Luego clasifica los pares O-D presentes en los envíos.
    //
    // Complejidad: O(A² × V) para la matriz de 1 salto + O(A³) para las de
    // 2 y 3 saltos, donde A = 30 aeropuertos y V = 2866 vuelos → trivial.
    // =========================================================================

    /**
     * Analiza qué pares origen-destino de los envíos son alcanzables en la
     * red de vuelos, sin restricciones de capacidad ni ventanas de tiempo.
     *
     * <p>Imprime una tabla de cobertura por número de saltos y lista los
     * pares O-D sin ruta en ningún nivel (si los hay).</p>
     *
     * @param tablero red cargada con aeropuertos, vuelos y envíos
     */
    public static void analizarCobertura(GestorDatos tablero) {
        System.out.println("\n========== ANÁLISIS DE COBERTURA DE RED ==========");

        int A = tablero.numAeropuertos; // IDs 1..A-1 (0 = sin asignar)

        // Matrices de alcanzabilidad: alcanza[i][j] = true si existe ruta i→j
        boolean[][] hop1 = new boolean[A][A];
        boolean[][] hop2 = new boolean[A][A];
        boolean[][] hop3 = new boolean[A][A];

        // Llenar hop1: existe al menos un vuelo directo i→j
        for (int v = 0; v < tablero.numVuelos; v++) {
            int o = tablero.vueloOrigen[v];
            int d = tablero.vueloDestino[v];
            if (o > 0 && o < A && d > 0 && d < A) hop1[o][d] = true;
        }

        // Llenar hop2: existe escala c tal que hop1[i][c] && hop1[c][j]
        for (int i = 1; i < A; i++) {
            for (int j = 1; j < A; j++) {
                if (i == j) continue;
                if (hop1[i][j]) { hop2[i][j] = true; continue; } // directo es mejor
                for (int c = 1; c < A; c++) {
                    if (c == i || c == j) continue;
                    if (hop1[i][c] && hop1[c][j]) { hop2[i][j] = true; break; }
                }
            }
        }

        // Llenar hop3: existe escala c1 tal que hop1[i][c1] && hop2[c1][j]
        for (int i = 1; i < A; i++) {
            for (int j = 1; j < A; j++) {
                if (i == j) continue;
                if (hop2[i][j]) { hop3[i][j] = true; continue; }
                for (int c = 1; c < A; c++) {
                    if (c == i || c == j) continue;
                    if (hop1[i][c] && hop2[c][j]) { hop3[i][j] = true; break; }
                }
            }
        }

        // Clasificar pares O-D únicos presentes en los envíos
        // Usamos un bitmap compacto: idPar = origen * A + destino
        boolean[] vistoPar = new boolean[A * A];
        int pares1 = 0, pares2 = 0, pares3 = 0, paresNinguno = 0, totalPares = 0;

        for (int e = 0; e < tablero.numEnvios; e++) {
            int o = tablero.envioOrigen[e];
            int d = tablero.envioDestino[e];
            if (o <= 0 || o >= A || d <= 0 || d >= A || o == d) continue;
            int key = o * A + d;
            if (vistoPar[key]) continue;
            vistoPar[key] = true;
            totalPares++;

            if      (hop1[o][d]) pares1++;
            else if (hop2[o][d]) pares2++;
            else if (hop3[o][d]) pares3++;
            else                 paresNinguno++;
        }

        System.out.printf("  Pares O-D únicos en envíos : %d%n", totalPares);
        System.out.printf("  Alcanzables en 1 salto     : %d  (%.1f %%)%n",
                pares1, pct(pares1, totalPares));
        System.out.printf("  Alcanzables en 2 saltos    : %d  (%.1f %%)%n",
                pares2, pct(pares2, totalPares));
        System.out.printf("  Alcanzables en 3 saltos    : %d  (%.1f %%)%n",
                pares3, pct(pares3, totalPares));
        System.out.printf("  Sin ruta en ≤3 saltos      : %d%n", paresNinguno);

        if (paresNinguno > 0) {
            System.out.println("  [ADVERTENCIA] Pares sin cobertura (primeros 10):");
            int mostrados = 0;
            for (int i = 1; i < A && mostrados < 10; i++) {
                for (int j = 1; j < A && mostrados < 10; j++) {
                    if (i == j) continue;
                    int key = i * A + j;
                    if (vistoPar[key] && !hop3[i][j]) {
                        System.out.printf("    %s -> %s%n",
                                iata(tablero, i), iata(tablero, j));
                        mostrados++;
                    }
                }
            }
        }
        System.out.println("==================================================\n");
    }

    // =========================================================================
    // 2. DISTRIBUCIÓN DE TRAMOS EN LA SOLUCIÓN
    //
    // Recorre solucionVuelos y cuenta cuántos envíos usaron 1, 2 o 3 tramos.
    // Útil para entender si el algoritmo usa vuelos directos preferentemente.
    // =========================================================================

    /**
     * Imprime la distribución de la solución por número de tramos usados
     * (1 = directo, 2 = 1 escala, 3 = 2 escalas) y la cantidad de rechazados.
     *
     * @param tablero        red con datos de envíos
     * @param solucionVuelos matriz [envio][salto] de la solución final
     */
    public static void analizarSolucion(GestorDatos tablero, int[][] solucionVuelos) {
        System.out.println("\n========== DISTRIBUCIÓN DE TRAMOS EN SOLUCIÓN ==========");

        int tramo1 = 0, tramo2 = 0, tramo3 = 0, sinRuta = 0;

        for (int e = 0; e < tablero.numEnvios; e++) {
            if (solucionVuelos[e][0] == -1) { sinRuta++; continue; }

            int saltos = 0;
            for (int s = 0; s < solucionVuelos[e].length; s++) {
                if (solucionVuelos[e][s] == -1) break;
                saltos++;
            }
            if      (saltos == 1) tramo1++;
            else if (saltos == 2) tramo2++;
            else                  tramo3++;
        }

        int ruteados = tramo1 + tramo2 + tramo3;
        System.out.printf("  Envíos ruteados            : %,d  (%.4f %%)%n",
                ruteados, pct(ruteados, tablero.numEnvios));
        System.out.printf("  Directo (1 tramo)          : %,d  (%.1f %% de ruteados)%n",
                tramo1, pct(tramo1, ruteados));
        System.out.printf("  1 Escala (2 tramos)        : %,d  (%.1f %%)%n",
                tramo2, pct(tramo2, ruteados));
        System.out.printf("  2 Escalas (3 tramos)       : %,d  (%.1f %%)%n",
                tramo3, pct(tramo3, ruteados));
        System.out.printf("  Sin ruta (rechazados)      : %,d%n", sinRuta);
        System.out.println("=========================================================\n");
    }

    // =========================================================================
    // 3. TABLA COMPARATIVA FASE 2 vs FASE 3
    // =========================================================================

    /**
     * Imprime una tabla comparativa del tránsito total antes y después del GVNS.
     *
     * @param fGreedy    f(x) al final de la Fase 2 (construcción), en minutos
     * @param fGVNS      f(x) al final de la Fase 3 (GVNS), en minutos
     * @param mejoradas  número de rutas mejoradas por GVNS
     */
    public static void compararFases(long fGreedy, long fGVNS, int mejoradas) {
        System.out.println("\n========== COMPARATIVA FASE 2 vs FASE 3 ==========");
        long delta = fGreedy - fGVNS;
        double mejoraPct = (fGreedy > 0) ? delta * 100.0 / fGreedy : 0.0;
        System.out.printf("  f(x) Fase 2 (construcción) : %,d min%n",  fGreedy);
        System.out.printf("  f(x) Fase 3 (GVNS final)   : %,d min%n",  fGVNS);
        System.out.printf("  Reducción absoluta         : %,d min  (%.4f %%)%n",
                delta, mejoraPct);
        System.out.printf("  Rutas modificadas por GVNS : %,d%n", mejoradas);
        System.out.println("===================================================\n");
    }

    // =========================================================================
    // VALIDADOR DE SOLUCIÓN — 5 INVARIANTES
    // =========================================================================

    /**
     * Verifica que la solución del planificador cumple todos los invariantes
     * matemáticos del problema. Imprime en consola cada violación encontrada
     * (hasta 20 ejemplos) y un resumen final.
     *
     * <p>Invariantes comprobados:
     * <ol>
     *   <li>Causalidad temporal: salida ≥ registro del envío.</li>
     *   <li>Continuidad de ruta: destino(tramo n) == origen(tramo n+1).</li>
     *   <li>Conexión mínima: salida(n+1) ≥ llegada(n) + 10 min.</li>
     *   <li>SLA cumplido: llegada final ≤ deadline del envío.</li>
     *   <li>Sin overbooking: Σ maletas por (vuelo, día) ≤ capacidad.</li>
     * </ol>
     *
     * @return {@code true} si la solución no tiene ninguna violación.
     */
    public static boolean validarSolucion(PlanificadorGVNSConcurrente plan,
                                          GestorDatos datos) {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║            VALIDADOR DE SOLUCIÓN GVNS               ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        int violCausalidad  = 0;
        int violContinuidad = 0;
        int violConexion    = 0;
        int violSLA         = 0;
        int ejemplosMostrados = 0;

        final int MAX_EJEMPLOS  = 20;
        final int ESCALA_MINIMA = 10;   // minutos mínimos entre tramos

        for (int e = 0; e < datos.numEnvios; e++) {
            if (plan.solucionVuelos[e][0] == -1) continue; // rechazado: ok, no aplica

            long tRegistro = datos.envioRegistroUTC[e];
            long deadline  = datos.envioDeadlineUTC[e];
            long llegadaAnterior = tRegistro; // se actualiza tramo a tramo

            for (int s = 0; s < plan.MAX_SALTOS; s++) {
                int  v   = plan.solucionVuelos[e][s];
                long sal = plan.solucionDias[e][s];
                if (v == -1) break;

                long dur    = duracionVuelo(datos, v);
                long llegada = sal + dur;

                // ── Invariante 1: causalidad ──────────────────────────────────
                if (s == 0 && sal < tRegistro) {
                    violCausalidad++;
                    if (ejemplosMostrados++ < MAX_EJEMPLOS)
                        System.out.printf("  [CAUSALIDAD]  envio=%d  sal=%d < reg=%d%n",
                                e, sal, tRegistro);
                }

                // ── Invariante 2: continuidad de ruta ─────────────────────────
                if (s > 0) {
                    int vPrev   = plan.solucionVuelos[e][s - 1];
                    int dstPrev = datos.vueloDestino[vPrev];
                    int oriCurr = datos.vueloOrigen[v];
                    if (dstPrev != oriCurr) {
                        violContinuidad++;
                        if (ejemplosMostrados++ < MAX_EJEMPLOS)
                            System.out.printf(
                                "  [CONTINUIDAD] envio=%d tramo%d→%d: %s→[salto]→%s%n",
                                e, s-1, s,
                                iata(datos, dstPrev), iata(datos, oriCurr));
                    }
                }

                // ── Invariante 3: conexión mínima ─────────────────────────────
                if (s > 0 && sal < llegadaAnterior + ESCALA_MINIMA) {
                    violConexion++;
                    if (ejemplosMostrados++ < MAX_EJEMPLOS)
                        System.out.printf(
                            "  [CONEXION]    envio=%d tramo%d: sal=%d < llegPrev+10=%d%n",
                            e, s, sal, llegadaAnterior + ESCALA_MINIMA);
                }

                llegadaAnterior = llegada;

                // ── Invariante 4: SLA (solo en el último tramo) ───────────────
                boolean esUltimo = (s + 1 >= plan.MAX_SALTOS || plan.solucionVuelos[e][s+1] == -1);
                if (esUltimo && llegada > deadline) {
                    violSLA++;
                    if (ejemplosMostrados++ < MAX_EJEMPLOS)
                        System.out.printf(
                            "  [SLA]         envio=%d llegada=%d > deadline=%d (+%d min)%n",
                            e, llegada, deadline, llegada - deadline);
                }
            }
        }

        // ── Invariante 5: sin overbooking (agrupa maletas por vuelo-día) ──────
        java.util.HashMap<Long, Integer> ocupacion = new java.util.HashMap<>();
        for (int e = 0; e < datos.numEnvios; e++) {
            for (int s = 0; s < plan.MAX_SALTOS; s++) {
                int  v   = plan.solucionVuelos[e][s];
                long sal = plan.solucionDias[e][s];
                if (v == -1) break;
                long key = (long) v * 100_000L + (sal / 1440L);
                ocupacion.merge(key, datos.envioMaletas[e], Integer::sum);
            }
        }
        int violOverbooking = 0;
        for (java.util.Map.Entry<Long, Integer> en : ocupacion.entrySet()) {
            int v   = (int)(en.getKey() / 100_000L);
            int ocu = en.getValue();
            int cap = datos.vueloCapacidad[v];
            if (ocu > cap) {
                violOverbooking++;
                if (violOverbooking <= 5)
                    System.out.printf(
                        "  [OVERBOOKING] vuelo=%d día=%d  carga=%d > cap=%d%n",
                        v, (int)(en.getKey() % 100_000L), ocu, cap);
            }
        }

        // ── Resumen ───────────────────────────────────────────────────────────
        int totalViol = violCausalidad + violContinuidad + violConexion
                      + violSLA + violOverbooking;
        System.out.println("\n── Resultado ────────────────────────────────────────────");
        System.out.printf("  Causalidad temporal  : %s%n", fmt(violCausalidad));
        System.out.printf("  Continuidad de ruta  : %s%n", fmt(violContinuidad));
        System.out.printf("  Conexión mínima 10m  : %s%n", fmt(violConexion));
        System.out.printf("  SLA incumplido       : %s%n", fmt(violSLA));
        System.out.printf("  Overbooking vuelo-día: %s%n", fmt(violOverbooking));
        if (totalViol == 0) {
            System.out.println("\n  ✓ SOLUCIÓN VÁLIDA — ninguna violación detectada");
        } else {
            System.out.printf("%n  ✗ %d VIOLACIÓN(ES) — revisar lógica del algoritmo%n",
                    totalViol);
        }
        System.out.println("─────────────────────────────────────────────────────────");
        return totalViol == 0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long duracionVuelo(GestorDatos datos, int v) {
        int sal = datos.vueloSalidaUTC[v];
        int lle = datos.vueloLlegadaUTC[v];
        return (lle >= sal) ? (lle - sal) : (1440 - sal + lle); // cruza medianoche
    }

    private static String fmt(int n) {
        return n == 0 ? "OK (0)" : "!!! " + n + " violaciones";
    }

    private static double pct(int parte, int total) {
        return (total == 0) ? 0.0 : parte * 100.0 / total;
    }

    private static String iata(GestorDatos t, int id) {
        if (id <= 0 || id >= t.iataAeropuerto.length) return "???";
        String c = t.iataAeropuerto[id];
        return (c != null) ? c : "???";
    }
}
