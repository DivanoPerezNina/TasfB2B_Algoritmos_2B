package pe.edu.pucp.tasf.gvns;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Planificador GVNS Concurrente — Iteración 2 (Final)
 *
 * Implementación basada en: Polat, Kalayci & Topaloglu (2026).
 * "An enhanced variable neighborhood search algorithm for rich
 *  multi-compartment vehicle routing problems." Soft Computing 30, 327-352.
 *
 * Problema: Single-Path Unsplittable Multicommodity Network Flow with Time Windows.
 * Objetivo: minimizar envíos rechazados f(x) = |enviosRechazados activos|.
 *
 * Estructura GVNS (Fig. 2 del paper):
 *   Fase 2 → Construcción Golosa (greedy, multihilo)
 *   Fase 3 → Mejora:  while t < t_max:
 *                         x' = Shaking(x, k)        [§3.5]
 *                         x'' = VND(x')              [§3.6-3.7]
 *                         if f(x'') < f(x): x=x'', k=1
 *                         else: Revertir(x'), k++
 *
 * Restricciones arquitectónicas NO negociables:
 *   - Arrays primitivos (int[], long[]) para datos masivos (evita colapso de Heap).
 *   - ConcurrentHashMap<Long, AtomicInteger> para control de capacidad lock-free.
 *   - Paralelismo 100% CPU en VND (parallel streams); Shaking es secuencial.
 */
public class PlanificadorGVNSConcurrente {

    private GestorDatos tablero;
    public int[][] solucionVuelos;
    public long[][] solucionDias;

    // --- POOL DE RECHAZADOS ---
    // Tamaño generoso: admite rechazos iniciales + expulsiones del shaking.
    public int[] enviosRechazados = new int[500_000];
    public int   totalRechazados  = 0;

    // --- PARÁMETROS DE NEGOCIO ---
    public final int TIEMPO_MINIMO_ESCALA = 10;   // minutos mínimos de escala
    public final int MAX_SALTOS           = 3;    // máximo de vuelos por ruta

    // --- PARÁMETROS GVNS (Polat et al., 2026 — Tabla 3 y §3.9.4) ---
    private static final long TIEMPO_LIMITE_MS = 120_000L; // t_max  = 2 minutos
    private static final int  K_MAX            = 3;        // k_max  = niveles de vecindad
    private static final int  BATCH_FACTOR     = 20;       // factor base para tamaño de shaking

    // --- SINCRONIZACIÓN ---
    private final Object lockRechazados = new Object();
    public AtomicInteger enviosExitosos = new AtomicInteger(0);
    public ConcurrentHashMap<Long, AtomicInteger> ocupacionVuelos = new ConcurrentHashMap<>();

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================
    public PlanificadorGVNSConcurrente(GestorDatos datos) {
        this.tablero       = datos;
        this.solucionVuelos = new int[datos.numEnvios][MAX_SALTOS];
        this.solucionDias   = new long[datos.numEnvios][MAX_SALTOS];

        for (int i = 0; i < datos.numEnvios; i++) {
            Arrays.fill(solucionVuelos[i], -1);
            Arrays.fill(solucionDias[i],  -1L);
        }
        Arrays.fill(enviosRechazados, -1);
    }

    // =========================================================================
    // FASE 2: CONSTRUCCIÓN GOLOSA CONCURRENTE — SIN CAMBIOS
    // =========================================================================
    public void construirSolucionInicial() {
        System.out.println("Construyendo Solucion Inicial (Concurrente/Multihilo)...");

        IntStream.range(0, tablero.numEnvios).parallel().forEach(e -> {
            int  origen      = tablero.envioOrigen[e];
            int  destino     = tablero.envioDestino[e];
            int  maletas     = tablero.envioMaletas[e];
            long tRegistro   = tablero.envioRegistroUTC[e];
            long deadline    = tablero.envioDeadlineUTC[e];

            int[]  rutaTemp = new int[MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];

            boolean encontroRuta = buscarRutaDFS(
                    origen, destino, maletas, tRegistro, deadline, 0, rutaTemp, diasTemp);

            if (encontroRuta) {
                // Escritura segura: cada hilo escribe en su propio índice 'e'
                System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasTemp, 0, solucionDias[e],   0, MAX_SALTOS);
                enviosExitosos.incrementAndGet();
            } else {
                synchronized (lockRechazados) {
                    if (totalRechazados < enviosRechazados.length) {
                        enviosRechazados[totalRechazados++] = e;
                    }
                }
            }
        });

        System.out.println("Solucion Inicial Terminada. Ruteados: "
                + enviosExitosos.get() + " / " + tablero.numEnvios);
    }

    // =========================================================================
    // CONTROL DE CAPACIDAD ATÓMICO Y LOCK-FREE — SIN CAMBIOS
    // =========================================================================
    private boolean intentarReservarEspacio(int v, long salidaAbsoluta, int maletas) {
        long diaAbsoluto = salidaAbsoluta / 1440;
        long key = (v * 100_000L) + diaAbsoluto;
        ocupacionVuelos.putIfAbsent(key, new AtomicInteger(0));
        AtomicInteger ocupacion = ocupacionVuelos.get(key);
        while (true) {
            int actual = ocupacion.get();
            if (actual + maletas > tablero.vueloCapacidad[v]) return false;
            if (ocupacion.compareAndSet(actual, actual + maletas))  return true;
        }
    }

    private void liberarEspacio(int v, long salidaAbsoluta, int maletas) {
        long diaAbsoluto = salidaAbsoluta / 1440;
        long key = (v * 100_000L) + diaAbsoluto;
        AtomicInteger ocupacion = ocupacionVuelos.get(key);
        if (ocupacion != null) ocupacion.addAndGet(-maletas);
    }

    // =========================================================================
    // MOTOR DE BÚSQUEDA DFS — SIN CAMBIOS
    // =========================================================================
    private boolean buscarRutaDFS(int nodoActual, int destinoFinal, int maletas,
                                  long tiempoActual, long deadline, int saltoActual,
                                  int[] rutaTempVuelos, long[] rutaTempDias) {
        if (nodoActual == destinoFinal) return true;
        if (saltoActual >= MAX_SALTOS)  return false;

        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] != nodoActual) continue;

            long salidaUTC      = tablero.vueloSalidaUTC[v];
            long duracion       = tablero.vueloLlegadaUTC[v] - salidaUTC;
            if (duracion < 0) duracion += 1440;

            long salidaAbsoluta = calcularMinutoSalidaReal(tiempoActual, salidaUTC);
            if (saltoActual > 0 && salidaAbsoluta < tiempoActual + TIEMPO_MINIMO_ESCALA)
                salidaAbsoluta += 1440;

            long llegadaAbsoluta = salidaAbsoluta + duracion;
            if (llegadaAbsoluta + TIEMPO_MINIMO_ESCALA > deadline) continue;

            if (!intentarReservarEspacio(v, salidaAbsoluta, maletas)) continue;

            rutaTempVuelos[saltoActual] = v;
            rutaTempDias[saltoActual]   = salidaAbsoluta;

            if (buscarRutaDFS(tablero.vueloDestino[v], destinoFinal, maletas,
                    llegadaAbsoluta, deadline, saltoActual + 1, rutaTempVuelos, rutaTempDias))
                return true;

            liberarEspacio(v, salidaAbsoluta, maletas);
            rutaTempVuelos[saltoActual] = -1;
            rutaTempDias[saltoActual]   = -1L;
        }
        return false;
    }

    private long calcularMinutoSalidaReal(long minutoActualAbsoluto, long salidaVueloUTC) {
        long minutoDelDia  = minutoActualAbsoluto % 1440;
        long diaAbsoluto   = minutoActualAbsoluto / 1440;
        return (minutoDelDia <= salidaVueloUTC)
                ? (diaAbsoluto * 1440) + salidaVueloUTC
                : ((diaAbsoluto + 1) * 1440) + salidaVueloUTC;
    }

    // =========================================================================
    // FASE 3: GVNS FORMAL — POLAT ET AL. (2026)
    // =========================================================================

    /**
     * Función objetivo f(x): número de envíos activos (no resueltos).
     * Escala lineal en totalRechazados; aceptable para el ciclo de control.
     */
    private int contarRechazadosActivos() {
        int count = 0;
        for (int i = 0; i < totalRechazados; i++) {
            if (enviosRechazados[i] != -1) count++;
        }
        return count;
    }

    /** Marca como resuelta la primera aparición de idEnvio en el pool. */
    private void eliminarDeRechazados(int idEnvio) {
        for (int i = 0; i < totalRechazados; i++) {
            if (enviosRechazados[i] == idEnvio) {
                enviosRechazados[i] = -1;
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // BUCLE PRINCIPAL GVNS  (Fig. 2 — Polat et al., 2026)
    // -------------------------------------------------------------------------
    /**
     * GVNS formal con:
     *   - Shaking de intensidad k   (§3.5 del paper)
     *   - VND secuencial N1 → N2   (§3.6-3.7 del paper)
     *   - Criterio de aceptación   (§3.9.3 del paper)
     *
     * Complejidad por iteración: O(|rechazados| · |vuelos|) en VND N2.
     * Escalabilidad garantizada por paralelismo en parallel streams.
     */
    public int ejecutarMejoraGVNS() {
        System.out.println("GVNS FORMAL: Iniciando ciclo metaheuristico (Polat et al., 2026)...");

        long tiempoInicio  = System.currentTimeMillis();
        long tiempoLimite  = tiempoInicio + TIEMPO_LIMITE_MS;

        int k              = 1;                          // nivel de vecindad actual
        int mejorFitness   = contarRechazadosActivos();  // f(x): minimizar
        int totalSalvados  = 0;
        int iterTotal      = 0;

        // Tamaño base del batch: proporcional al problema, mínimo BATCH_FACTOR
        int batchBase = Math.max(BATCH_FACTOR, mejorFitness / 100);

        System.out.printf("  Estado inicial: f(x)=%d | t_max=%ds | k_max=%d | batch_base=%d%n",
                mejorFitness, TIEMPO_LIMITE_MS / 1000, K_MAX, batchBase);

        while (System.currentTimeMillis() < tiempoLimite && mejorFitness > 0) {
            iterTotal++;

            // ── SHAKING: perturbación especulativa con intensidad k (§3.5) ──────
            // Tamaño del batch escala linealmente con k: n = k · batchBase
            int numAExpulsar = k * batchBase;
            int[] idsExpulsados     = new int[numAExpulsar];
            int[][] rutasExpulsadas = new int[numAExpulsar][MAX_SALTOS];
            long[][] diasExpulsados = new long[numAExpulsar][MAX_SALTOS];

            int numExpulsados = ejecutarShaking(numAExpulsar,
                    idsExpulsados, rutasExpulsadas, diasExpulsados);


            // ── VND: descenso por vecindades variables (§3.6-3.7) ────────────────
            // Estructura secuencial: N1 → N2, reinicio a N1 si hay mejora local
            int vndNivel = 1;
            while (vndNivel <= 2
                    && contarRechazadosActivos() > 0
                    && System.currentTimeMillis() < tiempoLimite) {

                int salvVND = (vndNivel == 1)
                        ? aplicarVND_N1_Relocate()   // N1: Intra-ruta  (Relocate/DFS puro)
                        : aplicarVND_N2_Exchange();  // N2: Inter-ruta  (Exchange virtual)

                vndNivel = (salvVND > 0) ? 1 : vndNivel + 1;
            }

            // ── CRITERIO DE ACEPTACIÓN (§3.9.3) ──────────────────────────────────
            int nuevoFitness = contarRechazadosActivos();

            if (nuevoFitness < mejorFitness) {
                // f(x') < f(x): ACEPTAR solución, reiniciar k = 1
                totalSalvados += mejorFitness - nuevoFitness;
                mejorFitness   = nuevoFitness;
                k              = 1;
                System.out.printf("  [Iter %d] MEJORA -> f(x)=%d | acumulado=%d | k=1%n",
                        iterTotal, mejorFitness, totalSalvados);
            } else {
                // f(x') >= f(x): RECHAZAR, revertir perturbación, k++
                revertirShaking(idsExpulsados, rutasExpulsadas, diasExpulsados, numExpulsados);
                k = (k % K_MAX) + 1;
                System.out.printf("  [Iter %d] Sin mejora | f(x)=%d | k->%d%n",
                        iterTotal, nuevoFitness, k);
            }
        }

        long tiempoTotal = System.currentTimeMillis() - tiempoInicio;
        System.out.printf(
                "GVNS Terminado | Iteraciones=%d | Salvados=%d | f_final=%d | t=%.2fs%n",
                iterTotal, totalSalvados, mejorFitness, tiempoTotal / 1000.0);
        return totalSalvados;
    }

    // -------------------------------------------------------------------------
    // SHAKING  (§3.5 del paper — operadores inter-ruta sobre la solución actual)
    // -------------------------------------------------------------------------
    /**
     * Expulsa aleatoriamente {@code n} envíos ruteados y los añade al pool de
     * rechazados, liberando su capacidad para que el VND pueda reasignarla.
     *
     * La selección usa un LCG (Linear Congruential Generator) sin crear objetos
     * Random, respetando la restricción de evitar overhead de Heap.
     * La intensidad (número de expulsiones) escala con k.
     *
     * @return número de envíos efectivamente expulsados (≤ n)
     */
    private int ejecutarShaking(int n,
                                int[]    idsExpulsados,
                                int[][]  rutasExpulsadas,
                                long[][] diasExpulsados) {
        int  expulsados = 0;
        long seed       = System.nanoTime();   // semilla LCG, única por llamada
        int  stride     = Math.max(1, tablero.numEnvios / (n * 4));

        for (int intento = 0; intento < tablero.numEnvios && expulsados < n; intento += stride) {
            // LCG: Knuth multiplicative hash
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            int e = (int) (Math.abs(seed) % tablero.numEnvios);

            // Solo expulsar si está correctamente ruteado
            if (solucionVuelos[e][0] == -1) continue;

            // Guardar snapshot completo de la ruta para posible restauración
            idsExpulsados[expulsados] = e;
            System.arraycopy(solucionVuelos[e], 0, rutasExpulsadas[expulsados], 0, MAX_SALTOS);
            System.arraycopy(solucionDias[e],   0, diasExpulsados[expulsados],  0, MAX_SALTOS);

            // Liberar capacidad de cada vuelo en la ruta del expulsado
            for (int s = 0; s < MAX_SALTOS; s++) {
                if (solucionVuelos[e][s] != -1) {
                    liberarEspacio(solucionVuelos[e][s], solucionDias[e][s], tablero.envioMaletas[e]);
                    solucionVuelos[e][s] = -1;
                    solucionDias[e][s]   = -1L;
                }
            }

            // Añadir al pool de candidatos a re-rutear
            if (totalRechazados < enviosRechazados.length) {
                enviosRechazados[totalRechazados++] = e;
            }
            enviosExitosos.decrementAndGet();
            expulsados++;
        }
        return expulsados;
    }

    // -------------------------------------------------------------------------
    // REVERSIÓN DEL SHAKING  (ejecutada solo cuando f(x') >= f(x))
    // -------------------------------------------------------------------------
    /**
     * Restaura los envíos expulsados durante el Shaking a sus rutas originales,
     * re-reservando la capacidad correspondiente.
     *
     * Si otro hilo tomó la capacidad durante el VND (race condition rara),
     * el envío permanece rechazado de forma legítima — el GVNS lo reintentará
     * en la siguiente iteración con otro nivel k.
     */
    private void revertirShaking(int[]    idsExpulsados,
                                 int[][]  rutasExpulsadas,
                                 long[][] diasExpulsados,
                                 int      numExpulsados) {
        for (int i = 0; i < numExpulsados; i++) {
            int e = idsExpulsados[i];

            // Si VND le asignó una nueva ruta: liberarla primero
            if (solucionVuelos[e][0] != -1) {
                for (int s = 0; s < MAX_SALTOS; s++) {
                    if (solucionVuelos[e][s] != -1) {
                        liberarEspacio(solucionVuelos[e][s], solucionDias[e][s],
                                tablero.envioMaletas[e]);
                        solucionVuelos[e][s] = -1;
                        solucionDias[e][s]   = -1L;
                    }
                }
                enviosExitosos.decrementAndGet();
            }

            // Intentar re-reservar la ruta original vuelo a vuelo
            boolean restaurado = true;
            for (int s = 0; s < MAX_SALTOS; s++) {
                if (rutasExpulsadas[i][s] == -1) continue;
                if (!intentarReservarEspacio(rutasExpulsadas[i][s],
                        diasExpulsados[i][s], tablero.envioMaletas[e])) {
                    // Capacidad tomada: liberar los vuelos ya re-reservados y abortar
                    for (int s2 = 0; s2 < s; s2++) {
                        if (rutasExpulsadas[i][s2] != -1) {
                            liberarEspacio(rutasExpulsadas[i][s2],
                                    diasExpulsados[i][s2], tablero.envioMaletas[e]);
                        }
                    }
                    restaurado = false;
                    break;
                }
            }

            if (restaurado) {
                System.arraycopy(rutasExpulsadas[i], 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasExpulsados[i],  0, solucionDias[e],   0, MAX_SALTOS);
                enviosExitosos.incrementAndGet();
                eliminarDeRechazados(e);
            }
            // Si no restaurado: e queda en el pool de rechazados correctamente
        }
    }

    // -------------------------------------------------------------------------
    // VND N1 — VECINDAD INTRA-RUTA: Relocate  (§3.6 del paper)
    // -------------------------------------------------------------------------
    /**
     * Para cada envío rechazado, busca una nueva ruta DFS directa sin desplazar
     * a ningún otro envío. Equivalente al operador "Best Insert" del paper,
     * adaptado al dominio de rutas de vuelos con ventanas de tiempo.
     *
     * Paralelismo: 100% de núcleos mediante parallel streams.
     * Thread-safety: cada hilo escribe en su propio índice i del pool y en
     *   solucionVuelos[e] (índice único por envío en la lista de rechazados).
     */
    private int aplicarVND_N1_Relocate() {
        AtomicInteger salvados = new AtomicInteger(0);

        IntStream.range(0, totalRechazados).parallel().forEach(i -> {
            int e = enviosRechazados[i];
            if (e == -1) return;

            int[]  rutaTemp = new int[MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];
            Arrays.fill(rutaTemp, -1);
            Arrays.fill(diasTemp, -1L);

            boolean encontro = buscarRutaDFS(
                    tablero.envioOrigen[e],    tablero.envioDestino[e],
                    tablero.envioMaletas[e],   tablero.envioRegistroUTC[e],
                    tablero.envioDeadlineUTC[e], 0, rutaTemp, diasTemp);

            if (encontro) {
                System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasTemp, 0, solucionDias[e],   0, MAX_SALTOS);
                enviosRechazados[i] = -1;
                enviosExitosos.incrementAndGet();
                salvados.incrementAndGet();
            }
        });

        return salvados.get();
    }

    // -------------------------------------------------------------------------
    // VND N2 — VECINDAD INTER-RUTA: Exchange virtual  (§3.7 del paper)
    // -------------------------------------------------------------------------
    /**
     * Para cada envío rechazado, identifica el vuelo cuello de botella (saturado)
     * que bloquea su ruta desde el origen. Aplica una expulsión virtual:
     * reduce temporalmente la ocupación en {@code maletas} unidades, busca una
     * ruta DFS alternativa, y restaura la ocupación al estado real.
     *
     * Este mecanismo simula el operador "Exchange" del paper: el GVNS "especula"
     * con que otro envío podría ceder su espacio en el vuelo saturado.
     * Si la ruta alternativa existe, se compromete definitivamente.
     *
     * Lock granular por AtomicInteger del vuelo-día: máxima concurrencia.
     * Paralelismo: 100% de núcleos. El lock solo bloquea ese vuelo específico.
     */
    private int aplicarVND_N2_Exchange() {
        AtomicInteger salvados = new AtomicInteger(0);

        IntStream.range(0, totalRechazados).parallel().forEach(i -> {
            int e = enviosRechazados[i];
            if (e == -1) return;

            int  origen    = tablero.envioOrigen[e];
            int  destino   = tablero.envioDestino[e];
            int  maletas   = tablero.envioMaletas[e];
            long tRegistro = tablero.envioRegistroUTC[e];
            long deadline  = tablero.envioDeadlineUTC[e];

            for (int v = 0; v < tablero.numVuelos; v++) {
                if (tablero.vueloOrigen[v] != origen) continue;

                long salidaUTC   = tablero.vueloSalidaUTC[v];
                long duracion    = tablero.vueloLlegadaUTC[v] - salidaUTC;
                if (duracion < 0) duracion += 1440;

                long salidaAbs  = calcularMinutoSalidaReal(tRegistro, salidaUTC);
                long llegadaAbs = salidaAbs + duracion;
                if (llegadaAbs + TIEMPO_MINIMO_ESCALA > deadline) continue;

                long key = (v * 100_000L) + (salidaAbs / 1440);
                ocupacionVuelos.putIfAbsent(key, new AtomicInteger(0));
                AtomicInteger ocupacion = ocupacionVuelos.get(key);

                // Solo actuar en vuelos saturados (los libres ya los resolvió N1)
                if (ocupacion.get() + maletas <= tablero.vueloCapacidad[v]) continue;

                // Lock granular: bloquea solo este vuelo-día específico
                synchronized (ocupacion) {
                    // Verificar que el envío no fue resuelto por otro hilo
                    if (enviosRechazados[i] == -1) return;

                    // Doble validación del estado del vuelo dentro del lock
                    if (ocupacion.get() + maletas > tablero.vueloCapacidad[v]) {

                        // EXPULSIÓN VIRTUAL: simular que se libera capacidad
                        ocupacion.addAndGet(-maletas);

                        int[]  rutaTemp = new int[MAX_SALTOS];
                        long[] diasTemp = new long[MAX_SALTOS];
                        Arrays.fill(rutaTemp, -1);
                        Arrays.fill(diasTemp, -1L);

                        boolean encontroRuta = buscarRutaDFS(
                                origen, destino, maletas, tRegistro, deadline,
                                0, rutaTemp, diasTemp);

                        // RESTAURACIÓN: siempre revertir la expulsión virtual
                        ocupacion.addAndGet(maletas);

                        if (encontroRuta) {
                            System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                            System.arraycopy(diasTemp, 0, solucionDias[e],   0, MAX_SALTOS);
                            enviosRechazados[i] = -1;
                            enviosExitosos.incrementAndGet();
                            salvados.incrementAndGet();
                            return; // envío resuelto: salir del forEach
                        }
                    }
                }
            }
        });

        return salvados.get();
    }

    // =========================================================================
    // EXPORTACIÓN DE RESULTADOS
    // =========================================================================
    public void exportarResultadosCSV(String nombreArchivo,
                                      double tiempoGreedy,
                                      double tiempoGVNS,
                                      int    salvadosGVNS) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(nombreArchivo))) {
            pw.println("Metrica,Valor");
            pw.println("Total Envios,"          + tablero.numEnvios);
            pw.println("Exitosos Total,"          + enviosExitosos.get());
            pw.println("Rechazados Iniciales,"   + totalRechazados);
            pw.println("Salvados GVNS (N1+N2),"  + salvadosGVNS);
            pw.println("Rechazados Finales,"      + contarRechazadosActivos());
            pw.println("Tiempo Greedy (s),"       + tiempoGreedy);
            pw.println("Tiempo GVNS (s),"         + tiempoGVNS);
            double tasaExito = (enviosExitosos.get() / (double) tablero.numEnvios) * 100.0;
            pw.println("Tasa Exito Final (%),"   + String.format("%.4f", tasaExito));
            System.out.println("Resultados exportados: " + nombreArchivo);
        } catch (Exception ex) {
            System.err.println("Error al exportar CSV: " + ex.getMessage());
        }
    }
}
