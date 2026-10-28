package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verificación de V15 (Fase 4, Venta multi-línea / carrito): distingue "V15 se puede aplicar contra un
 * esquema vacío" (ya lo confirman los demás tests al arrancar) de "V15 preserva los datos EXISTENTES" -- esta
 * prueba usa la API de Flyway directamente contra Postgres 15 real (Testcontainers) en dos pasos:
 *   1) Migra solo hasta V14 (el esquema tal como existía ANTES de esta fase) e inserta por JDBC crudo una
 *      venta histórica de una sola línea, con las columnas EXACTAS de esa versión
 *      (venta.presentacion_id/cantidad/precio_unitario, movimiento_inventario.venta_id), y su movimiento
 *      SALIDA_VENTA correspondiente.
 *   2) Migra V15 y verifica por JDBC crudo: la venta generó exactamente una fila de venta_detalle con la
 *      MISMA presentación/cantidad/precio; el movimiento de salida quedó con venta_detalle_id apuntando a esa
 *      fila (nunca a la venta directamente); venta.total conservó el valor ya calculado por Postgres antes de
 *      la migración (cantidad * precio_unitario), sin recalcularlo; las columnas antiguas
 *      (venta.presentacion_id/cantidad/precio_unitario, movimiento_inventario.venta_id) ya no existen.
 */
@Testcontainers
class MigracionVentaMultiLineaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresDePrueba.contenedor()
            .withDatabaseName("acopio_lechero_migracion_v15_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @Test
    void v15PreservaLaVentaHistoricaComoUnaLineaYReapuntaSuMovimientoAlDetalle() throws Exception {
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").target("14").load().migrate();

        UUID rolId = UUID.randomUUID();
        UUID usuarioId = UUID.randomUUID();
        UUID familiaId = UUID.randomUUID();
        UUID productoId = UUID.randomUUID();
        UUID presentacionId = UUID.randomUUID();
        UUID ventaId = UUID.randomUUID();
        UUID movimientoSalidaId = UUID.randomUUID();
        BigDecimal totalHistoricoEsperado;

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rolId + "', 'VENTA_MIGRACION_TEST')");
                st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES "
                        + "('" + usuarioId + "', 'Vendedor Test', 'vendedor-migracion-v15@planta.com', 'hash', '" + rolId + "', true)");
                st.execute("INSERT INTO familia (id, nombre, activo) VALUES ('" + familiaId + "', 'Yogurt Migracion V15 " + System.nanoTime() + "', true)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO producto (id, nombre, familia_id, activo) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, productoId);
                ps.setString(2, "Yogurt Migracion V15 " + System.nanoTime());
                ps.setObject(3, familiaId);
                ps.setBoolean(4, true);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO presentacion (id, producto_id, nombre, unidad_conteo, activo) VALUES (?, ?, 'Botella 1 L', 'botella', true)")) {
                ps.setObject(1, presentacionId);
                ps.setObject(2, productoId);
                ps.executeUpdate();
            }

            // Venta historica de una sola linea (esquema pre-V15: columnas presentacion_id/cantidad/precio_unitario
            // directas en venta, total GENERADO por Postgres).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, presentacion_id, cantidad, precio_unitario, vendedor_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, ventaId);
                ps.setString(2, UUID.randomUUID().toString());
                ps.setDate(3, java.sql.Date.valueOf(LocalDate.of(2026, 4, 5)));
                ps.setString(4, "PUBLICO");
                ps.setObject(5, presentacionId);
                ps.setInt(6, 8);
                ps.setBigDecimal(7, BigDecimal.valueOf(12.50));
                ps.setObject(8, usuarioId);
                ps.executeUpdate();
            }
            totalHistoricoEsperado = BigDecimal.valueOf(8).multiply(BigDecimal.valueOf(12.50)); // 100.00, tal como lo calculo Postgres (GENERATED)

            // Movimiento de salida (esquema pre-V15: columna venta_id).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO movimiento_inventario (id, presentacion_id, tipo_movimiento, cantidad, venta_id) VALUES (?, ?, 'SALIDA_VENTA', ?, ?)")) {
                ps.setObject(1, movimientoSalidaId);
                ps.setObject(2, presentacionId);
                ps.setInt(3, -8);
                ps.setObject(4, ventaId);
                ps.executeUpdate();
            }
        }

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").load().migrate();

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            // La venta historica migro a exactamente una fila de venta_detalle, con la MISMA presentacion/
            // cantidad/precio que tenia directamente en venta.
            UUID detalleId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, presentacion_id, cantidad, precio_unitario, subtotal FROM venta_detalle WHERE venta_id = ?")) {
                ps.setObject(1, ventaId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("la venta historica debe migrar a exactamente una fila de venta_detalle").isTrue();
                assertThat(rs.getObject("presentacion_id")).isEqualTo(presentacionId);
                assertThat(rs.getInt("cantidad")).isEqualTo(8);
                assertThat(rs.getBigDecimal("precio_unitario")).isEqualByComparingTo("12.50");
                assertThat(rs.getBigDecimal("subtotal")).isEqualByComparingTo("100.00");
                detalleId = (UUID) rs.getObject("id");
                assertThat(rs.next()).as("no debe haber una segunda fila de detalle para esta venta").isFalse();
            }

            // Movimiento de salida: mismo id, ahora con venta_detalle_id apuntando al detalle recien
            // verificado (nunca a la venta directamente), sin cambiar su cantidad.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT venta_detalle_id, cantidad FROM movimiento_inventario WHERE id = ?")) {
                ps.setObject(1, movimientoSalidaId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("el movimiento de salida historico debe conservar su id").isTrue();
                assertThat(rs.getObject("venta_detalle_id")).isEqualTo(detalleId);
                assertThat(rs.getInt("cantidad")).isEqualTo(-8);
            }

            // venta.total conservo el valor ya calculado por Postgres ANTES de la migracion (no se recalculo).
            try (PreparedStatement ps = c.prepareStatement("SELECT total FROM venta WHERE id = ?")) {
                ps.setObject(1, ventaId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("la venta historica debe conservarse (no se elimina)").isTrue();
                assertThat(rs.getBigDecimal("total")).isEqualByComparingTo(totalHistoricoEsperado);
            }

            // venta.total ya no es una columna generada: se puede escribir directamente (lo hara VentaService).
            try (PreparedStatement ps = c.prepareStatement("UPDATE venta SET total = ? WHERE id = ?")) {
                ps.setBigDecimal(1, BigDecimal.valueOf(999.99));
                ps.setObject(2, ventaId);
                int filas = ps.executeUpdate();
                assertThat(filas).as("venta.total debe ser una columna normal, escribible, despues de V15").isEqualTo(1);
            }

            // Columnas antiguas eliminadas por completo (sin una fuente de verdad paralela).
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT presentacion_id FROM venta LIMIT 1"))
                        .as("venta.presentacion_id debe haber sido eliminada por V15")
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT cantidad FROM venta LIMIT 1"))
                        .as("venta.cantidad debe haber sido eliminada por V15")
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT precio_unitario FROM venta LIMIT 1"))
                        .as("venta.precio_unitario debe haber sido eliminada por V15")
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT venta_id FROM movimiento_inventario LIMIT 1"))
                        .as("movimiento_inventario.venta_id debe haber sido eliminada por V15")
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }
}
