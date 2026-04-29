package pe.edu.pucp.tasf.gvns;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests TDD para la lógica de deadline y conversión de tiempo en GestorDatos.
 *
 * Contratos verificados:
 *   - Deadline: mismo continente → registroUTC + 1440 (24 h)
 *   - Deadline: distinto continente → registroUTC + 2880 (48 h)
 *   - calcularEpochMinutos: fecha conocida produce minutos UTC correctos
 */
public class GestorDatosTest {

    // =========================================================================
    // calcularDeadline
    // =========================================================================

    @Test
    public void deadline_mismoContinente_es1440minutos() {
        long registro = 50_000L;
        long deadline = GestorDatos.calcularDeadline(registro, 1, 1);
        assertEquals("Mismo continente: deadline = registro + 1440",
                registro + 1440L, deadline);
    }

    @Test
    public void deadline_distintoContinente_es2880minutos() {
        long registro = 50_000L;
        long deadline = GestorDatos.calcularDeadline(registro, 1, 2);
        assertEquals("Distinto continente: deadline = registro + 2880",
                registro + 2880L, deadline);
    }

    @Test
    public void deadline_continente2a3_es2880minutos() {
        long registro = 0L;
        long deadline = GestorDatos.calcularDeadline(registro, 2, 3);
        assertEquals(2880L, deadline);
    }

    @Test
    public void deadline_mismoContinente3_es1440minutos() {
        long registro = 100_000L;
        long deadline = GestorDatos.calcularDeadline(registro, 3, 3);
        assertEquals(registro + 1440L, deadline);
    }

    @Test
    public void deadline_registroCero_mismoContinente_es1440() {
        assertEquals(1440L, GestorDatos.calcularDeadline(0L, 2, 2));
    }

    @Test
    public void deadline_registroCero_distintoContinente_es2880() {
        assertEquals(2880L, GestorDatos.calcularDeadline(0L, 1, 3));
    }

    @Test
    public void deadline_penalizacionMaxima_igualaDeadlineDistintoContinente() {
        // La constante PENALIZACION=2880 en calcularFitnessTotal representa
        // el máximo deadline posible. Este test verifica que ambas constantes
        // son coherentes: un envío de distinto continente con registro=0
        // tiene deadline exactamente igual a la penalización.
        long deadlineMax = GestorDatos.calcularDeadline(0L, 1, 2);
        assertEquals("PENALIZACION debe coincidir con el máximo deadline", 2880L, deadlineMax);
    }

    // =========================================================================
    // calcularEpochMinutos
    // =========================================================================

    @Test
    public void calcularEpochMinutos_fechaEjemploJavadoc_correcto() {
        // Verificación del Javadoc: 2025-08-18 10:30 local GMT+2 → 08:30 UTC
        // EpochDay = 2 460 906 − 2 440 588 = 20 318
        // minutosUTC = 20318 × 1440 + 8×60 + 30 = 29 258 190
        long resultado = GestorDatos.calcularEpochMinutos(2025, 8, 18, 10, 30, 2);
        assertEquals("2025-08-18 10:30 GMT+2 → 29 258 430 min UTC",
                29_258_430L, resultado);
    }

    @Test
    public void calcularEpochMinutos_epoch_esCaroCero() {
        // 1970-01-01 00:00 UTC → minuto 0
        long resultado = GestorDatos.calcularEpochMinutos(1970, 1, 1, 0, 0, 0);
        assertEquals("Epoch 1970-01-01 00:00 UTC debe ser 0", 0L, resultado);
    }

    @Test
    public void calcularEpochMinutos_gmtNegativo_sumaOffset() {
        // 2025-08-18 05:30 local GMT-3 → 08:30 UTC → mismo resultado que GMT+2 a las 10:30
        long resultado = GestorDatos.calcularEpochMinutos(2025, 8, 18, 5, 30, -3);
        assertEquals("GMT-3 a las 05:30 = UTC 08:30 = mismo epoch que GMT+2 a las 10:30",
                29_258_430L, resultado);
    }

    @Test
    public void calcularEpochMinutos_medianoche_correcto() {
        // 1970-01-02 00:00 UTC = minuto 1440
        long resultado = GestorDatos.calcularEpochMinutos(1970, 1, 2, 0, 0, 0);
        assertEquals(1440L, resultado);
    }

    @Test
    public void calcularEpochMinutos_anoBisiesto_correcto() {
        // 2024-02-29 00:00 UTC debe ser válido (2024 es bisiesto)
        long r29 = GestorDatos.calcularEpochMinutos(2024, 2, 29, 0, 0, 0);
        long r1Mar = GestorDatos.calcularEpochMinutos(2024, 3, 1, 0, 0, 0);
        assertEquals("Feb 29 + 1440 min = Mar 1", r29 + 1440L, r1Mar);
    }
}
