package pe.edu.pucp.tasf.gvns;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Planificador GVNS Concurrente — Iteración 3
 *
 * Basado en: Polat, Kalayci &amp; Topaloglu (2026). Soft Computing 30, 327-352.
 * "An enhanced variable neighborhood search algorithm for rich multi-compartment
 * vehicle routing problems."
 *
 * Adaptación al problema de rutas de equipaje en redes de vuelos (Single-Path
 * Unsplittable Multicommodity Network Flow with Time Windows):
 *   - "Vehículo"    → Vuelo con capacidad de maletas.
 *   - "Cliente"     → Envío (origen, destino, maletas, ventana de tiempo).
 *   - "Ruta"        → Secuencia de vuelos (máximo MAX_SALTOS tramos).
 *
 * ── ESTRUCTURA DEL ALGORITMO (Polat et al. §3.2) ────────────────────────────
 *  Fase 2 — Solución inicial (§3.4):
 *    1. Permutación aleatoria de los envíos (Fisher-Yates con semilla fija).
 *    2. Para cada envío en ese orden: asignar la primera ruta factible
 *       encontrada por búsqueda greedy de 3 niveles (directo / 1 escala /
 *       2 escalas). Los no asignados van al pool de rechazados.
 *  Fase 3 — Mejora GVNS (§3.2):
 *    Loop hasta tiempo límite o f(x)=0:
 *      · Shaking:     expulsar k*batch envíos asignados → pool de rechazados.
 *      · VND N1:      intentar re-insertar rechazados vía DFS (Relocate).
 *      · VND N2:      liberar un vuelo temporalmente y re-insertar (Exchange).
 *      · Aceptación:  si mejora, k=1; si no, k=(k%K_MAX)+1 y se revierte.
 *
 * ── RESTRICCIONES ARQUITECTÓNICAS NO NEGOCIABLES ────────────────────────────
 *   - Arrays primitivos int[], long[] para datos masivos (sin ArrayList<Object>).
 *   - ConcurrentHashMap<Long, AtomicInteger> + CAS loop (lock-free, sin mutex).
 *   - Parallel streams en Fase 2 y VND; Shaking secuencial (requiere snapshot).
 */
public class PlanificadorGVNSConcurrente {

    // ── DATOS DEL PROBLEMA ───────────────────────────────────────────────────
    /** Red de vuelos y envíos cargados por GestorDatos. */
    private GestorDatos tablero;

    /**
     * solucionVuelos[e][s] = índice del vuelo en el tramo s del envío e.
     * -1 indica que el tramo no existe (envío directo usa solo s=0).
     */
    public int[][]  solucionVuelos;

    /**
     * solucionDias[e][s] = minuto UTC absoluto de salida del tramo s del envío e.
     * Permite reconstruir la cadena temporal de la ruta sin recalcular.
     */
    public long[][] solucionDias;

    // ── POOL DE RECHAZADOS ───────────────────────────────────────────────────
    /**
     * Envíos que no encontraron ruta factible en Fase 2 (o expulsados en Shaking).
     * La Fase 3 (VND N1/N2) intentará re-insertarlos.
     * Entradas marcadas -1 = ya salvados (espacios reutilizables).
     */
    public int[] enviosRechazados = new int[500_000];
    /** Número de posiciones ocupadas en enviosRechazados[] (incluye los -1). */
    public int   totalRechazados  = 0;

    // ── PARÁMETROS DE NEGOCIO ────────────────────────────────────────────────
    /** Tiempo mínimo de escala entre dos vuelos consecutivos (minutos). */
    public final int TIEMPO_MINIMO_ESCALA = 10;
    /** Máximo de tramos por ruta. 3 = directo, 1 escala o 2 escalas. */
    public final int MAX_SALTOS           = 3;

    // ── PARÁMETROS GVNS (Polat et al. 2026, Tabla 3) ─────────────────────────
    /** Tiempo de CPU máximo para la Fase 3 (ms). */
    private static final long TIEMPO_LIMITE_MS = 120_000L;
    /** Número máximo de estructuras de vecindad (k_max en el paper). */
    private static final int  K_MAX            = 3;
    /** Envíos expulsados por iteración de Shaking en k=1 (escala con k). */
    private static final int  BATCH_FACTOR     = 20;

    // ── ALEATORIEDAD — FASE 2 (Polat et al. §3.4) ────────────────────────────
    /**
     * Semilla para el generador de números aleatorios.
     * Determina el orden de procesamiento de envíos en la Fase 2.
     * Semilla fija → resultados reproducibles entre ejecuciones.
     */
    private final long   semilla;
    /**
     * Generador de números aleatorios seeded para la permutación inicial.
     * Solo se usa en construirSolucionInicial() (hilo principal, sin concurrencia).
     */
    private final Random rng;

    /**
     * Criterio de ordenamiento de envíos en la Fase 2.
     * Determina qué envíos procesan primero cuando la red tiene capacidad limitada.
     * Ver {@link CriterioOrden} para opciones disponibles.
     */
    private final CriterioOrden criterio;

    // ── SINCRONIZACIÓN ───────────────────────────────────────────────────────
    /** Mutex para escrituras en el pool de rechazados (escritura rara). */
    private final Object lockRechazados = new Object();
    /** Contador atómico de envíos con ruta asignada (Fase 2 + Fase 3). */
    public  AtomicInteger enviosExitosos = new AtomicInteger(0);
    /**
     * Mapa de ocupación de vuelos.
     * Clave: claveVueloDia(v, salidaAbsoluta) → vuelo v en el día de salidaAbsoluta.
     * Valor: maletas actualmente reservadas en ese vuelo-día.
     * Acceso lock-free mediante CAS (compareAndSet) en intentarReservarEspacio().
     */
    public  ConcurrentHashMap<Long, AtomicInteger> ocupacionVuelos = new ConcurrentHashMap<>();

    // ── CANCELACIONES EN TIEMPO REAL ─────────────────────────────────────────
    /**
     * Vuelos actualmente cancelados. Un vuelo cancelado tiene capacidad = 0
     * mientras esté en este conjunto.
     */
    private final java.util.Set<Integer>     vuelosCancelados    = new java.util.HashSet<>();
    /**
     * Capacidades originales guardadas al cancelar un vuelo.
     * Se restauran cuando se llama a restaurarVuelo() o restaurarAeropuerto().
     */
    private final java.util.Map<Integer, Integer> capacidadesGuardadas = new java.util.HashMap<>();

    // ── ÍNDICE DE ADYACENCIA ─────────────────────────────────────────────────
    /**
     * vuelosPorOrigen[a] = array de índices de vuelos que salen del aeropuerto a.
     * Construido en el constructor en O(V+A). Permite que buscarMejorRuta() itere
     * en O(grado) por nivel en lugar de O(V), reduciendo el nivel 2 de O(V²) a
     * O(grado²) ≈ 96² = 9 216 ops por envío (vs 8.2M sin índice).
     */
    private final int[][] vuelosPorOrigen;

    // ── MUESTRAS PARA ExportadorVisual ───────────────────────────────────────
    /** Primeros 100 envíos ruteados exitosamente en Fase 2 (para JSON de muestra). */
    public final int[]          muestraFase2    = new int[100];
    private final AtomicInteger idxMuestraFase2 = new AtomicInteger(0);

    /** Primeros 10 envíos salvados por la Fase 3 GVNS (para JSON de muestra). */
    public final int[]          muestraGVNS     = new int[10];
    private final AtomicInteger idxMuestraGVNS  = new AtomicInteger(0);

    // =========================================================================
    // CONSTRUCTORES
    // =========================================================================

