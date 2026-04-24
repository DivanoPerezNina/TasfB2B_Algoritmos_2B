package pe.edu.pucp.tasf.gvns;

/**
 * CriterioOrden — Define el orden de procesamiento de envíos en la Fase 2.
 *
 * <p>El orden determina qué envíos "ganan" los asientos disponibles cuando la
 * capacidad de la red es limitada. Con capacidad suficiente, el criterio no
 * afecta la tasa de éxito, pero sí el tránsito total (f(x)).
 *
 * <p>Configurado en {@link Main} mediante la constante {@code CRITERIO_ORDEN}.
 *
 * <h3>Comparación de criterios</h3>
 * <table border="1">
 *   <tr><th>Criterio</th><th>Ventaja</th><th>Desventaja</th></tr>
 *   <tr>
 *     <td>{@link #ALEATORIO}</td>
 *     <td>Sin sesgo por ID; máxima diversidad entre ejecuciones (paper §3.4)</td>
 *     <td>No prioriza envíos urgentes</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #EDF}</td>
 *     <td>Minimiza incumplimiento de deadlines; lógica de negocio clara</td>
 *     <td>Perjudica envíos con ventanas amplias aunque estén listos antes</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #FIFO}</td>
 *     <td>Justo por orden de llegada; fácil de justificar ante el cliente</td>
 *     <td>No diferencia urgencia; puede dejar sin ruta a envíos de deadline corto</td>
 *   </tr>
 * </table>
 */
public enum CriterioOrden {

    /**
     * Permutación aleatoria con Fisher-Yates (semilla fija → reproducible).
     * Equivale al proceso descrito en Polat et al. (2026) §3.4:
     * "the sequence of orders is established through a random generation".
     */
    ALEATORIO ("Aleatorio (Fisher-Yates, paper §3.4)"),

    /**
     * Earliest Deadline First: primero el envío con deadline más próximo.
     * Maximiza el cumplimiento de plazos cuando la red está saturada.
     * Recomendado para escenario {@link Escenario#COLAPSO}.
     */
    EDF       ("Earliest Deadline First — menor deadline primero"),

    /**
     * First In, First Out: primero el envío con registro UTC más antiguo.
     * Garantiza equidad por orden de llegada; útil para el escenario
     * {@link Escenario#TIEMPO_REAL} donde el tiempo de registro es relevante.
     */
    FIFO      ("FIFO — registro UTC más antiguo primero");

    /** Descripción legible para impresión en consola. */
    public final String descripcion;

    CriterioOrden(String descripcion) {
        this.descripcion = descripcion;
    }
}
