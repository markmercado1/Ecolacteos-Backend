package com.planta.acopio.service;

import com.planta.acopio.entity.AjustePrecioPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.repository.AjustePrecioPagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase B: el precio aplicado a las entregas de un pago incluye los ajustes de calidad (AjustePrecioPago, de un pago completo).
@ExtendWith(MockitoExtension.class)
class PrecioAplicadoTest {

    @Mock private AjustePrecioPagoRepository ajustePrecioRepository;

    private PrecioAplicado precioAplicado;

    @BeforeEach
    void setUp() {
        precioAplicado = new PrecioAplicado(ajustePrecioRepository);
    }

    @Test
    void sinAjustesEsElPrecioDelPagoOriginal() {
        Pago pago = pago("1.000");
        when(ajustePrecioRepository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(Optional.empty());

        assertThat(precioAplicado.efectivoDelPago(pago)).isEqualByComparingTo("1.00");
    }

    @Test
    void conAjusteDeCalidadEsElPrecioDelAjusteMasReciente() {
        Pago pago = pago("1.000");
        when(ajustePrecioRepository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pago.getId()))
                .thenReturn(Optional.of(ajuste(pago, "0.900")));

        assertThat(precioAplicado.efectivoDelPago(pago)).isEqualByComparingTo("0.90");
    }

    @Test
    void variosPagosSeResuelvenConUnaConsultaYElUltimoAjusteDeCadaUnoPisaAlAnterior() {
        Pago conAjustes = pago("1.000");
        Pago sinAjustes = pago("1.200");
        when(ajustePrecioRepository.findByPagoIdInOrderByCreadoEnAscIdAsc(any())).thenReturn(List.of(
                ajuste(conAjustes, "0.900"), ajuste(conAjustes, "0.800")));

        Map<UUID, BigDecimal> precios = precioAplicado.efectivosDePagos(List.of(conAjustes, sinAjustes));

        assertThat(precios.get(conAjustes.getId())).isEqualByComparingTo("0.80");
        assertThat(precios.get(sinAjustes.getId())).isEqualByComparingTo("1.20"); // aparece aunque no tenga ajustes
    }

    @Test
    void sinPagosNoConsultaNada() {
        assertThat(precioAplicado.efectivosDePagos(List.of())).isEmpty();
        verify(ajustePrecioRepository, never()).findByPagoIdInOrderByCreadoEnAscIdAsc(any());
    }

    @Test
    void elMontoRedondeaAlCentimoHalfUpTambienEnNegativo() {
        assertThat(PrecioAplicado.monto(new BigDecimal("-5"), new BigDecimal("0.900"))).isEqualByComparingTo("-4.50");
        assertThat(PrecioAplicado.monto(new BigDecimal("1"), new BigDecimal("0.005"))).isEqualByComparingTo("0.01");
        assertThat(PrecioAplicado.monto(new BigDecimal("-1"), new BigDecimal("0.005"))).isEqualByComparingTo("-0.01");
    }

    private Pago pago(String precio) {
        Pago p = new Pago();
        p.setId(UUID.randomUUID());
        p.setPrecioLitro(new BigDecimal(precio));
        return p;
    }

    private AjustePrecioPago ajuste(Pago pago, String nuevo) {
        AjustePrecioPago a = new AjustePrecioPago();
        a.setPago(pago);
        a.setPrecioLitroNuevo(new BigDecimal(nuevo));
        return a;
    }
}