    /**
     * Constructor con semilla y criterio por defecto.
     * Equivalente a {@code new PlanificadorGVNSConcurrente(datos, 12345L, CriterioOrden.ALEATORIO)}.
     */
    public PlanificadorGVNSConcurrente(GestorDatos datos) {
        this(datos, 12345L, CriterioOrden.ALEATORIO);
    }

    /**
     * Constructor con semilla explícita y criterio por defecto.
     *
     * @param datos   Red de vuelos y envíos ya cargada.
     * @param semilla Semilla para la permutación (solo relevante si criterio = ALEATORIO).
     */
    public PlanificadorGVNSConcurrente(GestorDatos datos, long semilla) {
        this(datos, semilla, CriterioOrden.ALEATORIO);
    }

    /**
     * Constructor principal.
     *
     * @param datos    Red de vuelos y envíos ya cargada por GestorDatos.
     * @param semilla  Semilla para la permutación aleatoria de la Fase 2 (§3.4).
     *                 Solo se usa cuando {@code criterio == CriterioOrden.ALEATORIO}.
     * @param criterio Criterio de ordenamiento de envíos en la Fase 2.
     *                 Ver {@link CriterioOrden}.
     */
    public PlanificadorGVNSConcurrente(GestorDatos datos, long semilla, CriterioOrden criterio) {
        this.tablero        = datos;
        this.semilla        = semilla;
        this.rng            = new Random(semilla);
        this.criterio       = criterio;

        // Matrices de solución: -1 = tramo sin asignar
        this.solucionVuelos = new int [datos.numEnvios][MAX_SALTOS];
        this.solucionDias   = new long[datos.numEnvios][MAX_SALTOS];

        for (int i = 0; i < datos.numEnvios; i++) {
            Arrays.fill(solucionVuelos[i], -1);
            Arrays.fill(solucionDias[i],  -1L);
        }
        Arrays.fill(enviosRechazados, -1);
        Arrays.fill(muestraFase2, -1);
        Arrays.fill(muestraGVNS,  -1);

        // Construir índice de adyacencia: vuelosPorOrigen[a] → vuelos que salen de a
        // Los IDs de aeropuerto son 1-based (1..numAeropuertos), así que el array
        // necesita tamaño numAeropuertos+1 para que el índice más alto sea válido.
        int A = datos.numAeropuertos + 1;
        int[] conteo = new int[A];
        for (int v = 0; v < datos.numVuelos; v++) conteo[datos.vueloOrigen[v]]++;
        this.vuelosPorOrigen = new int[A][];
        for (int a = 0; a < A; a++) vuelosPorOrigen[a] = new int[conteo[a]];
        int[] idx = new int[A];
        for (int v = 0; v < datos.numVuelos; v++) {
            int o = datos.vueloOrigen[v];
            vuelosPorOrigen[o][idx[o]++] = v;
        }
    }

    // =========================================================================
    // ACCESORES DE MUESTRA (para ExportadorVisual)
    // =========================================================================

    /** Número de envíos capturados en muestraFase2[] (máximo 100). */
    public int cantMuestraFase2() { return Math.min(idxMuestraFase2.get(), 100); }

    /** Número de envíos capturados en muestraGVNS[] (máximo 10). */
    public int cantMuestraGVNS()  { return Math.min(idxMuestraGVNS.get(),  10); }

    // =========================================================================
    // FASE 2: CONSTRUCCIÓN DE LA SOLUCIÓN INICIAL — Polat et al. (2026) §3.4
    //
    // El paper establece un proceso de 3 pasos:
    //   PASO 1: "the sequence of orders is established through a random generation"
    //           → Permutación aleatoria de los índices de envío (Fisher-Yates).
    //   PASO 2: "this random sequence serves as the basis for determining the
    //            specific customers to be visited"
    //           → Iterar sobre la permutación para determinar qué envíos procesar.
    //   PASO 3: "the final step involves the allocation of vehicles to designated tours"
    //           → Para cada envío en orden aleatorio: asignar la primera ruta
    //             factible hallada por búsqueda greedy de 3 niveles.
    //
    // La búsqueda greedy (buscarRutaGreedy) es equivalente al "allocation of
    // vehicles to tours": evalúa vuelo directo → 1 escala → 2 escalas y reserva
    // capacidad atómicamente (CAS). Envíos sin ruta van al pool de rechazados
    // (análogos a "placeholder vehicles incurring penalties" del paper).
    //
    // El orden aleatorio es la diferencia fundamental respecto a la versión
    // anterior (greedy secuencial 0..N-1). Con capacidad limitada, el orden de
    // procesamiento determina qué envíos "ganan" los asientos disponibles.
    // La aleatoriedad evita el sesgo sistemático por ID de envío.
    //
    // Complejidad por envío: O(V) directo / O(V²) 1 escala / O(V³) 2 escalas.
    // El DFS recursivo (exploración completa) se reserva para la Fase 3.
    // =========================================================================

    /**
     * Construye la solución inicial asignando rutas a todos los envíos.
     *
     * <p>Sigue el proceso descrito en Polat et al. (2026) §3.4:
     * <ol>
     *   <li>Genera una permutación aleatoria de los índices de envío.</li>
     *   <li>Procesa cada envío en ese orden aleatorio (en paralelo).</li>
     *   <li>Asigna la primera ruta factible (directo / 1 escala / 2 escalas).</li>
     *   <li>Envíos sin ruta quedan en el pool de rechazados para la Fase 3.</li>
     * </ol>
     */
    public void construirSolucionInicial() {
        System.out.printf("Fase 2: Construccion Inicial (criterio=%s, multihilo)...%n",
                criterio.descripcion);

        // PASO 1 (§3.4): Ordenar los envíos según el criterio configurado.
        // El orden determina qué envíos "ganan" los asientos cuando la capacidad es limitada.
        int[] permutacion = generarOrden(tablero.numEnvios);

        // PASO 2-3 (§3.4): Procesar envíos en el orden aleatorio y asignar rutas.
        // Cada hilo accede a permutacion[idx] para obtener el índice real del envío.
        // La escritura en solucionVuelos[e] y solucionDias[e] es segura porque
        // cada índice 'e' es único (no hay dos hilos escribiendo en la misma fila).
        IntStream.range(0, tablero.numEnvios).parallel().forEach(idx -> {
            int e = permutacion[idx]; // índice real del envío en orden aleatorio

            int  origen    = tablero.envioOrigen[e];
            int  destino   = tablero.envioDestino[e];
            int  maletas   = tablero.envioMaletas[e];
            long tRegistro = tablero.envioRegistroUTC[e];
            long deadline  = tablero.envioDeadlineUTC[e];

            int[]  rutaTemp = new int [MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];
            Arrays.fill(rutaTemp, -1);   // crítico: Java inicializa a 0, no a -1
            Arrays.fill(diasTemp, -1L);  // vuelo 0 existe, así que 0 ≠ "sin tramo"

            // Buscar y reservar la primera ruta factible (greedy, sin backtracking).
            boolean encontroRuta = buscarRutaGreedy(
                    origen, destino, maletas, tRegistro, deadline, rutaTemp, diasTemp);

            if (encontroRuta) {
                // Escritura segura: cada hilo escribe exclusivamente en su índice 'e'.
                System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasTemp, 0, solucionDias[e],   0, MAX_SALTOS);
                enviosExitosos.incrementAndGet();

                // Guardar en muestra para ExportadorVisual (primeros 100 exitosos).
                int mi = idxMuestraFase2.getAndIncrement();
                if (mi < 100) muestraFase2[mi] = e;

            } else {
                // Sin ruta: va al pool de rechazados (Fase 3 intentará reasignarlos).
                // "tours are temporarily assigned to placeholder vehicles,
                //  a practice that incurs penalties" — paper §3.4.
                synchronized (lockRechazados) {
                    if (totalRechazados < enviosRechazados.length)
                        enviosRechazados[totalRechazados++] = e;
                }
            }
        });

