package pe.edu.pucp.tasf.gvns;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class PlanificadorGVNSConcurrente {

    private GestorDatos tablero;
    public int[][] solucionVuelos;
    public long[][] solucionDias;

    // --- VARIABLES GVNS ---
    public int[] enviosRechazados = new int[200000];
    public int totalRechazados = 0;

    // Parámetros del negocio
    public final int TIEMPO_MINIMO_ESCALA = 10;
    public final int MAX_SALTOS = 3;

    // --- CANDADOS PARA CONCURRENCIA ---
    private final Object lockRechazados = new Object();
    public AtomicInteger enviosExitosos = new AtomicInteger(0);

    // Mapa concurrente seguro para hilos
    public ConcurrentHashMap<Long, AtomicInteger> ocupacionVuelos = new ConcurrentHashMap<>();

    public PlanificadorGVNSConcurrente(GestorDatos datos) {
        this.tablero = datos;
        this.solucionVuelos = new int[datos.numEnvios][MAX_SALTOS];
        this.solucionDias = new long[datos.numEnvios][MAX_SALTOS];

        for (int i = 0; i < datos.numEnvios; i++) {
            for (int j = 0; j < MAX_SALTOS; j++) {
                solucionVuelos[i][j] = -1;
                solucionDias[i][j] = -1;
            }
        }
        for (int i = 0; i < enviosRechazados.length; i++) {
            enviosRechazados[i] = -1;
        }
    }

    // =========================================================================
    // FASE 2: CONSTRUCTOR GOLOSO (MULTIHILO)
    // =========================================================================
    public void construirSolucionInicial() {
        System.out.println("🚀 Construyendo Solución Inicial (Concurrente/Multihilo)...");

        // Magia concurrente: divide los millones de envíos en todos los núcleos del CPU
        IntStream.range(0, tablero.numEnvios).parallel().forEach(e -> {

            int origen = tablero.envioOrigen[e];
            int destino = tablero.envioDestino[e];
            int maletas = tablero.envioMaletas[e];
            long tiempoRegistro = tablero.envioRegistroUTC[e];
            long deadline = tablero.envioDeadlineUTC[e];

            int[] rutaTemporalVuelos = new int[MAX_SALTOS];
            long[] rutaTemporalDias = new long[MAX_SALTOS];

            boolean encontroRuta = buscarRutaDFS(origen, destino, maletas, tiempoRegistro, deadline, 0, rutaTemporalVuelos, rutaTemporalDias);

            if (encontroRuta) {
                // Escritura segura porque cada hilo escribe en su propio índice 'e'
                System.arraycopy(rutaTemporalVuelos, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(rutaTemporalDias, 0, solucionDias[e], 0, MAX_SALTOS);
                enviosExitosos.incrementAndGet();
            } else {
                // Sincronización estricta: solo un hilo a la vez anota en la lista de rechazados
                synchronized (lockRechazados) {
                    if (totalRechazados < enviosRechazados.length) {
                        enviosRechazados[totalRechazados] = e;
                        totalRechazados++;
                    }
                }
            }
        });

        System.out.println("✅ Solución Inicial Terminada. Ruteados: " + enviosExitosos.get() + " / " + tablero.numEnvios);
    }

    // =========================================================================
    // CONTROL DE CAPACIDAD DINÁMICA (ATÓMICO Y LOCK-FREE)
    // =========================================================================
    private boolean intentarReservarEspacio(int v, long salidaAbsoluta, int maletas) {
        long diaAbsoluto = salidaAbsoluta / 1440;
        long key = (v * 100000L) + diaAbsoluto;

        ocupacionVuelos.putIfAbsent(key, new AtomicInteger(0));
        AtomicInteger ocupacion = ocupacionVuelos.get(key);

        while (true) {
            int actual = ocupacion.get();
            if (actual + maletas > tablero.vueloCapacidad[v]) {
                return false; // Se llenó
            }
            // Verifica que otro hilo no haya modificado el valor, si es seguro, reserva
            if (ocupacion.compareAndSet(actual, actual + maletas)) {
                return true;
            }
        }
    }

    private void liberarEspacio(int v, long salidaAbsoluta, int maletas) {
        long diaAbsoluto = salidaAbsoluta / 1440;
        long key = (v * 100000L) + diaAbsoluto;
        AtomicInteger ocupacion = ocupacionVuelos.get(key);
        if (ocupacion != null) {
            ocupacion.addAndGet(-maletas); // Devuelve los asientos
        }
    }

    // =========================================================================
    // MOTOR DE BÚSQUEDA RECURSIVO (MULTIHILO)
    // =========================================================================
    private boolean buscarRutaDFS(int nodoActual, int destinoFinal, int maletas, long tiempoActual, long deadline, int saltoActual, int[] rutaTempVuelos, long[] rutaTempDias) {

        if (nodoActual == destinoFinal) return true;
        if (saltoActual >= MAX_SALTOS) return false;

        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] == nodoActual) {

                long salidaUTC = tablero.vueloSalidaUTC[v];
                long duracion = tablero.vueloLlegadaUTC[v] - salidaUTC;
                if (duracion < 0) duracion += 1440;

                long salidaAbsoluta = calcularMinutoSalidaReal(tiempoActual, salidaUTC);

                if (saltoActual > 0 && salidaAbsoluta < (tiempoActual + TIEMPO_MINIMO_ESCALA)) {
                    salidaAbsoluta += 1440;
                }

                long llegadaAbsoluta = salidaAbsoluta + duracion;

                if (llegadaAbsoluta + TIEMPO_MINIMO_ESCALA > deadline) continue;

                // RESERVA PREVENTIVA (Segura para hilos)
                if (!intentarReservarEspacio(v, salidaAbsoluta, maletas)) continue;

                rutaTempVuelos[saltoActual] = v;
                rutaTempDias[saltoActual] = salidaAbsoluta;

                boolean exito = buscarRutaDFS(tablero.vueloDestino[v], destinoFinal, maletas, llegadaAbsoluta, deadline, saltoActual + 1, rutaTempVuelos, rutaTempDias);

                if (exito) {
                    return true;
                } else {
                    // ROLLBACK: La escala falló, soltamos el asiento para que otro hilo lo use
                    liberarEspacio(v, salidaAbsoluta, maletas);
                    rutaTempVuelos[saltoActual] = -1;
                    rutaTempDias[saltoActual] = -1;
                }
            }
        }
        return false;
    }

    private long calcularMinutoSalidaReal(long minutoActualAbsoluto, long salidaVueloUTC) {
        long minutoDelDia = minutoActualAbsoluto % 1440;
        long diaAbsoluto = minutoActualAbsoluto / 1440;
        if (minutoDelDia <= salidaVueloUTC) return (diaAbsoluto * 1440) + salidaVueloUTC;
        else return ((diaAbsoluto + 1) * 1440) + salidaVueloUTC;
    }

    // =========================================================================
    // FASE 3: OPTIMIZACIÓN GVNS (MULTIHILO AL 100% DE CPU)
    // =========================================================================
    public int ejecutarMejoraGVNS() {
        System.out.println("🌪️ Iniciando GVNS: Operador N1 (Concurrente/Multihilo al 100% CPU)...");
        AtomicInteger salvados = new AtomicInteger(0);
        AtomicInteger procesados = new AtomicInteger(0);

        // ¡Desatamos todos los núcleos sobre los rechazados en paralelo!
        IntStream.range(0, totalRechazados).parallel().forEach(i -> {
            int idRechazado = enviosRechazados[i];
            if (idRechazado == -1) return;

            boolean exito = aplicarOperadorN1(idRechazado);
            if (exito) {
                salvados.incrementAndGet();
                enviosRechazados[i] = -1;
            }

            int actuales = procesados.incrementAndGet();
            if (actuales > 0 && actuales % 10000 == 0) {
                System.out.println("   ... Procesados " + actuales + " rechazados en paralelo. Salvados: " + salvados.get());
            }
        });

        System.out.println("✅ GVNS Terminado. Maletas salvadas: " + salvados.get() + " / " + totalRechazados);
        return salvados.get();
    }

    private boolean aplicarOperadorN1(int idRechazado) {
        int origen = tablero.envioOrigen[idRechazado];
        int destino = tablero.envioDestino[idRechazado];
        int maletas = tablero.envioMaletas[idRechazado];
        long tiempoRegistro = tablero.envioRegistroUTC[idRechazado];
        long deadline = tablero.envioDeadlineUTC[idRechazado];

        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] == origen) {

                long salidaAbsoluta = calcularMinutoSalidaReal(tiempoRegistro, tablero.vueloSalidaUTC[v]);
                long duracion = tablero.vueloLlegadaUTC[v] - tablero.vueloSalidaUTC[v];
                if (duracion < 0) duracion += 1440;
                long llegadaAbsoluta = salidaAbsoluta + duracion;

                if (llegadaAbsoluta + TIEMPO_MINIMO_ESCALA <= deadline) {

                    long key = (v * 100000L) + (salidaAbsoluta / 1440);
                    ocupacionVuelos.putIfAbsent(key, new AtomicInteger(0));
                    AtomicInteger ocupacion = ocupacionVuelos.get(key);

                    if (ocupacion.get() + maletas > tablero.vueloCapacidad[v]) {

                        // BLOQUEO GRANULAR: Solo bloqueamos este avión en este día exacto.
                        // El resto de hilos siguen procesando a toda velocidad.
                        synchronized (ocupacion) {

                            // Doble validación por si otro hilo liberó espacio mientras esperábamos
                            if (ocupacion.get() + maletas > tablero.vueloCapacidad[v]) {

                                // EXPULSIÓN VIRTUAL
                                ocupacion.addAndGet(-maletas);

                                int[] rutaTempVuelos = new int[MAX_SALTOS];
                                long[] rutaTempDias = new long[MAX_SALTOS];
                                for(int s=0; s<MAX_SALTOS; s++) { rutaTempVuelos[s] = -1; rutaTempDias[s] = -1; }

                                boolean encontroRutaNueva = buscarRutaDFS(origen, destino, maletas, tiempoRegistro, deadline, 0, rutaTempVuelos, rutaTempDias);

                                // RESTAURACIÓN
                                ocupacion.addAndGet(maletas);

                                if (encontroRutaNueva) {
                                    System.arraycopy(rutaTempVuelos, 0, solucionVuelos[idRechazado], 0, MAX_SALTOS);
                                    System.arraycopy(rutaTempDias, 0, solucionDias[idRechazado], 0, MAX_SALTOS);
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    // =========================================================================
    // EXPORTACIÓN DE RESULTADOS
    // =========================================================================
    public void exportarResultadosCSV(String nombreArchivo, double tiempoGreedy, double tiempoGVNS, int salvadosGVNS) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(nombreArchivo))) {
            pw.println("Metrica,Valor");
            pw.println("Total Envios," + tablero.numEnvios);
            pw.println("Exitosos Greedy," + enviosExitosos.get());
            pw.println("Rechazados Iniciales," + totalRechazados);
            pw.println("Salvados GVNS N1," + salvadosGVNS);
            pw.println("Rechazados Finales," + (totalRechazados - salvadosGVNS));
            pw.println("Tiempo Greedy (s)," + tiempoGreedy);
            pw.println("Tiempo GVNS (s)," + tiempoGVNS);

            double tasaExito = ((enviosExitosos.get() + salvadosGVNS) / (double)tablero.numEnvios) * 100;
            pw.println("Tasa Exito Final (%)," + String.format("%.4f", tasaExito));

            System.out.println("📊 Archivo de resultados exportado: " + nombreArchivo);
        } catch (Exception e) {
            System.err.println("❌ Error al exportar CSV: " + e.getMessage());
        }
    }
}