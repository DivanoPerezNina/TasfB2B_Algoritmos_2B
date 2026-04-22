package pe.edu.pucp.tasf.alns;
import java.util.HashMap;
import java.util.Map;

/**
 * Clase para manejar configuraciones de experimentación, incluyendo argumentos CLI.
 */
public class ConfigExperimentacion {
    public String pathAeropuertos = "data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt";
    public String pathVuelos = "data/planes_vuelo.txt";
    public String pathEnviosDir = "data/_envios_preliminar_";
    public int maxEnvios = Integer.MAX_VALUE; // Limitado por memoria - para datasets completos usar --maxEnvios=Integer.MAX_VALUE con suficiente RAM
    public int iteraciones = 40000;
    public int threads = 4;
    public long seed = 12345;
    public double temperaturaInicial = 1000.0;
    public double coolingRate = 0.999;
    public int maxSaltos = 3;
    public int tiempoMinEscalaMin = 10;
    public boolean sequentialOnly = false; // Opción para ejecutar solo versión secuencial (más rápido para pruebas)

    // Pesos para función objetivo
    public long pesoUnassigned = 1_000_000;
    public long pesoLateRequests = 100_000;
    public long pesoLateBags = 1_000;
    public long pesoTotalLateness = 1;
    public long pesoTotalHops = 10;
    public long pesoStorageOveruse = 100;

    public ConfigExperimentacion(String[] args) {
        Map<String, String> argMap = parseArgs(args);
        if (argMap.containsKey("aeropuertos")) pathAeropuertos = argMap.get("aeropuertos");
        if (argMap.containsKey("vuelos")) pathVuelos = argMap.get("vuelos");
        if (argMap.containsKey("envios")) pathEnviosDir = argMap.get("envios");
        if (argMap.containsKey("maxEnvios")) maxEnvios = Integer.parseInt(argMap.get("maxEnvios"));
        if (argMap.containsKey("iteraciones")) iteraciones = Integer.parseInt(argMap.get("iteraciones"));
        if (argMap.containsKey("threads")) threads = Integer.parseInt(argMap.get("threads"));
        if (argMap.containsKey("seed")) seed = Long.parseLong(argMap.get("seed"));
        if (argMap.containsKey("sequential-only")) sequentialOnly = true;
        
        // Auto-tune moderado para datasets pequeños: reducir iteraciones conservadoramente
        if (maxEnvios <= 100) {
            iteraciones = Math.min(iteraciones, 5000);  // 5K es suficiente para 100 envíos
        } else if (maxEnvios <= 500) {
            iteraciones = Math.min(iteraciones, 12000);
        } else if (maxEnvios <= 1000) {
            iteraciones = Math.min(iteraciones, 22000);
        } else if (maxEnvios <= 5000) {
            iteraciones = Math.min(iteraciones, 32000);
        }
    }

    private Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                String[] parts = arg.substring(2).split("=");
                if (parts.length == 2) {
                    map.put(parts[0], parts[1]);
                }
            }
        }
        return map;
    }
}