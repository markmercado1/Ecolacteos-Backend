package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
 * Fase B (litros efectivos), V28: la migracion es SOLO ADITIVA. Mismo patron que MigracionZonasHistoricasIntegrationTest (Flyway
 * directo para sembrar datos "pre-V28"): migra hasta V27, siembra pagos con entregas corregidas -- incluido el caso de una entrega
 * cuyo registro.litros ya fue alterado fuera del codigo -- y ajustes de litros PENDIENTE y LIQUIDADO, y migra V28.
 *
 * Verifica que nada de lo existente cambia, que cada linea recibe lo realmente liquidado (y el original verdadero), que los ajustes
 * conservan su base, que una entrega no puede estar en dos pagos y que un pago que no suma sus litros DETIENE la migracion sin
 * dejar nada a medias.
 */
class MigracionLitrosEfectivosIntegrationTest {

    @Test
    void v28ConservaTodoCreaLasLineasConLoLiquidadoYRespaldaLosAjustes() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("27").load().migrate();

        UUID rol = UUID.randomUUID(), usuario = UUID.randomUUID(), unidad = UUID.randomUUID(), proveedor = UUID.randomUUID();
        UUID pago1 = UUID.randomUUID(), pago2 = UUID.randomUUID();
        UUID r1 = UUID.randomUUID(), r2 = UUID.randomUUID(), r3 = UUID.randomUUID(), r4 = UUID.randomUUID(), r5 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID(), c3 = UUID.randomUUID(), c4 = UUID.randomUUID();
        UUID ajustePendiente = UUID.randomUUID(), ajusteLiquidado = UUID.randomUUID();