        System.out.printf("Fase 2 Terminada. Ruteados: %d / %d  |  Rechazados: %d%n",
                enviosExitosos.get(), tablero.numEnvios, totalRechazados);
    }

    /**
     * Genera una permutación aleatoria de [0, n) usando el algoritmo Fisher-Yates.
     *
     * <p>El algoritmo garantiza que todas las n! permutaciones posibles son
     * equiprobables. La semilla del campo {@code rng} hace la permutación
     * determinista: misma semilla → mismo orden.
     *
     * <p>Referencia: Polat et al. (2026) §3.4 — "the sequence of orders is
     * established through a random generation".
     *
     * @param n número de elementos (= número de envíos).
     * @return array de longitud n con los índices 0..n-1 en orden aleatorio.
     */
    private int[] generarPermutacionAleatoria(int n) {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;

        // Fisher-Yates shuffle: recorre de derecha a izquierda e intercambia
        // cada posición con una posición aleatoria anterior (o igual).
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1); // 0 <= j <= i
            int tmp = perm[i];
            perm[i] = perm[j];
            perm[j] = tmp;
        }
        return perm;
    }

    /**
     * Genera el orden de procesamiento de los {@code n} envíos según {@link #criterio}.
     *
     * <ul>
     *   <li>{@link CriterioOrden#ALEATORIO}: Fisher-Yates con semilla fija (paper §3.4).</li>
     *   <li>{@link CriterioOrden#EDF}: orden por deadline ascendente (envíos más urgentes primero).
     *       Usa boxing temporal de Integer[] — O(N log N), aceptable para una sola ejecución.</li>
     *   <li>{@link CriterioOrden#FIFO}: orden por registro UTC ascendente (el que llegó antes, sale antes).</li>
     * </ul>
     *
     * @param n número de envíos a ordenar.
     * @return array de índices en el orden de procesamiento elegido.
     */
    private int[] generarOrden(int n) {
        if (criterio == CriterioOrden.ALEATORIO) {
            return generarPermutacionAleatoria(n);
        }

        // EDF o FIFO: ordenar índices por la clave temporal correspondiente.
        // Se usa Integer[] para poder aplicar comparador de objetos; el boxing
        // ocurre solo una vez en la construcción de la solución inicial.
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        if (criterio == CriterioOrden.EDF) {
            Arrays.sort(indices, (a, b) ->
                    Long.compare(tablero.envioDeadlineUTC[a], tablero.envioDeadlineUTC[b]));
        } else { // FIFO
            Arrays.sort(indices, (a, b) ->
                    Long.compare(tablero.envioRegistroUTC[a], tablero.envioRegistroUTC[b]));
        }

        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = indices[i];
        return perm;
    }

    // =========================================================================
    // BÚSQUEDA GREEDY ITERATIVA — 3 NIVELES (FASE 2)
    //
    // Asigna al envío la primera ruta factible encontrada, evaluando vuelos
    // en el orden en que aparecen en el array tablero.vueloOrigen[].
    //
    // La "greedy" aquí significa: primer vuelo que cabe, no el más barato.
    // Esta decisión es intencional: en la Fase 2 se busca velocidad (O(V³)
    // sin backtracking). La calidad de la solución se mejora en la Fase 3.
    //
    // Política de reserva de capacidad (lock-free):
    //   Nivel 1: intentar reservar v1 → si ok, éxito; si no, probar siguiente v1.
    //   Nivel 2: reservar v1, luego iterar v2; si ningún v2 cabe → liberar v1.
    //   Nivel 3: reservar v1, reservar v2, iterar v3; libera en cascada al fallar.
    //
    // Si ningún nivel encuentra ruta → el envío queda rechazado (Fase 3).
    // =========================================================================

    /**
     * Busca y reserva la primera ruta factible para un envío.
     *
     * <p>Evalúa tres niveles en orden:
     * <ol>
     *   <li><b>Nivel 1</b> — Vuelo directo: origen → destino (1 tramo).</li>
     *   <li><b>Nivel 2</b> — 1 escala: origen → escala → destino (2 tramos).</li>
     *   <li><b>Nivel 3</b> — 2 escalas: origen → e1 → e2 → destino (3 tramos).</li>
     * </ol>
     *
     * <p>En cada nivel, la capacidad se reserva atómicamente (CAS). Si un
     * vuelo intermedio no puede ser completado, su espacio se libera antes
     * de intentar la siguiente combinación.
     *
     * @param origen    ID del aeropuerto de origen del envío.
     * @param destino   ID del aeropuerto de destino del envío.
     * @param maletas   Número de maletas del envío (demanda de capacidad).
     * @param tRegistro Minuto UTC absoluto desde el que el envío está disponible.
     * @param deadline  Minuto UTC absoluto límite de llegada al destino.
     * @param rutaOut   Array de salida: índices de vuelo por tramo (se rellena si éxito).
     * @param diasOut   Array de salida: minutos UTC absolutos de salida por tramo.
     * @return {@code true} si se encontró y reservó una ruta factible.
     */
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

    /**
     * Calcula la duración del vuelo v en minutos, corrigiendo si la llegada
     * es al día siguiente (llegada UTC &lt; salida UTC → el vuelo cruza medianoche).
     */
    private long duracionVuelo(int v) {
        long dur = tablero.vueloLlegadaUTC[v] - tablero.vueloSalidaUTC[v];
        return (dur < 0) ? dur + 1440 : dur;
    }

    // =========================================================================
    // CONTROL DE CAPACIDAD — LOCK-FREE CON CAS (Compare-And-Set)
    //
    // El mapa ocupacionVuelos asocia a cada vuelo-día el número de maletas
    // ya reservadas. Las operaciones de reserva y liberación son atómicas:
    // ningún hilo puede reservar más allá de la capacidad del vuelo.
    //
    // Se usa CAS en lugar de synchronized para evitar contención de mutex
    // en escenarios con millones de envíos procesados en paralelo.
    // =========================================================================

    /**
     * Intenta reservar {@code maletas} asientos en el vuelo {@code v} para
     * el día correspondiente a {@code salidaAbsoluta}.
     *
     * <p>Implementa un CAS loop: lee la ocupación actual, verifica que la
     * suma no supere la capacidad, e intenta escribir atómicamente. Si otro
     * hilo modifica el contador antes del CAS, reintenta.
     *
     * @return {@code true} si la reserva fue exitosa; {@code false} si no hay
     *         capacidad disponible.
     */
    private boolean intentarReservarEspacio(int v, long salidaAbsoluta, int maletas) {
        long key = claveVueloDia(v, salidaAbsoluta);
        ocupacionVuelos.putIfAbsent(key, new AtomicInteger(0));
        AtomicInteger ocu = ocupacionVuelos.get(key);
        while (true) {
            int actual = ocu.get();
            if (actual + maletas > tablero.vueloCapacidad[v]) return false;
            if (ocu.compareAndSet(actual, actual + maletas))  return true;
            // CAS falló: otro hilo modificó el contador; reintentar.
        }
    }

    /**
     * Libera {@code maletas} asientos previamente reservados en el vuelo {@code v}.
     * Se llama cuando una ruta parcial no puede completarse (rollback de capacidad).
     */
    private void liberarEspacio(int v, long salidaAbsoluta, int maletas) {
        AtomicInteger ocu = ocupacionVuelos.get(claveVueloDia(v, salidaAbsoluta));
        if (ocu != null) ocu.addAndGet(-maletas);
    }

    /**
     * Genera la clave única para identificar un vuelo en un día específico.
     *
     * <p>Fórmula: {@code v * 100_000 + diaAbsoluto}, donde diaAbsoluto =
     * salidaAbsoluta / 1440. Permite que el mismo vuelo (vuelo v) tenga
     * ocupaciones distintas en días distintos (vuelo repetido en el schedule).
     */
    private static long claveVueloDia(int v, long salidaAbsoluta) {
        return v * 100_000L + (salidaAbsoluta / 1440);
    }

    /**
     * Calcula el minuto UTC absoluto de salida del vuelo, dado el minuto actual.
     *
     * <p>Si el vuelo sale más tarde en el mismo día: usamos ese horario.
     * Si el vuelo ya pasó hoy: usamos el horario del día siguiente (+1440 min).
     * Esto modela vuelos con horario fijo que operan diariamente.
     *
     * @param minutoActualAbsoluto minuto UTC desde el que el envío está disponible.
     * @param salidaVueloUTC       minuto del día UTC en que sale el vuelo (0-1439).
     * @return minuto UTC absoluto de la próxima salida factible del vuelo.
     */
    private long calcularMinutoSalidaReal(long minutoActualAbsoluto, long salidaVueloUTC) {
        long minDelDia  = minutoActualAbsoluto % 1440;
        long diaAbs     = minutoActualAbsoluto / 1440;
        return (minDelDia <= salidaVueloUTC)
                ? diaAbs * 1440 + salidaVueloUTC
                : (diaAbs + 1) * 1440 + salidaVueloUTC;
    }

    // =========================================================================
    // MOTOR DFS — EXCLUSIVO PARA FASE 3 (VND N1/N2)
    //
    // A diferencia de buscarRutaGreedy(), este DFS explora TODAS las rutas
    // posibles con backtracking. Es más lento (O(V^MAX_SALTOS) worst-case)
    // pero encuentra rutas que el greedy pierde por no retroceder.
    // Se usa en VND N1 (Relocate) y VND N2 (Exchange) donde la calidad de la
    // exploración justifica el costo adicional por envío rechazado.
    // =========================================================================

    /**
     * Búsqueda en profundidad (DFS) con backtracking para encontrar una ruta
     * desde {@code nodoActual} hasta {@code destinoFinal}.
     *
     * <p>Reserva capacidad en cada tramo; si la rama falla, libera y retrocede.
     * Llama recursivamente incrementando {@code saltoActual} hasta alcanzar
     * el destino o agotar {@code MAX_SALTOS}.
     *
     * @param nodoActual      aeropuerto desde el que se busca el siguiente vuelo.
     * @param destinoFinal    aeropuerto objetivo.
     * @param maletas         maletas a transportar (demanda de capacidad).
     * @param tiempoActual    minuto UTC absoluto más temprano para la salida.
     * @param deadline        minuto UTC absoluto límite de llegada.
     * @param saltoActual     profundidad actual en la recursión (0-indexed).
     * @param rutaTempVuelos  array de salida para índices de vuelo.
     * @param rutaTempDias    array de salida para minutos UTC de salida.
     * @return {@code true} si se encontró una ruta factible completa.
     */
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

    // =========================================================================
    // FASE 3: GVNS — CICLO METAHEURÍSTICO (Polat et al. 2026, §3.2)
    //
    // Función objetivo: f(x) = calcularTransitoTotal() en minutos.
    //   Minimizar el tránsito total = suma de (llegada_destino - registro) de
    //   todos los envíos asignados. Menor tránsito = maletas llegan antes.
    //
    // La Fase 2 greedy asigna el PRIMER vuelo directo encontrado (no el mejor).
    // El GVNS mejora esto: expulsa envíos con peor tránsito y les busca rutas
    // con menor tiempo de espera (buscarMejorRuta vs buscarRutaGreedy).
    //
    // Estructura VNS (Polat et al. Fig. 2):
    //   k ← 1
    //   while tiempo_disponible:
    //     Shaking(k):  expulsar k*BATCH envíos de peor tránsito → pool
    //     VND N1:      re-asignar con buscarMejorRuta (mínimo tránsito)
    //     if f(x') < f(x): aceptar, k←1
    //     else:            revertir, k←(k%K_MAX)+1
    // =========================================================================

    /** Cuenta los envíos activamente rechazados (ignora posiciones -1 ya salvadas). */
    private int contarRechazadosActivos() {
        int count = 0;
        for (int i = 0; i < totalRechazados; i++)
            if (enviosRechazados[i] != -1) count++;
        return count;
    }

    /** Marca el envío {@code idEnvio} como salvado en el pool (pone -1 en su posición). */
    private void eliminarDeRechazados(int idEnvio) {
        for (int i = 0; i < totalRechazados; i++) {
            if (enviosRechazados[i] == idEnvio) { enviosRechazados[i] = -1; return; }
        }
    }

    /**
     * Compacta el pool eliminando los huecos (-1) para evitar que totalRechazados
     * crezca sin límite. Sin esto, tras ~25 000 iteraciones (500 000 / batch=20)
     * el array se llena y los nuevos expulsados por shaking quedan sin ruta ni pool,
     * lo que eventualmente lleva a f(x) = 0 aceptado como "óptimo".
     * Llamar al inicio de cada iteración GVNS.
     */
    private void compactarPool() {
        int write = 0;
        for (int read = 0; read < totalRechazados; read++) {
            if (enviosRechazados[read] != -1)
                enviosRechazados[write++] = enviosRechazados[read];
        }
        totalRechazados = write;
    }

    /**
     * Tránsito individual del envío e: llegada al destino − tiempo de registro.
     * Retorna 0 si el envío no tiene ruta asignada.
     */
    private long transitoEnvio(int e) {
        if (solucionVuelos[e][0] == -1) return 0L;
        int ultimoTramo = 0;
        for (int s = 1; s < MAX_SALTOS; s++) {
            if (solucionVuelos[e][s] == -1) break;
            ultimoTramo = s;
        }
        return solucionDias[e][ultimoTramo]
                + duracionVuelo(solucionVuelos[e][ultimoTramo])
                - tablero.envioRegistroUTC[e];
    }

    /**
     * Función objetivo combinada para el GVNS.
     *
     * <p>Combina rechazos y tránsito en una sola métrica lexicográfica:
     * <pre>
     *   f(x) = rechazados_activos × PENALIZACION + tránsito_total
     * </pre>
     * La penalización (2880 min = máximo deadline posible) garantiza que
     * cualquier solución con menos rechazos sea siempre mejor que una con más,
     * independientemente del tránsito. Sin esto, cuando la Fase 2 ruteó 0 envíos
     * el GVNS arrancaría con f=0 y rechazaría cualquier mejora (más rechazos =
     * más tránsito = f sube desde 0).
     */
    private long calcularFitnessTotal() {
        final long PENALIZACION = 2880L; // minutos del máximo deadline (distinto continente)
        return (long) contarRechazadosActivos() * PENALIZACION + calcularTransitoTotal();
    }

    /**
     * Ejecuta el ciclo de mejora GVNS con función objetivo combinada (§3.2).
     *
     * <p>f(x) = rechazados × 2880 + tránsito_total. La penalización asegura que
     * reducir rechazos siempre tiene prioridad sobre reducir tránsito, lo que
     * hace al GVNS robusto incluso cuando la Fase 2 retorna 0% de éxito.
     *
     * <p>Pseudocódigo (Polat et al. Fig. 2):
     * <pre>
     *   k ← 1
     *   while tiempo_disponible:
     *     x' ← Shaking(x, k)          // expulsar k*BATCH envíos de peor tránsito
     *     x'' ← VND_MejorTransito(x') // re-asignar con mínimo tránsito
     *     if f(x'') &lt; f(x): x ← x''; k ← 1
     *     else:              revertir;  k ← (k%K_MAX)+1
     * </pre>
     *
     * @return número de iteraciones en las que se aceptó mejora.
     */
    public int ejecutarMejoraGVNS() {
        System.out.println("GVNS: Minimizando f(x)=rechazados×2880+tránsito (Polat et al. 2026, §3.2)...");

        long tiempoInicio = System.currentTimeMillis();
        long tiempoLimite = tiempoInicio + TIEMPO_LIMITE_MS;

        int  k              = 1;
        long mejorFitness   = calcularFitnessTotal();
        int  iterMejoras    = 0;
        int  iterTotal      = 0;
        // Escala el batch al tamaño del problema: ~0.001% de envíos por iteración
        int  batchBase      = Math.max(BATCH_FACTOR, tablero.numEnvios / 100_000);

        System.out.printf("  f(x) inicial = %,d | rechazados=%d | t_max=%ds | batch=%d%n",
                mejorFitness, contarRechazadosActivos(), TIEMPO_LIMITE_MS / 1000, batchBase);

        while (System.currentTimeMillis() < tiempoLimite) {
            iterTotal++;

            // Reciclar huecos del pool para que totalRechazados no desborde los 500 000 slots
            compactarPool();

            // ── SHAKING: expulsar k*batch envíos con peor tránsito ────────────
            int      numAExpulsar = k * batchBase;
            int[]    idsExp       = new int   [numAExpulsar];
            int[][]  rutasExp     = new int   [numAExpulsar][MAX_SALTOS];
            long[][] diasExp      = new long  [numAExpulsar][MAX_SALTOS];
            int numExp = ejecutarShaking(numAExpulsar, idsExp, rutasExp, diasExp);

            // ── VND N1: re-asignar expelled con mínimo tránsito ──────────────
            aplicarVND_N1_MejorTransito();

            // Si quedaron rechazados, aplicar N2 (Exchange) también
            if (contarRechazadosActivos() > 0) aplicarVND_N2_Exchange();

            // ── CRITERIO DE ACEPTACIÓN ────────────────────────────────────────
            long nuevoFitness = calcularFitnessTotal();
            if (nuevoFitness < mejorFitness) {
                long mejora = mejorFitness - nuevoFitness;
                mejorFitness = nuevoFitness;
                iterMejoras++;
                k = 1;
                System.out.printf("  [Iter %4d] MEJORA -%,d → f(x)=%,d | rec=%d | k=1%n",
                        iterTotal, mejora, mejorFitness, contarRechazadosActivos());
            } else {
                revertirShaking(idsExp, rutasExp, diasExp, numExp);
                k = (k % K_MAX) + 1;
                if (iterTotal % 50 == 0)
                    System.out.printf("  [Iter %4d] sin mejora | f(x)=%,d | rec=%d | k=%d%n",
                            iterTotal, nuevoFitness, contarRechazadosActivos(), k);
            }
        }

        long tTotal = System.currentTimeMillis() - tiempoInicio;
        System.out.printf("GVNS Terminado | Iter=%d | MejorasAceptadas=%d | f_final=%,d min | t=%.2fs%n",
                iterTotal, iterMejoras, mejorFitness, tTotal / 1000.0);
        return iterMejoras;
    }

    // ── SHAKING (Polat et al. §3.9.2 — Perturbation) ─────────────────────────

    /**
     * Expulsa los {@code n} envíos con mayor tránsito individual de la solución
     * y los coloca en el pool de rechazados, guardando snapshot para reversión.
     *
     * <p>Estrategia de selección: muestrear {@code n × SAMPLE_FACTOR} envíos
     * aleatoriamente y quedarse con los {@code n} de mayor tránsito (peor calidad).
     * Esto focaliza el shaking en la región de la solución con más margen de mejora,
     * en lugar de perturbar envíos ya bien ruteados.
     *
     * @param n               número de envíos a expulsar.
     * @param idsExpulsados   array de salida: IDs expulsados.
     * @param rutasExpulsadas array de salida: snapshot de rutas.
     * @param diasExpulsados  array de salida: snapshot de días de salida.
     * @return número real de envíos expulsados.
     */
    private int ejecutarShaking(int n,
                                int[]    idsExpulsados,
                                int[][]  rutasExpulsadas,
                                long[][] diasExpulsados) {
        // Muestrear n*SAMPLE_FACTOR candidatos aleatorios
        final int SAMPLE_FACTOR = 50;
        int  sampleSize  = Math.min(n * SAMPLE_FACTOR, tablero.numEnvios);
        int[]  candIds   = new int [sampleSize];
        long[] candTrans = new long[sampleSize];
        int    numCand   = 0;

        long seed   = System.nanoTime();
        long stride = Math.max(1L, tablero.numEnvios / sampleSize);
        for (long i = 0; i < tablero.numEnvios && numCand < sampleSize; i += stride) {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            int e = (int)(Math.abs(seed) % tablero.numEnvios);
            if (solucionVuelos[e][0] == -1) continue;
            candIds  [numCand] = e;
            candTrans[numCand] = transitoEnvio(e);
            numCand++;
        }

        // Selection sort parcial: extraer los n de mayor tránsito (O(n × sampleSize))
        int expulsados = 0;
        for (int i = 0; i < n && i < numCand; i++) {
            int maxIdx = i;
            for (int j = i + 1; j < numCand; j++)
                if (candTrans[j] > candTrans[maxIdx]) maxIdx = j;
            // swap
            int  tmpId = candIds[i];   candIds[i]   = candIds[maxIdx];   candIds[maxIdx]   = tmpId;
            long tmpTr = candTrans[i]; candTrans[i] = candTrans[maxIdx]; candTrans[maxIdx] = tmpTr;

            int e = candIds[i];
            if (solucionVuelos[e][0] == -1) continue; // ya expulsado en esta iteración

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

    /**
     * Restaura los envíos expulsados por {@link #ejecutarShaking} a su estado
     * anterior (si la iteración GVNS no produjo mejora).
     *
     * <p>Para cada envío expulsado: intenta re-reservar su ruta original.
     * Si la re-reserva falla (otro envío tomó esa capacidad durante el VND),
     * el envío queda en el pool de rechazados (comportamiento aceptable:
     * el VND ya encontró al menos una alternativa).
     */
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
            } else {
                // La ruta original ya no tiene capacidad — re-agregar al pool para que
                // el VND lo intente en la siguiente iteración (evita que el envío quede
                // en un limbo sin ruta ni pool, lo que provoca f(x) → 0 falso).
                synchronized (lockRechazados) {
                    if (totalRechazados < enviosRechazados.length)
                        enviosRechazados[totalRechazados++] = e;
                }
            }
        }
    }

    // ── VND N1: Mejor Tránsito (Polat et al. §3.6 — Best Insert) ────────────

    /**
     * Vecindad N1 — Re-asignar con mínimo tránsito: para cada envío en el pool
     * de rechazados (expulsados por shaking o rechazados en Fase 2), busca la
     * ruta de menor tránsito usando {@link #buscarMejorRuta}.
     *
     * <p>Diferencia clave respecto a la versión anterior (DFS first-feasible):
     * {@code buscarMejorRuta} evalúa TODOS los vuelos directos disponibles y
     * asigna el de menor (llegada − registro), en lugar de tomar el primero
     * que encuentre. Esto puede reducir el tránsito aunque todos los envíos
     * ya estuvieran asignados (situación con 0 rechazados en Fase 2).
     *
     * <p>Ejecutado secuencialmente para respetar el orden de asignación de
     * capacidad (el paralelismo en shaking ya asegura diversidad suficiente).
     *
     * @return número de envíos re-asignados en esta pasada.
     */
    private int aplicarVND_N1_MejorTransito() {
        int mejorados = 0;

        for (int i = 0; i < totalRechazados; i++) {
            int e = enviosRechazados[i];
            if (e == -1) continue;

            int[]  rutaTemp = new int [MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];
            Arrays.fill(rutaTemp, -1);
            Arrays.fill(diasTemp, -1L);

            boolean encontro = buscarMejorRuta(
                    tablero.envioOrigen[e],    tablero.envioDestino[e],
                    tablero.envioMaletas[e],   tablero.envioRegistroUTC[e],
                    tablero.envioDeadlineUTC[e], rutaTemp, diasTemp);

            if (encontro) {
                System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasTemp, 0, solucionDias  [e], 0, MAX_SALTOS);
                enviosRechazados[i] = -1;
                enviosExitosos.incrementAndGet();
                mejorados++;

                int idx = idxMuestraGVNS.getAndIncrement();
                if (idx < 10) muestraGVNS[idx] = e;
            }
        }
        return mejorados;
    }

    // ── buscarMejorRuta (VND N1) ──────────────────────────────────────────────

    /**
     * Busca y reserva la ruta de <b>menor tránsito</b> para un envío.
     *
     * <p>A diferencia de {@link #buscarRutaGreedy} (que retorna al primer vuelo
     * factible encontrado), este método escanea <em>todos</em> los vuelos directos
     * disponibles y elige el que minimiza {@code llegada − tRegistro}.
     * Usa el índice {@link #vuelosPorOrigen} para iterar solo sobre vuelos del
     * aeropuerto de origen (O(grado) en lugar de O(V)).
     *
     * <p>Para los niveles 2 y 3 (escalas), delega en {@link #buscarRutaGreedy}
     * ya que la ganancia marginal de optimizarlos no justifica la complejidad
     * (99.8% de los envíos en el dataset actual son directos).
     *
     * <p>Política de reservas: cuando se encuentra un vuelo directo mejor que
     * el candidato actual, se libera la reserva anterior y se toma la nueva.
     * Así se mantiene a lo sumo una reserva activa por envío en todo momento.
     *
     * @return {@code true} si se encontró y reservó una ruta.
     */
    private boolean buscarMejorRuta(int origen, int destino, int maletas,
                                    long tRegistro, long deadline,
                                    int[] rutaOut, long[] diasOut) {
        long mejorTransito = Long.MAX_VALUE;

        // ── NIVEL 1: vuelo directo de menor tránsito ─────────────────────────
        for (int v : vuelosPorOrigen[origen]) {
            if (tablero.vueloDestino[v] != destino) continue;

            long sal = calcularMinutoSalidaReal(tRegistro, tablero.vueloSalidaUTC[v]);
            long lle = sal + duracionVuelo(v);
            if (lle + TIEMPO_MINIMO_ESCALA > deadline) continue;

            long transito = lle - tRegistro;
            if (transito >= mejorTransito) continue; // no mejora al candidato actual

            if (!intentarReservarEspacio(v, sal, maletas)) continue;

            // Liberar la reserva anterior (si existía un candidato previo)
            if (rutaOut[0] != -1) liberarEspacio(rutaOut[0], diasOut[0], maletas);

            rutaOut[0] = v;  diasOut[0] = sal;
            Arrays.fill(rutaOut, 1, MAX_SALTOS, -1);
            Arrays.fill(diasOut, 1, MAX_SALTOS, -1L);
            mejorTransito = transito;
        }
        if (mejorTransito < Long.MAX_VALUE) return true;

        // ── NIVELES 2-3: si no hay directo, delegar al greedy (first-feasible) ─
        // La optimización de tránsito en rutas con escala tiene rendimiento
        // marginal y su complejidad (O(grado²)) no justifica la implementación
        // exhaustiva para el dataset actual (99.8% de rutas son directas).
        return buscarRutaGreedy(origen, destino, maletas, tRegistro, deadline, rutaOut, diasOut);
    }

    // ── VND N2: Exchange (Polat et al. §3.7 — 1-1 Exchange adaptado) ─────────

    /**
     * Vecindad N2 — Exchange: para cada envío rechazado, busca un vuelo directo
     * que esté lleno, libera temporalmente su capacidad, intenta insertar el
     * rechazado via DFS, y restaura la capacidad original.
     *
     * <p>Modela el intercambio de Shift Operator del paper: un envío cede su
     * lugar para que otro pueda ocuparlo, ampliando el espacio de búsqueda
     * más allá de lo que N1 puede alcanzar.
     *
     * @return número de envíos salvados en esta pasada.
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
    // REPLANIFICACIÓN POR CANCELACIÓN DE VUELO
    //
    // Escenario: un vuelo es cancelado en tiempo de ejecución. Todos los envíos
    // que lo usaban deben re-rutearse evitando ese vuelo.
    //
    // Estrategia:
    //   1. Marcar el vuelo como cancelado (capacidad = 0).
    //   2. Liberar TODOS los tramos de los envíos afectados.
    //   3. Re-rutear con buscarMejorRuta (el vuelo cancelado falla en CAS porque
    //      su capacidad es 0 → cualquier reserva será rechazada).
    //   4. Los que no encuentren ruta quedan en el pool de rechazados.
    //
    // Nota: la cancelación es permanente en esta sesión (tablero.vueloCapacidad[v]=0).
    // Para cancelaciones temporales, restaurar la capacidad original tras replanificar.
    // =========================================================================

    /**
     * Replanifica todos los envíos que usaban el vuelo {@code vueloId} por una
     * cancelación, buscando rutas alternativas de menor tránsito.
     *
     * <p>Uso típico (simulación de cancelación):
     * <pre>
     *   int salvados = plan.replanificarVueloCancelado(42);
     *   System.out.printf("Re-ruteados: %d%n", salvados);
     * </pre>
     *
     * @param vueloId índice del vuelo cancelado en tablero.vueloOrigen[].
     * @return número de envíos afectados que encontraron ruta alternativa.
     */
    public int replanificarVueloCancelado(int vueloId) {
        if (vueloId < 0 || vueloId >= tablero.numVuelos) {
            System.err.println("replanificarVueloCancelado: vueloId inválido = " + vueloId);
            return 0;
        }

        System.out.printf("%nREPLANIFICACIÓN: vuelo %d (%s→%s) cancelado.%n",
                vueloId,
                tablero.iataAeropuerto[tablero.vueloOrigen[vueloId]],
                tablero.iataAeropuerto[tablero.vueloDestino[vueloId]]);

        // Paso 1: marcar el vuelo como inoperable (capacidad = 0).
        // intentarReservarEspacio() falla para cualquier maleta cuando cap=0.
        int capacidadOriginal = tablero.vueloCapacidad[vueloId];
        tablero.vueloCapacidad[vueloId] = 0;

        // Paso 2: identificar y liberar todos los envíos que usan este vuelo
        int afectados = 0;
        for (int e = 0; e < tablero.numEnvios; e++) {
            boolean usaVuelo = false;
            for (int s = 0; s < MAX_SALTOS; s++) {
                if (solucionVuelos[e][s] == vueloId) { usaVuelo = true; break; }
            }
            if (!usaVuelo) continue;

            // Liberar toda la ruta del envío afectado
            for (int s = 0; s < MAX_SALTOS; s++) {
                if (solucionVuelos[e][s] != -1) {
                    // No liberamos el vuelo cancelado (su ocupación ya no importa)
                    if (solucionVuelos[e][s] != vueloId)
                        liberarEspacio(solucionVuelos[e][s], solucionDias[e][s], tablero.envioMaletas[e]);
                    solucionVuelos[e][s] = -1;
                    solucionDias  [e][s] = -1L;
                }
            }
            enviosExitosos.decrementAndGet();
            synchronized (lockRechazados) {
                if (totalRechazados < enviosRechazados.length)
                    enviosRechazados[totalRechazados++] = e;
            }
            afectados++;
        }
        System.out.printf("  Afectados: %d envíos | Buscando rutas alternativas...%n", afectados);

        // Paso 3: re-rutear con buscarMejorRuta (el vuelo cancelado falla CAS)
        int reruteados = 0;
        for (int i = 0; i < totalRechazados; i++) {
            int e = enviosRechazados[i];
            if (e == -1) continue;

            int[]  rutaTemp = new int [MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];
            Arrays.fill(rutaTemp, -1);
            Arrays.fill(diasTemp, -1L);

            boolean encontro = buscarMejorRuta(
                    tablero.envioOrigen[e],    tablero.envioDestino[e],
                    tablero.envioMaletas[e],   tablero.envioRegistroUTC[e],
                    tablero.envioDeadlineUTC[e], rutaTemp, diasTemp);

            if (encontro) {
                System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasTemp, 0, solucionDias  [e], 0, MAX_SALTOS);
                enviosRechazados[i] = -1;
                enviosExitosos.incrementAndGet();
                reruteados++;
            }
        }

        int sinRuta = afectados - reruteados;
        System.out.printf("  Re-ruteados: %d / %d | Sin ruta alternativa: %d%n",
                reruteados, afectados, sinRuta);

        if (sinRuta > 0) {
            System.out.printf("  [INFO] %d envíos irrecuperables quedan en pool.%n"
                    + "  Ejecuta ejecutarMejoraGVNS() para intentar recuperarlos.%n", sinRuta);
        }

        // Para reactivar el vuelo si la cancelación fue temporal, descomentar:
        // tablero.vueloCapacidad[vueloId] = capacidadOriginal;

        return reruteados;
    }

    // =========================================================================
    // CANCELACIONES EN TIEMPO REAL (API WEB)
    //
    // La interfaz web permite cancelar un vuelo o un aeropuerto completo.
    // Cada cancelación:
    //   1. Guarda la capacidad original del vuelo afectado.
    //   2. Pone su capacidad a 0 (bloqueo CAS automático en buscarMejorRuta).
    //   3. Libera los envíos que lo usaban y los re-rutea de inmediato.
    //   4. Los que no encuentren alternativa quedan en el pool para el GVNS.
    //
    // La restauración revierte el paso 1-2 y re-intenta enrutar el pool.
    //
    // Diseño: los métodos privados liberarEnviosDeVuelo() e intentarRerutearPool()
    // son los bloques de construcción compartidos entre cancelarVuelo,
    // cancelarAeropuerto y replanificarVueloCancelado().
    // =========================================================================

    // ── Helpers internos ──────────────────────────────────────────────────────

    /**
     * Libera del plan todos los envíos que usan el vuelo {@code vueloId},
     * añadiéndolos al pool de rechazados para re-ruteo posterior.
     *
     * @return número de envíos liberados.
     */
    private int liberarEnviosDeVuelo(int vueloId) {
        int afectados = 0;
        for (int e = 0; e < tablero.numEnvios; e++) {
            boolean usa = false;
            for (int s = 0; s < MAX_SALTOS; s++) {
                if (solucionVuelos[e][s] == vueloId) { usa = true; break; }
            }
            if (!usa) continue;

            for (int s = 0; s < MAX_SALTOS; s++) {
                if (solucionVuelos[e][s] != -1) {
                    if (solucionVuelos[e][s] != vueloId)
                        liberarEspacio(solucionVuelos[e][s], solucionDias[e][s],
                                tablero.envioMaletas[e]);
                    solucionVuelos[e][s] = -1;
                    solucionDias  [e][s] = -1L;
                }
            }
            enviosExitosos.decrementAndGet();
            synchronized (lockRechazados) {
                if (totalRechazados < enviosRechazados.length)
                    enviosRechazados[totalRechazados++] = e;
            }
            afectados++;
        }
        return afectados;
    }

    /**
     * Intenta re-rutear todos los envíos activos del pool de rechazados usando
     * {@link #buscarMejorRuta}. Llama a {@link #compactarPool()} antes de iterar
     * para garantizar que el array no desborde.
     *
     * @return número de envíos que encontraron ruta.
     */
    private int intentarRerutearPool() {
        compactarPool();
        int reruteados = 0;
        for (int i = 0; i < totalRechazados; i++) {
            int e = enviosRechazados[i];
            if (e == -1) continue;

            int[]  rutaTemp = new int [MAX_SALTOS];
            long[] diasTemp = new long[MAX_SALTOS];
            Arrays.fill(rutaTemp, -1);
            Arrays.fill(diasTemp, -1L);

            boolean encontro = buscarMejorRuta(
                    tablero.envioOrigen[e],     tablero.envioDestino[e],
                    tablero.envioMaletas[e],    tablero.envioRegistroUTC[e],
                    tablero.envioDeadlineUTC[e], rutaTemp, diasTemp);

            if (encontro) {
                System.arraycopy(rutaTemp, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(diasTemp, 0, solucionDias  [e], 0, MAX_SALTOS);
                enviosRechazados[i] = -1;
                enviosExitosos.incrementAndGet();
                reruteados++;
            }
        }
        return reruteados;
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Cancela el vuelo {@code vueloId} en tiempo de ejecución.
     *
     * <p>Pasos:
     * <ol>
     *   <li>Guarda la capacidad original.</li>
     *   <li>Pone su capacidad a 0 (nuevas reservas fallan automáticamente).</li>
     *   <li>Libera todos los envíos que lo usaban.</li>
     *   <li>Re-rutea el pool por rutas alternativas.</li>
     * </ol>
     *
     * <p>Si el vuelo ya estaba cancelado, retorna un resultado vacío (no-op).
     *
     * @param vueloId índice del vuelo a cancelar.
     * @return resumen de afectados, re-ruteados y sin ruta alternativa.
     * @throws IllegalArgumentException si {@code vueloId} está fuera de rango.
     */
    public ResultadoCancelacion cancelarVuelo(int vueloId) {
        if (vueloId < 0 || vueloId >= tablero.numVuelos)
            throw new IllegalArgumentException("vueloId inválido: " + vueloId);

        if (vuelosCancelados.contains(vueloId))
            return new ResultadoCancelacion(0, 0, 0);

        capacidadesGuardadas.put(vueloId, tablero.vueloCapacidad[vueloId]);
        tablero.vueloCapacidad[vueloId] = 0;
        vuelosCancelados.add(vueloId);

        System.out.printf("CANCELACIÓN vuelo %d (%s→%s)%n", vueloId,
                tablero.iataAeropuerto[tablero.vueloOrigen[vueloId]],
                tablero.iataAeropuerto[tablero.vueloDestino[vueloId]]);

        int afectados  = liberarEnviosDeVuelo(vueloId);
        int reruteados = intentarRerutearPool();

        ResultadoCancelacion r = new ResultadoCancelacion(afectados, reruteados,
                afectados - reruteados);
        System.out.printf("  → %s%n", r);
        return r;
    }

    /**
     * Restaura el vuelo {@code vueloId} previamente cancelado.
     *
     * <p>Devuelve su capacidad original e intenta re-rutear los envíos que aún
     * están en el pool (los que no encontraron alternativa al cancelar).
     *
     * <p>Si el vuelo no estaba cancelado, retorna un resultado vacío (no-op).
     *
     * @param vueloId índice del vuelo a restaurar.
     * @return resumen de envíos re-ruteados gracias a la restauración.
     */
    public ResultadoCancelacion restaurarVuelo(int vueloId) {
        if (!vuelosCancelados.contains(vueloId))
            return new ResultadoCancelacion(0, 0, 0);

        tablero.vueloCapacidad[vueloId] = capacidadesGuardadas.remove(vueloId);
        vuelosCancelados.remove(vueloId);

        System.out.printf("RESTAURACIÓN vuelo %d (%s→%s) cap=%d%n", vueloId,
                tablero.iataAeropuerto[tablero.vueloOrigen[vueloId]],
                tablero.iataAeropuerto[tablero.vueloDestino[vueloId]],
                tablero.vueloCapacidad[vueloId]);

        int reruteados = intentarRerutearPool();
        ResultadoCancelacion r = new ResultadoCancelacion(0, reruteados, 0);
        System.out.printf("  → %s%n", r);
        return r;
    }

    /**
     * Cancela todos los vuelos que tienen {@code aeropuertoId} como origen o destino.
     *
     * <p>Útil cuando un aeropuerto cierra completamente (meteorología extrema,
     * huelga, emergencia). Internamente llama a la lógica de {@link #cancelarVuelo}
     * para cada vuelo relacionado, agregando los resultados.
     *
     * @param aeropuertoId ID del aeropuerto a cancelar.
     * @return resumen agregado de afectados y re-ruteados.
     */
    public ResultadoCancelacion cancelarAeropuerto(int aeropuertoId) {
        System.out.printf("CANCELACIÓN aeropuerto %s%n",
                (aeropuertoId >= 1 && aeropuertoId < tablero.iataAeropuerto.length)
                        ? tablero.iataAeropuerto[aeropuertoId] : aeropuertoId);

        int afectadosTotal = 0;
        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] != aeropuertoId &&
                tablero.vueloDestino[v] != aeropuertoId) continue;
            if (vuelosCancelados.contains(v)) continue;

            capacidadesGuardadas.put(v, tablero.vueloCapacidad[v]);
            tablero.vueloCapacidad[v] = 0;
            vuelosCancelados.add(v);
            afectadosTotal += liberarEnviosDeVuelo(v);
        }

        int reruteados = intentarRerutearPool();
        ResultadoCancelacion r = new ResultadoCancelacion(
                afectadosTotal, reruteados, afectadosTotal - reruteados);
        System.out.printf("  → %s%n", r);
        return r;
    }

    /**
     * Restaura todos los vuelos del aeropuerto {@code aeropuertoId} que estaban cancelados.
     *
     * @param aeropuertoId ID del aeropuerto a reactivar.
     * @return resumen de envíos re-ruteados gracias a la restauración.
     */
    public ResultadoCancelacion restaurarAeropuerto(int aeropuertoId) {
        System.out.printf("RESTAURACIÓN aeropuerto %s%n",
                (aeropuertoId >= 1 && aeropuertoId < tablero.iataAeropuerto.length)
                        ? tablero.iataAeropuerto[aeropuertoId] : aeropuertoId);

        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] != aeropuertoId &&
                tablero.vueloDestino[v] != aeropuertoId) continue;
            if (!vuelosCancelados.contains(v)) continue;

            tablero.vueloCapacidad[v] = capacidadesGuardadas.remove(v);
            vuelosCancelados.remove(v);
        }

        int reruteados = intentarRerutearPool();
        ResultadoCancelacion r = new ResultadoCancelacion(0, reruteados, 0);
        System.out.printf("  → %s%n", r);
        return r;
    }

    /**
     * @return {@code true} si el vuelo está actualmente cancelado.
     */
    public boolean estaVueloCancelado(int vueloId) {
        return vuelosCancelados.contains(vueloId);
    }

    /**
     * @return {@code true} si TODOS los vuelos relacionados con el aeropuerto
     *         (origen o destino) están cancelados.
     */
    public boolean estaAeropuertoCancelado(int aeropuertoId) {
        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] == aeropuertoId ||
                tablero.vueloDestino[v] == aeropuertoId) {
                if (!vuelosCancelados.contains(v)) return false;
            }
        }
        return true;
    }

    // =========================================================================
    // EXPORTACIÓN CSV DE MÉTRICAS
    // =========================================================================

    /**
     * Exporta métricas de la ejecución completa (Fase 2 + Fase 3) a un archivo CSV.
     *
     * <p>Columnas: Total Envíos, Exitosos, Rechazados iniciales, Salvados por GVNS,
     * Rechazados finales, tránsito Fase 2, tiempos de CPU y tasa de éxito final.
     *
     * @param nombreArchivo ruta del archivo CSV de salida.
     * @param transitoFase2 tránsito total al finalizar la Fase 2 (minutos).
     * @param tiempoGreedy  segundos de CPU invertidos en la Fase 2.
     * @param tiempoGVNS    segundos de CPU invertidos en la Fase 3.
     * @param salvadosGVNS  envíos recuperados por N1+N2 en la Fase 3.
     */
    public void exportarResultadosCSV(String nombreArchivo,
                                      long   transitoFase2,
                                      double tiempoGreedy,
                                      double tiempoGVNS,
                                      int    salvadosGVNS) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(nombreArchivo))) {
            pw.println("Metrica,Valor");
            pw.println("Total Envios,"           + tablero.numEnvios);
            pw.println("Exitosos Total,"          + enviosExitosos.get());
            pw.println("Rechazados Iniciales,"    + totalRechazados);
            pw.println("Salvados GVNS (N1+N2),"   + salvadosGVNS);
            pw.println("Rechazados Finales,"       + contarRechazadosActivos());
            pw.println("Transito Fase2 (min),"     + transitoFase2);
            pw.println("Transito Final (min),"     + calcularTransitoTotal());
            pw.println("Tiempo Greedy (s),"        + tiempoGreedy);
            pw.println("Tiempo GVNS (s),"          + tiempoGVNS);
            double tasa = (enviosExitosos.get() / (double) tablero.numEnvios) * 100.0;
            pw.println("Tasa Exito Final (%),"    + String.format("%.4f", tasa));
            System.out.println("Resultados exportados: " + nombreArchivo);
        } catch (Exception ex) {
            System.err.println("Error al exportar CSV: " + ex.getMessage());
        }
    }

    /**
     * Calcula el tránsito total de todos los envíos asignados: suma de
     * (tiempo de llegada al destino − tiempo de registro del envío) en minutos.
     *
     * <p>Métrica de calidad de la solución: menor tránsito = maletas llegan
     * antes. Se usa para comparar la Fase 2 (construcción) contra la Fase 3
     * (GVNS) y medir la mejora del algoritmo.
     *
     * @return suma de tiempos de tránsito en minutos; 0 si no hay asignados.
     */
    public long calcularTransitoTotal() {
        long total = 0L;
        for (int e = 0; e < tablero.numEnvios; e++) {
            if (solucionVuelos[e][0] == -1) continue;

            // Encontrar el índice del último tramo asignado
            int ultimoTramo = 0;
            for (int s = 1; s < MAX_SALTOS; s++) {
                if (solucionVuelos[e][s] == -1) break;
                ultimoTramo = s;
            }

            int  v       = solucionVuelos[e][ultimoTramo];
            long salida  = solucionDias[e][ultimoTramo];
            long llegada = salida + duracionVuelo(v);
            total += llegada - tablero.envioRegistroUTC[e];
        }
        return total;
    }
}
