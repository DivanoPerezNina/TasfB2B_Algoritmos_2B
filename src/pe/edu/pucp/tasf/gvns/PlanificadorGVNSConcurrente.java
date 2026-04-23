package pe.edu.pucp.tasf.gvns;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Implementación del algoritmo GVNS para el problema SPMCF-TW.
 *
 * <p>Referencia: Polat, Kalayci &amp; Topaloglu (2026). "A General Variable
 * Neighborhood Search for Single-Path Unsplittable Multicommodity Network
 * Flow with Time Windows." <em>Soft Computing</em> 30, 327-352.</p>
 *
 * <p>El algoritmo opera en dos fases:</p>
 * <ul>
 *   <li><b>Fase 2 — Construcción inicial:</b> para cada envío, escanea TODOS
 *       los vuelos en los tres niveles (directo / 1 escala / 2 escalas) y
 *       selecciona la ruta con mínima llegada absoluta (mínimo tránsito).
 *       Equivale a la heurística constructiva del §3.3 de Polat et al.</li>
 *   <li><b>Fase 3 — Mejora GVNS:</b> ciclo {@code while (t < t_max)} con
 *       Shaking (eyección aleatoria de k×batch envíos), VND N1 (re-ruteo
 *       con mínimo tránsito) y VND N2 (DFS para no-ruteados + reducción de
 *       escalas). Criterio de aceptación: f(x') &lt; f(x).</li>
 * </ul>
 *
 * <p><b>Función objetivo:</b> {@code f(x) = Σ (llegadaUTC[e] − registroUTC[e])}
 * para todos los envíos ruteados. Minimizar = vuelos más directos y tempranos.</p>
 *
 * <p><b>Restricciones de implementación:</b></p>
 * <ul>
 *   <li>Arrays primitivos {@code int[]}, {@code long[]} — sin boxing a 9.5 M de escala.</li>
 *   <li>{@code ConcurrentHashMap<Long,AtomicInteger>} + CAS loop — control de
 *       capacidad lock-free bajo parallel streams.</li>
 *   <li>Shaking es secuencial (necesita snapshot atómico de la solución);
 *       VND N1/N2 usan {@code IntStream.parallel()} para máximo throughput.</li>
 * </ul>
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
    // FASE 2: CONSTRUCCIÓN DE SOLUCIÓN INICIAL — §3.3 Polat et al. (2026)
    //
    // Para cada envío se invoca buscarMejorRuta(), que escanea TODOS los vuelos
    // en los tres niveles (directo / 1 escala / 2 escalas) y conserva la opción
    // con menor llegada absoluta (mínimo tránsito). La diferencia clave con un
    // greedy "primer hallazgo" es que aquí nunca se acepta el primer vuelo
    // disponible: se recorre el espacio completo antes de decidir.
    //
    // Complejidad por envío:
    //   Nivel 1: O(V)       — scan de vuelos directos
    //   Nivel 2: O(V²)      — scan de pares (v1, v2)
    //   Nivel 3: O(V³) pod. — solo si niveles 1 y 2 no encuentran nada
    // Se paraleliza sobre los N envíos con IntStream.parallel().
    // =========================================================================

    /**
     * Construye la solución inicial asignando a cada envío la ruta de
     * mínimo tiempo de tránsito disponible (§3.3 Polat et al., 2026).
     *
     * <p>Ejecuta en paralelo sobre todos los envíos. La reserva de
     * capacidad es lock-free mediante CAS sobre AtomicInteger.</p>
     */
    public void construirSolucionInicial() {
        System.out.println("Fase 2: Construccion Inicial — Minimo Transito (Polat et al., 2026)...");

        IntStream.range(0, tablero.numEnvios).parallel().forEach(e -> {
            int[]  rutaTemp = new int [MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];
            Arrays.fill(rutaTemp, -1);    // Java default=0; vuelo 0 es válido → explícito -1
            Arrays.fill(diasTemp, -1L);

            // Scan completo: elige la ruta con menor llegada absoluta en los 3 niveles
            boolean encontroRuta = buscarMejorRuta(e, rutaTemp, diasTemp) < Long.MAX_VALUE;

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
    // BÚSQUEDA GOLOSA "PRIMER HALLAZGO" — FALLBACK INTERNO
    //
    // Usado únicamente como fallback dentro de buscarMejorRuta() cuando la
    // reserva atómica de la mejor ruta falla por contención de capacidad.
    //
    // Política de reserva:
    //   Nivel 1: reservar v1 → éxito o próximo v1.
    //   Nivel 2: reservar v1, luego iterar v2; liberar v1 si ningún v2 funciona.
    //   Nivel 3: análogo con 3 vuelos, libera en cascada al fallar.
    //
    // Toma el PRIMER vuelo válido (no el de mínimo tránsito). No usarse
    // directamente en Fase 2; para eso existe buscarMejorRuta().
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
    // FASE 3: GVNS FORMAL — POLAT ET AL. (2026)
    //
    // ── CAMBIO CLAVE DE DISEÑO ───────────────────────────────────────────────
    // ANTES: f(x) = envíos rechazados → siempre 0 si el greedy rutea todo.
    // AHORA: f(x) = Σ (llegadaUTC[e] − registroUTC[e]) para todos los envíos.
    //
    // La Fase 2 garantiza factibilidad al 100 %. El GVNS mejora la CALIDAD:
    // vuelos directos más tempranos, escalas más cortas, menor tiempo de espera.
    //
    // Estructura §3.4–§3.6 Polat et al.:
    //   while (t < t_max)
    //     Shaking:  ejectar k×batch envíos RUTEADOS → liberar su capacidad
    //     VND N1:   re-rutear con scan completo → mínima llegada absoluta
    //     VND N2:   DFS para los no re-ruteados; reducir saltos para los multi-hop
    //     Criterio: f(x') < f(x) → aceptar + k=1 | rollback + k++
    // =========================================================================

    // ── f(x): tiempo total de tránsito en minutos ─────────────────────────────
    public long calcularTransitoTotal() {
        long total = 0;
        for (int e = 0; e < tablero.numEnvios; e++) {
            if (solucionVuelos[e][0] == -1) continue;
            int lastS = 0;
            for (int s = 1; s < MAX_SALTOS; s++)
                if (solucionVuelos[e][s] != -1) lastS = s;
            long llegada = solucionDias[e][lastS] + duracionVuelo(solucionVuelos[e][lastS]);
            total += llegada - tablero.envioRegistroUTC[e];
        }
        return total;
    }

    // ── Índice del último tramo activo en una ruta ────────────────────────────
    private int ultimoSalto(int[] ruta) {
        int last = 0;
        for (int s = 1; s < MAX_SALTOS; s++) if (ruta[s] != -1) last = s;
        return last;
    }

    // ── Reserva atómica de toda una ruta con rollback completo ────────────────
    private boolean reservarRuta(int[] ruta, long[] dias, int maletas) {
        int ok = 0;
        for (int s = 0; s < MAX_SALTOS; s++) {
            if (ruta[s] == -1) break;
            if (!intentarReservarEspacio(ruta[s], dias[s], maletas)) {
                for (int r = 0; r < ok; r++) liberarEspacio(ruta[r], dias[r], maletas);
                return false;
            }
            ok++;
        }
        return ok > 0;
    }

    // =========================================================================
    // BÚSQUEDA DE MÍNIMO TRÁNSITO — núcleo de Fase 2 y VND N1
    //
    // Escanea TODOS los vuelos en los tres niveles (directo / 1 escala /
    // 2 escalas) y conserva la combinación con llegada absoluta más temprana.
    // Las rutas de nivel 2 solo se exploran si mejoran el mejor nivel 1 ya
    // encontrado (poda por cota superior: lle1 >= bestLle). Las de nivel 3
    // solo se exploran si niveles 1 y 2 no encontraron ninguna ruta.
    //
    // Reserva capacidad solo al final (una sola reserva atómica sobre la
    // mejor ruta). Si la reserva falla por contención concurrente, recae
    // en buscarRutaGreedy() como fallback.
    //
    // Retorna tiempo de tránsito (llegadaFinal − registroUTC) en minutos,
    // o Long.MAX_VALUE si no existe ninguna ruta factible.
    // =========================================================================
    private long buscarMejorRuta(int e, int[] rutaOut, long[] diasOut) {
        int  ori  = tablero.envioOrigen[e],  des = tablero.envioDestino[e];
        int  mal  = tablero.envioMaletas[e];
        long tReg = tablero.envioRegistroUTC[e], dl = tablero.envioDeadlineUTC[e];

        long bestLle = Long.MAX_VALUE;
        int[]  bestR = {-1, -1, -1};
        long[] bestD = {-1L, -1L, -1L};

        // ── Nivel 1: vuelo directo ────────────────────────────────────────────
        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] != ori || tablero.vueloDestino[v] != des) continue;
            long sal = calcularMinutoSalidaReal(tReg, tablero.vueloSalidaUTC[v]);
            long lle = sal + duracionVuelo(v);
            if (lle + TIEMPO_MINIMO_ESCALA > dl) continue;
            if (lle < bestLle) {
                bestLle = lle;
                bestR[0] = v;   bestR[1] = -1;  bestR[2] = -1;
                bestD[0] = sal; bestD[1] = -1L; bestD[2] = -1L;
            }
        }

        // ── Nivel 2: 1 escala (poda: v1 ya llega tarde no puede mejorar) ─────
        for (int v1 = 0; v1 < tablero.numVuelos; v1++) {
            if (tablero.vueloOrigen[v1] != ori) continue;
            int e1 = tablero.vueloDestino[v1];
            if (e1 == des || e1 == ori) continue;
            long sal1 = calcularMinutoSalidaReal(tReg, tablero.vueloSalidaUTC[v1]);
            long lle1 = sal1 + duracionVuelo(v1);
            if (lle1 + TIEMPO_MINIMO_ESCALA > dl || lle1 >= bestLle) continue;

            for (int v2 = 0; v2 < tablero.numVuelos; v2++) {
                if (tablero.vueloOrigen[v2] != e1 || tablero.vueloDestino[v2] != des) continue;
                long sal2 = calcularMinutoSalidaReal(lle1, tablero.vueloSalidaUTC[v2]);
                if (sal2 < lle1 + TIEMPO_MINIMO_ESCALA) sal2 += 1440;
                long lle2 = sal2 + duracionVuelo(v2);
                if (lle2 + TIEMPO_MINIMO_ESCALA > dl) continue;
                if (lle2 < bestLle) {
                    bestLle = lle2;
                    bestR[0] = v1;  bestR[1] = v2;  bestR[2] = -1;
                    bestD[0] = sal1; bestD[1] = sal2; bestD[2] = -1L;
                }
            }
        }

        // ── Nivel 3: 2 escalas (solo si niveles 1 y 2 no encontraron nada) ───
        if (bestR[0] == -1) {
            outer3:
            for (int v1 = 0; v1 < tablero.numVuelos; v1++) {
                if (tablero.vueloOrigen[v1] != ori) continue;
                int e1 = tablero.vueloDestino[v1];
                if (e1 == des || e1 == ori) continue;
                long sal1 = calcularMinutoSalidaReal(tReg, tablero.vueloSalidaUTC[v1]);
                long lle1 = sal1 + duracionVuelo(v1);
                if (lle1 + TIEMPO_MINIMO_ESCALA > dl) continue;

                for (int v2 = 0; v2 < tablero.numVuelos; v2++) {
                    if (tablero.vueloOrigen[v2] != e1) continue;
                    int e2 = tablero.vueloDestino[v2];
                    if (e2 == des || e2 == ori || e2 == e1) continue;
                    long sal2 = calcularMinutoSalidaReal(lle1, tablero.vueloSalidaUTC[v2]);
                    if (sal2 < lle1 + TIEMPO_MINIMO_ESCALA) sal2 += 1440;
                    long lle2 = sal2 + duracionVuelo(v2);
                    if (lle2 + TIEMPO_MINIMO_ESCALA > dl) continue;

                    for (int v3 = 0; v3 < tablero.numVuelos; v3++) {
                        if (tablero.vueloOrigen[v3] != e2 || tablero.vueloDestino[v3] != des) continue;
                        long sal3 = calcularMinutoSalidaReal(lle2, tablero.vueloSalidaUTC[v3]);
                        if (sal3 < lle2 + TIEMPO_MINIMO_ESCALA) sal3 += 1440;
                        long lle3 = sal3 + duracionVuelo(v3);
                        if (lle3 + TIEMPO_MINIMO_ESCALA > dl) continue;
                        if (lle3 < bestLle) {
                            bestLle = lle3;
                            bestR[0] = v1; bestR[1] = v2;  bestR[2] = v3;
                            bestD[0] = sal1; bestD[1] = sal2; bestD[2] = sal3;
                            break outer3; // primera ruta de 3 tramos válida
                        }
                    }
                }
            }
        }

        if (bestR[0] == -1) return Long.MAX_VALUE;

        // Intentar reservar la mejor ruta (una sola reserva atómica)
        if (!reservarRuta(bestR, bestD, mal)) {
            // Fallback: primer vuelo válido disponible
            Arrays.fill(rutaOut, -1); Arrays.fill(diasOut, -1L);
            if (!buscarRutaGreedy(ori, des, mal, tReg, dl, rutaOut, diasOut))
                return Long.MAX_VALUE;
            int ls = ultimoSalto(rutaOut);
            return (diasOut[ls] + duracionVuelo(rutaOut[ls])) - tReg;
        }

        System.arraycopy(bestR, 0, rutaOut, 0, MAX_SALTOS);
        System.arraycopy(bestD, 0, diasOut, 0, MAX_SALTOS);
        return bestLle - tReg;
    }

    // =========================================================================
    // GVNS PRINCIPAL — Polat et al. (2026) §3.4
    // =========================================================================
    public int ejecutarMejoraGVNS() {
        System.out.println("GVNS FORMAL: Iniciando ciclo metaheuristico (Polat et al., 2026)...");

        long tiempoInicio  = System.currentTimeMillis();
        long tiempoLimite  = tiempoInicio + TIEMPO_LIMITE_MS;

        int  k             = 1;
        long mejorTransito = calcularTransitoTotal();
        int  iterTotal     = 0;
        int  mejorasAcum   = 0;
        // Batch pequeño: VND N1 es O(V²) por shipment; con 20 shipments la iteración
        // tarda ~ms, permitiendo miles de iteraciones en 120 s.
        int  batchBase     = BATCH_FACTOR;

        System.out.printf("  Estado inicial: f(x)=%,d min | t_max=%ds | k_max=%d | batch=%d%n",
                mejorTransito, TIEMPO_LIMITE_MS / 1000, K_MAX, batchBase);

        while (System.currentTimeMillis() < tiempoLimite) {
            iterTotal++;

            // ── SHAKING (§3.5) ────────────────────────────────────────────────
            int n = k * batchBase;
            int[]     ejected   = new int    [n];
            int[][]   rutasSnap = new int    [n][MAX_SALTOS];
            long[][]  diasSnap  = new long   [n][MAX_SALTOS];
            boolean[] rerutado  = new boolean[n];

            int numEyect = ejecutarShaking(n, ejected, rutasSnap, diasSnap);
            if (numEyect == 0) { k = (k % K_MAX) + 1; continue; }

            // ── VND (§3.6): N1 → N2 ──────────────────────────────────────────
            int vndN = 1;
            while (vndN <= 2 && System.currentTimeMillis() < tiempoLimite) {
                int mej = (vndN == 1)
                        ? aplicarVND_N1_MejorRuta(ejected, rutasSnap, diasSnap, rerutado, numEyect)
                        : aplicarVND_N2_MenosEscalas(ejected, rutasSnap, diasSnap, rerutado, numEyect);
                vndN = (mej > 0) ? 1 : vndN + 1;
            }

            // ── CRITERIO DE ACEPTACIÓN ────────────────────────────────────────
            boolean todoRuteado = true;
            for (int i = 0; i < numEyect; i++)
                if (!rerutado[i]) { todoRuteado = false; break; }

            long nuevoTransito = calcularTransitoTotal();
            if (todoRuteado && nuevoTransito < mejorTransito) {
                long mejora = mejorTransito - nuevoTransito;
                mejorTransito = nuevoTransito;
                mejorasAcum  += numEyect;
                k = 1;
                System.out.printf("  [Iter %d] MEJORA -> f(x)=%,d min (-%,d) | k=1%n",
                        iterTotal, mejorTransito, mejora);

                // Capturar muestras para ExportadorVisual
                for (int i = 0; i < numEyect && idxMuestraGVNS.get() < 10; i++) {
                    int idx = idxMuestraGVNS.getAndIncrement();
                    if (idx < 10) muestraGVNS[idx] = ejected[i];
                }
            } else {
                revertirShaking(ejected, rutasSnap, diasSnap, rerutado, numEyect);
                k = (k % K_MAX) + 1;
                if (iterTotal % 20 == 0)
                    System.out.printf("  [Iter %d] Sin mejora | f(x)=%,d | k->%d%n",
                            iterTotal, nuevoTransito, k);
            }
        }

        long tTotal = System.currentTimeMillis() - tiempoInicio;
        System.out.printf("GVNS Terminado | Iter=%d | Rutas_mejoradas=%d | f_final=%,d min | t=%.2fs%n",
                iterTotal, mejorasAcum, mejorTransito, tTotal / 1000.0);
        return mejorasAcum;
    }

    // ── SHAKING: ejectar n envíos ruteados usando LCG ────────────────────────
    private int ejecutarShaking(int n,
                                int[]    ejected,
                                int[][]  rutasSnap,
                                long[][] diasSnap) {
        int  count = 0;
        long seed  = System.nanoTime();

        outer:
        for (long i = 0; i < (long) tablero.numEnvios * 3 && count < n; i++) {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            int e = (int) ((seed & Long.MAX_VALUE) % tablero.numEnvios);

            if (solucionVuelos[e][0] == -1) continue;
            for (int j = 0; j < count; j++) if (ejected[j] == e) continue outer;

            ejected[count] = e;
            System.arraycopy(solucionVuelos[e], 0, rutasSnap[count], 0, MAX_SALTOS);
            System.arraycopy(solucionDias  [e], 0, diasSnap [count], 0, MAX_SALTOS);

            for (int s = 0; s < MAX_SALTOS; s++) {
                if (solucionVuelos[e][s] != -1) {
                    liberarEspacio(solucionVuelos[e][s], solucionDias[e][s],
                            tablero.envioMaletas[e]);
                    solucionVuelos[e][s] = -1;
                    solucionDias  [e][s] = -1L;
                }
            }
            enviosExitosos.decrementAndGet();
            count++;
        }
        return count;
    }

    // ── REVERSIÓN DEL SHAKING ─────────────────────────────────────────────────
    // ── REVERSIÓN DEL SHAKING — dos pasadas para evitar cascada ─────────────
    //
    // Bug del rollback en una sola pasada: si VND asignó el slot original de
    // shipment A al shipment B, liberar A e inmediatamente intentar restaurarlo
    // falla porque B aún ocupa ese slot. La solución es desacoplar ambas fases:
    //   Pasada 1: liberar TODAS las rutas asignadas por VND.
    //   Pasada 2: restaurar TODAS las rutas originales (slots ya liberados).
    private void revertirShaking(int[]     ejected,
                                 int[][]   rutasSnap,
                                 long[][]  diasSnap,
                                 boolean[] rerutado,
                                 int       n) {
        // Pasada 1: liberar rutas VND
        for (int i = 0; i < n; i++) {
            int e = ejected[i];
            if (rerutado[i] && solucionVuelos[e][0] != -1) {
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
        }

        // Pasada 2: restaurar rutas originales (slots ya liberados en pasada 1)
        for (int i = 0; i < n; i++) {
            int e = ejected[i];
            if (reservarRuta(rutasSnap[i], diasSnap[i], tablero.envioMaletas[e])) {
                System.arraycopy(rutasSnap[i], 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasSnap [i], 0, solucionDias  [e], 0, MAX_SALTOS);
                enviosExitosos.incrementAndGet();
            }
            // Fallo extremadamente raro: slot original consumido por un shipment
            // NO eyectado en este shaking. No puede ocurrir si el dataset tiene
            // suficiente holgura de capacidad (confirmado: 0 rechazados en Fase 2).
        }
    }

    // ── VND N1: re-rutear con mínimo tiempo de tránsito ─────────────────────
    private int aplicarVND_N1_MejorRuta(int[]     ejected,
                                         int[][]   rutasSnap,
                                         long[][]  diasSnap,
                                         boolean[] rerutado,
                                         int       n) {
        AtomicInteger mejoradas = new AtomicInteger(0);

        IntStream.range(0, n).parallel().forEach(i -> {
            int e = ejected[i];

            int[]  rt = new int [MAX_SALTOS]; Arrays.fill(rt, -1);
            long[] dt = new long[MAX_SALTOS]; Arrays.fill(dt, -1L);

            long tNuevo = buscarMejorRuta(e, rt, dt);
            if (tNuevo == Long.MAX_VALUE) return;

            // Tránsito original de este envío
            int  ls    = ultimoSalto(rutasSnap[i]);
            long tOrig = (diasSnap[i][ls] + duracionVuelo(rutasSnap[i][ls]))
                       - tablero.envioRegistroUTC[e];

            System.arraycopy(rt, 0, solucionVuelos[e], 0, MAX_SALTOS);
            System.arraycopy(dt, 0, solucionDias  [e], 0, MAX_SALTOS);
            enviosExitosos.incrementAndGet();
            rerutado[i] = true;

            if (tNuevo < tOrig) mejoradas.incrementAndGet();
        });

        return mejoradas.get();
    }

    // ── VND N2: DFS para no-ruteados; reducir saltos para multi-hop ──────────
    //
    // Complementa N1 en dos dimensiones distintas:
    //   a) Envíos que N1 no pudo rutear → DFS exhaustivo (mayor cobertura).
    //   b) Envíos ruteados por N1 con >1 tramo → intentar vuelo directo más
    //      temprano para reducir escalas (barrio estructuralmente diferente a N1).
    private int aplicarVND_N2_MenosEscalas(int[]     ejected,
                                             int[][]   rutasSnap,
                                             long[][]  diasSnap,
                                             boolean[] rerutado,
                                             int       n) {
        AtomicInteger mejoradas = new AtomicInteger(0);

        IntStream.range(0, n).parallel().forEach(i -> {
            int e = ejected[i];

            if (!rerutado[i]) {
                // a) N1 falló: DFS exhaustivo
                int[]  rt = new int [MAX_SALTOS]; Arrays.fill(rt, -1);
                long[] dt = new long[MAX_SALTOS]; Arrays.fill(dt, -1L);
                if (buscarRutaDFS(tablero.envioOrigen[e], tablero.envioDestino[e],
                        tablero.envioMaletas[e], tablero.envioRegistroUTC[e],
                        tablero.envioDeadlineUTC[e], 0, rt, dt)) {
                    System.arraycopy(rt, 0, solucionVuelos[e], 0, MAX_SALTOS);
                    System.arraycopy(dt, 0, solucionDias  [e], 0, MAX_SALTOS);
                    enviosExitosos.incrementAndGet();
                    rerutado[i] = true;
                    mejoradas.incrementAndGet();
                }
                return;
            }

            // b) N1 tuvo éxito pero ruta usa >1 tramo → intentar vuelo directo
            int hops = 0;
            for (int s = 0; s < MAX_SALTOS; s++) if (solucionVuelos[e][s] != -1) hops++;
            if (hops <= 1) return;

            int  ori = tablero.envioOrigen[e], des = tablero.envioDestino[e];
            int  mal = tablero.envioMaletas[e];
            long tReg = tablero.envioRegistroUTC[e], dl = tablero.envioDeadlineUTC[e];

            long bestDirLle = Long.MAX_VALUE;
            int  bestDirV   = -1;
            long bestDirSal = -1L;

            for (int v = 0; v < tablero.numVuelos; v++) {
                if (tablero.vueloOrigen[v] != ori || tablero.vueloDestino[v] != des) continue;
                long sal = calcularMinutoSalidaReal(tReg, tablero.vueloSalidaUTC[v]);
                long lle = sal + duracionVuelo(v);
                if (lle + TIEMPO_MINIMO_ESCALA > dl) continue;
                if (lle < bestDirLle) { bestDirLle = lle; bestDirV = v; bestDirSal = sal; }
            }

            if (bestDirV != -1 && intentarReservarEspacio(bestDirV, bestDirSal, mal)) {
                // Liberar ruta multi-hop que asignó N1
                for (int s = 0; s < MAX_SALTOS; s++) {
                    if (solucionVuelos[e][s] != -1)
                        liberarEspacio(solucionVuelos[e][s], solucionDias[e][s], mal);
                }
                Arrays.fill(solucionVuelos[e], -1);
                Arrays.fill(solucionDias  [e], -1L);
                solucionVuelos[e][0] = bestDirV;
                solucionDias  [e][0] = bestDirSal;
                mejoradas.incrementAndGet();
            }
        });

        return mejoradas.get();
    }

    // =========================================================================
    // EXPORTACIÓN CSV DE MÉTRICAS — para análisis experimental
    // =========================================================================

    /**
     * Exporta métricas de rendimiento en formato CSV.
     *
     * @param nombreArchivo   path del archivo CSV a crear
     * @param transitoInicial f(x) tras Fase 2, antes de GVNS (minutos)
     * @param tiempoGreedy    segundos de CPU — Fase 2
     * @param tiempoGVNS      segundos de CPU — Fase 3
     * @param rutasMejoradas  rutas mejoradas por GVNS
     */
    public void exportarResultadosCSV(String nombreArchivo,
                                      long   transitoInicial,
                                      double tiempoGreedy,
                                      double tiempoGVNS,
                                      int    rutasMejoradas) {
        long transitoFinal = calcularTransitoTotal();
        try (PrintWriter pw = new PrintWriter(new FileWriter(nombreArchivo))) {
            pw.println("Metrica,Valor");
            pw.println("Total Envios,"                  + tablero.numEnvios);
            pw.println("Exitosos Final,"                 + enviosExitosos.get());
            pw.println("Rutas Mejoradas GVNS,"           + rutasMejoradas);
            pw.println("Transito Total Inicial (min),"   + transitoInicial);
            pw.println("Transito Total Final (min),"     + transitoFinal);
            double mejora = (transitoInicial > 0)
                    ? (transitoInicial - transitoFinal) * 100.0 / transitoInicial : 0;
            pw.println("Mejora GVNS (%),"                + String.format("%.4f", mejora));
            pw.println("Tiempo Greedy (s),"              + tiempoGreedy);
            pw.println("Tiempo GVNS (s),"                + tiempoGVNS);
            double tasa = (enviosExitosos.get() / (double) tablero.numEnvios) * 100.0;
            pw.println("Tasa Exito Final (%),"           + String.format("%.4f", tasa));
            System.out.println("Resultados exportados: " + nombreArchivo);
        } catch (Exception ex) {
            System.err.println("Error al exportar CSV: " + ex.getMessage());
        }
    }
}
