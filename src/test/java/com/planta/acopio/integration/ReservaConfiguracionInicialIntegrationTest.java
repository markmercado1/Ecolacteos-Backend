package com.planta.acopio.integration;

import com.planta.acopio.dto.ConfiguracionSistemaResponse;
import com.planta.acopio.dto.ConfiguracionSistemaUpdateRequest;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.service.ConfiguracionSistemaService;
import com.planta.acopio.service.ReservaConfig;
import com.planta.acopio.service.ReservaParametros;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase 4: los valores iniciales que siembra V23 en la configuracion (base nueva, sin tocar nada) son los acordados:
 * plazo 3 dias, 20 unidades, 2 pendientes por telefono, 24 h de retencion, atencion de lunes a sabado y cierre 18:00. Son
 * claves de la configuracion existente, asi que aparecen en Catalogos -> Configuracion (solo ADMIN) sin cambios en la web.
 * Clase aparte, con su propia base: no depende de lo que cambien otras pruebas.
 */
@Testcontainers
@SpringBootTest
class ReservaConfiguracionInicialIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("acopio_lechero_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ConfiguracionSistemaService configuracionService;
    @Autowired private ReservaConfig reservaConfig;
    @Autowired private JdbcTemplate jdbc;

    private String valor(String clave) {
        return jdbc.queryForObject("SELECT valor FROM configuracion_sistema WHERE clave = ?", String.class, clave);
    }

    @Test
    void laMigracionSiembraLosValoresInicialesAcordados() {
        assertThat(valor("RESERVA_PLAZO_DIAS")).isEqualTo("3");
        assertThat(valor("RESERVA_MAX_UNIDADES")).isEqualTo("20");
        assertThat(valor("RESERVA_MAX_PENDIENTES_TELEFONO")).isEqualTo("2");
        assertThat(valor("RESERVA_HORAS_PENDIENTE")).isEqualTo("24");
        assertThat(valor("RESERVA_DIAS_ATENCION")).as("lunes a sabado").isEqualTo("1,2,3,4,5,6");
        assertThat(valor("RESERVA_HORA_CIERRE")).isEqualTo("18:00");
        assertThat(valor("RESERVA_DIAS_NO_LABORABLES")).isEqualTo("NINGUNO");

        ReservaParametros p = reservaConfig.actuales();
        assertThat(p).isEqualTo(ReservaParametros.porDefecto());
        assertThat(p.diasAtencion()).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6).doesNotContain(7);
        assertThat(p.horaCierre()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void lasClavesAparecenEnLaConfiguracionExistenteYLasOriginalesSiguenIntactas() {
        List<String> claves = configuracionService.listar().stream().map(ConfiguracionSistemaResponse::clave).toList();

        assertThat(claves).contains("RESERVA_PLAZO_DIAS", "RESERVA_MAX_UNIDADES", "RESERVA_MAX_PENDIENTES_TELEFONO", "RESERVA_HORAS_PENDIENTE",
                "RESERVA_DIAS_ATENCION", "RESERVA_HORA_CIERRE", "RESERVA_DIAS_NO_LABORABLES");
        assertThat(claves).as("las claves anteriores no se tocan").contains("UMBRAL_CONCILIACION_PCT", "CAPACIDAD_PROCESAMIENTO_DIARIA_LITROS", "UMBRAL_ANOMALIA_Z_SCORE");
        assertThat(valor("UMBRAL_CONCILIACION_PCT")).isEqualTo("2.5");
    }

    @Test
    void unCambioValidoSeAplicaALasNuevasReservasYUnoInvalidoSeRechazaSinGuardarse() {
        configuracionService.actualizar("RESERVA_MAX_UNIDADES", new ConfiguracionSistemaUpdateRequest("12"));
        assertThat(reservaConfig.actuales().maxUnidades()).isEqualTo(12);

        assertThatThrownBy(() -> configuracionService.actualizar("RESERVA_MAX_UNIDADES", new ConfiguracionSistemaUpdateRequest("0")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("RESERVA_MAX_UNIDADES");
        assertThatThrownBy(() -> configuracionService.actualizar("RESERVA_DIAS_ATENCION", new ConfiguracionSistemaUpdateRequest("1,9")))
                .isInstanceOf(ValidacionNegocioException.class);
        assertThat(valor("RESERVA_MAX_UNIDADES")).isEqualTo("12");
        assertThat(valor("RESERVA_DIAS_ATENCION")).isEqualTo("1,2,3,4,5,6");

        // Una clave ajena a las reservas sigue aceptando cualquier texto (no se valida lo que no es RESERVA_*).
        configuracionService.actualizar("UMBRAL_CONCILIACION_PCT", new ConfiguracionSistemaUpdateRequest("3.0"));
        assertThat(valor("UMBRAL_CONCILIACION_PCT")).isEqualTo("3.0");
        configuracionService.actualizar("UMBRAL_CONCILIACION_PCT", new ConfiguracionSistemaUpdateRequest("2.5"));
        configuracionService.actualizar("RESERVA_MAX_UNIDADES", new ConfiguracionSistemaUpdateRequest("20"));
    }
}
