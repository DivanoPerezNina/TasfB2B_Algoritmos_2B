package pe.edu.pucp.tasf.alns;

public class ConfigExperimentacion {
    // Rutas de archivos
    public static final String AEROPUERTOS_FILE = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
    public static final String PLANES_VUELO_FILE = "data/planes_vuelo.txt";
    public static final String ENVIOS_DIR = "data/_envios_preliminar_/";

    // Fechas
    public static final int FECHA_INICIO_AAAAMMDD = 20260102; // 2026-01-02
    public static final long FECHA_INICIO_UTC = 1735689600L; // Deprecated, use FECHA_INICIO_AAAAMMDD

    // Debug
    // MAX_ENVIOS_DEBUG = 100000 sirve para pruebas rápidas.
    // Para corrida completa se cambia manualmente a 0.
    public static final int MAX_ENVIOS_DEBUG = 0; // 0 = sin límite, >0 detiene lectura en ese número

    // Exportación
    public static final boolean EXPORTAR_CSV = true;

    // Criterios de orden para el ALNS (similar a GVNS)
    // Deja vacío {} para desactivar pruebas con múltiples criterios
    // Ej: {CriterioOrden.FIFO, CriterioOrden.EDF, CriterioOrden.ALEATORIO}
    public static final CriterioOrden[] CRITERIOS_ALNS = {CriterioOrden.FIFO, CriterioOrden.EDF, CriterioOrden.ALEATORIO};

    // Escenarios activos
    public static final boolean EJECUTAR_3D = false;
    public static final boolean EJECUTAR_5D = false;
    public static final boolean EJECUTAR_7D = true;

    // Constantes de negocio
    public static final int MAX_SALTOS = 3;
    public static final int TIEMPO_MINIMO_ESCALA_MINUTOS = 10;
    public static final int SLA_MISMO_CONTINENTE_MINUTOS = 1440; // 24 horas
    public static final int SLA_DISTINTO_CONTINENTE_MINUTOS = 2880; // 48 horas

    // Bloques de días
    public static final int[] BLOQUES_DIAS = {3, 5, 7};

    // ALNS
    public static final int MAX_ITERACIONES_ALNS = 300;
    public static final long TIEMPO_LIMITE_ALNS_MS = 60000; // 1 minuto
    public static final long MAX_TIEMPO_ESCENARIO_MS = 20L * 60L * 1000L; // 20 min
    public static final int MAX_BLOQUES_SEGURIDAD = 500;
    public static final int HORIZONTE_EXTRA_MINUTOS = 2880; // 2 días

    // Condición de colapso operativo antiguo (se mantiene solo para compatibilidad si se usa en otro contexto)
    public static final boolean DETENER_POR_COLAPSO = false;
    public static final int MIN_ENVIOS_ANTES_COLAPSO = 10000;
    public static final int MIN_BLOQUES_ANTES_COLAPSO = 3;
    public static final int BLOQUES_CONSECUTIVOS_COLAPSO = 3;
    public static final double UMBRAL_TASA_CRITICA_COLAPSO = 0.50;

    // Condición de colapso SLA
    public static final boolean DETENER_POR_COLAPSO_SLA = true;
    public static final double UMBRAL_COLAPSO_SLA = 0.50;
    public static final int MIN_ENVIOS_ANTES_COLAPSO_SLA = 10000;
    public static final int MIN_BLOQUES_ANTES_COLAPSO_SLA = 3;
    public static final int BLOQUES_CONSECUTIVOS_COLAPSO_SLA = 3;

    // Modo stress de capacidad
    public static final boolean MODO_STRESS_COLAPSO = false;
    public static final double FACTOR_CAPACIDAD_AEROPUERTO = 1.0;
    public static final double FACTOR_CAPACIDAD_VUELO = 1.0;

    // Pesos para fitness
    public static final double PESO_MUY_ALTO = 1000000.0;
    public static final double PESO_ALTO = 10000.0;
    public static final double PESO_MEDIO = 100.0;
    public static final double PESO_BAJO = 1.0;
}