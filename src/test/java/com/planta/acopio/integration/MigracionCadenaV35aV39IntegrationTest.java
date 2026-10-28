package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cadena completa V35 a V39 desde una base en V34, con datos: aplica las cinco migraciones EN ORDEN de una sola vez, comprueba el historial de Flyway y que
 * los datos existentes se conservan (conteos de precios, ventas, clientes, el permiso mayorista y los tres tipos originales). El segundo caso corre la
 * misma comprobacion sobre una COPIA de una base real en V34 si se indica su URL en ECO_MIGRACION_COPIA_URL (jdbc:postgresql://host:puerto/copia);
 * nunca debe apuntar a la base real.
 */
class MigracionCadenaV35aV39IntegrationTest {

    @Test
    void lasCincoMigracionesSeAplicanEnOrdenYConservanLosDatos() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("34").load().migrate();
        String antes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO cliente (tipo_persona, nombre, mayorista, mayorista_motivo, canal_habitual) VALUES ('NATURAL', 'Bodega Antigua', TRUE, 'Compra por mayor', 'MAYORISTA')");
            st.execute("INSERT INTO cliente (tipo_persona, nombre) VALUES ('NATURAL', 'Cliente Regular')");
            antes = huella(st);
        }
        migrarYVerificar(base[0], base[1], base[2], antes, true);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ECO_MIGRACION_COPIA_URL", matches = ".+")
    void lasCincoMigracionesSobreUnaCopiaDeUnaBaseRealEnV34() throws Exception {
        String url = System.getenv("ECO_MIGRACION_COPIA_URL");
        String usuario = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_USUARIO", "postgres");
        String clave = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_CLAVE", "postgres");
        String antes;
        int mayoristas;
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("34");
            antes = huella(st);
            mayoristas = Integer.parseInt(escalar(st, "SELECT count(*) FROM cliente WHERE mayorista"));
        }
        migrarYVerificar(url, usuario, clave, antes, false);
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(Integer.parseInt(escalar(st, "SELECT count(*) FROM cliente_tipo_precio WHERE tipo_codigo = 'MAYORISTA'"))).isEqualTo(mayoristas);
        }
    }

    private static void migrarYVerificar(String url, String usuario, String clave, String antes, boolean verificarPermiso) throws Exception {
        Flyway.configure().dataSource(url, usuario, clave).locations("classpath:db/migration").target("39").load().migrate();
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::int >= 35 AND success"))
                    .isEqualTo("35,36,37,38,39");
            assertThat(huella(st)).isEqualTo(antes);   // precios, ventas y clientes: ni uno menos
            assertThat(escalar(st, "SELECT string_agg(codigo || ':' || regla_comprador, ',' ORDER BY codigo) FROM tipo_precio WHERE codigo IN ('MAYORISTA','PROVEEDOR','PUBLICO')"))
                    .isEqualTo("MAYORISTA:CLIENTE_HABILITADO,PROVEEDOR:PROVEEDOR_PADRON,PUBLICO:CUALQUIERA");
            assertThat(escalar(st, "SELECT count(*) FROM tipo_precio WHERE activo")).isEqualTo("3");
            if (verificarPermiso) {
                assertThat(escalar(st, "SELECT h.motivo FROM cliente_tipo_precio h JOIN cliente c ON c.id = h.cliente_id WHERE c.nombre = 'Bodega Antigua'")).isEqualTo("Compra por mayor");
            }
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String huella(Statement st) throws SQLException {
        return escalar(st, "SELECT (SELECT count(*) FROM precio_presentacion) || '/' || (SELECT count(*) FROM venta) || '/' || (SELECT count(*) FROM venta_detalle) || '/' || (SELECT count(*) FROM cliente)"
                + " || '/' || (SELECT count(*) FROM presentacion) || '/' || (SELECT count(*) FROM movimiento_inventario)");
    }
}
