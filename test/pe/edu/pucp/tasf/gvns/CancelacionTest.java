package pe.edu.pucp.tasf.gvns;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests TDD para las operaciones de cancelación en tiempo real:
 *   - cancelarVuelo / restaurarVuelo
 *   - cancelarAeropuerto / restaurarAeropuerto
 *
 * Red mínima (sin archivo):
 *
 *   Aeropuertos: A(1), B(2), C(3)
 *
 *   v0: A→B  depart=200, arrive=400, cap=1   ← capacidad 1 para forzar re-ruteo
 *   v1: A→B  depart=300, arrive=500, cap=10
 *   v2: A→C  depart=200, arrive=400, cap=10
 *
 *   Envíos:
 *     s0: A→B, 1 maleta, registro=100, deadline=1440
 *     s1: A→B, 1 maleta, registro=100, deadline=1440
 *
 * Tras construirSolucionInicial() (greedy lineal):
 *   s0 → v0 (primer vuelo A→B encontrado, cap=1)
 *   s1 → v1 (v0 lleno, siguiente A→B)
 */
public class CancelacionTest {

    private GestorDatos datos;
    private PlanificadorGVNSConcurrente plan;

    // ── IDs de vuelos y aeropuertos ──────────────────────────────────────────
    private static final int A = 1, B = 2, C = 3;
    private static final int V0 = 0, V1 = 1, V2 = 2; // v0: A→B(cap1), v1: A→B(cap10), v2: A→C

    @Before
    public void setUp() {
        datos = new GestorDatos();

        // Aeropuertos (IDs 1-based)
        datos.numAeropuertos     = 3;
        datos.iataAeropuerto[A]  = "AAA";
        datos.iataAeropuerto[B]  = "BBB";
        datos.iataAeropuerto[C]  = "CCC";

        // Vuelos
        datos.numVuelos = 3;

        datos.vueloOrigen   [V0] = A;  datos.vueloDestino   [V0] = B;
        datos.vueloSalidaUTC[V0] = 200; datos.vueloLlegadaUTC[V0] = 400;
        datos.vueloCapacidad[V0] = 1;   // solo 1 maleta → fuerza re-ruteo en tests

        datos.vueloOrigen   [V1] = A;  datos.vueloDestino   [V1] = B;
        datos.vueloSalidaUTC[V1] = 300; datos.vueloLlegadaUTC[V1] = 500;
        datos.vueloCapacidad[V1] = 10;

        datos.vueloOrigen   [V2] = A;  datos.vueloDestino   [V2] = C;
        datos.vueloSalidaUTC[V2] = 200; datos.vueloLlegadaUTC[V2] = 400;
        datos.vueloCapacidad[V2] = 10;

        // Envíos
        datos.numEnvios = 2;

        datos.envioOrigen     [0] = A;  datos.envioDestino     [0] = B;
        datos.envioMaletas    [0] = 1;
        datos.envioRegistroUTC[0] = 100L; datos.envioDeadlineUTC[0] = 1440L;

        datos.envioOrigen     [1] = A;  datos.envioDestino     [1] = B;
        datos.envioMaletas    [1] = 1;
        datos.envioRegistroUTC[1] = 100L; datos.envioDeadlineUTC[1] = 1440L;

        // FIFO para orden determinista: s0 primero → v0; s1 → v1
        plan = new PlanificadorGVNSConcurrente(datos, 42L, CriterioOrden.FIFO);
        plan.construirSolucionInicial();
    }

    // =========================================================================
    // ESTADO INICIAL
    // =========================================================================

    @Test
    public void estadoInicial_ambosEnviosRuteados() {
        assertEquals("Ambos envíos deben estar ruteados", 2, plan.enviosExitosos.get());
        assertNotEquals("s0 debe tener vuelo asignado", -1, plan.solucionVuelos[0][0]);
        assertNotEquals("s1 debe tener vuelo asignado", -1, plan.solucionVuelos[1][0]);
    }

    @Test
    public void estadoInicial_unoEnV0_otroEnV1() {
        // v0 tiene cap=1: solo un envío puede estar en él; el otro va a v1.
        // construirSolucionInicial() usa parallel() — no es determinista cuál
        // de los dos gana la reserva de v0, así que validamos la invariante
        // "exactamente uno en v0 y uno en v1" con XOR.
        boolean s0EnV0 = plan.solucionVuelos[0][0] == V0;
        boolean s1EnV0 = plan.solucionVuelos[1][0] == V0;
        assertTrue("Exactamente un envío debe estar en v0 (cap=1)", s0EnV0 ^ s1EnV0);

        boolean s0EnV1 = plan.solucionVuelos[0][0] == V1;
        boolean s1EnV1 = plan.solucionVuelos[1][0] == V1;
        assertTrue("El otro envío debe estar en v1", s0EnV1 ^ s1EnV1);
    }

