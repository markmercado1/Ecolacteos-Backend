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
 * Fase H, V33: la migracion es ADITIVA. Migra hasta V32, siembra una reserva retirada con su venta, migra V33 y verifica que no se crea
 * ninguna ficha de cliente ni autorizacion, que las ventas y reservas no cambian salvo el enlace venta -> reserva (ya existia al reves), y que
 * las restricciones se cumplen en la base.
 */
class MigracionClientesIntegrationTest {

    @Test
    void v33NoCreaFichasEnlazaLaVentaConSuReservaYSusRestriccionesSeCumplen() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("32").load().migrate();

        UUID rol = UUID.randomUUID(), usuario = UUID.randomUUID(), venta = UUID.randomUUID(), reserva = UUID.randomUUID(), anonima = UUID.randomUUID();
        UUID proveedor = UUID.randomUUID();
        String huella;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rol + "', 'VENTAS_V33_TEST')");
            st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES ('" + usuario + "', 'U', 'v33@planta.com', 'h', '" + rol + "', true)");
            st.execute("INSERT INTO proveedor (id, nombre, dni) VALUES ('" + proveedor + "', 'Juan', '33333333')");
            st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id) VALUES ('" + venta + "', 'v33-1', '2026-10-01', 'PUBLICO', 20, '" + usuario + "')");
            st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id) VALUES ('" + anonima + "', 'v33-2', '2026-10-01', 'MAYORISTA', 30, '" + usuario + "')");
            st.execute("INSERT INTO reserva (id, codigo, uuid_cliente, estado, nombre_cliente, telefono, fecha_retiro, fecha_maxima_retiro, creado_en, expira_en, total, venta_id) "
                    + "VALUES ('" + reserva + "', 'R33', 'r33', 'RETIRADA', 'Rosa', '+51987654321', '2026-10-01', '2026-10-04', now(), now(), 20, '" + venta + "')");
            huella = huella(st);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("33").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(huella(st)).isEqualTo(huella);
            assertThat(escalar(st, "SELECT count(*) FROM cliente")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM cliente_consentimiento")).isEqualTo("0");
            assertThat(escalar(st, "SELECT reserva_id FROM venta WHERE id = '" + venta + "'")).isEqualTo(reserva.toString());
            assertThat(escalar(st, "SELECT count(*) FROM venta WHERE comprador_tipo IS NULL AND cliente_id IS NULL AND proveedor_id IS NULL")).isEqualTo("2");

            UUID cliente = UUID.randomUUID();
            st.execute("INSERT INTO cliente (id, nombre, dni) VALUES ('" + cliente + "', 'Rosa', '04567890')");
            st.execute("INSERT INTO cliente_telefono (cliente_id, telefono) VALUES ('" + cliente + "', '+51987654321')");
            // otra ficha puede compartir el telefono, pero no el DNI
            UUID otra = UUID.randomUUID();
            st.execute("INSERT INTO cliente (id, nombre) VALUES ('" + otra + "', 'Luis')");
            st.execute("INSERT INTO cliente_telefono (cliente_id, telefono) VALUES ('" + otra + "', '+51987654321')");
            assertThatThrownBy(() -> st.execute("INSERT INTO cliente (nombre, dni) VALUES ('Duplicada', '04567890')")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_cliente_dni");
            assertThatThrownBy(() -> st.execute("INSERT INTO cliente_telefono (cliente_id, telefono) VALUES ('" + otra + "', '987654321')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_cliente_telefono_forma");
            assertThatThrownBy(() -> st.execute("INSERT INTO cliente (nombre, tipo_persona, ruc) VALUES ('Empresa', 'EMPRESA', '20601234565')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_cliente_documentos");
            // una autorizacion comercial otorgada siempre dice para que medios
            assertThatThrownBy(() -> st.execute("INSERT INTO cliente_consentimiento (cliente_id, finalidad, estado, forma, aviso_version, aviso_completo, registrado_por) "
                    + "VALUES ('" + cliente + "', 'COMUNICACIONES_COMERCIALES', 'OTORGADO', 'VERBAL_PRESENCIAL', 'v', false, '" + usuario + "')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_consentimiento_medios");
            // comprador coherente en la venta
            assertThatThrownBy(() -> st.execute("UPDATE venta SET comprador_tipo = 'CLIENTE' WHERE id = '" + anonima + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_venta_comprador");
            st.execute("UPDATE venta SET comprador_tipo = 'PROVEEDOR', proveedor_id = '" + proveedor + "', comprador_nombre = 'Juan' WHERE id = '" + anonima + "'");
            assertThatThrownBy(() -> st.execute("UPDATE venta SET cliente_id = '" + cliente + "' WHERE id = '" + anonima + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_venta_comprador");
            // una reserva tiene a lo sumo una venta
            assertThatThrownBy(() -> st.execute("UPDATE venta SET reserva_id = '" + reserva + "' WHERE id = '" + anonima + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_venta_reserva");
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String huella(Statement st) throws SQLException {
        return escalar(st, "SELECT (SELECT md5(string_agg(id || '|' || total || '|' || tipo_cliente, ',' ORDER BY id)) FROM venta) || '/' || "
                + "(SELECT md5(string_agg(id || '|' || estado || '|' || coalesce(total::text, '') || '|' || nombre_cliente, ',' ORDER BY id)) FROM reserva)");
    }
}
