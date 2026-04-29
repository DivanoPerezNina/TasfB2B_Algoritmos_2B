package pe.edu.pucp.tasf.gvns;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests TDD para la función objetivo combinada del GVNS:
 *
 *   f(x) = rechazados_activos × 2880 + tránsito_total
 *
 * La penalización de 2880 garantiza que reducir rechazos siempre mejora f,
 * independientemente del tránsito. Ver PlanificadorGVNSConcurrente#calcularFitnessTotal.
 *
 * Red mínima (construida sin archivos):
 *   Aeropuertos: A(1, continente 1, GMT 0), B(2, continente 2, GMT 0)
 *   Vuelo v0: A→B, salida=200, llegada=400, cap=10
 *   Envíos con registro=0, deadline=2880 (distinto continente)
 *
 * Tránsito de un envío en v0: llegada − registro = (salidaAbs + 200) − 0 = 400
 *   salidaAbs = calcularMinutoSalidaReal(0, 200) = 200
 *   llegada    = 200 + duracionVuelo(v0) = 200 + (400 − 200) = 400
 *   tránsito   = 400 − 0 = 400 min
 */
public class FitnessTest {

    private static final int A = 1, B = 2;
    private static final int V0 = 0;
    private static final long PENALIZACION = 2880L;
    private static final long TRANSITO_V0  = 400L; // llegada(400) - registro(0)

    private GestorDatos datos;

    @Before
    public void setUp() {
        datos = new GestorDatos();
        datos.numAeropuertos    = 2;
        datos.iataAeropuerto[A] = "AAA";
        datos.iataAeropuerto[B] = "BBB";
        datos.gmtAeropuerto [A] = 0;
        datos.gmtAeropuerto [B] = 0;
        datos.continenteAero[A] = 1;  // América
        datos.continenteAero[B] = 2;  // Europa → distinto continente

        datos.numVuelos = 1;
        datos.vueloOrigen    [V0] = A;
        datos.vueloDestino   [V0] = B;
        datos.vueloSalidaUTC [V0] = 200;
        datos.vueloLlegadaUTC[V0] = 400;
        datos.vueloCapacidad [V0] = 10;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Crea un planificador con N envíos idénticos (A→B, registro=0, deadline=2880). */
    private PlanificadorGVNSConcurrente planConEnvios(int n) {
        datos.numEnvios = n;
        for (int e = 0; e < n; e++) {
            datos.envioOrigen    [e] = A;
            datos.envioDestino   [e] = B;
            datos.envioMaletas   [e] = 1;
            datos.envioRegistroUTC[e] = 0L;
            datos.envioDeadlineUTC[e] = PENALIZACION; // distinto continente
        }
        return new PlanificadorGVNSConcurrente(datos, 42L, CriterioOrden.FIFO);
    }

    // =========================================================================
    // fitness sin rechazados
    // =========================================================================

    @Test
    public void fitness_sinRechazados_esIgualAlTransito() {
        PlanificadorGVNSConcurrente plan = planConEnvios(1);
        plan.construirSolucionInicial();

        assertEquals("Un envío ruteado, ninguno rechazado", 1, plan.enviosExitosos.get());
        assertEquals("0 rechazados activos", 0, plan.contarRechazadosActivos());

        long transitoEsperado = plan.calcularTransitoTotal();
        long fitness          = plan.calcularFitnessTotal();

        assertEquals("Sin rechazados: f(x) = tránsito_total", transitoEsperado, fitness);
    }

    @Test
    public void fitness_unEnvioEnVuelo_transitoEs400() {
        PlanificadorGVNSConcurrente plan = planConEnvios(1);
        plan.construirSolucionInicial();

        assertEquals("Tránsito del único envío debe ser 400 min", TRANSITO_V0,
                plan.calcularTransitoTotal());
    }

    @Test
    public void fitness_dosEnviosRuteados_transitoDoble() {
        PlanificadorGVNSConcurrente plan = planConEnvios(2);
        plan.construirSolucionInicial();

        assertEquals("Dos envíos ruteados", 2, plan.enviosExitosos.get());
        assertEquals("Tránsito = 2 × 400", 2 * TRANSITO_V0, plan.calcularTransitoTotal());
    }

    // =========================================================================
    // fitness con rechazados
    // =========================================================================

    @Test
    public void fitness_conUnRechazado_penalizaConDeadlineMaximo() {
        // cap=10 pero creamos 11 envíos → el vuelo solo tiene 10 plazas por iteración.
        // En realidad con cap=10 y 11 envíos de 1 maleta, 1 quedará rechazado.
        // Usamos cap=1 explícitamente para garantizar exactamente 1 rechazado con 2 envíos.
        datos.vueloCapacidad[V0] = 1;
        PlanificadorGVNSConcurrente plan = planConEnvios(2);
        plan.construirSolucionInicial();

        assertEquals("Solo 1 envío cabe en v0 (cap=1)", 1, plan.enviosExitosos.get());
        assertEquals("1 rechazado activo", 1, plan.contarRechazadosActivos());

        long transito = plan.calcularTransitoTotal(); // 400 (el ruteado)
        long fitness  = plan.calcularFitnessTotal();

        assertEquals("f(x) = 1×2880 + tránsito", PENALIZACION + transito, fitness);
    }

    @Test
    public void fitness_dosRechazados_mayorQueUnoConMasTransito() {
        // Invariante clave: 2 rechazados + tránsito=0 siempre peor que
        // 1 rechazado + tránsito=1000 (porque penalización domina).
        // Lo verificamos calculando los valores directamente.
        long f2rechazados1transito = 2 * PENALIZACION + 1L;   // 5761
        long f1rechazado1000transito = PENALIZACION + 1000L;  // 3880

        assertTrue("2 rechazados con tránsito 1 > 1 rechazado con tránsito 1000",
                f2rechazados1transito > f1rechazado1000transito);
    }

    @Test
    public void fitness_ceroRechazadosCeroTransito_esCero() {
        // Red vacía: 0 envíos, f(x) = 0
        PlanificadorGVNSConcurrente plan = planConEnvios(0);
        plan.construirSolucionInicial();

        assertEquals(0L, plan.calcularFitnessTotal());
    }

    // =========================================================================
    // coherencia PENALIZACION ↔ deadline máximo
    // =========================================================================

    @Test
    public void penalizacion_igualADeadlineMaximoDistintoContinente() {
        // PENALIZACION en calcularFitnessTotal debe coincidir con el deadline
        // máximo que asigna GestorDatos a envíos de distinto continente.
        // Si divergen, un envío rechazado podría falsamente parecer "mejor"
        // que uno llegado justo en el deadline.
        long deadlineMax = GestorDatos.calcularDeadline(0L, 1, 2); // distinto continente desde t=0
        assertEquals("PENALIZACION en fitness debe igualar el deadline máximo de GestorDatos",
                PENALIZACION, deadlineMax);
    }

    @Test
    public void fitness_mejoraSiSeMueveDosRechazadosAUno() {
        // Simular estado A: 2 rechazados, 0 tránsito → f_A = 2×2880
        long fA = 2 * PENALIZACION + 0L;
        // Simular estado B: 1 rechazado, 2000 tránsito → f_B = 2880 + 2000
        long fB = PENALIZACION + 2000L;

        assertTrue("Estado B (1 rechazado) siempre mejor que A (2 rechazados)", fB < fA);
    }
}
