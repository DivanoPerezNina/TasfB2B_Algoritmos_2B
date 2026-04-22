package pe.edu.pucp.tasf;

import java.time.ZonedDateTime;

/**
 * Representa un tramo asignado en una ruta de envío.
 */
public class TramoAsignado {
    public GestorDatos.Vuelo vuelo;
    public ZonedDateTime salidaReal;
    public ZonedDateTime llegadaReal;
    public int diaAbsoluto; // día absoluto para instancia

    public TramoAsignado(GestorDatos.Vuelo vuelo, ZonedDateTime salidaReal, ZonedDateTime llegadaReal, int diaAbsoluto) {
        this.vuelo = vuelo;
        this.salidaReal = salidaReal;
        this.llegadaReal = llegadaReal;
        this.diaAbsoluto = diaAbsoluto;
    }
}