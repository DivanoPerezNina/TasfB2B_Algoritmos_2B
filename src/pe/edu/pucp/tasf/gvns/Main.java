package pe.edu.pucp.tasf.gvns;

public class Main {
    public static void main(String[] args) {
        GestorDatos datos = new GestorDatos();
        System.out.println("--- INICIANDO LECTURA DEL TABLERO ---");

        datos.cargarAeropuertos("datos/aeropuertos.txt");

        if (datos.numAeropuertos == 0) {
            System.err.println("❌ No se cargaron aeropuertos. Revisa el archivo.");
            return;
        }

        datos.cargarVuelos("datos/vuelos.txt");

        System.out.println("\n--- PRUEBA DE ARISTAS ---");

        if (datos.numVuelos > 0) {
            int idVueloPrueba = 0;
            int idOri = datos.vueloOrigen[idVueloPrueba];
            int idDes = datos.vueloDestino[idVueloPrueba];
            System.out.println("Vuelo 0: " + idOri + " -> " + idDes);
            System.out.println("Sale  (UTC min): " + datos.vueloSalidaUTC[idVueloPrueba]);
            System.out.println("Llega (UTC min): " + datos.vueloLlegadaUTC[idVueloPrueba]);
            System.out.println("Capacidad: " + datos.vueloCapacidad[idVueloPrueba] + " maletas");
        } else {
            System.out.println("⚠️ No se cargaron vuelos.");
        }
        // ... (después de probar las aristas) ...

        System.out.println("\n--- LEYENDO ENVÍOS (CARROS) ---");
        // Le pasamos la ruta de la carpeta donde están todos los _envios_XXX_.txt
        datos.cargarTodosLosEnvios("datos/_envios_preliminar_");

        if (datos.numEnvios > 0) {
            int e = 0; // Envío de prueba
            System.out.println("Envío 0 (Origen): " + datos.envioOrigen[e]);
            System.out.println("Destino: " + datos.envioDestino[e]);
            System.out.println("Maletas: " + datos.envioMaletas[e]);
            System.out.println("Registro (UTC Absoluto): " + datos.envioRegistroUTC[e] + " min");
            System.out.println("Deadline (UTC Absoluto): " + datos.envioDeadlineUTC[e] + " min");
            System.out.println("Minutos para entregar: " + (datos.envioDeadlineUTC[e] - datos.envioRegistroUTC[e]));
        }

        /*
        System.out.println("\n--- FASE 2: SOLUCIÓN INICIAL GOLOSA ---");
        PlanificadorGVNS plan = new PlanificadorGVNS(datos);
        long tiempoInicio = System.currentTimeMillis();
        plan.construirSolucionInicial();
        long tiempoFin = System.currentTimeMillis();
        System.out.printf("⏱️ Tiempo de CPU de la Solución Inicial: %.2f segundos%n", (tiempoFin - tiempoInicio) / 1000.0);

        // --- AQUI ENTRA LA FASE 3 ---
        System.out.println("\n--- FASE 3: OPTIMIZACIÓN GVNS ---");
        long inicioGVNS = System.currentTimeMillis();
        plan.ejecutarMejoraGVNS();
        long finGVNS = System.currentTimeMillis();
        System.out.printf("⏱️ Tiempo de CPU GVNS: %.2f segundos%n", (finGVNS - inicioGVNS) / 1000.0);
*/
        System.out.println("\n--- FASE 2: SOLUCIÓN INICIAL (MULTIHILO) ---");
        PlanificadorGVNSConcurrente plan = new PlanificadorGVNSConcurrente(datos);

        long tiempoInicio = System.currentTimeMillis();
        plan.construirSolucionInicial();
        long tiempoFin = System.currentTimeMillis();
        double tiempoGreedy = (tiempoFin - tiempoInicio) / 1000.0;
        System.out.printf("⏱️ Tiempo de CPU de la Solución Inicial: %.2f segundos%n", tiempoGreedy);

        System.out.println("\n--- FASE 3: OPTIMIZACIÓN GVNS ---");
        long inicioGVNS = System.currentTimeMillis();
        int salvados = plan.ejecutarMejoraGVNS();
        long finGVNS = System.currentTimeMillis();
        double tiempoGVNS = (finGVNS - inicioGVNS) / 1000.0;
        System.out.printf("⏱️ Tiempo de CPU GVNS: %.2f segundos%n", tiempoGVNS);

        // Exportamos los datos de este escenario
        plan.exportarResultadosCSV("resultados_escenario_concurrente.csv", tiempoGreedy, tiempoGVNS, salvados);
        // plan.exportarResultadosCSV("resultados_escenario_base.csv", tiempoGreedy, tiempoGVNS, salvados);
    }
}