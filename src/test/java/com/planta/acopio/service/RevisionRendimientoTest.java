package com.planta.acopio.service;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.RangoRendimientoDto;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.RangoRendimiento;
import com.planta.acopio.entity.UnidadContenido;
import com.planta.acopio.entity.UnidadRendimiento;
import com.planta.acopio.exception.ValidacionNegocioException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RevisionRendimientoTest {
    private Presentacion presentacion(String contenido, UnidadContenido unidad) {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setNombre("Presentación de prueba");
        p.setContenidoCantidad(contenido == null ? null : new BigDecimal(contenido));
        p.setContenidoUnidad(unidad);
        return p;
    }

    @Test
    void convierteMililitrosYLitrosYSumaSinRedondearAntesDeComparar() {
        var medio = presentacion("500", UnidadContenido.ML);
        var litro = presentacion("1", UnidadContenido.L);
        var rango = new RangoRendimiento(new BigDecimal("90"), new BigDecimal("100"), UnidadRendimiento.L);
        var resultado = RevisionRendimiento.evaluar(rango, new BigDecimal("100"), List.of(
                new DetalleSalidaRequest(medio.getId(), 100), new DetalleSalidaRequest(litro.getId(), 50)), List.of(medio, litro));
        assertThat(resultado.fueraDeRango()).isFalse();
        assertThat(resultado.descripcion()).contains("100 L por 100 L");
    }

    @Test
    void gramosSeConviertenAKilosYDetectaExceso() {
        var pieza = presentacion("500", UnidadContenido.G);
        var rango = new RangoRendimiento(BigDecimal.TEN, new BigDecimal("20"), UnidadRendimiento.KG);
        assertThat(RevisionRendimiento.evaluar(rango, new BigDecimal("100"),
                List.of(new DetalleSalidaRequest(pieza.getId(), 1000)), List.of(pieza)).fueraDeRango()).isTrue();
    }

    @Test
    void noInventaUnaConversionEntreLitrosYKilosNiAceptaContenidoAusente() {
        var rango = new RangoRendimiento(BigDecimal.ONE, BigDecimal.TEN, UnidadRendimiento.KG);
        for (var p : List.of(presentacion("1", UnidadContenido.L), presentacion(null, null))) {
            assertThatThrownBy(() -> RevisionRendimiento.evaluar(rango, BigDecimal.TEN,
                    List.of(new DetalleSalidaRequest(p.getId(), 1)), List.of(p)))
                    .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("contenido compatible");
        }
    }

    @Test
    void sinRangoNoImponeLimitesYLoteSinSalidasTieneRendimientoCero() {
        assertThat(RevisionRendimiento.evaluar(null, BigDecimal.TEN, List.of(), List.of()).descripcion()).isNull();
        var rango = new RangoRendimiento(BigDecimal.ONE, BigDecimal.TEN, UnidadRendimiento.KG);
        assertThat(RevisionRendimiento.evaluar(rango, BigDecimal.TEN, List.of(), List.of()).fueraDeRango()).isTrue();
    }

    @Test
    void rechazaMezclarPresentacionesEnUnRangoDeUnidades() {
        var a = presentacion(null, null);
        var b = presentacion(null, null);
        var rango = new RangoRendimiento(BigDecimal.ONE, BigDecimal.TEN, UnidadRendimiento.UNIDAD);
        assertThatThrownBy(() -> RevisionRendimiento.evaluar(rango, BigDecimal.TEN,
                List.of(new DetalleSalidaRequest(a.getId(), 1), new DetalleSalidaRequest(b.getId(), 1)), List.of(a, b)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("una sola presentación");
    }

    @Test
    void validaPrecisionDeLecheYOrdenDeLimitesEnLosContratos() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new RangoRendimientoDto(BigDecimal.TEN, BigDecimal.ONE, UnidadRendimiento.KG))).isNotEmpty();
            for (String litros : List.of("0", "-1", "0.001", "10000000")) {
                var solicitud = new CrearLoteRequest("uuid", LocalDate.now(), UUID.randomUUID(), new BigDecimal(litros), List.of());
                assertThat(validator.validate(solicitud)).isNotEmpty();
            }
        }
    }
}
