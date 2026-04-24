package pe.edu.pucp.tasf.gvns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * GestorDatos — Carga y almacena la red expandida en el tiempo.
 *
 * Optimizaciones activas (Iteración 3):
 *  - BufferedReader con búfer de 65 536 B en cargarArchivoEnvio (Objetivo 1).
 *  - Conversión UTC sin objetos LocalDateTime: puro aritmética de enteros
 *    (elimina ~9.5 M asignaciones de Heap en la carga masiva).
 *  - Lookup inverso iataAeropuerto[] para que AuditorRutas y ExportadorVisual
 *    puedan obtener el código IATA de un ID en O(1).
 */
public class GestorDatos {

    // ── AEROPUERTOS ──────────────────────────────────────────────────────────
    public Map<String, Integer> mapaIataAId = new HashMap<>();
    public int    numAeropuertos = 0;
    public int[]  capacidadAlmacen = new int[50];
    public int[]  gmtAeropuerto    = new int[50];
    public int[]  continenteAero   = new int[50];
    /** Lookup inverso: ID → código IATA (ej. iataAeropuerto[3] == "OAKB"). */
    public String[] iataAeropuerto = new String[50];

    // ── VUELOS ───────────────────────────────────────────────────────────────
    public int   numVuelos      = 0;
    public int[] vueloOrigen    = new int[5000];
    public int[] vueloDestino   = new int[5000];
    public int[] vueloSalidaUTC = new int[5000];   // minutos dentro del día UTC
    public int[] vueloLlegadaUTC= new int[5000];   // minutos dentro del día UTC
    public int[] vueloCapacidad = new int[5000];

    // ── ENVÍOS (9.5 M) ───────────────────────────────────────────────────────
    // Arrays primitivos: ~80 MB cada int[], ~160 MB cada long[].
    // NO usar List<Object> — colapsa el Heap a esta escala.
    public int    numEnvios         = 0;
    public int[]  envioOrigen       = new int [20_000_000];
    public int[]  envioDestino      = new int [20_000_000];
    public int[]  envioMaletas      = new int [20_000_000];
    public long[] envioRegistroUTC  = new long[20_000_000];
    public long[] envioDeadlineUTC  = new long[20_000_000];

