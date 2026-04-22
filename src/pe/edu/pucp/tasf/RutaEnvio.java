package pe.edu.pucp.tasf;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Representa la ruta asignada a un envío.
 */
public class RutaEnvio {
    public enum Estado { ON_TIME, LATE, UNASSIGNED }

    public GestorDatos.Envio envio;
    public List<TramoAsignado> tramos = new ArrayList<>();
    public Estado estado = Estado.UNASSIGNED;
    public long tardanzaMin = 0;
    public int hops = 0;
    public long waitingTimeMin = 0;

    public RutaEnvio(GestorDatos.Envio envio) {
        this.envio = envio;
    }

    public void calcularMetricas() {
        if (tramos.isEmpty()) {
            estado = Estado.UNASSIGNED;
            return;
        }
        hops = tramos.size();
        ZonedDateTime llegadaFinal = tramos.get(tramos.size() - 1).llegadaReal;
        if (llegadaFinal.isAfter(envio.deadlineUTC)) {
            estado = Estado.LATE;
            tardanzaMin = Duration.between(envio.deadlineUTC, llegadaFinal).toMinutes();
        } else {
            estado = Estado.ON_TIME;
        }
        // Waiting time: tiempo desde registro hasta salida del primer vuelo
        if (!tramos.isEmpty()) {
            ZonedDateTime salidaPrimera = tramos.get(0).salidaReal;
            waitingTimeMin = Duration.between(envio.registroUTC, salidaPrimera).toMinutes();
        }
    }
}