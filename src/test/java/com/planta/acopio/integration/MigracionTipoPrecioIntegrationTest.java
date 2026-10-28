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
 * Tipos de precio administrables, V37: migracion SOLO ADITIVA. Migra hasta V36, guarda una huella de precios, ventas y clientes, migra V37 y
 * verifica que nada cambia, que nacen los tres tipos activos (PUBLICO protegido) y que la base impide codigos fuera del conjunto, protegidos
 * desactivados y cambios sin motivo.
 */
class MigracionTipoPrecioIntegrationTest {

    @Test
    void v37NoTocaLoExistenteSiembraLosTresTiposYProtegeAPublico() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("36").load().migrate();
        String antes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            antes = huella(st);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("37").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(huella(st)).isEqualTo(antes);
            assertThat(escalar(st, "SELECT string_agg(codigo || ':' || activo || ':' || protegido, ',' ORDER BY codigo) FROM tipo_precio"))
                    .isEqualTo("MAYORISTA:true:false,PROVEEDOR:true:false,PUBLICO:true:true");
            assertThat(escalar(st, "SELECT count(*) FROM cambio_tipo_precio")).isEqualTo("0");
            assertThatThrownBy(() -> st.execute("INSERT INTO tipo_precio (codigo, nombre, descripcion) VALUES ('MAYORISTA', 'Repetido', 'x')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO tipo_precio (codigo, nombre, descripcion) VALUES ('OTRO', 'Otro', 'x')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("UPDATE tipo_precio SET activo = false WHERE codigo = 'PUBLICO'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO cambio_tipo_precio (codigo, activo, motivo) VALUES ('MAYORISTA', false, '  ')")).isInstanceOf(SQLException.class);
            st.execute("INSERT INTO cambio_tipo_precio (codigo, activo, motivo) VALUES ('MAYORISTA', false, 'Prueba')");
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