    // =========================================================================
    // cancelarVuelo
    // =========================================================================

    @Test
    public void cancelarVuelo_conAlternativa_reruta() {
        // s0 está en v0 (cap=1). Al cancelar v0, s0 debe re-rutearse a v1 (cap=10).
        ResultadoCancelacion r = plan.cancelarVuelo(V0);

        assertEquals("1 envío afectado",   1, r.afectados);
        assertEquals("1 envío re-ruteado", 1, r.reruteados);
        assertEquals("0 sin ruta",         0, r.sinRuta);
        assertEquals("ambos siguen ruteados", 2, plan.enviosExitosos.get());
        assertEquals("s0 ahora en v1", V1, plan.solucionVuelos[0][0]);
    }

    @Test
    public void cancelarVuelo_marcaCapacidadCero() {
        plan.cancelarVuelo(V0);
        assertEquals("capacidad de v0 debe ser 0", 0, datos.vueloCapacidad[V0]);
        assertTrue("v0 debe constar como cancelado", plan.estaVueloCancelado(V0));
    }

    @Test
    public void cancelarVuelo_sinAlternativa_quedaEnPool() {
        // El envío en v1 (cap=10) no tiene alternativa al cancelar v1:
        // v0 (cap=1) ya está ocupado por el otro envío.
        // Detectamos dinámicamente cuál está en v1.
        int idxEnV1 = (plan.solucionVuelos[0][0] == V1) ? 0 : 1;

        ResultadoCancelacion r = plan.cancelarVuelo(V1);

        assertEquals("1 afectado",  1, r.afectados);
        assertEquals("0 re-ruteados (v0 lleno)", 0, r.reruteados);
        assertEquals("1 sin ruta",  1, r.sinRuta);
        assertEquals("solo el de v0 queda ruteado", 1, plan.enviosExitosos.get());
        assertEquals("el de v1 queda sin ruta", -1, plan.solucionVuelos[idxEnV1][0]);
    }

    @Test
    public void cancelarVuelo_dosVeces_segundaEsNoOp() {
        plan.cancelarVuelo(V0);
        ResultadoCancelacion r2 = plan.cancelarVuelo(V0);

        assertEquals("segunda cancelación no afecta envíos", 0, r2.afectados);
        assertEquals("segunda cancelación no re-rutea",      0, r2.reruteados);
    }