    // =========================================================================
    // CARGA DE AEROPUERTOS
    // =========================================================================
    public void cargarAeropuertos(String rutaArchivo) {
        int idActual        = 1;
        int continenteActual= 1;

        File archivo = new File(rutaArchivo);
        System.out.println("Buscando archivo en: " + archivo.getAbsolutePath());

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(archivo), StandardCharsets.UTF_16))) {

            String linea;
            while ((linea = br.readLine()) != null) {
                String limpia = linea.trim();
                if (limpia.isEmpty() || limpia.startsWith("*") || limpia.startsWith("PDDS"))
                    continue;

                String lower = limpia.toLowerCase();
                if (lower.contains("america")) { continenteActual = 1; continue; }
                if (lower.contains("europa"))  { continenteActual = 2; continue; }
                if (lower.contains("asia"))    { continenteActual = 3; continue; }

                if (limpia.contains("Latitude") && limpia.length() > 10) {
                    String[] partes = limpia.split("\\s+");

                    int    idxLat    = -1;
                    String codigoIata= null;
                    for (int i = 0; i < partes.length; i++) {
                        if (partes[i].contains("Latitude")) { idxLat = i; break; }
                    }
                    for (String parte : partes) {
                        if (parte.matches("[A-Z]{4}")) { codigoIata = parte; break; }
                    }

                    if (idxLat > 2 && codigoIata != null) {
                        try {
                            int capacidad = Integer.parseInt(partes[idxLat - 1]);
                            int gmt       = Integer.parseInt(partes[idxLat - 2].replace("+", ""));

                            mapaIataAId.put(codigoIata, idActual);
                            iataAeropuerto [idActual] = codigoIata;   // ← reverse lookup
                            capacidadAlmacen[idActual] = capacidad;
                            gmtAeropuerto  [idActual] = gmt;
                            continenteAero [idActual] = continenteActual;

                            System.out.printf("  Registrado: %s (ID %d, GMT %+d, CAP %d)%n",
                                    codigoIata, idActual, gmt, capacidad);
                            idActual++;
                        } catch (NumberFormatException e) {
                            System.err.println("  Linea con formato inesperado: " + limpia);
                        }
                    }
                }
            }
            this.numAeropuertos = idActual - 1;
            System.out.println("Total Aeropuertos: " + this.numAeropuertos);

        } catch (Exception e) {
            System.err.println("Error cargando aeropuertos: " + e.getMessage());
        }
    }

    // =========================================================================
    // CARGA DE VUELOS
    // Formato de línea: OAKB-EBCI-10:30-14:45-100
    // =========================================================================
    public void cargarVuelos(String rutaArchivo) {
        if (this.numAeropuertos == 0) {
            System.out.println("Sin aeropuertos en memoria. Carga de vuelos omitida.");
            return;
        }

        int count = 0;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(rutaArchivo), StandardCharsets.UTF_8))) {

            String linea;
            while ((linea = br.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty() || !linea.contains("-")) continue;

                String[] p = linea.split("-");
                if (p.length < 5) continue;

                String ori = p[0].trim();
                String des = p[1].trim();

                if (!mapaIataAId.containsKey(ori) || !mapaIataAId.containsKey(des)) continue;

                int idO = mapaIataAId.get(ori);
                int idD = mapaIataAId.get(des);

                // Parseo de tiempo local de salida y llegada
                String[] tSal = p[2].split(":");
                String[] tLle = p[3].split(":");
                int hS = Integer.parseInt(tSal[0]), mS = Integer.parseInt(tSal[1]);
                int hL = Integer.parseInt(tLle[0]), mL = Integer.parseInt(tLle[1]);

                // Conversión a UTC usando offset GMT del aeropuerto
                int salidaUTC  = (hS - gmtAeropuerto[idO]) * 60 + mS;
                int llegadaUTC = (hL - gmtAeropuerto[idD]) * 60 + mL;

                // Ajuste de rango al día [0, 1440)
                if (salidaUTC  < 0) salidaUTC  += 1440;
                if (llegadaUTC < 0) llegadaUTC += 1440;
                // Si la llegada parece anterior a la salida → cruza medianoche
                if (llegadaUTC <= salidaUTC) llegadaUTC += 1440;

                vueloOrigen    [count] = idO;
                vueloDestino   [count] = idD;
                vueloSalidaUTC [count] = salidaUTC;
                vueloLlegadaUTC[count] = llegadaUTC;
                vueloCapacidad [count] = Integer.parseInt(p[4].trim());
                count++;
            }
            this.numVuelos = count;
            System.out.println("Total Vuelos: " + this.numVuelos);

        } catch (Exception e) {
            System.err.println("Error cargando vuelos: " + e.getMessage());
        }
    }

    // =========================================================================
    // CARGA MASIVA DE ENVÍOS — OBJETIVO 1
    //
    //  Cambios respecto a la versión anterior:
    //  1. BufferedReader con búfer explícito de 65 536 B (línea cargarArchivoEnvio).
    //  2. Conversión UTC sin LocalDateTime: método estático calcularEpochMinutos()
    //     evita ~9.5 M asignaciones de objetos LocalDateTime + LocalDateTime en Heap.
    // =========================================================================
    /**
     * Descarta todos los envíos cargados para reutilizar los arrays en una nueva
     * ventana de simulación. No libera memoria (los arrays siguen en heap).
     * Llamar antes de {@link #cargarTodosLosEnvios} en el escenario COLAPSO.
     */
    public void resetEnvios() {
        numEnvios = 0;
    }

    /**
     * Carga todos los envíos de {@code rutaDirectorio} sin filtro de tiempo.
     * Equivale a {@code cargarTodosLosEnvios(ruta, Long.MIN_VALUE, Long.MAX_VALUE)}.
     *
     * @param rutaDirectorio carpeta con archivos {@code _envios_IATA_.txt}
     */
    public void cargarTodosLosEnvios(String rutaDirectorio) {
        cargarTodosLosEnvios(rutaDirectorio, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * Carga solo los envíos cuyo {@code envioRegistroUTC} cae en
     * {@code [inicioUTC, finUTC)}, permitiendo filtrar por ventana de simulación.
     *
     * <p>Usado por los escenarios de período y colapso para restringir la carga
     * a los días relevantes sin necesidad de archivos separados por día.
     *
     * @param rutaDirectorio carpeta con archivos {@code _envios_IATA_.txt}
     * @param inicioUTC      minuto UTC absoluto de inicio (inclusive)
     * @param finUTC         minuto UTC absoluto de fin (exclusive)
     */
    public void cargarTodosLosEnvios(String rutaDirectorio, long inicioUTC, long finUTC) {
        File carpeta = new File(rutaDirectorio);
        if (!carpeta.isDirectory()) {
            System.err.println("La ruta de envios no es una carpeta: " + rutaDirectorio);
            return;
        }

        File[] archivos = carpeta.listFiles();
        if (archivos != null) {
            for (File archivo : archivos) {
                if (archivo.getName().startsWith("_envios_") && archivo.getName().endsWith(".txt")) {
                    System.out.println("Procesando " + archivo.getName() + "...");
                    cargarArchivoEnvio(archivo, inicioUTC, finUTC);
                }
            }
        }
        System.out.println("Total Envios Cargados en Memoria: " + this.numEnvios);
    }

    /**
     * Lee un archivo de envíos línea por línea con un búfer de 65 536 B.
     *
     * Formato de línea: ENV12345-20250818-10-30-EBCI-5-CLI99
     * Campos: ID - FechaYYYYMMDD - Hora - Minuto - DestinoIATA - Maletas - ClienteID
     *
     * La conversión de tiempo local → UTC absoluto (minutos desde Epoch) se realiza
     * mediante aritmética pura de enteros (ver calcularEpochMinutos), eliminando
     * la creación de objetos LocalDateTime que saturaban el GC en la carga masiva.
     */
    private void cargarArchivoEnvio(File archivo, long inicioUTC, long finUTC) {
        // Extraer IATA de origen del nombre del archivo: _envios_OAKB_.txt → OAKB
        String nombre    = archivo.getName();
        String iataOrigen= nombre.replace("_envios_", "").replace("_.txt", "").trim();

        if (!mapaIataAId.containsKey(iataOrigen)) return;

        int idOrigen = mapaIataAId.get(iataOrigen);
        int gmtOri   = gmtAeropuerto[idOrigen];

        // ── CAMBIO CLAVE: BufferedReader con búfer de 65 536 B ───────────────
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(archivo), StandardCharsets.UTF_8),
                65_536)) {

            String linea;
            while ((linea = br.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty() || !linea.contains("-")) continue;

                // split con límite 7 evita crear splits extra si el ID lleva guiones
                String[] p = linea.split("-", 7);
                if (p.length < 6) continue;

                // Parseo de campos (sin crear objetos de fecha)
                String fechaStr   = p[1];
                int    horaLoc    = Integer.parseInt(p[2]);
                int    minLoc     = Integer.parseInt(p[3]);
                String iataDest   = p[4];
                int    cantMaletas= Integer.parseInt(p[5]);

                if (!mapaIataAId.containsKey(iataDest)) continue;
                int idDestino = mapaIataAId.get(iataDest);

                // Parseo de fecha sin LocalDateTime
                int anio = Integer.parseInt(fechaStr.substring(0, 4));
                int mes  = Integer.parseInt(fechaStr.substring(4, 6));
                int dia  = Integer.parseInt(fechaStr.substring(6, 8));

                // ── CAMBIO CLAVE: UTC en minutos absolutos sin objeto LocalDateTime ──
                long minutosUTC = calcularEpochMinutos(anio, mes, dia, horaLoc, minLoc, gmtOri);

                if (numEnvios >= envioOrigen.length) {
                    System.err.println("LIMITE ALCANZADO: mas de 20 millones de envios.");
                    break;
                }

                // Filtro de ventana temporal: solo cargar si el registro cae en [inicioUTC, finUTC)
                if (minutosUTC < inicioUTC || minutosUTC >= finUTC) continue;

                envioOrigen    [numEnvios] = idOrigen;
                envioDestino   [numEnvios] = idDestino;
                envioMaletas   [numEnvios] = cantMaletas;
                envioRegistroUTC[numEnvios]= minutosUTC;

                // Deadline: mismo continente → 24 h (1440 min), distinto → 48 h (2880 min)
                boolean mismoContinente = (continenteAero[idOrigen] == continenteAero[idDestino]);
                envioDeadlineUTC[numEnvios] = minutosUTC + (mismoContinente ? 1440L : 2880L);

                numEnvios++;

                if (numEnvios % 1_000_000 == 0)
                    System.out.println("   ... " + (numEnvios / 1_000_000) + " M envios en RAM...");
            }

        } catch (Exception e) {
            System.err.println("Error leyendo " + archivo.getName() + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // CONVERSIÓN DE TIEMPO PURA — SIN OBJETOS JAVA.TIME
    //
    // Algoritmo: Número de Día Juliano (JDN) → días desde Epoch (1970-01-01).
    // Referencia: Meeus, "Astronomical Algorithms", Cap. 7.
    //
    // Verificación para 2025-08-18 10:30 local GMT+2 (→ 08:30 UTC):
    //   JDN(2025-08-18) = 2 460 906
    //   EpochDay        = 2 460 906 − 2 440 588 = 20 318
    //   minutosUTC      = 20 318 × 1440 + 8×60 + 30 − 0 = 29 258 190
    // =========================================================================
    /**
     * Convierte una fecha/hora local a minutos absolutos UTC desde Epoch
     * (1970-01-01 00:00 UTC) usando solo aritmética de enteros.
     *
     * @param anio     año (ej. 2025)
     * @param mes      mes 1-12
     * @param dia      día 1-31
     * @param horaLoc  hora local (0-23)
     * @param minLoc   minuto local (0-59)
     * @param gmtHoras offset UTC del aeropuerto de origen (ej. +2 o -5)
     * @return minutos desde Epoch (1970-01-01 00:00 UTC)
     */
    public static long calcularEpochMinutos(int anio, int mes, int dia,
                                            int horaLoc, int minLoc, int gmtHoras) {
        // Paso 1: Número de Día Juliano (JDN) de la fecha gregoriana
        int a   = (14 - mes) / 12;
        int y   = anio + 4800 - a;
        int m   = mes  + 12 * a - 3;
        long jdn = dia
                 + (153L * m + 2) / 5
                 + 365L * y
                 + y / 4
                 - y / 100
                 + y / 400
                 - 32045L;

        // Paso 2: Días desde Epoch (JDN del 1970-01-01 = 2 440 588)
        long epochDay = jdn - 2_440_588L;

        // Paso 3: Minutos locales desde Epoch
        long minutosLocales = epochDay * 1440L + horaLoc * 60L + minLoc;

        // Paso 4: Convertir a UTC restando el offset GMT
        return minutosLocales - gmtHoras * 60L;
    }
}
