package com.planta.acopio.service;

import com.planta.acopio.entity.AnalisisCalidad;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.ResultadoCalidad;
import com.planta.acopio.repository.AnalisisCalidadRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalisisCalidadBandejaTest {
    @Mock AnalisisCalidadRepository analisisRepository;
    @Mock RegistroAcopioRepository registroRepository;

    @Test
    void devuelveUnaFilaPorEntregaConElUltimoResultadoConfirmado() {
        var fecha = LocalDate.of(2026, 9, 13);
        var proveedor = new Proveedor();
        proveedor.setId(UUID.randomUUID());
        proveedor.setNombre("Juan Ortiz");
        var entrega = new RegistroAcopio();
        entrega.setId(UUID.randomUUID());
        entrega.setProveedor(proveedor);
        entrega.setFechaHora(fecha.atTime(9, 49));
        entrega.setLitros(new BigDecimal("85.00"));

        var ultimo = new AnalisisCalidad();
        ultimo.setRegistroAcopio(entrega);
        ultimo.setResultado(ResultadoCalidad.RECHAZADO);
        ultimo.setCreadoEn(fecha.atTime(11, 0));
        var anterior = new AnalisisCalidad();
        anterior.setRegistroAcopio(entrega);
        anterior.setResultado(ResultadoCalidad.APROBADO);
        anterior.setCreadoEn(fecha.atTime(10, 0));

        when(analisisRepository.listarBandejaPorFechaDeEntrega(
                fecha.atStartOfDay(), fecha.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(ultimo, anterior));
        var service = new AnalisisCalidadService(analisisRepository, registroRepository);
        var filas = service.listarBandeja(fecha);

        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).registroAcopioId()).isEqualTo(entrega.getId());
        assertThat(filas.get(0).proveedorNombre()).isEqualTo("Juan Ortiz");
        assertThat(filas.get(0).litros()).isEqualByComparingTo("85.00");
        assertThat(filas.get(0).resultado()).isEqualTo("RECHAZADO");
        verify(analisisRepository).listarBandejaPorFechaDeEntrega(
                fecha.atStartOfDay(), fecha.plusDays(1).atStartOfDay());
    }
}
