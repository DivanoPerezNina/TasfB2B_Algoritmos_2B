package pe.edu.pucp.tasf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class GestorDatos {

    public Map<String, Integer> mapaIataAId = new HashMap<>();
    public int numAeropuertos = 0;
    public int[] capacidadAlmacen = new int[50];
    public int[] gmtAeropuerto = new int[50];
    public int[] continenteAero = new int[50];

    public int numVuelos = 0;
    public int[] vueloOrigen = new int[5000];
    public int[] vueloDestino = new int[5000];
    public int[] vueloSalidaUTC = new int[5000];
    public int[] vueloLlegadaUTC = new int[5000];
    public int[] vueloCapacidad = new int[5000];

    public void cargarAeropuertos(String rutaArchivo) {
        int idActual = 1;
        int continenteActual = 1;

        File archivo = new File(rutaArchivo);
        System.out.println("🔍 Buscando archivo en: " + archivo.getAbsolutePath());

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(archivo), StandardCharsets.UTF_16))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String limpia = linea.trim();

                // Ignorar basura
                if (limpia.isEmpty() || limpia.startsWith("*") || limpia.startsWith("PDDS")) continue;

                // Detectar continente
                String lower = limpia.toLowerCase();
                if (lower.contains("america")) { continenteActual = 1; continue; }
                if (lower.contains("europa")) { continenteActual = 2; continue; }
                if (lower.contains("asia")) { continenteActual = 3; continue; }

                // Una línea de aeropuerto válida suele tener el código IATA (4 letras) y la palabra "Latitude"
                if (limpia.contains("Latitude") && limpia.length() > 10) {
                    // Reemplaza este bloque dentro del if (limpia.contains("Latitude")):

                    String[] partes = limpia.split("\\s+");

// Buscar el índice de "Latitude"
                    int idxLat = -1;
                    for (int i = 0; i < partes.length; i++) {
                        if (partes[i].contains("Latitude")) { idxLat = i; break; }
                    }

// Buscar el código IATA: primer token de exactamente 4 letras mayúsculas
                    String codigoIata = null;
                    for (int i = 0; i < partes.length; i++) {
                        if (partes[i].matches("[A-Z]{4}")) { codigoIata = partes[i]; break; }
                    }

                    if (idxLat > 2 && codigoIata != null) {
                        try {
                            int capacidad = Integer.parseInt(partes[idxLat - 1]);
                            int gmt = Integer.parseInt(partes[idxLat - 2].replace("+", ""));

                            mapaIataAId.put(codigoIata, idActual);
                            capacidadAlmacen[idActual] = capacidad;
                            gmtAeropuerto[idActual] = gmt;
                            continenteAero[idActual] = continenteActual;

                            System.out.println("   ✈️ Registrado: " + codigoIata + " (ID " + idActual + ", GMT " + gmt + ", CAP " + capacidad + ")");
                            idActual++;
                        } catch (NumberFormatException e) {
                            System.err.println("   ⚠️ Línea con formato inesperado: " + limpia);
                        }
                    }
                }
            }
            this.numAeropuertos = idActual - 1;
            System.out.println("✅ Total Aeropuertos: " + this.numAeropuertos);
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    public void cargarVuelos(String rutaArchivo) {
        if (this.numAeropuertos == 0) {
            System.out.println("⚠️ No puedo cargar vuelos porque no hay aeropuertos en memoria.");
            return;
        }

        int count = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(rutaArchivo), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty() || !linea.contains("-")) continue;

                String[] p = linea.split("-");
                if (p.length < 5) continue;

                String ori = p[0].trim();
                String des = p[1].trim();

                if (mapaIataAId.containsKey(ori) && mapaIataAId.containsKey(des)) {
                    int idO = mapaIataAId.get(ori);
                    int idD = mapaIataAId.get(des);

                    // Parseo básico de horas locales
                    int hS = Integer.parseInt(p[2].split(":")[0]);
                    int mS = Integer.parseInt(p[2].split(":")[1]);
                    int hL = Integer.parseInt(p[3].split(":")[0]);
                    int mL = Integer.parseInt(p[3].split(":")[1]);

                    // Convertir a UTC usando los GMT cargados
                    int salidaUTC = ((hS - gmtAeropuerto[idO]) * 60) + mS;
                    int llegadaUTC = ((hL - gmtAeropuerto[idD]) * 60) + mL;

                    // Ajuste de días
                    if (salidaUTC < 0) salidaUTC += 1440;
                    if (llegadaUTC < 0) llegadaUTC += 1440;
                    if (llegadaUTC <= salidaUTC) llegadaUTC += 1440;

                    vueloOrigen[count] = idO;
                    vueloDestino[count] = idD;
                    vueloSalidaUTC[count] = salidaUTC;
                    vueloLlegadaUTC[count] = llegadaUTC;
                    vueloCapacidad[count] = Integer.parseInt(p[4]);
                    count++;
                }
            }
            this.numVuelos = count;
            System.out.println("✅ Total Vuelos: " + this.numVuelos);
        } catch (Exception e) {
            System.err.println("❌ Error Vuelos: " + e.getMessage());
        }
    }

    // --- ARREGLOS PARA LOS ENVÍOS (LOS "CARROS" / MALETAS) ---
    // ¡20 millones de espacios! Ultra rápido y amigable con la RAM (aprox 80MB por int[])
    public int numEnvios = 0;
    public int[] envioOrigen = new int[20_000_000];
    public int[] envioDestino = new int[20_000_000];
    public int[] envioMaletas = new int[20_000_000];
    public long[] envioRegistroUTC = new long[20_000_000];
    public long[] envioDeadlineUTC = new long[20_000_000];

    public void cargarTodosLosEnvios(String rutaDirectorio) {
        File carpeta = new File(rutaDirectorio);
        if (!carpeta.isDirectory()) {
            System.err.println("⚠️ La ruta de envíos no es una carpeta: " + rutaDirectorio);
            return;
        }

        File[] archivos = carpeta.listFiles();
        if (archivos != null) {
            for (File archivo : archivos) {
                if (archivo.getName().startsWith("_envios_") && archivo.getName().endsWith(".txt")) {
                    System.out.println("⏳ Procesando " + archivo.getName() + "...");
                    cargarArchivoEnvio(archivo);
                }
            }
        }
        System.out.println("✅ Total Envíos Cargados en Memoria: " + this.numEnvios);
    }

    private void cargarArchivoEnvio(File archivo) {
        String nombre = archivo.getName();
        String iataOrigen = nombre.replace("_envios_", "").replace("_.txt", "").trim();

        if (!mapaIataAId.containsKey(iataOrigen)) return;

        int idOrigen = mapaIataAId.get(iataOrigen);
        int gmtOri = gmtAeropuerto[idOrigen];

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(archivo), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty() || !linea.contains("-")) continue;

                String[] p = linea.split("-");
                // CORRECCIÓN APLICADA: El formato tiene 7 campos, necesitamos length < 7
                if (p.length < 7) continue;

                String fechaStr = p[1];
                int horaLoc = Integer.parseInt(p[2]);
                int minLoc = Integer.parseInt(p[3]);
                String iataDest = p[4];
                int cantMaletas = Integer.parseInt(p[5]);
                // p[6] es el ID de cliente, lo ignoramos para ahorrar RAM

                if (!mapaIataAId.containsKey(iataDest)) continue;
                int idDestino = mapaIataAId.get(iataDest);

                int anio = Integer.parseInt(fechaStr.substring(0, 4));
                int mes = Integer.parseInt(fechaStr.substring(4, 6));
                int dia = Integer.parseInt(fechaStr.substring(6, 8));

                LocalDateTime fechaLocal = LocalDateTime.of(anio, mes, dia, horaLoc, minLoc);
                LocalDateTime fechaUTC = fechaLocal.minusHours(gmtOri);
                long minutosAbsolutosUTC = fechaUTC.toEpochSecond(ZoneOffset.UTC) / 60;

                // Expandir arreglos dinámicamente en caso extremo de superar los 20 millones
                if (numEnvios >= envioOrigen.length) {
                    System.err.println("⚠️ LÍMITE ALCANZADO: ¡Hay más de 20 millones de envíos!");
                    break;
                }

                envioOrigen[numEnvios] = idOrigen;
                envioDestino[numEnvios] = idDestino;
                envioMaletas[numEnvios] = cantMaletas;
                envioRegistroUTC[numEnvios] = minutosAbsolutosUTC;

                boolean mismoContinente = (continenteAero[idOrigen] == continenteAero[idDestino]);
                envioDeadlineUTC[numEnvios] = minutosAbsolutosUTC + (mismoContinente ? 1440 : 2880);

                numEnvios++;

                // Pequeño log para saber que el programa sigue vivo procesando millones
                if (numEnvios % 1_000_000 == 0) {
                    System.out.println("   ... " + (numEnvios / 1_000_000) + " millones de envíos en RAM...");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error leyendo " + archivo.getName() + ": " + e.getMessage());
        }
    }
}