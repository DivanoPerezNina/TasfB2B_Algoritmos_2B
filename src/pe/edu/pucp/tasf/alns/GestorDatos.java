package pe.edu.pucp.tasf.alns;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Clase para gestionar la carga de datos del problema de logística aérea.
 * Reutiliza parsers existentes para aeropuertos, vuelos y envíos.
 */
public class GestorDatos {

    // Clases de datos
    public static class Aeropuerto {
        public String iata;
        public int gmtOffset; // en minutos
        public int capacidad; // capacidad de almacenamiento
        public String continente;

        public Aeropuerto(String iata, int gmtOffset, int capacidad, String continente) {
            this.iata = iata;
            this.gmtOffset = gmtOffset;
            this.capacidad = capacidad;
            this.continente = continente;
        }
    }

    public static class Vuelo {
        public String origen;
        public String destino;
        public int salidaMinutos; // minutos del día
        public int llegadaMinutos; // minutos del día
        public int capacidad;

        public Vuelo(String origen, String destino, int salidaMinutos, int llegadaMinutos, int capacidad) {
            this.origen = origen;
            this.destino = destino;
            this.salidaMinutos = salidaMinutos;
            this.llegadaMinutos = llegadaMinutos;
            this.capacidad = capacidad;
        }
    }

    public static class Envio {
        public String id;
        public String origen;
        public String destino;
        public int maletas;
        public ZonedDateTime registroUTC;
        public ZonedDateTime deadlineUTC;

        public Envio(String id, String origen, String destino, int maletas, ZonedDateTime registroUTC, ZonedDateTime deadlineUTC) {
            this.id = id;
            this.origen = origen;
            this.destino = destino;
            this.maletas = maletas;
            this.registroUTC = registroUTC;
            this.deadlineUTC = deadlineUTC;
        }
    }

    // Mapas y listas
    public Map<String, Aeropuerto> aeropuertos = new HashMap<>();
    public List<Vuelo> vuelos = new ArrayList<>();
    public List<Envio> envios = new ArrayList<>();

    // Preprocesados
    public Map<String, List<Vuelo>> vuelosPorOrigen = new HashMap<>();

    /**
     * Carga todos los datos.
     */
    public void cargarDatos(String pathAeropuertos, String pathVuelos, String pathEnviosDir, int maxEnvios) throws IOException {
        cargarAeropuertos(pathAeropuertos);
        cargarVuelos(pathVuelos);
        cargarEnvios(pathEnviosDir, maxEnvios);
        preprocesar();
    }

