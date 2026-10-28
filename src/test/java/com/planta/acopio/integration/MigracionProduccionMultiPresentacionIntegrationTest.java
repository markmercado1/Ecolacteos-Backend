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
 * Verificación pedida explícitamente por el usuario antes de dar la Fase 3 (Producción con varias
 * presentaciones de salida e Inventario por presentación) por cerrada: distingue "V12 se puede aplicar
 * contra un esquema vacío" (lo que ya confirman los demás tests al arrancar) de "V12 preserva los datos
 * EXISTENTES" -- esta prueba usa la API de Flyway directamente contra Postgres 15 real (Testcontainers) en
 * dos pasos:
 *   1) Migra solo hasta V11 (el esquema tal como existía ANTES de esta fase) e inserta por JDBC crudo un
 *      lote histórico con unidades_obtenidas > 0, un lote histórico con unidades_obtenidas = 0 (caso real
 *      "sin ningún rendimiento", que debe preservarse SIN generar una fila de detalle ni un movimiento
 *      ficticio), y una venta histórica -- con las columnas EXACTAS de esa versión
 *      (movimiento_inventario.producto_id/lote_produccion_id, venta.producto_id).
 *   2) Migra V12 y verifica por JDBC crudo: el lote con unidades_obtenidas > 0 generó exactamente un
 *      lote_produccion_detalle hacia la presentación "Unidad" de su producto, con la MISMA cantidad; el
 *      lote con unidades_obtenidas = 0 no generó ningún detalle; los movimientos de inventario (entrada y
 *      salida) quedaron con presentacion_id apuntando a esa misma presentación "Unidad"; la venta quedó con
 *      presentacion_id apuntando a la misma presentación; las columnas antiguas (producto_id de
 *      movimiento_inventario/venta, lote_produccion_id de movimiento_inventario, unidades_obtenidas de
 *      lote_produccion) ya no existen.
 */
