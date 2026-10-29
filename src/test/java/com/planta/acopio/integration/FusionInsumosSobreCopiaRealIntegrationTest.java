package com.planta.acopio.integration;

import com.planta.acopio.dto.DuplicadosInsumoResponse;
import com.planta.acopio.dto.FusionInsumoResponse;
import com.planta.acopio.service.FusionInsumoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fusiona "azucar" y "Azúcar" sobre una COPIA de una base real ya migrada a V41 (ECO_MIGRACION_COPIA_URL). MODIFICA esa
 * base: nunca debe apuntar a la base real. Solo corre si la variable esta definida.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ECO_MIGRACION_COPIA_URL", matches = ".+")
class FusionInsumosSobreCopiaRealIntegrationTest {

    @DynamicPropertySource
    static void copia(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("ECO_MIGRACION_COPIA_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("ECO_MIGRACION_COPIA_USUARIO", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("ECO_MIGRACION_COPIA_CLAVE", "postgres"));
    }

    @Autowired private FusionInsumoService fusionService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void fusionaElAzucarRealSinPerderStockNiHistorial() {
        String email = jdbc.queryForObject("SELECT u.email FROM usuario u JOIN rol r ON r.id = u.rol_id WHERE r.nombre = 'ADMIN' LIMIT 1", String.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null));
        try {
            DuplicadosInsumoResponse antes = fusionService.duplicados();
            assertThat(antes.indiceUnicoActivo()).isFalse();
            assertThat(antes.grupos()).hasSize(1);
            var insumos = antes.grupos().get(0).insumos();
            assertThat(insumos).hasSize(2);
            // Se conserva el que tiene la ortografia correcta (con tilde); el otro se fusiona en el.
            UUID destino = insumos.stream().filter(i -> i.nombre().equals("Azúcar")).findFirst().orElseThrow().id();
            UUID origen = insumos.stream().filter(i -> !i.id().equals(destino)).findFirst().orElseThrow().id();
            BigDecimal total = insumos.stream().map(DuplicadosInsumoResponse.Insumo::stock).reduce(BigDecimal.ZERO, BigDecimal::add);
            long movimientos = insumos.stream().mapToLong(DuplicadosInsumoResponse.Insumo::movimientos).sum();
            Integer insumosDescuadradosAntes = descuadrados();

            var vp = fusionService.vistaPrevia(origen, destino);
            assertThat(vp.fusionable()).as(String.join(" ", vp.impedimentos())).isTrue();
            FusionInsumoResponse r = fusionService.fusionar(origen, destino);

            assertThat(r.stockDestinoDespues()).isEqualByComparingTo(total);
            assertThat(jdbc.queryForObject("SELECT stock FROM insumo WHERE id = ?", BigDecimal.class, destino)).isEqualByComparingTo(total);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo WHERE insumo_id = ?", Long.class, destino)).isEqualTo(movimientos);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_insumo WHERE insumo_id = ?", Long.class, origen)).isZero();
            assertThat(descuadrados()).isEqualTo(insumosDescuadradosAntes).isZero();   // stock = SUM(movimientos) en TODOS los insumos
            assertThat(fusionService.duplicados().indiceUnicoActivo()).isTrue();
            assertThat(fusionService.duplicados().grupos()).isEmpty();
            System.out.println("[FUSION REAL] stock fusionado=" + total + " movimientos=" + movimientos + " recetas=" + r.recetas()
                    + " lotesProduccion=" + r.lotesProduccion() + " retiros=" + r.retiros());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Integer descuadrados() {
        return jdbc.queryForObject("SELECT count(*) FROM insumo i WHERE i.stock <> "
                + "(SELECT COALESCE(SUM(m.cantidad), 0) FROM movimiento_insumo m WHERE m.insumo_id = i.id)", Integer.class);
    }
}