        String huellaAntes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rol + "', 'ACOPIADOR_V28_TEST')");
            st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES ('" + usuario + "', 'Usuario V28', 'v28@planta.com', 'hash', '" + rol + "', true)");
            st.execute("INSERT INTO unidad (id, placa, responsable_id) VALUES ('" + unidad + "', 'V28-001', '" + usuario + "')");
            st.execute("INSERT INTO proveedor (id, nombre, dni) VALUES ('" + proveedor + "', 'Proveedor V28', '28282828')");

            // Entregas. r2 viene con registro.litros ya ALTERADO (90) aunque la correccion guarda el original (100), como en la BD de desarrollo.
            entrega(st, r1, proveedor, usuario, unidad, "2026-10-12 06:00:00", "50.00");
            entrega(st, r2, proveedor, usuario, unidad, "2026-10-13 06:00:00", "90.00");
            entrega(st, r3, proveedor, usuario, unidad, "2026-10-14 06:00:00", "40.00");
            entrega(st, r4, proveedor, usuario, unidad, "2026-10-21 06:00:00", "20.00");
            entrega(st, r5, proveedor, usuario, unidad, "2026-10-22 06:00:00", "10.00"); // sin liquidar

            // pago1 (semana 12-18 oct, creado el 19): r1 50 + r2 90 + r3 40 = 180 a 1.800
            st.execute("INSERT INTO pago (id, proveedor_id, semana_inicio, semana_fin, litros_totales, precio_litro, creado_en) VALUES ('" + pago1
                    + "', '" + proveedor + "', '2026-10-12', '2026-10-18', 180.00, 1.800, '2026-10-19 10:00:00')");
            for (UUID r : new UUID[]{r1, r2, r3}) {
                st.execute("INSERT INTO pago_registro_acopio (pago_id, registro_acopio_id) VALUES ('" + pago1 + "', '" + r + "')");
            }
            // pago2 (semana 19-25 oct, creado el 26): r4 20 a 1.500
            st.execute("INSERT INTO pago (id, proveedor_id, semana_inicio, semana_fin, litros_totales, precio_litro, creado_en) VALUES ('" + pago2
                    + "', '" + proveedor + "', '2026-10-19', '2026-10-25', 20.00, 1.500, '2026-10-26 10:00:00')");
            st.execute("INSERT INTO pago_registro_acopio (pago_id, registro_acopio_id) VALUES ('" + pago2 + "', '" + r4 + "')");

            // Correcciones. c2: 100 -> 90 ANTES de liquidar (el 15, el pago es del 19). c3: 40 -> 35 DESPUES (el 20). c4: 20 -> 18 DESPUES (el 27).
            correccion(st, c2, r2, usuario, "100.00", "90.00", "Error de digitacion", "2026-10-15 09:00:00");
            correccion(st, c3, r3, usuario, "40.00", "35.00", "Balde mal medido", "2026-10-20 09:00:00");
            correccion(st, c4, r4, usuario, "20.00", "18.00", "Doble conteo", "2026-10-27 09:00:00");
            st.execute("INSERT INTO ajuste_pago (id, pago_id, registro_acopio_id, correccion_registro_id, litros_diferencia, precio_litro, estado, creado_en) VALUES ('"
                    + ajustePendiente + "', '" + pago1 + "', '" + r3 + "', '" + c3 + "', -5.00, 1.800, 'PENDIENTE', '2026-10-20 09:00:00')");
            st.execute("INSERT INTO ajuste_pago (id, pago_id, registro_acopio_id, correccion_registro_id, litros_diferencia, precio_litro, estado, creado_en) VALUES ('"
                    + ajusteLiquidado + "', '" + pago2 + "', '" + r4 + "', '" + c4 + "', -2.00, 1.500, 'LIQUIDADO', '2026-10-27 09:00:00')");
        }
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2])) {
            huellaAntes = huella(c);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("28").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            // 1. Nada de lo existente cambia: ni entregas, ni pagos, ni sus uniones, ni correcciones, ni los datos de los ajustes.
            assertThat(huella(c)).isEqualTo(huellaAntes);

            // 2. Una linea por entrega liquidada; r5 (sin liquidar) no tiene.
            assertThat(escalar(st, "SELECT count(*) FROM pago_linea")).isEqualTo("4");
            assertThat(escalar(st, "SELECT count(*) FROM pago_linea WHERE registro_acopio_id = '" + r5 + "'")).isEqualTo("0");

            // 3. Sin correccion: original = liquidado = registro.litros, sin correccion aplicada.
            assertThat(linea(st, r1)).isEqualTo("50.00|50.00|1.800|-");
            // 4. Correccion ANTERIOR al pago sobre un registro alterado: original VERDADERO (100) y liquidado = lo pagado (90), con su correccion.
            assertThat(linea(st, r2)).isEqualTo("100.00|90.00|1.800|" + c2);
            // 5. Correccion POSTERIOR al pago: lo liquidado fue el original (40); esa correccion no es "aplicada" (genero un ajuste).
            assertThat(linea(st, r3)).isEqualTo("40.00|40.00|1.800|-");
            assertThat(linea(st, r4)).isEqualTo("20.00|20.00|1.500|-");

            // 6. Cada pago suma exactamente lo que liquido (la propia migracion lo exige; se vuelve a comprobar).
            assertThat(escalar(st, "SELECT count(*) FROM pago p WHERE p.litros_totales <> (SELECT sum(l.litros_liquidados) FROM pago_linea l WHERE l.pago_id = p.id)")).isEqualTo("0");

            // 7. Ajustes existentes: base = corregido - diferencia; el liquidado congela el precio con el que se creo.
            assertThat(escalar(st, "SELECT litros_contabilizados_antes || '|' || COALESCE(precio_liquidado::text, '-') FROM ajuste_pago WHERE id = '" + ajustePendiente + "'"))
                    .isEqualTo("40.00|-");
            assertThat(escalar(st, "SELECT litros_contabilizados_antes || '|' || COALESCE(precio_liquidado::text, '-') FROM ajuste_pago WHERE id = '" + ajusteLiquidado + "'"))
                    .isEqualTo("20.00|1.500");

            // 8. Una entrega no puede estar en dos pagos (indice unico nuevo).
            UUID otroPago = UUID.randomUUID();
            st.execute("INSERT INTO pago (id, proveedor_id, semana_inicio, semana_fin, litros_totales, precio_litro) VALUES ('" + otroPago + "', '" + proveedor
                    + "', '2026-11-02', '2026-11-08', 10.00, 1.000)");
            assertThatThrownBy(() -> st.execute("INSERT INTO pago_registro_acopio (pago_id, registro_acopio_id) VALUES ('" + otroPago + "', '" + r1 + "')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_pago_registro_acopio_registro");
            assertThatThrownBy(() -> st.execute("INSERT INTO pago_linea (pago_id, registro_acopio_id, litros_originales, litros_liquidados, precio_base) VALUES ('"
                    + otroPago + "', '" + r1 + "', 10, 10, 1)")).isInstanceOf(SQLException.class).hasMessageContaining("uq_pago_linea_registro");
        }
    }

    // Un pago que no suma sus litros_totales con las entregas que liquida no se puede atribuir sin inventar: la migracion se detiene.
    @Test
    void v28SeDetieneSiAlgunPagoNoSumaSusLitrosYNoDejaNadaAMedias() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("27").load().migrate();

        UUID rol = UUID.randomUUID(), usuario = UUID.randomUUID(), unidad = UUID.randomUUID(), proveedor = UUID.randomUUID();
        UUID pago = UUID.randomUUID(), r1 = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rol + "', 'ACOPIADOR_V28_TEST2')");
            st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES ('" + usuario + "', 'Usuario V28', 'v28b@planta.com', 'hash', '" + rol + "', true)");
            st.execute("INSERT INTO unidad (id, placa, responsable_id) VALUES ('" + unidad + "', 'V28-002', '" + usuario + "')");
            st.execute("INSERT INTO proveedor (id, nombre, dni) VALUES ('" + proveedor + "', 'Proveedor V28 b', '28282829')");
            entrega(st, r1, proveedor, usuario, unidad, "2026-10-12 06:00:00", "90.00");
            st.execute("INSERT INTO pago (id, proveedor_id, semana_inicio, semana_fin, litros_totales, precio_litro, creado_en) VALUES ('" + pago
                    + "', '" + proveedor + "', '2026-10-12', '2026-10-18', 100.00, 1.000, '2026-10-19 10:00:00')"); // dice 100 pero la entrega tiene 90
            st.execute("INSERT INTO pago_registro_acopio (pago_id, registro_acopio_id) VALUES ('" + pago + "', '" + r1 + "')");
        }

        assertThatThrownBy(() -> Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("28").load().migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("V28 detenida");

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT to_regclass('pago_linea')")).isNull();                       // la tabla nueva no quedo
            assertThat(escalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'ajuste_pago' AND column_name = 'precio_liquidado'"))
                    .isEqualTo("0");                                                                    // ni las columnas nuevas
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("27");
        }
    }

    private static void entrega(Statement st, UUID id, UUID proveedor, UUID usuario, UUID unidad, String fechaHora, String litros) throws SQLException {
        st.execute("INSERT INTO registro_acopio (id, uuid_cliente, proveedor_id, acopiador_id, unidad_id, fecha_hora, litros) VALUES ('" + id + "', '"
                + UUID.randomUUID() + "', '" + proveedor + "', '" + usuario + "', '" + unidad + "', '" + fechaHora + "', " + litros + ")");
    }

    private static void correccion(Statement st, UUID id, UUID registro, UUID usuario, String anterior, String corregido, String motivo, String creadoEn)
            throws SQLException {
        st.execute("INSERT INTO correccion_registro (id, registro_acopio_id, usuario_id, litros_anterior, litros_corregido, motivo, creado_en) VALUES ('" + id
                + "', '" + registro + "', '" + usuario + "', " + anterior + ", " + corregido + ", '" + motivo + "', '" + creadoEn + "')");
    }

    private static String linea(Statement st, UUID registro) throws SQLException {
        return escalar(st, "SELECT litros_originales || '|' || litros_liquidados || '|' || precio_base || '|' || COALESCE(correccion_aplicada_id::text, '-') "
                + "FROM pago_linea WHERE registro_acopio_id = '" + registro + "'");
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Huella de lo que V28 NO debe tocar. */
    private static String huella(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            return escalar(st, "SELECT (SELECT md5(string_agg(id || '|' || litros || '|' || fecha_hora, ',' ORDER BY id)) FROM registro_acopio) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || litros_totales || '|' || precio_litro || '|' || creado_en, ',' ORDER BY id)) FROM pago) || '/' || "
                    + "(SELECT md5(string_agg(pago_id || '|' || registro_acopio_id, ',' ORDER BY pago_id, registro_acopio_id)) FROM pago_registro_acopio) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || litros_anterior || '|' || litros_corregido || '|' || motivo, ',' ORDER BY id)) FROM correccion_registro) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || litros_diferencia || '|' || precio_litro || '|' || estado, ',' ORDER BY id)) FROM ajuste_pago)");
        }
    }
}
