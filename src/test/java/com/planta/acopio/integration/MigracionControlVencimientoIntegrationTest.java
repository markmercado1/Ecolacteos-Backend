package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase 1 (control de vencimiento), V21: la migracion es SOLO ADITIVA. Con el mismo patron de
 * MigracionProductoPresentacionIntegrationTest (Flyway directo, sin @SpringBootTest, para poder sembrar datos
 * "pre-V21"): migra hasta V20, siembra un producto con una produccion, una venta y un ajuste, toma una huella del
 * libro y migra V21. Verifica que NADA del libro cambia -- conteo, suma, huella de los movimientos, saldos
 * acumulados (lo que lee el kardex), stock por presentacion y la DEFINICION de movimiento_inventario (columnas,
 * indices, restricciones) -- y que lo nuevo nace vacio/apagado.
 */
@Testcontainers
class MigracionControlVencimientoIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("acopio_lechero_migracion_vencimiento_test")
            .withUsername("acopio")
            .withPassword("acopio");

    private record Huella(long movimientos, long suma, String libro, String saldosKardex, String stockPorPresentacion, String definicionTabla) {}

    @Test
    void v21NoTocaElLibroNiElKardexYLoNuevoNaceApagado() throws Exception {
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").target("20").load().migrate();

        UUID rolId = UUID.randomUUID();
        UUID usuarioId = UUID.randomUUID();
        UUID familiaId = UUID.randomUUID();
        UUID productoId = UUID.randomUUID();
        UUID presentacionId = UUID.randomUUID();
        UUID loteId = UUID.randomUUID();
        UUID detalleLoteId = UUID.randomUUID();
        UUID ventaId = UUID.randomUUID();
        UUID detalleVentaId = UUID.randomUUID();
        UUID ajusteId = UUID.randomUUID();

        Huella antes;
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rolId + "', 'PRODUCCION_MIGRACION_TEST')");
                st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES "
                        + "('" + usuarioId + "', 'Operario Test', 'operario-migracion@planta.com', 'hash', '" + rolId + "', true)");
                st.execute("INSERT INTO familia (id, nombre) VALUES ('" + familiaId + "', 'Familia migracion vencimiento')");
                st.execute("INSERT INTO producto (id, nombre, familia_id) VALUES ('" + productoId + "', 'Yogurt migracion', '" + familiaId + "')");
                st.execute("INSERT INTO presentacion (id, producto_id, nombre, unidad_conteo) VALUES "
                        + "('" + presentacionId + "', '" + productoId + "', 'Botella 1 L', 'botella')");
                st.execute("INSERT INTO lote_produccion (id, uuid_cliente, fecha, producto_id, litros_usados, responsable_id) VALUES "
                        + "('" + loteId + "', '" + UUID.randomUUID() + "', '2026-04-01', '" + productoId + "', 100, '" + usuarioId + "')");
                st.execute("INSERT INTO lote_produccion_detalle (id, lote_id, presentacion_id, cantidad_obtenida) VALUES "
                        + "('" + detalleLoteId + "', '" + loteId + "', '" + presentacionId + "', 60)");
                st.execute("INSERT INTO movimiento_inventario (id, presentacion_id, tipo_movimiento, cantidad, lote_produccion_detalle_id) VALUES "
                        + "('" + UUID.randomUUID() + "', '" + presentacionId + "', 'ENTRADA_PRODUCCION', 60, '" + detalleLoteId + "')");
                st.execute("INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, total, vendedor_id) VALUES "
                        + "('" + ventaId + "', '" + UUID.randomUUID() + "', '2026-04-02', 'PUBLICO', 250.00, '" + usuarioId + "')");
                st.execute("INSERT INTO venta_detalle (id, venta_id, presentacion_id, cantidad, precio_unitario) VALUES "
                        + "('" + detalleVentaId + "', '" + ventaId + "', '" + presentacionId + "', 20, 12.50)");
                st.execute("INSERT INTO movimiento_inventario (id, presentacion_id, tipo_movimiento, cantidad, venta_detalle_id) VALUES "
                        + "('" + UUID.randomUUID() + "', '" + presentacionId + "', 'SALIDA_VENTA', -20, '" + detalleVentaId + "')");
                st.execute("INSERT INTO ajuste_inventario (id, presentacion_id, usuario_id, cantidad_anterior, cantidad_corregida, motivo) VALUES "
                        + "('" + ajusteId + "', '" + presentacionId + "', '" + usuarioId + "', 40, 37, 'Rotura')");
                st.execute("INSERT INTO movimiento_inventario (id, presentacion_id, tipo_movimiento, cantidad, ajuste_inventario_id) VALUES "
                        + "('" + UUID.randomUUID() + "', '" + presentacionId + "', 'AJUSTE', -3, '" + ajusteId + "')");
            }
            antes = huella(c);
        }
        assertThat(antes.movimientos()).isEqualTo(3);
        assertThat(antes.suma()).isEqualTo(37);

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").load().migrate();

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            Huella despues = huella(c);

            // 1) El libro y el kardex no cambian en nada.
            assertThat(despues.movimientos()).as("conteo de movimientos").isEqualTo(antes.movimientos());
            assertThat(despues.suma()).as("suma global de cantidades").isEqualTo(antes.suma());
            assertThat(despues.libro()).as("huella de los movimientos (id, tipo, cantidad, fecha, origen)").isEqualTo(antes.libro());
            assertThat(despues.saldosKardex()).as("saldos acumulados que lee el kardex").isEqualTo(antes.saldosKardex());
            assertThat(despues.stockPorPresentacion()).as("stock por presentacion").isEqualTo(antes.stockPorPresentacion());
            // 2) La definicion de movimiento_inventario (columnas, indices, restricciones) no cambia.
            assertThat(despues.definicionTabla()).as("definicion de movimiento_inventario").isEqualTo(antes.definicionTabla());

            // 3) Lo nuevo nace apagado/vacio: ningun dato existente se reinterpreta.
            assertThat(escalar(c, "SELECT count(*) FROM producto WHERE controla_vencimiento")).isZero();
            assertThat(escalar(c, "SELECT count(*) FROM producto WHERE NOT controla_vencimiento")).isEqualTo(
                    escalar(c, "SELECT count(*) FROM producto"));
            assertThat(escalar(c, "SELECT count(*) FROM presentacion WHERE saldo_sin_trazabilidad_inicial IS NOT NULL")).isZero();
            assertThat(escalar(c, "SELECT count(*) FROM lote_produccion_detalle WHERE fecha_vencimiento IS NOT NULL "
                    + "OR fecha_vencimiento_original IS NOT NULL OR vencimiento_corregido_en IS NOT NULL")).isZero();
            assertThat(escalar(c, "SELECT count(*) FROM movimiento_inventario_lote")).isZero();

            // 4) Reglas de la tabla hija: cantidad distinta de cero y unicidad por movimiento y lote.
            UUID movimientoId = (UUID) unico(c, "SELECT id FROM movimiento_inventario WHERE tipo_movimiento = 'ENTRADA_PRODUCCION'");
            insertarAsignacion(c, movimientoId, detalleLoteId, 60);
            assertThatThrownBy(() -> insertarAsignacion(c, movimientoId, detalleLoteId, 60))
                    .as("el mismo movimiento no se reparte dos veces en el mismo lote").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertarAsignacion(c, movimientoId, detalleLoteId, 0))
                    .as("una asignacion de cantidad cero no tiene sentido").isInstanceOf(SQLException.class);
            insertarAsignacion(c, movimientoId, null, 5);
            assertThatThrownBy(() -> insertarAsignacion(c, movimientoId, null, 5))
                    .as("el mismo movimiento no se reparte dos veces en 'sin trazabilidad'").isInstanceOf(SQLException.class);
        }
    }

    private static void insertarAsignacion(Connection c, UUID movimientoId, UUID loteDetalleId, int cantidad) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO movimiento_inventario_lote (movimiento_id, lote_detalle_id, cantidad) VALUES (?, ?, ?)")) {
            ps.setObject(1, movimientoId);
            ps.setObject(2, loteDetalleId);
            ps.setInt(3, cantidad);
            ps.executeUpdate();
        }
    }

    private static Object unico(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getObject(1);
        }
    }

    private static long escalar(Connection c, String sql) throws SQLException {
        return ((Number) unico(c, sql)).longValue();
    }

    private static String texto(Connection c, String sql) throws SQLException {
        return String.valueOf(unico(c, sql));
    }

    private static Huella huella(Connection c) throws SQLException {
        return new Huella(
                escalar(c, "SELECT count(*) FROM movimiento_inventario"),
                escalar(c, "SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_inventario"),
                texto(c, "SELECT md5(COALESCE(string_agg(id::text || '|' || presentacion_id::text || '|' || tipo_movimiento || '|' || cantidad "
                        + "|| '|' || creado_en::text || '|' || COALESCE(lote_produccion_detalle_id::text, '') || '|' "
                        + "|| COALESCE(venta_detalle_id::text, '') || '|' || COALESCE(ajuste_inventario_id::text, ''), ';' ORDER BY creado_en, id), '')) "
                        + "FROM movimiento_inventario"),
                texto(c, "SELECT md5(COALESCE(string_agg(presentacion_id::text || ':' || id::text || ':' || saldo, ';' "
                        + "ORDER BY presentacion_id, creado_en, id), '')) FROM (SELECT presentacion_id, id, creado_en, "
                        + "SUM(cantidad) OVER (PARTITION BY presentacion_id ORDER BY creado_en, id) AS saldo FROM movimiento_inventario) t"),
                texto(c, "SELECT COALESCE(string_agg(presentacion_id::text || '=' || s, ';' ORDER BY presentacion_id), '') FROM "
                        + "(SELECT presentacion_id, SUM(cantidad) AS s FROM movimiento_inventario GROUP BY presentacion_id) t"),
                texto(c, "SELECT md5("
                        + "(SELECT COALESCE(string_agg(column_name || ':' || data_type || ':' || is_nullable || ':' "
                        + "|| COALESCE(character_maximum_length::text, ''), '|' ORDER BY column_name), '') FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'movimiento_inventario') || '##' || "
                        + "(SELECT COALESCE(string_agg(indexdef, '|' ORDER BY indexname), '') FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'movimiento_inventario') || '##' || "
                        + "(SELECT COALESCE(string_agg(conname || ':' || pg_get_constraintdef(oid), '|' ORDER BY conname), '') FROM pg_constraint "
                        + "WHERE conrelid = 'public.movimiento_inventario'::regclass))"));
    }
}
