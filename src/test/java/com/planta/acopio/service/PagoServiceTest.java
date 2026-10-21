package com.planta.acopio.service;

import com.planta.acopio.dto.GenerarPagoRequest;
import com.planta.acopio.entity.*;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// CORE_DESIGN.md §16, prioridad 2 (operacion transaccional): generacion de
// pago -- no debe permitir doble liquidacion de la misma semana (UNIQUE) y
// debe vincular exactamente los registro_acopio liquidados (mejora #4,
// decision aprobada §18 punto 4).
@ExtendWith(MockitoExtension.class)
class PagoServiceTest {

    @Mock private PagoRepository pagoRepository;
    @Mock private ProveedorRepository proveedorRepository;
    @Mock private RegistroAcopioRepository registroAcopioRepository;
    @Mock private PrecioLitroRepository precioLitroRepository;
    @Mock private EntityManager entityManager;

    private PagoService service;
    private final UUID proveedorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PagoService(pagoRepository, proveedorRepository, registroAcopioRepository,
                precioLitroRepository, entityManager);
    }

    @Test
    void rechazaGenerarPagoSiYaExisteParaLaMismaSemana() {
        var dto = new GenerarPagoRequest(proveedorId, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 8));
        when(pagoRepository.findByProveedorIdAndSemanaInicio(proveedorId, dto.semanaInicio()))
                .thenReturn(Optional.of(new Pago()));

        assertThatThrownBy(() -> service.generar(dto))
                .isInstanceOf(ConflictoNegocioException.class);

        verify(pagoRepository, never()).save(any());
    }

    @Test
    void rechazaGenerarPagoSinRegistrosEnElRango() {
        var dto = new GenerarPagoRequest(proveedorId, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 8));
        when(pagoRepository.findByProveedorIdAndSemanaInicio(proveedorId, dto.semanaInicio())).thenReturn(Optional.empty());
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedorConId()));
        when(registroAcopioRepository.findByProveedorIdAndFechaHoraBetween(eq(proveedorId), any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.generar(dto))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void generaPagoVinculandoExactamenteLosRegistrosDelRango() {
        var dto = new GenerarPagoRequest(proveedorId, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 8));
        when(pagoRepository.findByProveedorIdAndSemanaInicio(proveedorId, dto.semanaInicio())).thenReturn(Optional.empty());
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedorConId()));

        RegistroAcopio r1 = registroCon(BigDecimal.valueOf(40));
        RegistroAcopio r2 = registroCon(BigDecimal.valueOf(35));
        when(registroAcopioRepository.findByProveedorIdAndFechaHoraBetween(eq(proveedorId), any(), any()))
                .thenReturn(List.of(r1, r2));

        PrecioLitro precio = new PrecioLitro();
        precio.setPrecio(BigDecimal.valueOf(1.5));
        when(precioLitroRepository.findVigenteHoy()).thenReturn(Optional.of(precio));

        when(pagoRepository.save(any(Pago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generar(dto);

        ArgumentCaptor<Pago> captor = ArgumentCaptor.forClass(Pago.class);
        verify(pagoRepository).save(captor.capture());

        Pago guardado = captor.getValue();
        assertThat(guardado.getLitrosTotales()).isEqualByComparingTo(BigDecimal.valueOf(75));
        assertThat(guardado.getRegistrosLiquidados()).containsExactlyInAnyOrder(r1, r2); // mejora #4
    }

    // Segunda auditoria (flujos end-to-end §6/§14-G): un rango con otra
    // semanaInicio (pasa el UNIQUE) no debe poder re-liquidar un registro que
    // ya quedo vinculado a un pago anterior via pago_registro_acopio.
    @Test
    void rechazaGenerarPagoSiAlgunRegistroYaFueLiquidadoEnOtroPago() {
        var dto = new GenerarPagoRequest(proveedorId, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 8));
        when(pagoRepository.findByProveedorIdAndSemanaInicio(proveedorId, dto.semanaInicio())).thenReturn(Optional.empty());
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedorConId()));

        RegistroAcopio r1 = registroCon(BigDecimal.valueOf(40));
        RegistroAcopio r2 = registroCon(BigDecimal.valueOf(35)); // este ya esta en otro pago
        when(registroAcopioRepository.findByProveedorIdAndFechaHoraBetween(eq(proveedorId), any(), any()))
                .thenReturn(List.of(r1, r2));

        when(pagoRepository.existeRegistroYaLiquidado(r1.getId())).thenReturn(false);
        when(pagoRepository.existeRegistroYaLiquidado(r2.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.generar(dto))
                .isInstanceOf(ConflictoNegocioException.class);

        verify(pagoRepository, never()).save(any());
    }

    private Proveedor proveedorConId() {
        Proveedor p = new Proveedor();
        p.setId(proveedorId);
        p.setNombre("Proveedor Test");
        return p;
    }

    private RegistroAcopio registroCon(BigDecimal litros) {
        RegistroAcopio r = new RegistroAcopio();
        r.setId(UUID.randomUUID());
        r.setLitros(litros);
        r.setFechaHora(LocalDateTime.now());
        return r;
    }
}
