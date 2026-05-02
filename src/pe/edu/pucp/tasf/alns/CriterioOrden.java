package pe.edu.pucp.tasf.alns;

/**
 * CriterioOrden — Define el orden de procesamiento de envíos en el ALNS.
 *
 * <p>El orden determina qué envíos se priorizan cuando los recursos de la red son limitados.
 * 
 * <h3>Criterios disponibles:</h3>
 * <ul>
 *   <li><b>FIFO</b> — First In, First Out: por releaseUTC ascendente (el que llegó antes, sale antes).</li>
 *   <li><b>EDF</b> — Earliest Deadline First: por deadlineUTC ascendente (envíos más urgentes primero).</li>
 *   <li><b>ALEATORIO</b> — Permutación aleatoria con Fisher-Yates (reproducible con semilla).</li>
 * </ul>
 */
public enum CriterioOrden {

    /**
     * FIFO — First In, First Out: orden por registro UTC ascendente.
     * Prioriza equidad y orden de llegada.
     */
    FIFO("FIFO — por releaseUTC (orden de llegada)"),

    /**
     * EDF — Earliest Deadline First: orden por deadline ascendente.
     * Prioriza los envíos más urgentes para maximizar el cumplimiento de plazos.
     */
    EDF("EDF — por deadlineUTC (urgencia)"),

    /**
     * Permutación aleatoria con Fisher-Yates (semilla fija → reproducible).
     * Maximize diversidad sin sesgo por ID.
     */
    ALEATORIO("Aleatorio — Fisher-Yates (sin sesgo)");

    /** Descripción legible para impresión en consola. */
    public final String descripcion;

    CriterioOrden(String descripcion) {
        this.descripcion = descripcion;
    }
}
