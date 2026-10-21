package com.planta.acopio.service;

import com.planta.acopio.entity.*;
import com.planta.acopio.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// CORE_DESIGN.md §16, prioridad 1: deteccion de anomalia por z-score contra
// el umbral configurable (RF-INN-03, documento fuente §2.10.3).
@ExtendWith(MockitoExtension.class)
class AnomaliaServiceTest {

    @Mock private RegistroAcopioRepository registroAcopioRepository;
    @Mock private AlertaAnomaliaRepository alertaAnomaliaRepository;
    @Mock private AnalisisCalidadRepository analisisCalidadRepository;
    @Mock private ConfiguracionSistemaService configuracionSistemaService;
    @Mock private PrediccionProveedorRepository prediccionProveedorRepository;
    @Mock private ProveedorRepository proveedorRepository;

    private AnomaliaService service;
    private final UUID proveedorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AnomaliaService(registroAcopioRepository, alertaAnomaliaRepository,
                configuracionSistemaService, prediccionProveedorRepository, analisisCalidadRepository,
                proveedorRepository);
    }

    @Test
    void noEvaluaConHistoricoInsuficiente() {
        RegistroAcopio registro = registroDe(proveedorId, BigDecimal.valueOf(500));
        when(registroAcopioRepository.findLitrosPorProveedorDesdeExcluyendo(eq(proveedorId), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(40), BigDecimal.valueOf(42))); // solo 2, se requieren >=3

        service.evaluarNuevoRegistro(registro);

        verify(alertaAnomaliaRepository, never()).save(any());
    }

    @Test
    void creaAlertaVolumenAtipicoCuandoZScoreSuperaElUmbral() {
        RegistroAcopio registro = registroDe(proveedorId, BigDecimal.valueOf(500)); // muy por encima del historico
        when(registroAcopioRepository.findLitrosPorProveedorDesdeExcluyendo(eq(proveedorId), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(40), BigDecimal.valueOf(42), BigDecimal.valueOf(41), BigDecimal.valueOf(43)));
        when(configuracionSistemaService.valorDe("UMBRAL_ANOMALIA_Z_SCORE")).thenReturn("2.0");
        when(analisisCalidadRepository.existeAguaAnadidaRecienteParaProveedor(eq(proveedorId), any())).thenReturn(false);

        service.evaluarNuevoRegistro(registro);

        ArgumentCaptor<AlertaAnomalia> captor = ArgumentCaptor.forClass(AlertaAnomalia.class);
        verify(alertaAnomaliaRepository).save(captor.capture());
        assertThat(captor.getValue().getTipo()).isEqualTo(TipoAlerta.VOLUMEN_ATIPICO);
    }

    @Test
    void noCreaAlertaCuandoElValorEstaDentroDelRangoNormal() {
        RegistroAcopio registro = registroDe(proveedorId, BigDecimal.valueOf(41)); // dentro del rango normal
        when(registroAcopioRepository.findLitrosPorProveedorDesdeExcluyendo(eq(proveedorId), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(40), BigDecimal.valueOf(42), BigDecimal.valueOf(41), BigDecimal.valueOf(43)));
        when(configuracionSistemaService.valorDe("UMBRAL_ANOMALIA_Z_SCORE")).thenReturn("2.0");
        when(analisisCalidadRepository.existeAguaAnadidaRecienteParaProveedor(eq(proveedorId), any())).thenReturn(false);

        service.evaluarNuevoRegistro(registro);

        verify(alertaAnomaliaRepository, never()).save(any());
    }

    private RegistroAcopio registroDe(UUID proveedorId, BigDecimal litros) {
        RegistroAcopio r = new RegistroAcopio();
        r.setId(UUID.randomUUID());
        Proveedor p = new Proveedor();
        p.setId(proveedorId);
        r.setProveedor(p);
        r.setLitros(litros);
        r.setFechaHora(LocalDateTime.now());
        return r;
    }
}
