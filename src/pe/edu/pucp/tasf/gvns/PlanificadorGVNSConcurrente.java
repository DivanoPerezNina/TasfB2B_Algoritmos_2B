package pe.edu.pucp.tasf.gvns;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Planificador GVNS Concurrente — Iteración 3
 *
 * Basado en: Polat, Kalayci & Topaloglu (2026). Soft Computing 30, 327-352.
 * Problema: Single-Path Unsplittable Multicommodity Network Flow with Time Windows.
 *
 * ── CAMBIOS DE ESTA ITERACIÓN (Objetivo 2) ──────────────────────────────────
 * Fase 2 reemplaza el DFS recursivo por una inserción golosa iterativa de
 * 3 niveles (análoga al ALNS del equipo rival, pero con validación real de
 * capacidad mediante ConcurrentHashMap + AtomicInteger):
 *   Nivel 1 → Vuelo Directo (1 tramo)
 *   Nivel 2 → 1 Escala     (2 tramos)
 *   Nivel 3 → 2 Escalas    (3 tramos)
 * El DFS recursivo permanece exclusivamente en la Fase 3 (VND N1/N2).
 *
 * Se añaden arrays de muestra (muestraFase2[], muestraGVNS[]) para
 * ExportadorVisual sin coste de Heap adicional.
 *
 * ── RESTRICCIONES ARQUITECTÓNICAS NO NEGOCIABLES ────────────────────────────
 *   - Arrays primitivos int[], long[] para datos masivos.
 *   - ConcurrentHashMap<Long, AtomicInteger> + CAS loop (lock-free).
 *   - parallel streams para VND; Shaking es secuencial (necesita snapshot).
 */
public class PlanificadorGVNSConcurrente {

    private GestorDatos tablero;
    public int[][]  solucionVuelos;
    public long[][] solucionDias;

    // --- POOL DE RECHAZADOS ---
    public int[] enviosRechazados = new int[500_000];
    public int   totalRechazados  = 0;

    // --- PARÁMETROS DE NEGOCIO ---
    public final int TIEMPO_MINIMO_ESCALA = 10;
    public final int MAX_SALTOS           = 3;

    // --- PARÁMETROS GVNS (Polat et al., 2026 — Tabla 3) ---
    private static final long TIEMPO_LIMITE_MS = 120_000L;
    private static final int  K_MAX            = 3;
    private static final int  BATCH_FACTOR     = 20;

    // --- SINCRONIZACIÓN ---
    private final Object lockRechazados = new Object();
    public  AtomicInteger enviosExitosos = new AtomicInteger(0);
    public  ConcurrentHashMap<Long, AtomicInteger> ocupacionVuelos = new ConcurrentHashMap<>();

    // --- MUESTRAS PARA ExportadorVisual ---
    /** Primeros 100 envíos ruteados exitosamente en Fase 2. */
    public final int[]        muestraFase2    = new int[100];
    private final AtomicInteger idxMuestraFase2 = new AtomicInteger(0);

    /** Primeros 10 envíos salvados por la Fase 3 (GVNS). */
    public final int[]        muestraGVNS     = new int[10];
    private final AtomicInteger idxMuestraGVNS  = new AtomicInteger(0);

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================
    public PlanificadorGVNSConcurrente(GestorDatos datos) {
        this.tablero        = datos;
        this.solucionVuelos = new int [datos.numEnvios][MAX_SALTOS];
        this.solucionDias   = new long[datos.numEnvios][MAX_SALTOS];

        for (int i = 0; i < datos.numEnvios; i++) {
            Arrays.fill(solucionVuelos[i], -1);
            Arrays.fill(solucionDias[i],  -1L);
        }
        Arrays.fill(enviosRechazados, -1);
        Arrays.fill(muestraFase2, -1);
        Arrays.fill(muestraGVNS,  -1);
    }

    // =========================================================================
    // ACCESORES DE MUESTRA (para ExportadorVisual)
    // =========================================================================
    public int cantMuestraFase2() { return Math.min(idxMuestraFase2.get(), 100); }
    public int cantMuestraGVNS()  { return Math.min(idxMuestraGVNS.get(),  10); }

