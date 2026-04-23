package pe.edu.pucp.tasf.gvns;

public class Main {
    public static void main(String[] args) {
        GestorDatos datos = new GestorDatos();
        System.out.println("--- INICIANDO LECTURA DEL TABLERO ---");

        datos.cargarAeropuertos("datos/aeropuertos.txt");

        if (datos.numAeropuertos == 0) {
            System.err.println("No se cargaron aeropuertos. Revisa el archivo.");
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
            System.out.println("No se cargaron vuelos.");
        }

        System.out.println("\n--- LEYENDO ENVIOS ---");
        datos.cargarTodosLosEnvios("datos/_envios_preliminar_");

        if (datos.numEnvios > 0) {
            int e = 0;
            System.out.println("Envio 0 (Origen):          " + datos.envioOrigen[e]);
            System.out.println("Destino:                   " + datos.envioDestino[e]);
            System.out.println("Maletas:                   " + datos.envioMaletas[e]);
            System.out.println("Registro (UTC Absoluto):   " + datos.envioRegistroUTC[e] + " min");
            System.out.println("Deadline (UTC Absoluto):   " + datos.envioDeadlineUTC[e] + " min");
            System.out.println("Minutos para entregar:     "
                    + (datos.envioDeadlineUTC[e] - datos.envioRegistroUTC[e]));
        }

        // ── Análisis de cobertura del grafo antes de resolver ─────────────────
        System.out.println("\n--- ANALISIS DE RED ---");
        AnalizadorRed.analizarCobertura(datos);

        // ── FASE 2: Construcción de solución inicial — mínimo tránsito ────────
        System.out.println("\n--- FASE 2: SOLUCION INICIAL (MULTIHILO) ---");
        PlanificadorGVNSConcurrente plan = new PlanificadorGVNSConcurrente(datos);

        long tiempoInicio = System.currentTimeMillis();
        plan.construirSolucionInicial();
        long tiempoFin = System.currentTimeMillis();
        double tiempoGreedy = (tiempoFin - tiempoInicio) / 1000.0;
        System.out.printf("Tiempo de CPU de la Solucion Inicial: %.2f segundos%n", tiempoGreedy);

        // Capturar f(x) antes de GVNS para la tabla comparativa
        long fGreedy = plan.calcularTransitoTotal();
        System.out.printf("Transito total Fase 2: %,d min%n", fGreedy);

        // Distribución de tramos en la solución inicial
        AnalizadorRed.analizarSolucion(datos, plan.solucionVuelos);

        // ── FASE 3: Optimización GVNS ─────────────────────────────────────────
        System.out.println("\n--- FASE 3: OPTIMIZACION GVNS ---");
        long inicioGVNS = System.currentTimeMillis();
        int salvados = plan.ejecutarMejoraGVNS();
        long finGVNS = System.currentTimeMillis();
        double tiempoGVNS = (finGVNS - inicioGVNS) / 1000.0;
        System.out.printf("Tiempo de CPU GVNS: %.2f segundos%n", tiempoGVNS);

        // Tabla comparativa Fase 2 vs Fase 3
        long fGVNS = plan.calcularTransitoTotal();
        AnalizadorRed.compararFases(fGreedy, fGVNS, salvados);

        // ── Exportar métricas CSV ─────────────────────────────────────────────
        plan.exportarResultadosCSV("resultados_escenario_concurrente.csv",
                fGreedy, tiempoGreedy, tiempoGVNS, salvados);

        // ── AUDITORÍA MATEMÁTICA de la solución ───────────────────────────────
        System.out.println("\n--- AUDITORIA DE SOLUCION ---");
        AuditorRutas.auditarSolucion(
                datos,
                plan.solucionVuelos,
                plan.solucionDias,
                plan.ocupacionVuelos);

        // ── EXPORTACIÓN JSON para frontend React/Vue ──────────────────────────
        System.out.println("\n--- EXPORTACION VISUAL ---");
        int enviosRuteados   = plan.enviosExitosos.get();
        int enviosRechazados = datos.numEnvios - enviosRuteados;

        ExportadorVisual.exportarJSON(
                "visualizacion_casos.json",
                plan,
                datos,
                enviosRuteados,
                enviosRechazados,
                tiempoGreedy,
                tiempoGVNS,
                salvados);

        System.out.println("\n--- FIN ---");
    }
}
