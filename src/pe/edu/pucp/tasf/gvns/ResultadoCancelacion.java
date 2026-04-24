package pe.edu.pucp.tasf.gvns;

/**
 * DTO inmutable que resume el resultado de una operación de cancelación
 * (vuelo o aeropuerto) ejecutada en tiempo real desde la interfaz web.
 *
 * <ul>
 *   <li>{@link #afectados}  — envíos cuya ruta fue interrumpida por la cancelación.</li>
 *   <li>{@link #reruteados} — de los afectados, cuántos encontraron ruta alternativa.</li>
 *   <li>{@link #sinRuta}    — quedan en el pool de rechazados hasta la próxima ejecución GVNS.</li>
 * </ul>
 *
 * Para restauraciones: {@code afectados} = 0; {@code reruteados} = envíos del pool
 * que pudieron ser re-ruteados ahora que el recurso está disponible de nuevo.
 */
public final class ResultadoCancelacion {

    public final int afectados;
    public final int reruteados;
    public final int sinRuta;

    public ResultadoCancelacion(int afectados, int reruteados, int sinRuta) {
        this.afectados  = afectados;
        this.reruteados = reruteados;
        this.sinRuta    = sinRuta;
    }

    @Override
    public String toString() {
        return String.format(
                "Afectados=%d | Re-ruteados=%d | Sin ruta=%d",
                afectados, reruteados, sinRuta);
    }
}