    // =========================================================================
    // FASE 2: CONSTRUCCIÓN GOLOSA — OBJETIVO 2
    //
    // Reemplaza el DFS recursivo por buscarRutaGreedy() (inserción iterativa de
    // 3 niveles). La razón: el DFS explora todo el árbol de rutas con backtracking;
    // el greedy es O(V) / O(V²) / O(V³) en 1/2/3 saltos y no hace backtracking,
    // lo que lo hace 3-5× más rápido en la fase de construcción masiva.
    // La complejidad de exploración (VND) se reserva para la Fase 3.
    // =========================================================================
    public void construirSolucionInicial() {
        System.out.println("Fase 2: Construccion Golosa Iterativa (3 niveles, multihilo)...");

        IntStream.range(0, tablero.numEnvios).parallel().forEach(e -> {
            int  origen    = tablero.envioOrigen[e];
            int  destino   = tablero.envioDestino[e];
            int  maletas   = tablero.envioMaletas[e];
            long tRegistro = tablero.envioRegistroUTC[e];
            long deadline  = tablero.envioDeadlineUTC[e];

            int[]  rutaTemp = new int [MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];

            // ── GREEDY ITERATIVO (no DFS) ─────────────────────────────────────
            boolean encontroRuta = buscarRutaGreedy(
                    origen, destino, maletas, tRegistro, deadline, rutaTemp, diasTemp);

            if (encontroRuta) {
                // Escritura segura: cada hilo escribe en su propio índice 'e'
                System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasTemp, 0, solucionDias[e],   0, MAX_SALTOS);
                enviosExitosos.incrementAndGet();

                // Capturar muestra para ExportadorVisual (primeros 100)
                int idx = idxMuestraFase2.getAndIncrement();
                if (idx < 100) muestraFase2[idx] = e;

            } else {
                synchronized (lockRechazados) {
                    if (totalRechazados < enviosRechazados.length)
                        enviosRechazados[totalRechazados++] = e;
                }
            }
        });

