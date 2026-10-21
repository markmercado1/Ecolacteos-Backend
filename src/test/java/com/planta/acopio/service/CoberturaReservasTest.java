package com.planta.acopio.service;

import com.planta.acopio.service.CoberturaReservas.Demanda;
import com.planta.acopio.service.CoberturaReservas.DemandaReserva;
import com.planta.acopio.service.CoberturaReservas.Lote;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Fase 4: cobertura de reservas (condicion de Hall por fecha de retiro). Un lote solo respalda una reserva si su
// vencimiento (ultimo dia valido, inclusive) llega a la fecha de retiro.
class CoberturaReservasTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 12);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 13);
    private static final LocalDate D3 = LocalDate.of(2026, 10, 14);
    private static final LocalDate D10 = LocalDate.of(2026, 10, 21);

    private static Lote lote(LocalDate vence, int saldo) { return new Lote(vence, saldo); }
    private static Demanda dem(LocalDate retiro, int cantidad) { return new Demanda(retiro, cantidad); }

    // ------------------------------------------------------------------ cubre

    @Test
    void sinReservasSiempreEstaCubierto() {
        assertThat(CoberturaReservas.cubre(List.of(), List.of())).isTrue();
        assertThat(CoberturaReservas.cubre(List.of(lote(D1, 5)), List.of())).isTrue();
    }

    @Test
    void unLoteQueVenceAntesDeLaFechaDeRetiroNoRespaldaLaReserva() {
        List<Lote> lotes = List.of(lote(D1, 10), lote(D10, 5));

        assertThat(CoberturaReservas.cubre(lotes, List.of(dem(D2, 5)))).as("solo cuenta el lote que llega a D2").isTrue();
        assertThat(CoberturaReservas.cubre(lotes, List.of(dem(D2, 6)))).as("el lote que vence en D1 no sirve para retirar en D2").isFalse();
        assertThat(CoberturaReservas.cubre(lotes, List.of(dem(D1, 15)))).as("para retirar en D1 sirven ambos lotes").isTrue();
    }

    @Test
    void elDiaDeVencimientoEsInclusivo() {
        assertThat(CoberturaReservas.cubre(List.of(lote(D2, 4)), List.of(dem(D2, 4)))).isTrue();
        assertThat(CoberturaReservas.cubre(List.of(lote(D2, 4)), List.of(dem(D3, 1)))).isFalse();
    }

    @Test
    void variasReservasDeFechasDistintasSeEvaluanJuntas() {
        List<Lote> lotes = List.of(lote(D1, 10), lote(D10, 5));
        // D1:10 + D2:5 = 15 <= 15 (en t=D1) y D2:5 <= 5 (en t=D2): cubierto.
        assertThat(CoberturaReservas.cubre(lotes, List.of(dem(D1, 10), dem(D2, 5)))).isTrue();
        // Una unidad mas para D2 deja sin cobertura el tramo t=D2.
        assertThat(CoberturaReservas.cubre(lotes, List.of(dem(D1, 10), dem(D2, 6)))).isFalse();
        // Mas de lo que hay en total.
        assertThat(CoberturaReservas.cubre(lotes, List.of(dem(D1, 10), dem(D1, 6)))).isFalse();
    }

    @Test
    void sinLotesNingunaReservaEstaCubierta() {
        assertThat(CoberturaReservas.cubre(List.of(), List.of(dem(D1, 1)))).isFalse();
    }

    // ------------------------------------------------------------------ faltante

    @Test
    void elFaltanteEsElPeorTramoYCeroSiTodoEstaCubierto() {
        List<Lote> lotes = List.of(lote(D1, 10), lote(D10, 5));
        assertThat(CoberturaReservas.faltante(lotes, List.of(dem(D1, 10), dem(D2, 5)))).isZero();
        assertThat(CoberturaReservas.faltante(lotes, List.of(dem(D1, 10), dem(D2, 8)))).isEqualTo(3);
        assertThat(CoberturaReservas.faltante(List.of(), List.of(dem(D1, 4)))).isEqualTo(4);
    }

    // ------------------------------------------------------------------ disponible

    @Test
    void disponibleParaUnaFechaSoloCuentaLosLotesQueLlegan() {
        List<Lote> lotes = List.of(lote(D1, 10), lote(D10, 5));

        assertThat(CoberturaReservas.disponible(lotes, List.of(), D1)).isEqualTo(15);
        assertThat(CoberturaReservas.disponible(lotes, List.of(), D2)).isEqualTo(5);
        assertThat(CoberturaReservas.disponible(lotes, List.of(), D10)).isEqualTo(5);
        assertThat(CoberturaReservas.disponible(lotes, List.of(), D10.plusDays(1))).as("pasado el ultimo vencimiento no hay nada").isZero();
    }

    @Test
    void disponibleDescuentaLasReservasQueTambienNecesitanEsosLotes() {
        List<Lote> lotes = List.of(lote(D1, 10), lote(D10, 5));

        // Una reserva de 10 para D1 deja 5 para D1 y, de los 5 del lote largo, 5 para D2 (la reserva de D1 usa los del lote corto).
        assertThat(CoberturaReservas.disponible(lotes, List.of(dem(D1, 10)), D1)).isEqualTo(5);
        assertThat(CoberturaReservas.disponible(lotes, List.of(dem(D1, 10)), D2)).isEqualTo(5);
        // Una reserva de 12 para D1 NECESITA 2 unidades del lote largo: para D2 solo quedan 3.
        assertThat(CoberturaReservas.disponible(lotes, List.of(dem(D1, 12)), D2)).isEqualTo(3);
        // Una reserva posterior consume lo que solo ese lote puede dar.
        assertThat(CoberturaReservas.disponible(lotes, List.of(dem(D2, 5)), D1)).as("para D1 quedan los 10 del lote corto").isEqualTo(10);
        assertThat(CoberturaReservas.disponible(lotes, List.of(dem(D2, 5)), D2)).isZero();
    }

    @Test
    void siLasReservasYaNoEstanCubiertasNoSeAgravaElFaltante() {
        assertThat(CoberturaReservas.disponible(List.of(lote(D1, 3)), List.of(dem(D1, 5)), D1)).isZero();
    }

    // ------------------------------------------------------------------ venta directa (FEFO)

    @Test
    void unaVentaQueNoTocaLoQueNecesitanLasReservasSePermite() {
        // La venta sale primero del lote corto (FEFO); la reserva de D2 solo usa el lote largo.
        List<Lote> fefo = List.of(lote(D1, 10), lote(D10, 5));
        assertThat(CoberturaReservas.cubreTrasVender(fefo, List.of(dem(D2, 5)), 10)).isTrue();
    }

    @Test
    void unaVentaQueConsumeLoQueNecesitaUnaReservaSeRechaza() {
        List<Lote> fefo = List.of(lote(D1, 10), lote(D10, 5));
        assertThat(CoberturaReservas.cubreTrasVender(fefo, List.of(dem(D2, 5)), 11)).as("la unidad 11 sale del lote que respalda la reserva").isFalse();
        assertThat(CoberturaReservas.cubreTrasVender(fefo, List.of(dem(D1, 10)), 6)).as("quedan 9 para una reserva de 10").isFalse();
        assertThat(CoberturaReservas.cubreTrasVender(fefo, List.of(dem(D1, 10)), 5)).isTrue();
    }

    @Test
    void sinReservasCualquierVentaVendibleSePermite() {
        assertThat(CoberturaReservas.cubreTrasVender(List.of(lote(D1, 10)), List.of(), 10)).isTrue();
    }

    @Test
    void siYaHabiaUnFaltanteUnaVentaQueNoLoAgravaSePermiteYUnaQueLoAgravaNo() {
        // Reserva de 5 para D1 pero solo 3 unidades en el lote D1 (por un ajuste) y 20 en el lote largo (D10).
        List<Lote> fefo = List.of(lote(D1, 3), lote(D10, 20));
        List<Demanda> demandas = List.of(dem(D1, 30));            // faltante en t = D1 (30 > 23)
        // Vender 2 deja 21: agrava el faltante (el suministro en t=D1 baja).
        assertThat(CoberturaReservas.cubreTrasVender(fefo, demandas, 2)).isFalse();
    }

    // ------------------------------------------------------------------ maximo vendible (unidades libres)

    @Test
    void sinReservasTodoLoVendibleEstaLibre() {
        assertThat(CoberturaReservas.maximoVendible(List.of(lote(D1, 3), lote(D10, 20)), List.of())).isEqualTo(23);
        assertThat(CoberturaReservas.maximoVendible(List.of(), List.of())).isZero();
    }

    @Test
    void elMaximoVendibleRespetaLasUnidadesQueNecesitaUnaReservaDeUnaFechaLejana() {
        // FEFO vende primero el lote que vence en D1 (no sirve a una reserva de D2): 10 libres; la 11.a tocaria el lote de D10.
        List<Lote> fefo = List.of(lote(D1, 10), lote(D10, 5));
        assertThat(CoberturaReservas.maximoVendible(fefo, List.of(dem(D2, 5)))).isEqualTo(10);
        // Con 3 reservadas para D2, se pueden vender 12: quedan 3 en el lote que llega a D2.
        assertThat(CoberturaReservas.maximoVendible(fefo, List.of(dem(D2, 3)))).isEqualTo(12);
    }

    @Test
    void siLasReservasYaNoEstanCubiertasNoHayNadaLibreQueAgrave() {
        // Faltante existente: cualquier venta que reduzca la oferta de esa fecha lo agrava; lo que no la toca sigue libre.
        List<Lote> fefo = List.of(lote(D1, 3), lote(D10, 20));
        assertThat(CoberturaReservas.maximoVendible(fefo, List.of(dem(D2, 30)))).isEqualTo(3);
    }

    @Test
    void elMaximoVendibleCoincideConProbarCadaCantidad() {
        List<Lote> fefo = List.of(lote(D1, 4), lote(D2, 6), lote(D3, 5), lote(D10, 7));
        List<Demanda> demandas = List.of(dem(D2, 5), dem(D3, 4), dem(D10, 2));
        int esperado = 0;
        for (int q = 0; q <= 22; q++) if (CoberturaReservas.cubreTrasVender(fefo, demandas, q)) esperado = q;
        assertThat(CoberturaReservas.maximoVendible(fefo, demandas)).isEqualTo(esperado);
    }

    // ------------------------------------------------------------------ reservas sin cobertura

    @Test
    void lasReservasMasNuevasSonLasQueQuedanSinCobertura() {
        UUID antigua = UUID.randomUUID();
        UUID nueva = UUID.randomUUID();
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 11, 9, 0);
        List<Lote> lotes = List.of(lote(D10, 10));
        List<DemandaReserva> demandas = List.of(
                new DemandaReserva(nueva, t0.plusHours(2), D2, 4),
                new DemandaReserva(antigua, t0, D2, 8));

        assertThat(CoberturaReservas.sinCobertura(lotes, demandas)).containsExactly(nueva);
    }

    @Test
    void sinFaltantesNingunaReservaQuedaSinCobertura() {
        List<DemandaReserva> demandas = List.of(
                new DemandaReserva(UUID.randomUUID(), LocalDateTime.of(2026, 10, 11, 9, 0), D2, 4),
                new DemandaReserva(UUID.randomUUID(), LocalDateTime.of(2026, 10, 11, 10, 0), D3, 6));

        assertThat(CoberturaReservas.sinCobertura(List.of(lote(D10, 10)), demandas)).isEmpty();
    }
}
