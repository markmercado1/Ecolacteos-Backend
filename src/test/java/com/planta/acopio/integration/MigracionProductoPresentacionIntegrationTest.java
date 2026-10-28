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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verificacion pedida explicitamente por el usuario antes de dar la ampliacion Producto+Presentacion
 * (Fase 1) y Precios por presentacion (Fase 2) por cerradas: "Successfully validated N migrations" (lo que
 * ya confirmaban los demas tests de integracion al arrancar contra un esquema VACIO) NO es evidencia de que
 * V10/V11 preserven datos EXISTENTES -- solo prueba que el SQL es sintacticamente aplicable desde cero.
 *
 * Esta prueba, deliberadamente, NO usa @SpringBootTest (que migraria todo automaticamente al arrancar el
 * contexto, sin dar oportunidad de insertar datos "pre-Fase1/2" a mitad de camino). En su lugar usa la API
 * de Flyway directamente contra un Postgres 15 real (Testcontainers), en dos pasos:
 *   1) Migra solo hasta V9 (el esquema tal como existia ANTES de esta ampliacion) e inserta datos por JDBC
 *      crudo, con las tablas/columnas EXACTAS de esa version (tipo_queso, precio_queso.tipo_queso_id,
 *      venta.tipo_queso_id).
 *   2) Migra el resto (V10 + V11) y verifica por JDBC crudo, fila por fila, que esos datos sobrevivieron:
 *      mismo id de producto, mismo nombre, familia asignada; presentacion "Unidad" creada; el precio
 *      historico migrado a precio_presentacion con el MISMO id, mismo importe, misma modalidad, misma
 *      vigencia, apuntando a esa presentacion; la venta historica sigue apuntando al mismo producto.
 */
