package com.planta.acopio.service;

import com.planta.acopio.entity.CorreccionRegistro;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.repository.CorreccionRegistroRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase B: litros efectivos = ultima correccion VALIDA (con motivo) o, sin ella, el original. Se derivan: el registro no se toca.
@ExtendWith(MockitoExtension.class)
class LitrosEfectivosTest {

    @Mock private CorreccionRegistroRepository correccionRepository;

    private LitrosEfectivos litrosEfectivos;

    @BeforeEach
    void setUp() {
        litrosEfectivos = new LitrosEfectivos(correccionRepository);
    }

    @Test
    void sinCorreccionesLosLitrosEfectivosSonLosOriginales() {
        RegistroAcopio r = registro(100);
        when(correccionRepository.findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(any())).thenReturn(List.of());

        var efectivo = litrosEfectivos.deRegistro(r);

        assertThat(efectivo.litros()).isEqualByComparingTo("100");
        assertThat(efectivo.correccion()).isNull();
    }

    @Test
    void conVariasCorreccionesGanaLaUltimaEnOrdenCronologico() {
        RegistroAcopio r = registro(100);
        CorreccionRegistro primera = correccion(r, 90, "Balde mal medido");
        CorreccionRegistro segunda = correccion(r, 85, "Se volvio a pesar");
        when(correccionRepository.findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(any())).thenReturn(List.of(primera, segunda));

        var efectivo = litrosEfectivos.deRegistro(r);

        assertThat(efectivo.litros()).isEqualByComparingTo("85");
        assertThat(efectivo.correccion()).isSameAs(segunda);
        assertThat(r.getLitros()).isEqualByComparingTo("100"); // el original no se sobrescribe
    }

    @Test
    void unaCorreccionSinMotivoNoSeAplicaYSeConservaLaAnteriorValida() {
        RegistroAcopio r = registro(100);
        CorreccionRegistro valida = correccion(r, 90, "Error de digitacion");
        CorreccionRegistro sinMotivo = correccion(r, 50, "   ");
        CorreccionRegistro nula = correccion(r, 10, null);
        when(correccionRepository.findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(any())).thenReturn(List.of(valida, sinMotivo, nula));

        var efectivo = litrosEfectivos.deRegistro(r);

        assertThat(efectivo.litros()).isEqualByComparingTo("90");
        assertThat(efectivo.correccion()).isSameAs(valida);
    }

    @Test
    void unaCorreccionSinMotivoComoUnicaNoCambiaLosLitros() {
        RegistroAcopio r = registro(100);
        when(correccionRepository.findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(any())).thenReturn(List.of(correccion(r, 50, "")));

        assertThat(litrosEfectivos.deRegistro(r).litros()).isEqualByComparingTo("100");
    }

    @Test
    void variasEntregasSeResuelvenConUnaSolaConsultaYCadaUnaPorSuCuenta() {
        RegistroAcopio a = registro(100);
        RegistroAcopio b = registro(40);
        RegistroAcopio c = registro(70);
        CorreccionRegistro deA = correccion(a, 90, "Medicion");
        CorreccionRegistro deC = correccion(c, 75, "Doble conteo");
        when(correccionRepository.findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(any())).thenReturn(List.of(deA, deC));

        Map<UUID, LitrosEfectivos.Efectivo> efectivos = litrosEfectivos.deRegistros(List.of(a, b, c));

        assertThat(efectivos.get(a.getId()).litros()).isEqualByComparingTo("90");
        assertThat(efectivos.get(b.getId()).litros()).isEqualByComparingTo("40");
        assertThat(efectivos.get(c.getId()).litros()).isEqualByComparingTo("75");
        verify(correccionRepository, times(1)).findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(any(Collection.class));
    }

    @Test
    void sinEntregasNoConsultaNada() {
        assertThat(litrosEfectivos.deRegistros(List.of())).isEmpty();
        verify(correccionRepository, never()).findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(any());
    }

    private RegistroAcopio registro(int litros) {
        RegistroAcopio r = new RegistroAcopio();
        r.setId(UUID.randomUUID());
        r.setLitros(BigDecimal.valueOf(litros));
        return r;
    }

    private CorreccionRegistro correccion(RegistroAcopio r, int corregido, String motivo) {
        CorreccionRegistro c = new CorreccionRegistro();
        c.setId(UUID.randomUUID());
        c.setRegistroAcopio(r);
        c.setLitrosAnterior(r.getLitros());
        c.setLitrosCorregido(BigDecimal.valueOf(corregido));
        c.setMotivo(motivo);
        return c;
    }
}
