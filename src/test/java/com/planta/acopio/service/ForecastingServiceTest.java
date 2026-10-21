package com.planta.acopio.service;

import com.planta.acopio.entity.PrediccionZona;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.repository.PrecioLitroRepository;
import com.planta.acopio.repository.PrediccionZonaRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// CORE_DESIGN.md §16, prioridad 5 (operacion multi-entidad): el forecasting
// debe degradar con gracia (factor estacional neutro = 1.0) cuando aun no hay
// historico suficiente para calcular un promedio general de la zona, en vez
// de fallar por division por cero -- CORE_DESIGN.md §19 (riesgo documentado).
@ExtendWith(MockitoExtension.class)
class ForecastingServiceTest {

    @Mock private RegistroAcopioRepository registroAcopioRepository;
    @Mock private PrediccionZonaRepository prediccionZonaRepository;
    @Mock private ConfiguracionSistemaService configuracionSistemaService;
    @Mock private PrecioLitroRepository precioLitroRepository;
    @Mock private ZonaRepository zonaRepository;

    private ForecastingService service;
    private final UUID zonaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ForecastingService(registroAcopioRepository, prediccionZonaRepository,
                configuracionSistemaService, precioLitroRepository, zonaRepository);
    }

    @Test
    void usaFactorEstacionalNeutroCuandoNoHayHistoricoGeneral() {
        Zona zona = new Zona();
        zona.setId(zonaId);
        zona.setNombre("Zona Norte");
        when(zonaRepository.findById(zonaId)).thenReturn(Optional.of(zona));

        when(prediccionZonaRepository.findByZonaIdAndSemanaInicio(eq(zonaId), any())).thenReturn(Optional.empty());
        when(registroAcopioRepository.findLitrosPorZonaYRango(eq(zonaId), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(100), BigDecimal.valueOf(100)));
        // Sin historico general aun (zona nueva) -> promedioGeneral = 0
        when(registroAcopioRepository.promedioHistoricoGeneralPorZona(zonaId)).thenReturn(BigDecimal.ZERO);
        when(registroAcopioRepository.promedioLitrosPorZonaYMes(eq(zonaId), anyInt())).thenReturn(BigDecimal.ZERO);
        when(precioLitroRepository.findVigenteHoy()).thenReturn(Optional.empty());
        when(configuracionSistemaService.valorDe("CAPACIDAD_PROCESAMIENTO_DIARIA_LITROS")).thenReturn("5000");
        when(prediccionZonaRepository.save(any(PrediccionZona.class))).thenAnswer(inv -> inv.getArgument(0));

        var resultado = service.proyectarSemana(zonaId);

        // mediaMovil4Semanas = 200/4 = 50; factorEstacional=1.0 (neutro) -> litrosEstimados = 50
        assertThat(resultado.litrosEstimados()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(resultado.recomendacionMezcla()).isEqualTo("Mantener mezcla actual");
        assertThat(resultado.alertaCapacidad()).isFalse();
    }
}