    @Test(expected = IllegalArgumentException.class)
    public void cancelarVuelo_idNegativo_lanzaExcepcion() {
        plan.cancelarVuelo(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void cancelarVuelo_idFueraDeRango_lanzaExcepcion() {
        plan.cancelarVuelo(999);
    }

    // =========================================================================
    // restaurarVuelo
    // =========================================================================

    @Test
    public void restaurarVuelo_despuesDeCancelar_restauraCapacidad() {
        plan.cancelarVuelo(V0);
        plan.restaurarVuelo(V0);

        assertEquals("capacidad original restaurada", 1, datos.vueloCapacidad[V0]);
        assertFalse("v0 ya no debe constar cancelado", plan.estaVueloCancelado(V0));
    }

    @Test
    public void restaurarVuelo_conEnviosEnPool_loRerutea() {
        // El envío en v1 queda en pool al cancelar (v0 lleno con el otro).
        // Detectamos dinámicamente cuál está en v1.
        int idxEnV1 = (plan.solucionVuelos[0][0] == V1) ? 0 : 1;

        plan.cancelarVuelo(V1);
        assertEquals("el de v1 sin ruta tras cancelar", -1, plan.solucionVuelos[idxEnV1][0]);

        ResultadoCancelacion r = plan.restaurarVuelo(V1);

        assertEquals("1 envío re-ruteado al restaurar", 1, r.reruteados);
        assertEquals("ambos ruteados de nuevo", 2, plan.enviosExitosos.get());
    }

    @Test
    public void restaurarVuelo_noCancelado_esNoOp() {
        ResultadoCancelacion r = plan.restaurarVuelo(V0);
        assertEquals("no re-rutea si no estaba cancelado", 0, r.reruteados);
        assertEquals("capacidad sin cambio", 1, datos.vueloCapacidad[V0]);
    }

    // =========================================================================
    // cancelarAeropuerto
    // =========================================================================

    @Test
    public void cancelarAeropuerto_cancelaTodosVuelosRelacionados() {
        // Cancelar aeropuerto A (origen de v0, v1, v2).
        // Ambos envíos (A→B) pierden ruta. No hay alternativa (todos los A→B cancelados).
        ResultadoCancelacion r = plan.cancelarAeropuerto(A);

        assertEquals("ambos envíos afectados", 2, r.afectados);
        assertEquals("ninguno puede re-rutearse", 0, r.reruteados);
        assertEquals("0 envíos ruteados", 0, plan.enviosExitosos.get());

        assertTrue("v0 cancelado", plan.estaVueloCancelado(V0));
        assertTrue("v1 cancelado", plan.estaVueloCancelado(V1));
        assertTrue("v2 cancelado", plan.estaVueloCancelado(V2));
    }

    @Test
    public void cancelarAeropuerto_marcaCapacidadCeroEnTodos() {
        plan.cancelarAeropuerto(A);

        assertEquals("cap v0 = 0", 0, datos.vueloCapacidad[V0]);
        assertEquals("cap v1 = 0", 0, datos.vueloCapacidad[V1]);
        assertEquals("cap v2 = 0", 0, datos.vueloCapacidad[V2]);
    }

    @Test
    public void cancelarAeropuerto_aeropuertoDestino_afectaVuelosEntrantesYSalientes() {
        // Cancelar aeropuerto B: afecta v0 (A→B) y v1 (A→B).
        // v2 (A→C) no está relacionado con B → no se cancela.
        plan.cancelarAeropuerto(B);

        assertTrue("v0 cancelado (destino=B)", plan.estaVueloCancelado(V0));
        assertTrue("v1 cancelado (destino=B)", plan.estaVueloCancelado(V1));
        assertFalse("v2 NO cancelado (no relacionado con B)", plan.estaVueloCancelado(V2));
    }

    // =========================================================================
    // restaurarAeropuerto
    // =========================================================================

    @Test
    public void restaurarAeropuerto_restauraCapacidadesOriginales() {
        plan.cancelarAeropuerto(A);
        plan.restaurarAeropuerto(A);

        assertEquals("cap v0 restaurada", 1,  datos.vueloCapacidad[V0]);
        assertEquals("cap v1 restaurada", 10, datos.vueloCapacidad[V1]);
        assertEquals("cap v2 restaurada", 10, datos.vueloCapacidad[V2]);

        assertFalse("v0 no cancelado", plan.estaVueloCancelado(V0));
        assertFalse("v1 no cancelado", plan.estaVueloCancelado(V1));
        assertFalse("v2 no cancelado", plan.estaVueloCancelado(V2));
    }

    @Test
    public void restaurarAeropuerto_rerrutaEnviosDelPool() {
        plan.cancelarAeropuerto(A);
        assertEquals("nadie ruteado tras cancelar A", 0, plan.enviosExitosos.get());

        ResultadoCancelacion r = plan.restaurarAeropuerto(A);

        assertEquals("2 envíos re-ruteados al restaurar A", 2, r.reruteados);
        assertEquals("ambos ruteados de nuevo", 2, plan.enviosExitosos.get());
    }

    @Test
    public void restaurarAeropuerto_noCancelado_esNoOp() {
        ResultadoCancelacion r = plan.restaurarAeropuerto(C); // C no tiene nada cancelado
        assertEquals("no re-rutea si no había cancelaciones", 0, r.reruteados);
        assertEquals("capacidades sin cambio", 10, datos.vueloCapacidad[V2]);
    }

    // =========================================================================
    // Ciclo completo: cancelar → restaurar → cancelar de nuevo
    // =========================================================================

    @Test
    public void cicloCancelarRestaurar_idempotente() {
        // Primera cancelación
        plan.cancelarVuelo(V0);
        assertTrue(plan.estaVueloCancelado(V0));

        // Restaurar
        plan.restaurarVuelo(V0);
        assertFalse(plan.estaVueloCancelado(V0));
        assertEquals(1, datos.vueloCapacidad[V0]);

        // Segunda cancelación: debe funcionar igual que la primera
        ResultadoCancelacion r2 = plan.cancelarVuelo(V0);
        assertTrue(plan.estaVueloCancelado(V0));
        assertEquals("v0 cancelado de nuevo", 0, datos.vueloCapacidad[V0]);
        // s0 puede estar en v0 o v1 dependiendo del estado; solo validar consistencia
        assertTrue("re-ruteados + sinRuta == afectados",
                r2.reruteados + r2.sinRuta == r2.afectados);
    }
}
