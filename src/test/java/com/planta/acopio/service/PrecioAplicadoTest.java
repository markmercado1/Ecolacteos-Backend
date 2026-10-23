package com.planta.acopio.service;

import com.planta.acopio.entity.AjusteCalidadEntrega;
import com.planta.acopio.entity.AjustePrecioPago;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.repository.AjusteCalidadEntregaRepository;
import com.planta.acopio.repository.AjustePrecioPagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase B: el precio aplicado a las entregas de un pago incluye los ajustes de calidad (AjustePrecioPago, de un pago completo).
@ExtendWith(MockitoExtension.class)
class PrecioAplicadoTest {

    @Mock private AjustePrecioPagoRepository ajustePrecioRepository;
    @Mock private AjusteCalidadEntregaRepository ajusteCalidadRepository;

    private PrecioAplicado precioAplicado;

    @BeforeEach
    void setUp() {
        precioAplicado = new PrecioAplicado(ajustePrecioRepository, ajusteCalidadRepository);
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


    // ---- Fase C: precio de UNA entrega ("la entrega manda") y total reconocido

    @Test
    void unaEntregaSinPrecioPropioSigueAlPrecioEfectivoDeSuPago() {
        Pago pago = pago("1.000");
        LineaPago linea = linea(pago, "100");
        when(ajusteCalidadRepository.findFirstByPagoLineaIdOrderByCreadoEnDescIdDesc(linea.getId())).thenReturn(Optional.empty());
        when(ajustePrecioRepository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(Optional.of(ajuste(pago, "0.800")));

        assertThat(precioAplicado.deLinea(linea)).isEqualByComparingTo("0.80");
    }

    @Test
    void unaEntregaConPrecioDeCalidadPropioIgnoraElAjusteDelPagoCompleto() {
        Pago pago = pago("1.000");
        LineaPago linea = linea(pago, "100");
        when(ajusteCalidadRepository.findFirstByPagoLineaIdOrderByCreadoEnDescIdDesc(linea.getId()))
                .thenReturn(Optional.of(calidad(linea, "0.900")));

        assertThat(precioAplicado.deLinea(linea)).isEqualByComparingTo("0.90"); // el pago tiene 0.80 pero la entrega manda
        verify(ajustePrecioRepository, never()).findFirstByPagoIdOrderByCreadoEnDescIdDesc(any());
    }

    @Test
    void variasEntregasSeResuelvenConDosConsultasYElUltimoPrecioDeCalidadDeCadaUnaGana() {
        Pago pago = pago("1.000");
        LineaPago conCalidad = linea(pago, "100");
        LineaPago sinCalidad = linea(pago, "100");
        when(ajustePrecioRepository.findByPagoIdInOrderByCreadoEnAscIdAsc(any())).thenReturn(List.of(ajuste(pago, "0.950")));
        when(ajusteCalidadRepository.findByPagoLineaIdInOrderByCreadoEnAscIdAsc(any())).thenReturn(List.of(
                calidad(conCalidad, "0.900"), calidad(conCalidad, "0.850")));

        Map<UUID, BigDecimal> precios = precioAplicado.deLineas(List.of(conCalidad, sinCalidad));

        assertThat(precios.get(conCalidad.getId())).isEqualByComparingTo("0.85");
        assertThat(precios.get(sinCalidad.getId())).isEqualByComparingTo("0.95"); // sigue al ajuste del pago completo
        verify(ajusteCalidadRepository, times(1)).findByPagoLineaIdInOrderByCreadoEnAscIdAsc(any());
    }

    // Ejemplo de la auditoria: 600 L a S/ 1.00 con 100 L de calidad deficiente a 0.90 = 500 x 1.00 + 100 x 0.90 = S/ 590.00.
    // Con el precio unico por pago solo se podia llegar a 0.983 -> S/ 589.80.
    @Test
    void elEjemploDeLaAuditoriaDaExactamenteSolesQuinientosNoventa() {
        Pago pago = pago("1.000");
        List<LineaPago> lineas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lineas.add(linea(pago, "100"));
        }
        Map<UUID, BigDecimal> precios = new HashMap<>();
        for (LineaPago l : lineas) {
            precios.put(l.getId(), new BigDecimal("1.000"));
        }
        precios.put(lineas.get(0).getId(), new BigDecimal("0.900"));

        assertThat(PrecioAplicado.totalReconocido(lineas, precios)).isEqualByComparingTo("590.00");
        // el atajo anterior (un solo precio para todo el pago) da 589.80
        assertThat(PrecioAplicado.monto(new BigDecimal("600"), new BigDecimal("0.983"))).isEqualByComparingTo("589.80");
    }

    @Test
    void elTotalSeRedondeaUnaSolaVezAlFinalYNoPorLinea() {
        Pago pago = pago("1.005");
        List<LineaPago> lineas = new ArrayList<>();
        Map<UUID, BigDecimal> precios = new HashMap<>();
        for (int i = 0; i < 10; i++) { // 10 entregas de 1 L a 1.005
            LineaPago l = linea(pago, "1");
            lineas.add(l);
            precios.put(l.getId(), new BigDecimal("1.005"));
        }

        assertThat(PrecioAplicado.totalReconocido(lineas, precios)).isEqualByComparingTo("10.05"); // por linea serian 10 x 1.01 = 10.10
    }

    private LineaPago linea(Pago pago, String litros) {
        LineaPago l = new LineaPago();
        l.setId(UUID.randomUUID());
        l.setPago(pago);
        l.setLitrosLiquidados(new BigDecimal(litros));
        return l;
    }

    private AjusteCalidadEntrega calidad(LineaPago linea, String nuevo) {
        AjusteCalidadEntrega a = new AjusteCalidadEntrega();
        a.setPagoLinea(linea);
        a.setPrecioNuevo(new BigDecimal(nuevo));
        return a;
    }
}
