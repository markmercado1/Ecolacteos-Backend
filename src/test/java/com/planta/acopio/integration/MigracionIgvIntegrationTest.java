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
 * IGV configurable, V32: la migracion es NEUTRA. Migra hasta V31, siembra una venta con su linea, migra V32 y verifica que la venta y
 * sus importes no cambian, que su linea queda "sin determinar" (todo NULL), que no se crea ninguna tasa ni clasificacion, y que las
 * restricciones de la foto tributaria se cumplen en la base (valor + IGV = subtotal; exonerado/inafecto con IGV 0).
 */
class MigracionIgvIntegrationTest {

    @Test
    void v32NoTocaVentasNiCreaConfiguracionYLaFotoTributariaSeValidaEnLaBase() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("31").load().migrate();

        UUID rol = UUID.randomUUID(), usuario = UUID.randomUUID(), familia = UUID.randomUUID(), producto = UUID.randomUUID();
        UUID presentacion = UUID.randomUUID(), venta = UUID.randomUUID(), linea = UUID.randomUUID();
        String huellaAntes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rol + "', 'VENTAS_V32_TEST')");
            st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES ('" + usuario + "', 'U V32', 'v32@planta.com', 'h', '" + rol + "', true)");
            st.execute("INSERT INTO familia (id, nombre) VALUES ('" + familia + "', 'Quesos V32')");
            st.execute("INSERT INTO producto (id, nombre, familia_id) VALUES ('" + producto + "', 'Queso V32', '" + familia + "')");
            st.execute("INSERT INTO presentacion (id, producto_id, nombre, unidad_conteo) VALUES ('" + presentacion + "', '" + producto + "', 'Pieza', 'pieza')");
            st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id) VALUES ('" + venta + "', 'v32-1', '2026-10-01', 'PUBLICO', 47.20, '" + usuario + "')");
            st.execute("INSERT INTO venta_detalle (id, venta_id, presentacion_id, cantidad, precio_unitario) VALUES ('" + linea + "', '" + venta + "', '" + presentacion + "', 2, 23.60)");
            huellaAntes = huella(st);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("32").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(huella(st)).isEqualTo(huellaAntes);
            assertThat(escalar(st, "SELECT count(*) FROM tasa_igv")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM afectacion_igv_presentacion")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM venta_detalle WHERE afectacion_igv IS NULL AND tasa_igv IS NULL AND valor_venta IS NULL AND igv IS NULL"))
                    .isEqualTo("1");

            // foto valida: gravada que suma el subtotal (47.20 = 40.00 + 7.20)
            st.execute("UPDATE venta_detalle SET afectacion_igv = 'GRAVADO', tasa_igv = 18, valor_venta = 40.00, igv = 7.20 WHERE id = '" + linea + "'");
            // foto invalida: no suma el subtotal, exonerada con IGV, gravada sin tasa, valor desconocido
            assertThatThrownBy(() -> st.execute("UPDATE venta_detalle SET valor_venta = 40.01 WHERE id = '" + linea + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_venta_detalle_igv");
            assertThatThrownBy(() -> st.execute("UPDATE venta_detalle SET afectacion_igv = 'EXONERADO', tasa_igv = NULL WHERE id = '" + linea + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_venta_detalle_igv");
            assertThatThrownBy(() -> st.execute("UPDATE venta_detalle SET tasa_igv = NULL WHERE id = '" + linea + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_venta_detalle_igv");
            assertThatThrownBy(() -> st.execute("UPDATE venta_detalle SET afectacion_igv = 'OTRO' WHERE id = '" + linea + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_venta_detalle_igv");
            st.execute("UPDATE venta_detalle SET afectacion_igv = 'EXONERADO', tasa_igv = NULL, valor_venta = 47.20, igv = 0 WHERE id = '" + linea + "'");

            // tasas y afectaciones
            st.execute("INSERT INTO tasa_igv (tasa, vigente_desde, motivo) VALUES (18, '2026-01-01', 'Tasa general')");
            assertThatThrownBy(() -> st.execute("INSERT INTO tasa_igv (tasa, vigente_desde, motivo) VALUES (10, '2026-01-01', 'Repetida')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO tasa_igv (tasa, vigente_desde, motivo) VALUES (0, '2026-02-01', 'Cero')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_tasa_igv_rango");
            assertThatThrownBy(() -> st.execute("INSERT INTO tasa_igv (tasa, vigente_desde, motivo) VALUES (18, '2026-03-01', '  ')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_tasa_igv_motivo");
            st.execute("INSERT INTO afectacion_igv_presentacion (presentacion_id, afectacion, vigente_desde, motivo) VALUES ('" + presentacion + "', 'GRAVADO', '2026-01-01', 'Contador')");
            assertThatThrownBy(() -> st.execute("INSERT INTO afectacion_igv_presentacion (presentacion_id, afectacion, vigente_desde, motivo) VALUES ('"
                    + presentacion + "', 'EXONERADO', '2026-01-01', 'Misma fecha')")).isInstanceOf(SQLException.class).hasMessageContaining("uq_afectacion_igv_vigencia");
            assertThatThrownBy(() -> st.execute("INSERT INTO afectacion_igv_presentacion (presentacion_id, afectacion, vigente_desde, motivo) VALUES ('"
                    + presentacion + "', 'EXENTO', '2026-02-01', 'x')")).isInstanceOf(SQLException.class).hasMessageContaining("ck_afectacion_igv_valor");
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String huella(Statement st) throws SQLException {
        return escalar(st, "SELECT (SELECT md5(string_agg(id || '|' || total || '|' || fecha, ',' ORDER BY id)) FROM venta) || '/' || "
                + "(SELECT md5(string_agg(id || '|' || cantidad || '|' || precio_unitario || '|' || subtotal, ',' ORDER BY id)) FROM venta_detalle)");
    }
}