@Testcontainers
class MigracionProductoPresentacionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresDePrueba.contenedor()
            .withDatabaseName("acopio_lechero_migracion_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @Test
    void v10YV11PreservanLosDatosExistentesAlMigrarDesdeElEsquemaAnteriorAProductoYPrecioPresentacion() throws Exception {
        String url = postgres.getJdbcUrl();
        String user = postgres.getUsername();
        String pass = postgres.getPassword();

        // 1) Esquema tal como existia ANTES de esta ampliacion (V1..V9 solamente).
        Flyway.configure()
                .dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();

        UUID rolId = UUID.randomUUID();
        UUID usuarioId = UUID.randomUUID();
        UUID tipoQuesoId = UUID.randomUUID();
        UUID precioQuesoId = UUID.randomUUID();
        UUID ventaId = UUID.randomUUID();
        UUID uuidClienteVenta = UUID.randomUUID();

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rolId + "', 'VENTAS_MIGRACION_TEST')");
                st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES "
                        + "('" + usuarioId + "', 'Vendedor Test', 'vendedor-migracion@planta.com', 'hash', '" + rolId + "', true)");
            }

            // Esquema PRE-V10: tabla tipo_queso, sin familia_id (todavia no existe la columna).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tipo_queso (id, nombre, rendimiento_esperado_pct, ciclo_capital, activo) VALUES (?, ?, ?, ?, ?)")) {
                ps.setObject(1, tipoQuesoId);
                ps.setString(2, "Queso Fresco Migracion " + System.nanoTime());
                ps.setBigDecimal(3, BigDecimal.valueOf(12.50));
                ps.setString(4, "RAPIDO");
                ps.setBoolean(5, true);
                ps.executeUpdate();
            }

            // Esquema PRE-V11: tabla precio_queso, columna tipo_queso_id (todavia no renombrada a producto_id
            // por V10 -- ese rename ocurre recien en el paso 2 de esta prueba).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO precio_queso (id, tipo_queso_id, modalidad, precio, vigente_desde, creado_en) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, precioQuesoId);
                ps.setObject(2, tipoQuesoId);
                ps.setString(3, "PUBLICO");
                ps.setBigDecimal(4, BigDecimal.valueOf(15.75));
                ps.setDate(5, java.sql.Date.valueOf(LocalDate.of(2026, 3, 1)));
                ps.setTimestamp(6, java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 3, 1, 8, 0)));
                ps.executeUpdate();
            }

            // Venta historica: columna tipo_queso_id (pre-V10). total es columna GENERATED, no se inserta.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO venta (id, uuid_cliente, fecha, tipo_cliente, tipo_queso_id, cantidad, precio_unitario, vendedor_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setObject(1, ventaId);
                ps.setString(2, uuidClienteVenta.toString());
                ps.setDate(3, java.sql.Date.valueOf(LocalDate.of(2026, 3, 2)));
                ps.setString(4, "PUBLICO");
                ps.setObject(5, tipoQuesoId);
                ps.setInt(6, 4);
                ps.setBigDecimal(7, BigDecimal.valueOf(15.75));
                ps.setObject(8, usuarioId);
                ps.executeUpdate();
            }
        }

        // 2) Migrar el resto (V10 + V11) sobre esos datos ya existentes. Fijado a "11" (no sin target,
        // que arrastraria automaticamente cualquier migracion futura -- V12 en adelante ya cambio de nuevo
        // el destino de venta.producto_id, y esta prueba verifica especificamente V10/V11, no el estado
        // final del esquema; esa verificacion vive en su propio test, ver
        // MigracionProduccionMultiPresentacionIntegrationTest).
        Flyway.configure()
                .dataSource(url, user, pass)
                .locations("classpath:db/migration")
                .target("11")
                .load()
                .migrate();

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            // producto: mismo id, mismo nombre, mismo rendimiento -- ahora con familia_id asignado ('Queso').
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT nombre, rendimiento_esperado_pct, activo, familia_id FROM producto WHERE id = ?")) {
                ps.setObject(1, tipoQuesoId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("el producto migrado desde tipo_queso debe existir con el MISMO id").isTrue();
                assertThat(rs.getBigDecimal("rendimiento_esperado_pct")).isEqualByComparingTo("12.50");
                assertThat(rs.getBoolean("activo")).isTrue();
                assertThat(rs.getObject("familia_id")).as("familia_id ya no debe ser null tras V10").isNotNull();
            }

            // familia asignada es 'Queso' (la sembrada por V10 para todo producto existente).
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT f.nombre FROM producto p JOIN familia f ON f.id = p.familia_id WHERE p.id = ?")) {
                ps.setObject(1, tipoQuesoId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("nombre")).isEqualTo("Queso");
            }

            // presentacion "Unidad" creada para ese producto, sin contenido inventado (contenido_cantidad NULL).
            UUID presentacionId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, contenido_cantidad, contenido_unidad FROM presentacion WHERE producto_id = ? AND nombre = 'Unidad'")) {
                ps.setObject(1, tipoQuesoId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("V10 debe crear la presentacion 'Unidad' para el producto migrado").isTrue();
                assertThat(rs.getObject("contenido_cantidad")).as("nunca se inventa un contenido").isNull();
                assertThat(rs.getObject("contenido_unidad")).isNull();
                presentacionId = (UUID) rs.getObject("id");
            }

            // precio_presentacion: MISMO id que precio_queso tenia, mismo importe/modalidad/vigencia, ahora
            // apuntando a la presentacion "Unidad" recien verificada -- nunca se recalculo el importe.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT presentacion_id, modalidad, precio, vigente_desde FROM precio_presentacion WHERE id = ?")) {
                ps.setObject(1, precioQuesoId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("el precio historico debe migrar conservando su id original").isTrue();
                assertThat(rs.getObject("presentacion_id")).isEqualTo(presentacionId);
                assertThat(rs.getString("modalidad")).isEqualTo("PUBLICO");
                assertThat(rs.getBigDecimal("precio")).isEqualByComparingTo("15.75");
                assertThat(rs.getDate("vigente_desde").toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 1));
            }

            // la tabla precio_queso ya no existe (migrada por completo, sin dejar una fuente de verdad paralela).
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT 1 FROM precio_queso"))
                        .as("precio_queso debe haber sido eliminada por V11 tras migrar todos sus datos")
                        .isInstanceOf(java.sql.SQLException.class);
            }

            // venta historica: mismo id, mismo total (columna GENERATED recalculada identicamente a partir
            // de los mismos cantidad/precio_unitario ya guardados -- nunca se recalculo con un precio nuevo),
            // ahora en la columna producto_id (renombrada por V10) apuntando al MISMO producto.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT producto_id, cantidad, precio_unitario, total FROM venta WHERE id = ?")) {
                ps.setObject(1, ventaId);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as("la venta historica debe conservarse con el MISMO id").isTrue();
                assertThat(rs.getObject("producto_id")).isEqualTo(tipoQuesoId);
                assertThat(rs.getInt("cantidad")).isEqualTo(4);
                assertThat(rs.getBigDecimal("total")).isEqualByComparingTo(BigDecimal.valueOf(4).multiply(BigDecimal.valueOf(15.75)));
            }
        }
    }
}
