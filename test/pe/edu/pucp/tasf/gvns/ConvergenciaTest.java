package pe.edu.pucp.tasf.gvns;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests TDD para el historial de convergencia del GVNS.
 *
 * Verifica que ejecutarMejoraGVNS() puebla historialFitness con entradas
 * coherentes que permitan graficar la curva de convergencia en expnum.
 *
 * Red mínima: A→B (cap=1), 1 envío. El GVNS tiene poco que mejorar,
 * pero debe ejecutar al menos algunas iteraciones y registrarlas.
 * Se sobrescribe TIEMPO_LIMITE_MS = 2 s para que los tests sean rápidos.
 */
public class ConvergenciaTest {

    private static final int A = 1, B = 2;
    private GestorDatos datos;

    @Before
    public void setUp() {
        datos = new GestorDatos();
        datos.numAeropuertos    = 2;
        datos.iataAeropuerto[A] = "AAA";
        datos.iataAeropuerto[B] = "BBB";
        datos.gmtAeropuerto [A] = 0;
        datos.gmtAeropuerto [B] = 0;
        datos.continenteAero[A] = 1;
        datos.continenteAero[B] = 2;

        datos.numVuelos = 1;
        datos.vueloOrigen    [0] = A;
        datos.vueloDestino   [0] = B;
        datos.vueloSalidaUTC [0] = 100;
        datos.vueloLlegadaUTC[0] = 200;
        datos.vueloCapacidad [0] = 10;

        datos.numEnvios = 1;
        datos.envioOrigen    [0] = A;
        datos.envioDestino   [0] = B;
        datos.envioMaletas   [0] = 1;
        datos.envioRegistroUTC[0] = 0L;
        datos.envioDeadlineUTC[0] = 2880L;
    }

    /** Crea un planificador con tiempo límite de 2 s (suficiente para tests). */
    private PlanificadorGVNSConcurrente planRapido() {
        PlanificadorGVNSConcurrente plan =
                new PlanificadorGVNSConcurrente(datos, 42L, CriterioOrden.FIFO);
        plan.TIEMPO_LIMITE_MS = 2_000L; // 2 s en vez de 120 s
        return plan;
    }

    @Test
    public void historial_sinEjecutarGVNS_estaVacio() {
        PlanificadorGVNSConcurrente plan = planRapido();
        plan.construirSolucionInicial();
        // No llamamos ejecutarMejoraGVNS()
        assertTrue("Sin ejecutar Fase 3, historial debe estar vacío",
                plan.historialFitness.isEmpty());
    }

    @Test
    public void historial_trasGVNS_tieneEntradas() {
        PlanificadorGVNSConcurrente plan = planRapido();
        plan.construirSolucionInicial();
        plan.ejecutarMejoraGVNS();

        assertFalse("Tras ejecutar Fase 3, historial no debe estar vacío",
                plan.historialFitness.isEmpty());
    }

    @Test
    public void historial_iteracionesCrecientes() {
        PlanificadorGVNSConcurrente plan = planRapido();
        plan.construirSolucionInicial();
        plan.ejecutarMejoraGVNS();

        long iterAnterior = 0;
        for (long[] entrada : plan.historialFitness) {
            long iterActual = entrada[0];
            assertTrue("Iteraciones deben ser estrictamente crecientes",
                    iterActual > iterAnterior);
            iterAnterior = iterActual;
        }
    }

    @Test
    public void historial_tiempoNoNegativo() {
        PlanificadorGVNSConcurrente plan = planRapido();
        plan.construirSolucionInicial();
        plan.ejecutarMejoraGVNS();

        for (long[] entrada : plan.historialFitness) {
            assertTrue("Ms transcurridos no puede ser negativo", entrada[1] >= 0);
        }
    }

    @Test
    public void historial_fitnessNuncaAumenta() {
        // El historial registra el MEJOR fitness hasta cada iteración.
        // Por definición, la curva de convergencia no puede subir.
        PlanificadorGVNSConcurrente plan = planRapido();
        plan.construirSolucionInicial();
        plan.ejecutarMejoraGVNS();

        long fitnessAnterior = Long.MAX_VALUE;
        for (long[] entrada : plan.historialFitness) {
            long fitnessActual = entrada[2];
            assertTrue("Mejor fitness no puede aumentar entre iteraciones",
                    fitnessActual <= fitnessAnterior);
            fitnessAnterior = fitnessActual;
        }
    }

    @Test
    public void historial_cadaEntradaTieneTresCampos() {
        PlanificadorGVNSConcurrente plan = planRapido();
        plan.construirSolucionInicial();
        plan.ejecutarMejoraGVNS();

        for (long[] entrada : plan.historialFitness) {
            assertEquals("Cada entrada debe tener 3 campos: [iteracion, ms, fitness]",
                    3, entrada.length);
        }
    }
}
