package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tipos de precio dinamicos, V39: migra hasta V38 con un cliente mayorista, precios y ventas de los tres tipos originales; migra V39 y verifica que
 * nada se pierde (mismos conteos y codigos), que el cliente conserva su permiso mayorista en cliente_tipo_precio con su motivo, que cada tipo original
 * trae su regla de comprador y que la integridad pasa del CHECK de tres valores a la clave foranea del catalogo (un tipo nuevo se admite; uno que
 * no existe, no).
 */
class MigracionTiposDinamicosIntegrationTest {

    @Test
    void v39ConservaDatosYPermisosYPasaDelCheckALaClaveForanea() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("38").load().migrate();
        String antes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO cliente (tipo_persona, nombre, mayorista, mayorista_motivo, canal_habitual) VALUES ('NATURAL', 'Bodega Antigua', TRUE, 'Compra por mayor', 'MAYORISTA')");
            st.execute("INSERT INTO cliente (tipo_persona, nombre) VALUES ('NATURAL', 'Cliente Regular')");
            antes = huella(st);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("39").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(huella(st)).isEqualTo(antes);
            assertThat(escalar(st, "SELECT string_agg(codigo || ':' || regla_comprador, ',' ORDER BY codigo) FROM tipo_precio"))
                    .isEqualTo("MAYORISTA:CLIENTE_HABILITADO,PROVEEDOR:PROVEEDOR_PADRON,PUBLICO:CUALQUIERA");
            // el mayorista conserva su permiso con el motivo que ya tenia; el regular no recibe ninguno
            assertThat(escalar(st, "SELECT count(*) FROM cliente_tipo_precio")).isEqualTo("1");
            assertThat(escalar(st, "SELECT h.motivo FROM cliente_tipo_precio h JOIN cliente c ON c.id = h.cliente_id WHERE c.nombre = 'Bodega Antigua' AND h.tipo_codigo = 'MAYORISTA'"))
                    .isEqualTo("Compra por mayor");
            assertThat(escalar(st, "SELECT canal_habitual FROM cliente WHERE nombre = 'Bodega Antigua'")).isEqualTo("MAYORISTA");   // nada cambia en silencio

            // integridad: un codigo que no existe en el catalogo se rechaza; uno nuevo del catalogo se admite
            assertThatThrownBy(() -> st.execute("UPDATE cliente SET canal_habitual = 'NOEXISTE' WHERE nombre = 'Cliente Regular'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO tipo_precio (codigo, nombre, descripcion) VALUES ('mal codigo', 'Malo', 'x')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO tipo_precio (codigo, nombre, descripcion, regla_comprador) VALUES ('RARO', 'Raro', 'x', 'OTRA')")).isInstanceOf(SQLException.class);
            st.execute("INSERT INTO tipo_precio (codigo, nombre, descripcion, regla_comprador) VALUES ('DISTRIBUIDOR', 'Distribuidor', 'Reventa', 'CLIENTE_HABILITADO')");
            st.execute("UPDATE cliente SET canal_habitual = 'DISTRIBUIDOR' WHERE nombre = 'Cliente Regular'");
            assertThatThrownBy(() -> st.execute("INSERT INTO cliente_tipo_precio (cliente_id, tipo_codigo, motivo) SELECT id, 'NOEXISTE', 'x' FROM cliente LIMIT 1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO cliente_tipo_precio (cliente_id, tipo_codigo, motivo) SELECT id, 'DISTRIBUIDOR', '  ' FROM cliente LIMIT 1")).isInstanceOf(SQLException.class);
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String huella(Statement st) throws SQLException {
        return escalar(st, "SELECT (SELECT count(*) FROM precio_presentacion) || '/' || (SELECT count(*) FROM venta) || '/' || (SELECT count(*) FROM cliente)");
    }
}
