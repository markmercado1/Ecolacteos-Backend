package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase C (calidad por entrega), V29: la migracion es SOLO ADITIVA. Mismo patron que MigracionLitrosEfectivosIntegrationTest (Flyway directo
 * para sembrar datos "pre-V29"): migra hasta V28, siembra un pago con sus lineas, un analisis y ajustes de litros y de precio, y migra V29.
 * Verifica que nada de lo existente cambia, que la tabla nueva nace vacia y que sus restricciones se cumplen en la base.
 */
class MigracionAjusteCalidadEntregaIntegrationTest {

    @Test
    void v29NoTocaNadaLaTablaNaceVaciaYSusRestriccionesSeCumplen() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("28").load().migrate();

        UUID rol = UUID.randomUUID(), usuario = UUID.randomUUID(), unidad = UUID.randomUUID(), proveedor = UUID.randomUUID();
        UUID pago = UUID.randomUUID(), r1 = UUID.randomUUID(), r2 = UUID.randomUUID(), linea1 = UUID.randomUUID(), analisis1 = UUID.randomUUID();
        UUID correccion = UUID.randomUUID();
        String huellaAntes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rol + "', 'ACOPIADOR_V29_TEST')");
            st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES ('" + usuario + "', 'Usuario V29', 'v29@planta.com', 'hash', '" + rol + "', true)");
            st.execute("INSERT INTO unidad (id, placa, responsable_id) VALUES ('" + unidad + "', 'V29-001', '" + usuario + "')");
            st.execute("INSERT INTO proveedor (id, nombre, dni) VALUES ('" + proveedor + "', 'Proveedor V29', '29292929')");
            for (UUID r : new UUID[]{r1, r2}) {
                st.execute("INSERT INTO registro_acopio (id, uuid_cliente, proveedor_id, acopiador_id, unidad_id, fecha_hora, litros) VALUES ('" + r + "', '"
                        + UUID.randomUUID() + "', '" + proveedor + "', '" + usuario + "', '" + unidad + "', '2026-10-12 06:00:00', 100.00)");
            }
            st.execute("INSERT INTO pago (id, proveedor_id, semana_inicio, semana_fin, litros_totales, precio_litro, creado_en) VALUES ('" + pago + "', '" + proveedor
                    + "', '2026-10-12', '2026-10-18', 200.00, 1.000, '2026-10-19 10:00:00')");
            for (UUID r : new UUID[]{r1, r2}) {
                st.execute("INSERT INTO pago_registro_acopio (pago_id, registro_acopio_id) VALUES ('" + pago + "', '" + r + "')");
            }
            st.execute("INSERT INTO pago_linea (id, pago_id, registro_acopio_id, litros_originales, litros_liquidados, precio_base) VALUES ('" + linea1 + "', '"
                    + pago + "', '" + r1 + "', 100.00, 100.00, 1.000)");
            st.execute("INSERT INTO pago_linea (pago_id, registro_acopio_id, litros_originales, litros_liquidados, precio_base) VALUES ('" + pago + "', '" + r2 + "', 100.00, 100.00, 1.000)");
            st.execute("INSERT INTO analisis_calidad (id, uuid_cliente, registro_acopio_id, folio_muestra, resultado) VALUES ('" + analisis1 + "', '" + UUID.randomUUID()
                    + "', '" + r1 + "', 'F-V29', 'OBSERVADO')");
            st.execute("INSERT INTO correccion_registro (id, registro_acopio_id, usuario_id, litros_anterior, litros_corregido, motivo) VALUES ('" + correccion + "', '" + r1
                    + "', '" + usuario + "', 100.00, 95.00, 'Medicion')");
            st.execute("INSERT INTO ajuste_pago (pago_id, registro_acopio_id, correccion_registro_id, litros_diferencia, precio_litro, litros_contabilizados_antes) VALUES ('"
                    + pago + "', '" + r1 + "', '" + correccion + "', -5.00, 1.000, 100.00)");
            st.execute("INSERT INTO ajuste_precio_pago (pago_id, precio_litro_anterior, precio_litro_nuevo, motivo, usuario_id) VALUES ('" + pago + "', 1.000, 0.950, 'Ajuste', '" + usuario + "')");
        }
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2])) {
            huellaAntes = huella(c);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("29").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(huella(c)).isEqualTo(huellaAntes);                                    // nada existente cambia
            assertThat(escalar(st, "SELECT count(*) FROM ajuste_calidad_entrega")).isEqualTo("0"); // nace vacia

            // un precio de calidad valido entra
            st.execute("INSERT INTO ajuste_calidad_entrega (pago_linea_id, precio_anterior, precio_nuevo, analisis_calidad_id, motivo, usuario_id) VALUES ('" + linea1
                    + "', 1.000, 0.900, '" + analisis1 + "', 'Agua en la leche', '" + usuario + "')");

            // restricciones de la base
            assertThatThrownBy(() -> st.execute(insertar(linea1, "1.000", "1.000", analisis1, "Sin cambio", usuario))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_ajuste_calidad_entrega_cambia");
            assertThatThrownBy(() -> st.execute(insertar(linea1, "1.000", "0", analisis1, "Precio cero", usuario))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(insertar(linea1, "1.000", "0.800", analisis1, "   ", usuario))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(insertar(linea1, "1.000", "0.800", UUID.randomUUID(), "Analisis inexistente", usuario)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(insertar(UUID.randomUUID(), "1.000", "0.800", analisis1, "Linea inexistente", usuario)))
                    .isInstanceOf(SQLException.class);
            assertThat(escalar(st, "SELECT count(*) FROM ajuste_calidad_entrega")).isEqualTo("1");
        }
    }

    private static String insertar(UUID linea, String anterior, String nuevo, UUID analisis, String motivo, UUID usuario) {
        return "INSERT INTO ajuste_calidad_entrega (pago_linea_id, precio_anterior, precio_nuevo, analisis_calidad_id, motivo, usuario_id) VALUES ('" + linea + "', "
                + anterior + ", " + nuevo + ", '" + analisis + "', '" + motivo + "', '" + usuario + "')";
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Huella de lo que V29 NO debe tocar. */
    private static String huella(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            return escalar(st, "SELECT (SELECT md5(string_agg(id || '|' || litros_totales || '|' || precio_litro, ',' ORDER BY id)) FROM pago) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || pago_id || '|' || registro_acopio_id || '|' || litros_liquidados || '|' || precio_base, ',' ORDER BY id)) FROM pago_linea) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || folio_muestra || '|' || resultado, ',' ORDER BY id)) FROM analisis_calidad) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || litros_diferencia || '|' || litros_contabilizados_antes, ',' ORDER BY id)) FROM ajuste_pago) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || precio_litro_nuevo, ',' ORDER BY id)) FROM ajuste_precio_pago)");
        }
    }
}
