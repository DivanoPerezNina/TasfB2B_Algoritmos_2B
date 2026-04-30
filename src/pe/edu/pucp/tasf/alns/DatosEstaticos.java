package pe.edu.pucp.tasf.alns;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatosEstaticos {
    // Aeropuertos
    public static String[] airportCode;
    public static int[] airportGmt;
    public static String[] airportContinent; // Asumir inferido o hardcodeado
    public static int[] airportCapacity;
    public static double[] airportLat;
    public static double[] airportLon;
    public static Map<String, Integer> airportCodeToId = new HashMap<>();
    public static String[] airportIdToCode;

    // Vuelos
    public static int[] flightOrigin;
    public static int[] flightDestination;
    public static int[] flightDepartureUTCMinuteOfDay;
    public static int[] flightArrivalUTCMinuteOfDay;
    public static int[] flightCapacity;
    public static int numFlights;

    public static void cargarAeropuertos() throws IOException {
        List<String> codeList = new ArrayList<>();
        List<Integer> gmtList = new ArrayList<>();
        List<String> continentList = new ArrayList<>();
        List<Integer> capacityList = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(ConfigExperimentacion.AEROPUERTOS_FILE), StandardCharsets.UTF_16))) {
            String line;
            while ((line = br.readLine()) != null) {
                AirportData airport = parseAirportLine(line);
                if (airport == null) continue;
                codeList.add(airport.code);
                gmtList.add(airport.gmt);
                capacityList.add(airport.capacity);
                continentList.add(airport.continent);
            }
        }

        int count = codeList.size();
        airportCode = new String[count];
        airportGmt = new int[count];
        airportContinent = new String[count];
        airportCapacity = new int[count];
        airportLat = new double[count];
        airportLon = new double[count];
        airportIdToCode = new String[count];

        for (int i = 0; i < count; i++) {
            String code = codeList.get(i);
            airportCode[i] = code;
            airportGmt[i] = gmtList.get(i);
            airportContinent[i] = continentList.get(i);
            int originalCapacity = capacityList.get(i);
            airportCapacity[i] = ConfigExperimentacion.MODO_STRESS_COLAPSO
                    ? Math.max(1, (int) Math.round(originalCapacity * ConfigExperimentacion.FACTOR_CAPACIDAD_AEROPUERTO))
                    : originalCapacity;
            airportLat[i] = 0.0;
            airportLon[i] = 0.0;
            airportIdToCode[i] = code;
            airportCodeToId.put(code, i);
        }

        System.out.println("Cargados " + count + " aeropuertos");
    }

    private static AirportData parseAirportLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        String upper = trimmed.toUpperCase();
        if (upper.startsWith("*") || upper.startsWith("PDDS")) return null;
        if (upper.startsWith("GMT") || upper.contains(" GMT ")) return null;

        String normalized = trimmed
                .replace("\uFEFF", "")
                .replace('"', ' ')
                .replace((char)0xBA, ' ')
                .replace((char)0xB0, ' ')
                .replaceAll("\\s+", " ");
        String[] tokens = normalized.split(" ");

        int codeIndex = -1;
        String code = null;
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].replaceAll("[^A-Za-z]", "");
            if (token.length() == 4 && token.equals(token.toUpperCase())) {
                code = token;
                codeIndex = i;
                break;
            }
        }
        if (code == null) return null;

        int gmtIndex = -1;
        Integer gmt = null;
        for (int i = codeIndex + 1; i < tokens.length; i++) {
            String candidate = tokens[i];
            if (candidate.matches("[-+]?[0-9]{1,2}")) {
                int value = Integer.parseInt(candidate);
                if (value >= -12 && value <= 14) {
                    gmt = value;
                    gmtIndex = i;
                    break;
                }
            }
        }
        if (gmt == null) return null;

        Integer capacity = null;
        for (int i = gmtIndex + 1; i < tokens.length; i++) {
            String candidate = tokens[i];
            if (candidate.matches("[0-9]+")) {
                capacity = Integer.parseInt(candidate);
                break;
            }
        }
        if (capacity == null) return null;

        String continent = inferContinent(code);
        return new AirportData(code, gmt, capacity, continent);
    }

    private static class AirportData {
        final String code;
        final int gmt;
        final int capacity;
        final String continent;

        AirportData(String code, int gmt, int capacity, String continent) {
            this.code = code;
            this.gmt = gmt;
            this.capacity = capacity;
            this.continent = continent;
        }
    }

    private static double parseCoordinate(String line, String prefix) {
        if (line == null || prefix == null) return 0.0;
        int idx = line.indexOf(prefix);
        if (idx < 0) return 0.0;
        int start = idx + prefix.length();
        String rest = line.substring(start).trim();
        if (rest.isEmpty()) return 0.0;
        String token = rest.split("\\s+")[0].replaceAll("[^0-9+eE.-]", "");
        if (token.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String inferContinent(String code) {
        String upper = code.toUpperCase();
        if (upper.startsWith("E") || upper.startsWith("L") || upper.startsWith("B") || upper.startsWith("F") || upper.startsWith("G") || upper.startsWith("D")) {
            return "Europa";
        }
        if (upper.startsWith("V") || upper.startsWith("Z") || upper.startsWith("O") || upper.startsWith("H") || upper.startsWith("W") || upper.startsWith("P") || upper.startsWith("R")) {
            return "Asia";
        }
        return "America";
    }

    public static void cargarVuelos() throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(ConfigExperimentacion.PLANES_VUELO_FILE));
        String line;
        int count = 0;
        while ((line = br.readLine()) != null) {
            if (!line.trim().isEmpty()) count++;
        }
        br.close();

        flightOrigin = new int[count];
        flightDestination = new int[count];
        flightDepartureUTCMinuteOfDay = new int[count];
        flightArrivalUTCMinuteOfDay = new int[count];
        flightCapacity = new int[count];
        numFlights = count;

        br = new BufferedReader(new FileReader(ConfigExperimentacion.PLANES_VUELO_FILE));
        int i = 0;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            // SKBO-SEQM-19:01-19:48-0340
            String[] parts = line.split("-");
            String orig = parts[0];
            String dest = parts[1];
            String dep = parts[2];
            String arr = parts[3];
            int cap = Integer.parseInt(parts[4]);

            int origId = airportCodeToId.get(orig);
            int destId = airportCodeToId.get(dest);

            int depMin = timeToUTCMinuteOfDay(dep, airportGmt[origId]);
            int arrMin = timeToUTCMinuteOfDay(arr, airportGmt[destId]);
            if (arrMin <= depMin) arrMin += 1440; // Día siguiente

            flightOrigin[i] = origId;
            flightDestination[i] = destId;
            flightDepartureUTCMinuteOfDay[i] = depMin;
            flightArrivalUTCMinuteOfDay[i] = arrMin;
            flightCapacity[i] = ConfigExperimentacion.MODO_STRESS_COLAPSO
                    ? Math.max(1, (int) Math.round(cap * ConfigExperimentacion.FACTOR_CAPACIDAD_VUELO))
                    : cap;
            i++;
        }
        br.close();
    }

    private static int timeToUTCMinuteOfDay(String time, int gmt) {
        String[] hm = time.split(":");
        int h = Integer.parseInt(hm[0]);
        int m = Integer.parseInt(hm[1]);
        int localMin = h * 60 + m;
        int utcMin = localMin - gmt * 60;
        if (utcMin < 0) utcMin += 1440;
        if (utcMin >= 1440) utcMin -= 1440;
        return utcMin;
    }

    public static void cargarDatos() throws IOException {
        cargarAeropuertos();
        cargarVuelos();
    }

    /**
     * Calcula el número de minutos desde 1970-01-01 00:00:00 UTC.
     */
    public static long calcularEpochMinutos(int year, int month, int day, int hour, int minute, int second) {
        long totalDays = 0;
        
        // Contar días desde 1970-01-01
        for (int y = 1970; y < year; y++) {
            totalDays += isLeapYear(y) ? 366 : 365;
        }
        
        // Contar días desde inicio de año
        int[] daysInMonth = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if (isLeapYear(year)) {
            daysInMonth[2] = 29;
        }
        for (int m = 1; m < month; m++) {
            totalDays += daysInMonth[m];
        }
        totalDays += day - 1;
        
        long totalMinutes = totalDays * 1440L + hour * 60L + minute;
        return totalMinutes;
    }

    private static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
    }
}