@Testcontainers
class MigracionProduccionMultiPresentacionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresDePrueba.contenedor()
            .withDatabaseName("acopio_lechero_migracion_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @Test
    void v12PreservaLosLotesHistoricosConYSinRendimientoYReapuntaVentasYMovimientosALaPresentacionUnidad() throws Exception {
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").target("11").load().migrate();

        UUID rolId = UUID.randomUUID();
        UUID usuarioId = UUID.randomUUID();
        UUID familiaId = UUID.randomUUID();
        UUID productoId = UUID.randomUUID();
        UUID presentacionUnidadId = UUID.randomUUID();
        UUID loteConRendimientoId = UUID.randomUUID();
        UUID loteSinRendimientoId = UUID.randomUUID();
        UUID movimientoEntradaId = UUID.randomUUID();
        UUID ventaId = UUID.randomUUID();
        UUID movimientoSalidaId = UUID.randomUUID();

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rolId + "', 'PROD_MIGRACION_TEST')");
                st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES "
                        + "('" + usuarioId + "', 'Responsable Test', 'responsable-migracion-v12@planta.com', 'hash', '" + rolId + "', true)");
                st.execute("INSERT INTO familia (id, nombre, activo) VALUES ('" + familiaId + "', 'Queso Migracion V12 " + System.nanoTime() + "', true)");
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO producto (id, nombre, familia_id, rendimiento_esperado_pct, activo) VALUES (?, ?, ?, ?, ?)")) {
                ps.setObject(1, productoId);
                ps.setString(2, "Queso Migracion V12 " + System.nanoTime());
                ps.setObject(3, familiaId);
                ps.setBigDecimal(4, BigDecimal.valueOf(12.00));
                ps.setBoolean(5, true);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO presentacion (id, producto_id, nombre, unidad_conteo, activo) VALUES (?, ?, 'Unidad', 'unidad', true)")) {
                ps.setObject(1, presentacionUnidadId);
                ps.setObject(2, productoId);
                ps.executeUpdate();
            }

            // Lote historico CON rendimiento (esquema pre-V12: columna unidades_obtenidas todavia existe).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO lote_produccion (id, uuid_cliente, fecha, producto_id, litros_usados, unidades_obtenidas, rendimiento_pct, responsable_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, loteConRendimientoId);
                ps.setString(2, UUID.randomUUID().toString());
                ps.setDate(3, java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)));
                ps.setObject(4, productoId);
                ps.setBigDecimal(5, BigDecimal.valueOf(100));
                ps.setInt(6, 90);
                ps.setBigDecimal(7, BigDecimal.valueOf(90.00));
                ps.setObject(8, usuarioId);
                ps.executeUpdate();
            }
            // Lote historico SIN ningun rendimiento (caso real ya existente antes de esta fase).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO lote_produccion (id, uuid_cliente, fecha, producto_id, litros_usados, unidades_obtenidas, responsable_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, loteSinRendimientoId);
                ps.setString(2, UUID.randomUUID().toString());
                ps.setDate(3, java.sql.Date.valueOf(LocalDate.of(2026, 4, 2)));
                ps.setObject(4, productoId);
                ps.setBigDecimal(5, BigDecimal.valueOf(50));
                ps.setInt(6, 0);
                ps.setObject(7, usuarioId);
                ps.executeUpdate();
            }
            // Movimiento de entrada (esquema pre-V12: columnas producto_id/lote_produccion_id).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO movimiento_inventario (id, producto_id, tipo_movimiento, cantidad, lote_produccion_id) VALUES (?, ?, 'ENTRADA_PRODUCCION', ?, ?)")) {
                ps.setObject(1, movimientoEntradaId);
                ps.setObject(2, productoId);
                ps.setInt(3, 90);
                ps.setObject(4, loteConRendimientoId);
                ps.executeUpdate();
            }
            // Venta historica (esquema pre-V12: columna producto_id).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, producto_id, cantidad, precio_unitario, vendedor_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, ventaId);
                ps.setString(2, UUID.randomUUID().toString());
                ps.setDate(3, java.sql.Date.valueOf(LocalDate.of(2026, 4, 3)));
                ps.setString(4, "PUBLICO");
                ps.setObject(5, productoId);
                ps.setInt(6, 5);
                ps.setBigDecimal(7, BigDecimal.valueOf(15.00));
                ps.setObject(8, usuarioId);
                ps.executeUpdate();
            }
            // Movimiento de salida (esquema pre-V12: columna producto_id).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO movimiento_inventario (id, producto_id, tipo_movimiento, cantidad, venta_id) VALUES (?, ?, 'SALIDA_VENTA', ?, ?)")) {
                ps.setObject(1, movimientoSalidaId);
                ps.setObject(2, productoId);
                ps.setInt(3, -5);
                ps.setObject(4, ventaId);
                ps.executeUpdate();
            }
        }

        // target("12"): esta prueba verifica el estado que deja V12. Migrar hasta la ultima version la rompia desde
        // V15, que elimina venta.presentacion_id (V15 tiene su propia prueba: MigracionVentaMultiLineaIntegrationTest).
        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").target("12").load().migrate();

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            // Lote CON rendimiento: exactamente un detalle, hacia la presentacion "Unidad", con la MISMA cantidad.
            UUID detalleId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, presentacion_id, cantidad_obtenida FROM lote_produccion_detalle WHERE lote_id = ?")) {
                ps.setObject(1, loteConRendimientoId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("el lote con unidades_obtenidas > 0 debe migrar a exactamente un detalle").isTrue();
                assertThat(rs.getObject("presentacion_id")).isEqualTo(presentacionUnidadId);
                assertThat(rs.getInt("cantidad_obtenida")).isEqualTo(90);
                detalleId = (UUID) rs.getObject("id");
                assertThat(rs.next()).as("no debe haber una segunda fila de detalle para este lote").isFalse();
            }

            // Lote SIN rendimiento: cero filas de detalle (nunca una fila en cero) -- el lote en si sigue existiendo.
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) AS n FROM lote_produccion_detalle WHERE lote_id = ?")) {
                ps.setObject(1, loteSinRendimientoId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("n")).as("un lote historico sin rendimiento no debe generar ninguna fila de detalle").isEqualTo(0);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT fecha, litros_usados FROM lote_produccion WHERE id = ?")) {
                ps.setObject(1, loteSinRendimientoId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("el lote sin rendimiento debe conservarse (no se elimina)").isTrue();
                assertThat(rs.getDate("fecha").toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 2));
                assertThat(rs.getBigDecimal("litros_usados")).isEqualByComparingTo("50");
            }

            // Movimiento de entrada: mismo id, ahora con presentacion_id (=Unidad) y lote_produccion_detalle_id
            // apuntando al detalle recien verificado -- nunca al lote directamente.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT presentacion_id, lote_produccion_detalle_id, cantidad FROM movimiento_inventario WHERE id = ?")) {
                ps.setObject(1, movimientoEntradaId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("el movimiento de entrada historico debe conservar su id").isTrue();
                assertThat(rs.getObject("presentacion_id")).isEqualTo(presentacionUnidadId);
                assertThat(rs.getObject("lote_produccion_detalle_id")).isEqualTo(detalleId);
                assertThat(rs.getInt("cantidad")).isEqualTo(90);
            }

            // Venta historica: mismo id, ahora con presentacion_id (=Unidad) en vez de producto_id.
            try (PreparedStatement ps = c.prepareStatement("SELECT presentacion_id, cantidad FROM venta WHERE id = ?")) {
                ps.setObject(1, ventaId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("la venta historica debe conservar su id").isTrue();
                assertThat(rs.getObject("presentacion_id")).isEqualTo(presentacionUnidadId);
                assertThat(rs.getInt("cantidad")).isEqualTo(5);
            }

            // Movimiento de salida: mismo id, presentacion_id = Unidad, sin lote_produccion_detalle_id.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT presentacion_id, lote_produccion_detalle_id, cantidad FROM movimiento_inventario WHERE id = ?")) {
                ps.setObject(1, movimientoSalidaId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("el movimiento de salida historico debe conservar su id").isTrue();
                assertThat(rs.getObject("presentacion_id")).isEqualTo(presentacionUnidadId);
                assertThat(rs.getObject("lote_produccion_detalle_id")).isNull();
                assertThat(rs.getInt("cantidad")).isEqualTo(-5);
            }

            // Columnas antiguas eliminadas por completo (sin una fuente de verdad paralela).
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT producto_id FROM movimiento_inventario LIMIT 1"))
                        .as("movimiento_inventario.producto_id debe haber sido eliminada por V12")
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT lote_produccion_id FROM movimiento_inventario LIMIT 1"))
                        .as("movimiento_inventario.lote_produccion_id debe haber sido eliminada por V12")
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT producto_id FROM venta LIMIT 1"))
                        .as("venta.producto_id debe haber sido eliminada por V12")
                        .isInstanceOf(java.sql.SQLException.class);
            }
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT unidades_obtenidas FROM lote_produccion LIMIT 1"))
                        .as("lote_produccion.unidades_obtenidas debe haber sido eliminada por V12")
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }
}
