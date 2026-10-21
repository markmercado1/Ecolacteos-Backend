package com.planta.acopio.service;

import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.ScoreConfianzaProveedor;
import com.planta.acopio.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// CORE_DESIGN.md §16, prioridad 1: formula del score de confianza (RF-INN-01),
// documento fuente §2.10.1: score = 0.5*calidad + 0.3*regularidad + 0.2*anomalias.
@ExtendWith(MockitoExtension.class)
class ScoreConfianzaServiceTest {

    @Mock private RegistroAcopioRepository registroAcopioRepository;
    @Mock private AnalisisCalidadRepository analisisCalidadRepository;
    @Mock private AlertaAnomaliaRepository alertaAnomaliaRepository;
    @Mock private ScoreConfianzaProveedorRepository scoreRepository;
    @Mock private ProveedorRepository proveedorRepository;

    private ScoreConfianzaService service;
    private final UUID proveedorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ScoreConfianzaService(registroAcopioRepository, analisisCalidadRepository,
                alertaAnomaliaRepository, scoreRepository, proveedorRepository);
    }

    @Test
    void calculaScorePonderadoCorrectamenteConDatosPerfectos() {
        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));

        // 100% aprobados, litros perfectamente regulares (sin desviacion), 0 alertas
        // -> componente_calidad=100, componente_regularidad=100, componente_anomalias=100
        // -> score = 0.5*100 + 0.3*100 + 0.2*100 = 100
        when(analisisCalidadRepository.contarTotalPorProveedorDesde(eq(proveedorId), any())).thenReturn(10L);
        when(analisisCalidadRepository.contarAprobadosPorProveedorDesde(eq(proveedorId), any())).thenReturn(10L);
        when(registroAcopioRepository.findLitrosPorProveedorDesde(eq(proveedorId), any()))
                .thenReturn(List.of(BigDecimal.valueOf(50), BigDecimal.valueOf(50), BigDecimal.valueOf(50)));
        when(alertaAnomaliaRepository.contarPorProveedorDesde(eq(proveedorId), any())).thenReturn(0L);
        when(scoreRepository.findByProveedorIdAndPeriodo(eq(proveedorId), any())).thenReturn(Optional.empty());

        service.recalcularScoreSemanal(proveedorId, LocalDate.now());

        ArgumentCaptor<ScoreConfianzaProveedor> captor = ArgumentCaptor.forClass(ScoreConfianzaProveedor.class);
        verify(scoreRepository).save(captor.capture());

        assertThat(captor.getValue().getScore().doubleValue()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void penalizaComponenteAnomaliasPorCadaAlertaSinBajarDeCero() {
        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));

        when(analisisCalidadRepository.contarTotalPorProveedorDesde(eq(proveedorId), any())).thenReturn(0L);
        when(analisisCalidadRepository.contarAprobadosPorProveedorDesde(eq(proveedorId), any())).thenReturn(0L);
        when(registroAcopioRepository.findLitrosPorProveedorDesde(eq(proveedorId), any())).thenReturn(List.of());
        // 15 alertas * 10 = 150 de penalizacion -> se acota a 0, no a un negativo
        when(alertaAnomaliaRepository.contarPorProveedorDesde(eq(proveedorId), any())).thenReturn(15L);
        when(scoreRepository.findByProveedorIdAndPeriodo(eq(proveedorId), any())).thenReturn(Optional.empty());

        service.recalcularScoreSemanal(proveedorId, LocalDate.now());

        ArgumentCaptor<ScoreConfianzaProveedor> captor = ArgumentCaptor.forClass(ScoreConfianzaProveedor.class);
        verify(scoreRepository).save(captor.capture());

        assertThat(captor.getValue().getComponenteAnomalias().doubleValue()).isEqualTo(0.0);
    }
}