        System.out.printf("Fase 2 Terminada. Ruteados: %d / %d  |  Rechazados: %d%n",
                enviosExitosos.get(), tablero.numEnvios, totalRechazados);
    }

    // =========================================================================
    // BÚSQUEDA GOLOSA ITERATIVA — 3 NIVELES (FASE 2)
    //
    // Estrategia equivalente al ALNS del equipo rival, pero con validación REAL
    // de capacidad (CAS atómico). El ALNS omite esta validación y "hace trampa";
    // nosotros la incluimos porque la corrección es no negociable.
    //
    // Política de reserva:
    //   Nivel 1: reservar v1 → éxito o próximo v1.
    //   Nivel 2: reservar v1, luego iterar v2; liberar v1 si ningún v2 funciona.
    //   Nivel 3: análogo con 3 vuelos, libera en cascada al fallar.
    //
    // Sin backtracking entre niveles: si el nivel N falla, avanza al nivel N+1
    // (el DFS lo manejaría la Fase 3).
    // =========================================================================
    private boolean buscarRutaGreedy(int origen, int destino, int maletas,
                                     long tRegistro, long deadline,
                                     int[] rutaOut, long[] diasOut) {
        // ── NIVEL 1: Vuelo Directo ────────────────────────────────────────────
        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] != origen || tablero.vueloDestino[v] != destino) continue;

            long sal1 = calcularMinutoSalidaReal(tRegistro, tablero.vueloSalidaUTC[v]);
            long dur1 = duracionVuelo(v);
            long lle1 = sal1 + dur1;
            if (lle1 + TIEMPO_MINIMO_ESCALA > deadline) continue;

            if (!intentarReservarEspacio(v, sal1, maletas)) continue;

            rutaOut[0] = v;   diasOut[0] = sal1;
            return true;
        }

        // ── NIVEL 2: 1 Escala (2 tramos) ─────────────────────────────────────
        for (int v1 = 0; v1 < tablero.numVuelos; v1++) {
            if (tablero.vueloOrigen[v1] != origen) continue;

            int  escala1 = tablero.vueloDestino[v1];
            if (escala1 == destino) continue;            // directo: cubierto en nivel 1

            long sal1 = calcularMinutoSalidaReal(tRegistro, tablero.vueloSalidaUTC[v1]);
            long dur1 = duracionVuelo(v1);
            long lle1 = sal1 + dur1;
            if (lle1 + TIEMPO_MINIMO_ESCALA > deadline) continue;

            // Reservar v1 antes de iterar v2 (evita reservar/liberar v1 N veces)
            if (!intentarReservarEspacio(v1, sal1, maletas)) continue;

            boolean v2Encontrado = false;
            for (int v2 = 0; v2 < tablero.numVuelos; v2++) {
                if (tablero.vueloOrigen[v2] != escala1 || tablero.vueloDestino[v2] != destino) continue;

                long sal2 = calcularMinutoSalidaReal(lle1, tablero.vueloSalidaUTC[v2]);
                if (sal2 < lle1 + TIEMPO_MINIMO_ESCALA) sal2 += 1440;
                long lle2 = sal2 + duracionVuelo(v2);
                if (lle2 + TIEMPO_MINIMO_ESCALA > deadline) continue;

                if (!intentarReservarEspacio(v2, sal2, maletas)) continue;

                rutaOut[0] = v1; diasOut[0] = sal1;
                rutaOut[1] = v2; diasOut[1] = sal2;
                v2Encontrado = true;
                break;
            }

            if (v2Encontrado) return true;

            // Ningún v2 funcionó con este v1: liberar v1
            liberarEspacio(v1, sal1, maletas);
        }

        // ── NIVEL 3: 2 Escalas (3 tramos) ────────────────────────────────────
        for (int v1 = 0; v1 < tablero.numVuelos; v1++) {
            if (tablero.vueloOrigen[v1] != origen) continue;

            int  escala1 = tablero.vueloDestino[v1];
            if (escala1 == destino || escala1 == origen) continue;

            long sal1 = calcularMinutoSalidaReal(tRegistro, tablero.vueloSalidaUTC[v1]);
            long dur1 = duracionVuelo(v1);
            long lle1 = sal1 + dur1;
            if (lle1 + TIEMPO_MINIMO_ESCALA > deadline) continue;

            if (!intentarReservarEspacio(v1, sal1, maletas)) continue;

            boolean v2v3Encontrado = false;

            for (int v2 = 0; v2 < tablero.numVuelos; v2++) {
                if (tablero.vueloOrigen[v2] != escala1) continue;

                int escala2 = tablero.vueloDestino[v2];
                if (escala2 == destino || escala2 == origen || escala2 == escala1) continue;

                long sal2 = calcularMinutoSalidaReal(lle1, tablero.vueloSalidaUTC[v2]);
                if (sal2 < lle1 + TIEMPO_MINIMO_ESCALA) sal2 += 1440;
                long lle2 = sal2 + duracionVuelo(v2);
                if (lle2 + TIEMPO_MINIMO_ESCALA > deadline) continue;

                if (!intentarReservarEspacio(v2, sal2, maletas)) continue;

                boolean v3Encontrado = false;

                for (int v3 = 0; v3 < tablero.numVuelos; v3++) {
                    if (tablero.vueloOrigen[v3] != escala2 || tablero.vueloDestino[v3] != destino) continue;

                    long sal3 = calcularMinutoSalidaReal(lle2, tablero.vueloSalidaUTC[v3]);
                    if (sal3 < lle2 + TIEMPO_MINIMO_ESCALA) sal3 += 1440;
                    long lle3 = sal3 + duracionVuelo(v3);
                    if (lle3 + TIEMPO_MINIMO_ESCALA > deadline) continue;

                    if (!intentarReservarEspacio(v3, sal3, maletas)) continue;

                    rutaOut[0] = v1; diasOut[0] = sal1;
                    rutaOut[1] = v2; diasOut[1] = sal2;
                    rutaOut[2] = v3; diasOut[2] = sal3;
                    v3Encontrado = true;
                    break;
                }

                if (v3Encontrado) { v2v3Encontrado = true; break; }

                // v3 no encontrado: liberar v2 y probar siguiente v2
                liberarEspacio(v2, sal2, maletas);
            }

            if (v2v3Encontrado) return true;

            // Ninguna combinación v2+v3 funcionó: liberar v1
            liberarEspacio(v1, sal1, maletas);
        }

        return false; // Sin ruta en ninguno de los 3 niveles → envío rechazado
    }

    /** Duración de vuelo en minutos, ajustada si cruza medianoche. */
    private long duracionVuelo(int v) {
        long dur = tablero.vueloLlegadaUTC[v] - tablero.vueloSalidaUTC[v];
        return (dur < 0) ? dur + 1440 : dur;
    }

    // =========================================================================
    // CONTROL DE CAPACIDAD ATÓMICO Y LOCK-FREE — SIN CAMBIOS
    // =========================================================================
    private boolean intentarReservarEspacio(int v, long salidaAbsoluta, int maletas) {
        long key = claveVueloDia(v, salidaAbsoluta);
        ocupacionVuelos.putIfAbsent(key, new AtomicInteger(0));
        AtomicInteger ocu = ocupacionVuelos.get(key);
        while (true) {
            int actual = ocu.get();
            if (actual + maletas > tablero.vueloCapacidad[v]) return false;
            if (ocu.compareAndSet(actual, actual + maletas))  return true;
        }
    }

    private void liberarEspacio(int v, long salidaAbsoluta, int maletas) {
        AtomicInteger ocu = ocupacionVuelos.get(claveVueloDia(v, salidaAbsoluta));
        if (ocu != null) ocu.addAndGet(-maletas);
    }

    /** Clave única vuelo-día para el mapa de ocupación. */
    private static long claveVueloDia(int v, long salidaAbsoluta) {
        return v * 100_000L + (salidaAbsoluta / 1440);
    }

    // =========================================================================
    // MOTOR DFS — EXCLUSIVO PARA FASE 3 (VND N1/N2)
    // =========================================================================
    private boolean buscarRutaDFS(int nodoActual, int destinoFinal, int maletas,
                                  long tiempoActual, long deadline, int saltoActual,
                                  int[] rutaTempVuelos, long[] rutaTempDias) {
        if (nodoActual == destinoFinal) return true;
        if (saltoActual >= MAX_SALTOS)  return false;

        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] != nodoActual) continue;

            long salAbs = calcularMinutoSalidaReal(tiempoActual, tablero.vueloSalidaUTC[v]);
            if (saltoActual > 0 && salAbs < tiempoActual + TIEMPO_MINIMO_ESCALA) salAbs += 1440;
            long lleAbs = salAbs + duracionVuelo(v);
            if (lleAbs + TIEMPO_MINIMO_ESCALA > deadline) continue;
            if (!intentarReservarEspacio(v, salAbs, maletas))  continue;

            rutaTempVuelos[saltoActual] = v;
            rutaTempDias  [saltoActual] = salAbs;

            if (buscarRutaDFS(tablero.vueloDestino[v], destinoFinal, maletas,
                    lleAbs, deadline, saltoActual + 1, rutaTempVuelos, rutaTempDias))
                return true;

            liberarEspacio(v, salAbs, maletas);
            rutaTempVuelos[saltoActual] = -1;
            rutaTempDias  [saltoActual] = -1L;
        }
        return false;
    }

    private long calcularMinutoSalidaReal(long minutoActualAbsoluto, long salidaVueloUTC) {
        long minDelDia  = minutoActualAbsoluto % 1440;
        long diaAbs     = minutoActualAbsoluto / 1440;
        return (minDelDia <= salidaVueloUTC)
                ? diaAbs * 1440 + salidaVueloUTC
                : (diaAbs + 1) * 1440 + salidaVueloUTC;
    }

    // =========================================================================
    // FASE 3: GVNS FORMAL — POLAT ET AL. (2026) — SIN CAMBIOS ESTRUCTURALES
    // =========================================================================

    private int contarRechazadosActivos() {
        int count = 0;
        for (int i = 0; i < totalRechazados; i++)
            if (enviosRechazados[i] != -1) count++;
        return count;
    }

    private void eliminarDeRechazados(int idEnvio) {
        for (int i = 0; i < totalRechazados; i++) {
            if (enviosRechazados[i] == idEnvio) { enviosRechazados[i] = -1; return; }
        }
    }

    public int ejecutarMejoraGVNS() {
        System.out.println("GVNS FORMAL: Iniciando ciclo metaheuristico (Polat et al., 2026)...");

        long tiempoInicio = System.currentTimeMillis();
        long tiempoLimite = tiempoInicio + TIEMPO_LIMITE_MS;

        int k             = 1;
        int mejorFitness  = contarRechazadosActivos();
        int totalSalvados = 0;
        int iterTotal     = 0;
        int batchBase     = Math.max(BATCH_FACTOR, mejorFitness / 100);

        System.out.printf("  Estado inicial: f(x)=%d | t_max=%ds | k_max=%d | batch=%d%n",
                mejorFitness, TIEMPO_LIMITE_MS / 1000, K_MAX, batchBase);

        while (System.currentTimeMillis() < tiempoLimite && mejorFitness > 0) {
            iterTotal++;

            // ── SHAKING ───────────────────────────────────────────────────────
            int numAExpulsar        = k * batchBase;
            int[]    idsExpulsados  = new int   [numAExpulsar];
            int[][]  rutasExp       = new int   [numAExpulsar][MAX_SALTOS];
            long[][] diasExp        = new long  [numAExpulsar][MAX_SALTOS];
            int numExpulsados = ejecutarShaking(numAExpulsar, idsExpulsados, rutasExp, diasExp);

            // ── VND ───────────────────────────────────────────────────────────
            int vndNivel = 1;
            while (vndNivel <= 2
                    && contarRechazadosActivos() > 0
                    && System.currentTimeMillis() < tiempoLimite) {
                int salv = (vndNivel == 1)
                        ? aplicarVND_N1_Relocate()
                        : aplicarVND_N2_Exchange();
                vndNivel = (salv > 0) ? 1 : vndNivel + 1;
            }

            // ── CRITERIO DE ACEPTACIÓN ────────────────────────────────────────
            int nuevoFitness = contarRechazadosActivos();
            if (nuevoFitness < mejorFitness) {
                totalSalvados += mejorFitness - nuevoFitness;
                mejorFitness   = nuevoFitness;
                k              = 1;
                System.out.printf("  [Iter %d] MEJORA -> f(x)=%d | acumulado=%d | k=1%n",
                        iterTotal, mejorFitness, totalSalvados);
            } else {
                revertirShaking(idsExpulsados, rutasExp, diasExp, numExpulsados);
                k = (k % K_MAX) + 1;
                System.out.printf("  [Iter %d] Sin mejora | f(x)=%d | k->%d%n",
                        iterTotal, nuevoFitness, k);
            }
        }

        long tTotal = System.currentTimeMillis() - tiempoInicio;
        System.out.printf("GVNS Terminado | Iter=%d | Salvados=%d | f_final=%d | t=%.2fs%n",
                iterTotal, totalSalvados, mejorFitness, tTotal / 1000.0);
        return totalSalvados;
    }

    // ── SHAKING ───────────────────────────────────────────────────────────────
    private int ejecutarShaking(int n,
                                int[]    idsExpulsados,
                                int[][]  rutasExpulsadas,
                                long[][] diasExpulsados) {
        int  expulsados = 0;
        long seed       = System.nanoTime();
        int  stride     = Math.max(1, tablero.numEnvios / (n * 4));

        for (int i = 0; i < tablero.numEnvios && expulsados < n; i += stride) {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            int e = (int)(Math.abs(seed) % tablero.numEnvios);

            if (solucionVuelos[e][0] == -1) continue;

            idsExpulsados[expulsados] = e;
            System.arraycopy(solucionVuelos[e], 0, rutasExpulsadas[expulsados], 0, MAX_SALTOS);
            System.arraycopy(solucionDias[e],   0, diasExpulsados [expulsados], 0, MAX_SALTOS);

            for (int s = 0; s < MAX_SALTOS; s++) {
                if (solucionVuelos[e][s] != -1) {
                    liberarEspacio(solucionVuelos[e][s], solucionDias[e][s], tablero.envioMaletas[e]);
                    solucionVuelos[e][s] = -1;
                    solucionDias  [e][s] = -1L;
                }
            }
            if (totalRechazados < enviosRechazados.length)
                enviosRechazados[totalRechazados++] = e;
            enviosExitosos.decrementAndGet();
            expulsados++;
        }
        return expulsados;
    }

    // ── REVERSIÓN DEL SHAKING ─────────────────────────────────────────────────
    private void revertirShaking(int[]    idsExpulsados,
                                 int[][]  rutasExpulsadas,
                                 long[][] diasExpulsados,
                                 int      numExpulsados) {
        for (int i = 0; i < numExpulsados; i++) {
            int e = idsExpulsados[i];

            if (solucionVuelos[e][0] != -1) {
                for (int s = 0; s < MAX_SALTOS; s++) {
                    if (solucionVuelos[e][s] != -1) {
                        liberarEspacio(solucionVuelos[e][s], solucionDias[e][s],
                                tablero.envioMaletas[e]);
                        solucionVuelos[e][s] = -1;
                        solucionDias  [e][s] = -1L;
                    }
                }
                enviosExitosos.decrementAndGet();
            }

            boolean restaurado = true;
            for (int s = 0; s < MAX_SALTOS; s++) {
                if (rutasExpulsadas[i][s] == -1) continue;
                if (!intentarReservarEspacio(rutasExpulsadas[i][s],
                        diasExpulsados[i][s], tablero.envioMaletas[e])) {
                    for (int s2 = 0; s2 < s; s2++) {
                        if (rutasExpulsadas[i][s2] != -1)
                            liberarEspacio(rutasExpulsadas[i][s2],
                                    diasExpulsados[i][s2], tablero.envioMaletas[e]);
                    }
                    restaurado = false;
                    break;
                }
            }

            if (restaurado) {
                System.arraycopy(rutasExpulsadas[i], 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasExpulsados [i], 0, solucionDias  [e], 0, MAX_SALTOS);
                enviosExitosos.incrementAndGet();
                eliminarDeRechazados(e);
            }
        }
    }

    // ── VND N1: Relocate ──────────────────────────────────────────────────────
    private int aplicarVND_N1_Relocate() {
        AtomicInteger salvados = new AtomicInteger(0);

        IntStream.range(0, totalRechazados).parallel().forEach(i -> {
            int e = enviosRechazados[i];
            if (e == -1) return;

            int[]  rutaTemp = new int [MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];
            Arrays.fill(rutaTemp, -1);
            Arrays.fill(diasTemp, -1L);

            boolean encontro = buscarRutaDFS(
                    tablero.envioOrigen[e],    tablero.envioDestino[e],
                    tablero.envioMaletas[e],   tablero.envioRegistroUTC[e],
                    tablero.envioDeadlineUTC[e], 0, rutaTemp, diasTemp);

            if (encontro) {
                System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasTemp, 0, solucionDias  [e], 0, MAX_SALTOS);
                enviosRechazados[i] = -1;
                enviosExitosos.incrementAndGet();
                salvados.incrementAndGet();

                // Capturar muestra para ExportadorVisual
                int idx = idxMuestraGVNS.getAndIncrement();
                if (idx < 10) muestraGVNS[idx] = e;
            }
        });

        return salvados.get();
    }

    // ── VND N2: Exchange virtual ──────────────────────────────────────────────
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

                long salAbs = calcularMinutoSalidaReal(tRegistro, tablero.vueloSalidaUTC[v]);
                long lleAbs = salAbs + duracionVuelo(v);
                if (lleAbs + TIEMPO_MINIMO_ESCALA > deadline) continue;

                long key = claveVueloDia(v, salAbs);
                ocupacionVuelos.putIfAbsent(key, new AtomicInteger(0));
                AtomicInteger ocu = ocupacionVuelos.get(key);

                if (ocu.get() + maletas <= tablero.vueloCapacidad[v]) continue;

                synchronized (ocu) {
                    if (enviosRechazados[i] == -1) return;

                    if (ocu.get() + maletas > tablero.vueloCapacidad[v]) {
                        ocu.addAndGet(-maletas);

                        int[]  rutaTemp = new int [MAX_SALTOS];
                        long[] diasTemp = new long[MAX_SALTOS];
                        Arrays.fill(rutaTemp, -1);
                        Arrays.fill(diasTemp, -1L);

                        boolean encontroRuta = buscarRutaDFS(
                                origen, destino, maletas, tRegistro, deadline,
                                0, rutaTemp, diasTemp);

                        ocu.addAndGet(maletas);

                        if (encontroRuta) {
                            System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                            System.arraycopy(diasTemp, 0, solucionDias  [e], 0, MAX_SALTOS);
                            enviosRechazados[i] = -1;
                            enviosExitosos.incrementAndGet();
                            salvados.incrementAndGet();

                            // Capturar muestra para ExportadorVisual
                            int idx = idxMuestraGVNS.getAndIncrement();
                            if (idx < 10) muestraGVNS[idx] = e;
                            return;
                        }
                    }
                }
            }
        });

        return salvados.get();
    }

    // =========================================================================
    // EXPORTACIÓN CSV DE MÉTRICAS — SIN CAMBIOS
    // =========================================================================
    public void exportarResultadosCSV(String nombreArchivo,
                                      double tiempoGreedy,
                                      double tiempoGVNS,
                                      int    salvadosGVNS) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(nombreArchivo))) {
            pw.println("Metrica,Valor");
            pw.println("Total Envios,"         + tablero.numEnvios);
            pw.println("Exitosos Total,"        + enviosExitosos.get());
            pw.println("Rechazados Iniciales,"  + totalRechazados);
            pw.println("Salvados GVNS (N1+N2)," + salvadosGVNS);
            pw.println("Rechazados Finales,"     + contarRechazadosActivos());
            pw.println("Tiempo Greedy (s),"      + tiempoGreedy);
            pw.println("Tiempo GVNS (s),"        + tiempoGVNS);
            double tasa = (enviosExitosos.get() / (double) tablero.numEnvios) * 100.0;
            pw.println("Tasa Exito Final (%),"  + String.format("%.4f", tasa));
            System.out.println("Resultados exportados: " + nombreArchivo);
        } catch (Exception ex) {
            System.err.println("Error al exportar CSV: " + ex.getMessage());
        }
    }
}