    public void cargarAeropuertos(String path) throws IOException {
        // Archivo en UTF-16 con BOM
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_16);
        String continenteActual = "Unknown";
        for (String line : lines) {
            String limpia = line.trim();
            String lower = limpia.toLowerCase(Locale.ROOT);
            if (lower.contains("america")) {
                continenteActual = "America";
                continue;
            }
            if (lower.contains("europa")) {
                continenteActual = "Europa";
                continue;
            }
            if (lower.contains("asia")) {
                continenteActual = "Asia";
                continue;
            }

            if (line.trim().isEmpty() || !line.matches("^\\d{2}\\s+.*")) continue;
            try {
                // Formato: 12   EBCI   Bruselas            Belgica         brus    +2     440
                String[] partes = line.trim().split("\\s+");
                if (partes.length >= 7) {
                    // partes[0] = ID numérico
                    // partes[1] = IATA (4 letras)
                    String iata = partes[1];
                    if (!iata.matches("[A-Z]{4}")) continue; // validar IATA
                    
                    // Las columnas numéricas al final son GMT y Capacidad
                    int cap = Integer.parseInt(partes[6]); // capacidad 
                    int gmt = Integer.parseInt(partes[5]); // GMT
                    
                    String continente = continenteActual;
                    aeropuertos.put(iata, new Aeropuerto(iata, gmt * 60, cap, continente));
                }
            } catch (NumberFormatException e) {
                // Saltar líneas no parseables
            }
        }
    }

    private void cargarVuelos(String path) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("-");
            if (parts.length == 5) {
                String orig = parts[0];
                String dest = parts[1];
                String[] salida = parts[2].split(":");
                String[] llegada = parts[3].split(":");
                int cap = Integer.parseInt(parts[4]);
                int salMin = Integer.parseInt(salida[0]) * 60 + Integer.parseInt(salida[1]);
                int llegMin = Integer.parseInt(llegada[0]) * 60 + Integer.parseInt(llegada[1]);
                vuelos.add(new Vuelo(orig, dest, salMin, llegMin, cap));
            }
        }
    }

    private void cargarEnvios(String dirPath, int maxEnvios) throws IOException {
        Path dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) return;
        int count = 0;
        long startTime = System.currentTimeMillis();
        
        for (Path file : Files.newDirectoryStream(dir, "*_envios_*.txt")) {
            String filename = file.getFileName().toString();
            String origen = filename.replaceAll("^_envios_", "").replaceAll("_\\.txt$", "");
            
            // Usar BufferedReader en lugar de cargar todo en memoria
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), 65536)) {
                String line;
                int countArchivo = 0;
                while ((line = reader.readLine()) != null && count < maxEnvios) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.split("-");
                    if (parts.length >= 7) {
                        try {
                            String id = parts[0];
                            String fecha = parts[1];
                            int hh = Integer.parseInt(parts[2]);
                            int mm = Integer.parseInt(parts[3]);
                            String destino = parts[4];
                            int maletas = Integer.parseInt(parts[5]);
                            
                            int year = Integer.parseInt(fecha.substring(0, 4));
                            int month = Integer.parseInt(fecha.substring(4, 6));
                            int day = Integer.parseInt(fecha.substring(6, 8));
                            
                            ZonedDateTime registro = ZonedDateTime.of(year, month, day, hh, mm, 0, 0, ZoneOffset.UTC);
                            String continenteOrigen = "Unknown";
                            Aeropuerto aeroOrigen = aeropuertos.get(origen);
                            if (aeroOrigen != null && aeroOrigen.continente != null) {
                                continenteOrigen = aeroOrigen.continente;
                            }

                            String continenteDestino = "Unknown";
                            Aeropuerto aeroDestino = aeropuertos.get(destino);
                            if (aeroDestino != null && aeroDestino.continente != null) {
                                continenteDestino = aeroDestino.continente;
                            }

                            boolean mismoContinente = continenteOrigen.equalsIgnoreCase(continenteDestino)
                                    && !"Unknown".equalsIgnoreCase(continenteOrigen);
                            ZonedDateTime deadline = registro.plusHours(mismoContinente ? 24 : 48);
                            
                            envios.add(new Envio(id, origen, destino, maletas, registro, deadline));
                            count++;
                            countArchivo++;
                            
                            // Mostrar progreso cada 50K envíos
                            if (count % 50000 == 0) {
                                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                                System.out.printf("Cargados: %d envíos en %d seg (%.0f envíos/seg)%n", 
                                    count, elapsed, count / (double) elapsed);
                            }
                        } catch (NumberFormatException | java.time.DateTimeException e) {
                            // Saltar líneas malformadas
                        }
                    }
                }
                if (countArchivo > 0) {
                    System.out.println("  " + filename + ": " + countArchivo);
                }
            }
            if (count >= maxEnvios) break;
        }
        System.out.printf("Total cargado: %d envíos en %.2f segundos%n", count, (System.currentTimeMillis() - startTime) / 1000.0);
    }

    private void preprocesar() {
        for (Vuelo v : vuelos) {
            vuelosPorOrigen.computeIfAbsent(v.origen, k -> new ArrayList<>()).add(v);
        }
        // Ordenar por salida
        for (List<Vuelo> list : vuelosPorOrigen.values()) {
            list.sort(Comparator.comparingInt(v -> v.salidaMinutos));
        }
    }
}