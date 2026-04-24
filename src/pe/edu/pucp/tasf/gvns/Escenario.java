package pe.edu.pucp.tasf.gvns;

/**
 * Escenario — Define los tres modos de simulación del enunciado Tasf.B2B (2026-1).
 *
 * <p>Uso en {@link Main}: cambiar la constante {@code ESCENARIO} para elegir el
 * modo de ejecución. El planificador y la carga de datos se adaptan automáticamente.
 *
 * <ul>
 *   <li>{@link #TIEMPO_REAL}  — Ventana de 1 día (operación continua).</li>
 *   <li>{@link #PERIODO_3D}, {@link #PERIODO_5D}, {@link #SEMANA} — Ventana fija.
 *       Deben ejecutarse en 30–90 minutos según el enunciado.</li>
 *   <li>{@link #COLAPSO} — Extiende la ventana un día a la vez hasta que la red
 *       satura (tasa de rechazo supera {@code UMBRAL_COLAPSO}).</li>
 * </ul>
 */
public enum Escenario {

    /**
     * Operación día a día en tiempo real.
     * Procesa únicamente los envíos registrados durante el día de inicio.
     * Equivalente a una ventana de 1440 minutos (1 día UTC).
     */
    TIEMPO_REAL ("Tiempo real — 1 día",    1),

    /** Simulación de período de 3 días. */
    PERIODO_3D  ("Simulación período — 3 días",    3),

    /** Simulación de período de 5 días. */
    PERIODO_5D  ("Simulación período — 5 días",    5),

    /** Simulación de período de 1 semana (7 días). */
    SEMANA      ("Simulación período — 1 semana",  7),

    /**
     * Simulación hasta el colapso operacional.
     * Procesa un día a la vez y detiene cuando la tasa de rechazo supera
     * el umbral configurado en {@link Main#UMBRAL_COLAPSO}.
     * {@code diasVentana = -1} indica modo incremental.
     */
    COLAPSO     ("Simulación hasta el colapso",   -1);

    /** Nombre legible del escenario (para impresión en consola). */
    public final String nombre;

    /**
     * Número de días de la ventana de simulación.
     * {@code -1} significa modo incremental (escenario COLAPSO).
     */
    public final int diasVentana;

    Escenario(String nombre, int diasVentana) {
        this.nombre      = nombre;
        this.diasVentana = diasVentana;
    }

    /** @return {@code true} si es un escenario de ventana fija (no colapso). */
    public boolean esVentanaFija() {
        return diasVentana > 0;
    }
}
