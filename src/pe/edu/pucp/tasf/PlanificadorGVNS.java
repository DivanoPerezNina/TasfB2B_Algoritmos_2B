package pe.edu.pucp.tasf;

import java.util.HashMap;
import java.util.Map;
import java.io.FileWriter;
import java.io.PrintWriter;

public class PlanificadorGVNS {

    private GestorDatos tablero;
    public int[][] solucionVuelos;
    public long[][] solucionDias;

    // --- VARIABLES GVNS ---
    public int[] enviosRechazados = new int[200000]; // Ajustado para soportar bastantes rechazados
    public int totalRechazados = 0;

    // Parámetros del negocio (Reglas del FAQ)
    public final int TIEMPO_MINIMO_ESCALA = 10; // Minutos
    public final int MAX_SALTOS = 3;

    public PlanificadorGVNS(GestorDatos datos) {
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
    // FASE 2: CONSTRUCTOR GOLOSO (GREEDY INITIAL SOLUTION)
    // =========================================================================
    public void construirSolucionInicial() {
        System.out.println("🚀 Construyendo Solución Inicial (DFS-Greedy)...");
        int enviosExitosos = 0;

        for (int e = 0; e < tablero.numEnvios; e++) {
            int origen = tablero.envioOrigen[e];
            int destino = tablero.envioDestino[e];
            int maletas = tablero.envioMaletas[e];
            long tiempoRegistro = tablero.envioRegistroUTC[e];
            long deadline = tablero.envioDeadlineUTC[e];

            int[] rutaTemporalVuelos = new int[MAX_SALTOS];
            long[] rutaTemporalDias = new long[MAX_SALTOS];

            // Iniciar búsqueda recursiva (DFS)
            boolean encontroRuta = buscarRutaDFS(origen, destino, maletas, tiempoRegistro, deadline, 0, rutaTemporalVuelos, rutaTemporalDias);

            if (encontroRuta) {
                System.arraycopy(rutaTemporalVuelos, 0, solucionVuelos[e], 0, MAX_SALTOS);
                System.arraycopy(rutaTemporalDias, 0, solucionDias[e], 0, MAX_SALTOS);

                // Ocupar los asientos en el avión
                for (int s = 0; s < MAX_SALTOS; s++) {
                    if (rutaTemporalVuelos[s] != -1) {
                        registrarOcupacionVuelo(rutaTemporalVuelos[s], rutaTemporalDias[s], maletas);
                    }
                }
                enviosExitosos++;
            } else {
                // Registrar el rechazo para que el GVNS lo intente salvar después
                if (totalRechazados < enviosRechazados.length) {
                    enviosRechazados[totalRechazados] = e;
                    totalRechazados++;
                }
            }
        }
        System.out.println("✅ Solución Inicial Terminada. Ruteados: " + enviosExitosos + " / " + tablero.numEnvios);
    }

    // =========================================================================
    // MOTOR DE BÚSQUEDA RECURSIVO (DFS)
    // =========================================================================
    private boolean buscarRutaDFS(int nodoActual, int destinoFinal, int maletas, long tiempoActual, long deadline, int saltoActual, int[] rutaTempVuelos, long[] rutaTempDias) {

        if (nodoActual == destinoFinal) return true; // Llegamos al destino
        if (saltoActual >= MAX_SALTOS) return false; // Límite de escalas alcanzado

        // Buscar todos los vuelos que salen de este nodo
        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] == nodoActual) {

                long salidaUTC = tablero.vueloSalidaUTC[v];
                long duracion = tablero.vueloLlegadaUTC[v] - salidaUTC;
                if (duracion < 0) duracion += 1440; // Ajuste de cambio de día

                // Calcular el momento exacto en que tomará el vuelo
                long salidaAbsoluta = calcularMinutoSalidaReal(tiempoActual, salidaUTC);

                // REGLA FAQ: Si es una escala (salto > 0), debe esperar mínimo 10 minutos
                if (saltoActual > 0 && salidaAbsoluta < (tiempoActual + TIEMPO_MINIMO_ESCALA)) {
                    salidaAbsoluta += 1440;
                }

                long llegadaAbsoluta = salidaAbsoluta + duracion;

                // Restricción 1: Time Window (+ 10 min de recogida final)
                if (llegadaAbsoluta + TIEMPO_MINIMO_ESCALA > deadline) continue;

                // Restricción 2: Capacidad del Vuelo
                if (vueloLleno(v, salidaAbsoluta, maletas)) continue;

                // Restricción 3: Capacidad del Almacén (Para futura implementación)
                // if (!hayEspacioEnAlmacen(nodoActual, tiempoActual, salidaAbsoluta, maletas)) continue;

                rutaTempVuelos[saltoActual] = v;
                rutaTempDias[saltoActual] = salidaAbsoluta;

                // Viajamos al siguiente nodo recursivamente
                boolean exito = buscarRutaDFS(tablero.vueloDestino[v], destinoFinal, maletas, llegadaAbsoluta, deadline, saltoActual + 1, rutaTempVuelos, rutaTempDias);

                if (exito) return true;

                // Backtracking implícito: si falla, limpia el temporal
                rutaTempVuelos[saltoActual] = -1;
                rutaTempDias[saltoActual] = -1;
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
    // CONTROL DE CAPACIDAD DINÁMICA
    // =========================================================================
    public Map<Long, Integer> ocupacionVuelos = new HashMap<>();

    private boolean vueloLleno(int v, long salidaAbsoluta, int maletas) {
        long diaAbsoluto = salidaAbsoluta / 1440;
        long key = (v * 100000L) + diaAbsoluto;
        int ocupadas = ocupacionVuelos.getOrDefault(key, 0);
        return (ocupadas + maletas > tablero.vueloCapacidad[v]);
    }

    private void registrarOcupacionVuelo(int v, long salidaAbsoluta, int maletas) {
        long diaAbsoluto = salidaAbsoluta / 1440;
        long key = (v * 100000L) + diaAbsoluto;
        ocupacionVuelos.put(key, ocupacionVuelos.getOrDefault(key, 0) + maletas);
    }

    // =========================================================================
    // FASE 3: OPTIMIZACIÓN GVNS (BÚSQUEDA LOCAL Y RELOCATE N1)
    // =========================================================================
    public void ejecutarMejoraGVNS() {
        System.out.println("🌪️ Iniciando GVNS: Operador N1 (Relocate / Ejection Chain)...");
        System.out.println("   -> Objetivo: Encontrar ruta para " + totalRechazados + " maletas.");

        int salvados = 0;

        for (int i = 0; i < totalRechazados; i++) {
            int idRechazado = enviosRechazados[i];
            if (idRechazado == -1) continue;

            // Intentamos forzar la inserción expulsando a otro temporalmente
            boolean exito = aplicarOperadorN1(idRechazado);
            if (exito) {
                salvados++;
                enviosRechazados[i] = -1; // Lo quitamos de la lista negra
            }

            if (i > 0 && i % 10000 == 0) {
                System.out.println("   ... Procesados " + i + " rechazados. Salvados hasta ahora: " + salvados);
            }
        }

        System.out.println("✅ GVNS Terminado. Maletas salvadas: " + salvados + " / " + totalRechazados);
        int totalRuteados = (tablero.numEnvios - totalRechazados) + salvados;
        System.out.println("🏆 Tasa de éxito final: " + String.format("%.4f", ((double)totalRuteados / tablero.numEnvios) * 100) + "%");
    }

    private boolean aplicarOperadorN1(int idRechazado) {
        int origen = tablero.envioOrigen[idRechazado];
        int destino = tablero.envioDestino[idRechazado];
        int maletas = tablero.envioMaletas[idRechazado];
        long tiempoRegistro = tablero.envioRegistroUTC[idRechazado];
        long deadline = tablero.envioDeadlineUTC[idRechazado];

        // 1. Buscar el cuello de botella
        for (int v = 0; v < tablero.numVuelos; v++) {
            if (tablero.vueloOrigen[v] == origen) {

                long salidaAbsoluta = calcularMinutoSalidaReal(tiempoRegistro, tablero.vueloSalidaUTC[v]);
                long duracion = tablero.vueloLlegadaUTC[v] - tablero.vueloSalidaUTC[v];
                if (duracion < 0) duracion += 1440;
                long llegadaAbsoluta = salidaAbsoluta + duracion;

                if (llegadaAbsoluta + TIEMPO_MINIMO_ESCALA <= deadline) {

                    // Encontramos el vuelo que le sirve pero está lleno
                    if (vueloLleno(v, salidaAbsoluta, maletas)) {

                        long key = (v * 100000L) + (salidaAbsoluta / 1440);
                        int ocupacionOriginal = ocupacionVuelos.getOrDefault(key, 0);

                        // 2. EXPULSIÓN: Le hacemos espacio mágicamente (simulando que reubicamos a alguien)
                        ocupacionVuelos.put(key, ocupacionOriginal - maletas);

                        int[] rutaTempVuelos = new int[MAX_SALTOS];
                        long[] rutaTempDias = new long[MAX_SALTOS];

                        // Inicializar el temporal
                        for(int s=0; s<MAX_SALTOS; s++) { rutaTempVuelos[s] = -1; rutaTempDias[s] = -1; }

                        // 3. Probamos buscar la ruta para este rechazado
                        boolean encontroRutaNueva = buscarRutaDFS(origen, destino, maletas, tiempoRegistro, deadline, 0, rutaTempVuelos, rutaTempDias);

                        // RESTAURACIÓN (Backtracking de ocupación)
                        ocupacionVuelos.put(key, ocupacionOriginal);

                        if (encontroRutaNueva) {
                            // MAGIA: El movimiento se acepta.
                            System.arraycopy(rutaTempVuelos, 0, solucionVuelos[idRechazado], 0, MAX_SALTOS);
                            System.arraycopy(rutaTempDias, 0, solucionDias[idRechazado], 0, MAX_SALTOS);

                            for (int s = 0; s < MAX_SALTOS; s++) {
                                if (rutaTempVuelos[s] != -1) {
                                    registrarOcupacionVuelo(rutaTempVuelos[s], rutaTempDias[s], maletas);
                                }
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public void exportarResultadosCSV(String nombreArchivo, double tiempoGreedy, double tiempoGVNS, int salvadosGVNS) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(nombreArchivo))) {
            pw.println("Metrica,Valor");
            pw.println("Total Envios," + tablero.numEnvios);
            pw.println("Exitosos Greedy," + (tablero.numEnvios - totalRechazados));
            pw.println("Rechazados Iniciales," + totalRechazados);
            pw.println("Salvados GVNS N1," + salvadosGVNS);
            pw.println("Rechazados Finales," + (totalRechazados - salvadosGVNS));
            pw.println("Tiempo Greedy (s)," + tiempoGreedy);
            pw.println("Tiempo GVNS (s)," + tiempoGVNS);
            pw.println("Tasa Exito Final (%)," + String.format("%.4f", (((tablero.numEnvios - totalRechazados) + salvadosGVNS) / (double)tablero.numEnvios) * 100));

            System.out.println("📊 Archivo de resultados exportado: " + nombreArchivo);
        } catch (Exception e) {
            System.err.println("❌ Error al exportar CSV: " + e.getMessage());
        }
    }
}