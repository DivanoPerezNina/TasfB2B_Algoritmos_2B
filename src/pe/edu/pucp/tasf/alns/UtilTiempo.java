package pe.edu.pucp.tasf.alns;
import java.time.*;
import java.time.temporal.ChronoUnit;

/**
 * Utilidades para manejo de tiempo.
 */
public class UtilTiempo {
    public static ZonedDateTime calcularSalidaReal(GestorDatos.Vuelo vuelo, ZonedDateTime registro, GestorDatos.Aeropuerto aeroOrigen) {
        // Calcular el día absoluto basado en registro
        LocalDate diaRegistro = registro.toLocalDate();
        int diaAbs = (int) ChronoUnit.DAYS.between(LocalDate.of(2024, 1, 1), diaRegistro); // ejemplo base
        // Salida en el día del registro o siguiente si ya pasó
        LocalTime salidaTemplate = LocalTime.of(vuelo.salidaMinutos / 60, vuelo.salidaMinutos % 60);
        ZonedDateTime salidaTentativa = ZonedDateTime.of(diaRegistro, salidaTemplate, ZoneOffset.UTC).plusMinutes(aeroOrigen.gmtOffset);
        if (salidaTentativa.isBefore(registro)) {
            salidaTentativa = salidaTentativa.plusDays(1);
            diaAbs++;
        }
        return salidaTentativa;
    }

    public static ZonedDateTime calcularLlegadaReal(ZonedDateTime salida, GestorDatos.Vuelo vuelo, GestorDatos.Aeropuerto aeroDestino) {
        if (aeroDestino == null) {
            // Si no existe el aeropuerto destino, asumimos GMT+0
            long duracionMin = vuelo.llegadaMinutos - vuelo.salidaMinutos;
            if (duracionMin < 0) duracionMin += 1440;
            return salida.plusMinutes(duracionMin);
        }
        long duracionMin = vuelo.llegadaMinutos - vuelo.salidaMinutos;
        if (duracionMin < 0) duracionMin += 1440; // siguiente día
        return salida.plusMinutes(duracionMin);
    }

    public static boolean esFactible(ZonedDateTime llegadaPrev, ZonedDateTime salidaSig, int minEscala) {
        return Duration.between(llegadaPrev, salidaSig).toMinutes() >= minEscala;
    }
    
    public static boolean esFactible(RutaEnvio ruta, ZonedDateTime deadline) {
        if (ruta.tramos.isEmpty()) return false;
        ZonedDateTime llegadaFinal = ruta.tramos.get(ruta.tramos.size() - 1).llegadaReal;
        return !llegadaFinal.isAfter(deadline);
    }
}