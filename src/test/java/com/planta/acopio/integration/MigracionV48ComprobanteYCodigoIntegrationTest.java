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
 * V48 sobre una base en V47 con datos: las ventas existentes se numeran UNA vez como nota de venta (NV01) en orden cronologico,
 * las presentaciones existentes reciben un codigo PRD-NNN en orden alfabetico, los datos anteriores no cambian y los INSERT que no
 * mencionan las columnas nuevas (la app movil, las pruebas) siguen funcionando con los valores por defecto de la BD.
 */
class MigracionV48ComprobanteYCodigoIntegrationTest {

    private static void migrar(String[] base, String objetivo) {
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target(objetivo).load().migrate();
    }

    @Test
    void lasVentasYPresentacionesExistentesSeNumeranEnOrdenYLosInsertsAntiguosSiguenFuncionando() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        migrar(base, "47");

        UUID rol = UUID.randomUUID(), usuario = UUID.randomUUID(), familia = UUID.randomUUID(), producto = UUID.randomUUID(), producto2 = UUID.randomUUID();
        UUID presA = UUID.randomUUID(), presB = UUID.randomUUID(), presC = UUID.randomUUID();
        UUID ventaVieja = UUID.randomUUID(), ventaMedia = UUID.randomUUID(), ventaNueva = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rol + "', 'VENTAS_V48_TEST')");
            st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES ('" + usuario + "', 'U V48', 'v48@planta.com', 'h', '" + rol + "', true)");
            st.execute("INSERT INTO familia (id, nombre) VALUES ('" + familia + "', 'Lacteos V48')");
            st.execute("INSERT INTO producto (id, nombre, familia_id) VALUES ('" + producto + "', 'Yogurt V48', '" + familia + "')");
            st.execute("INSERT INTO producto (id, nombre, familia_id) VALUES ('" + producto2 + "', 'Leche V48', '" + familia + "')");
            // se insertan en desorden a proposito: el codigo sigue el orden alfabetico (producto, presentacion), no el de insercion
            st.execute("INSERT INTO presentacion (id, producto_id, nombre, unidad_conteo) VALUES ('" + presC + "', '" + producto + "', 'Vaso', 'vaso')");
            st.execute("INSERT INTO presentacion (id, producto_id, nombre, unidad_conteo) VALUES ('" + presA + "', '" + producto2 + "', 'Botella', 'botella')");
            st.execute("INSERT INTO presentacion (id, producto_id, nombre, unidad_conteo) VALUES ('" + presB + "', '" + producto + "', 'Botella', 'botella')");
            // la fecha manda sobre el orden de insercion
            st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id) VALUES ('" + ventaNueva + "', 'v48-3', '2026-10-20', 'PUBLICO', 30, '" + usuario + "')");
            st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id) VALUES ('" + ventaVieja + "', 'v48-1', '2026-10-01', 'PUBLICO', 10, '" + usuario + "')");
            st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id) VALUES ('" + ventaMedia + "', 'v48-2', '2026-10-10', 'MAYORISTA', 20, '" + usuario + "')");
        }

        migrar(base, "48");

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("48");

            // ventas: NV01 en orden cronologico, todas notas de venta, datos anteriores intactos
            assertThat(escalar(st, "SELECT string_agg(uuid_cliente || '=' || serie || '-' || numero, ',' ORDER BY numero) FROM venta"))
                    .isEqualTo("v48-1=NV01-1,v48-2=NV01-2,v48-3=NV01-3");
            assertThat(escalar(st, "SELECT count(*) FROM venta WHERE tipo_comprobante = 'NOTA_VENTA'")).isEqualTo("3");
            assertThat(escalar(st, "SELECT string_agg(total::text, ',' ORDER BY fecha) FROM venta")).isEqualTo("10.00,20.00,30.00");

            // presentaciones: alfabetico por producto y presentacion (Leche/Botella, Yogurt/Botella, Yogurt/Vaso)
            assertThat(escalar(st, "SELECT string_agg(codigo, ',' ORDER BY id = '" + presA + "' DESC, id = '" + presB + "' DESC) FROM presentacion"))
                    .isEqualTo("PRD-001,PRD-002,PRD-003");
            assertThat(escalar(st, "SELECT codigo FROM presentacion WHERE id = '" + presB + "'")).isEqualTo("PRD-002");
            assertThat(escalar(st, "SELECT codigo FROM presentacion WHERE id = '" + presC + "'")).isEqualTo("PRD-003");

            // un INSERT que no menciona las columnas nuevas (como el de la app movil) recibe los valores por defecto y la numeracion continua
            UUID otra = UUID.randomUUID();
            st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id) VALUES ('" + otra + "', 'v48-4', '2026-10-25', 'PUBLICO', 5, '" + usuario + "')");
            assertThat(escalar(st, "SELECT tipo_comprobante || ' ' || serie || '-' || numero FROM venta WHERE id = '" + otra + "'")).isEqualTo("NOTA_VENTA NV01-4");
            UUID nuevaPres = UUID.randomUUID();
            st.execute("INSERT INTO presentacion (id, producto_id, nombre, unidad_conteo) VALUES ('" + nuevaPres + "', '" + producto + "', 'Galon', 'galon')");
            assertThat(escalar(st, "SELECT codigo FROM presentacion WHERE id = '" + nuevaPres + "'")).isEqualTo("PRD-004");

            // una boleta lleva su propia serie y su propia secuencia
            UUID boleta = UUID.randomUUID();
            st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id, tipo_comprobante, serie, numero) VALUES ('" + boleta
                    + "', 'v48-5', '2026-10-26', 'PUBLICO', 7, '" + usuario + "', 'BOLETA', 'B001', nextval('venta_b001_seq'))");
            assertThat(escalar(st, "SELECT serie || '-' || numero FROM venta WHERE id = '" + boleta + "'")).isEqualTo("B001-1");

            // invariantes: la serie corresponde al tipo, el numero no se repite dentro de la serie, el codigo es unico
            assertThatThrownBy(() -> st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id, tipo_comprobante, serie, numero) VALUES ('"
                    + UUID.randomUUID() + "', 'v48-6', '2026-10-26', 'PUBLICO', 7, '" + usuario + "', 'BOLETA', 'NV01', 99)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_venta_comprobante");
            assertThatThrownBy(() -> st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id, serie, numero) VALUES ('"
                    + UUID.randomUUID() + "', 'v48-7', '2026-10-26', 'PUBLICO', 7, '" + usuario + "', 'NV01', 1)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_venta_serie_numero");
            assertThatThrownBy(() -> st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id, tipo_comprobante) VALUES ('"
                    + UUID.randomUUID() + "', 'v48-8', '2026-10-26', 'PUBLICO', 7, '" + usuario + "', 'FACTURA')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("UPDATE presentacion SET codigo = 'PRD-001' WHERE id = '" + presC + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_presentacion_codigo");
        }
    }

    @Test
    void sobreUnaBaseSinVentasNiPresentacionesLaMigracionSeAplicaYLaNumeracionEmpiezaEnUno() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        migrar(base, "48");
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT last_value || '/' || is_called FROM venta_nv01_seq")).isEqualTo("1/false");
            assertThat(escalar(st, "SELECT last_value || '/' || is_called FROM venta_b001_seq")).isEqualTo("1/false");
            assertThat(escalar(st, "SELECT last_value || '/' || is_called FROM presentacion_codigo_seq")).isEqualTo("1/false");
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
