package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MigracionSalidasIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = PostgresDePrueba.contenedor();

    @Test
    void v36ConservaMovimientosStockEInformesHistoricos() throws Exception {
        var configuracion = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        configuracion.target("35").load().migrate();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var st = c.createStatement()) {
            st.execute("""
                INSERT INTO usuario (nombre, email, password_hash, rol_id)
                SELECT 'Prueba migración', 'migracion@example.test', 'hash', id FROM rol WHERE nombre = 'ADMIN';
                INSERT INTO insumo (nombre, unidad_medida, stock) VALUES ('Sal histórica', 'kg', 20);
                INSERT INTO movimiento_insumo (insumo_id, tipo_movimiento, cantidad)
                SELECT id, 'SALDO_INICIAL', 20 FROM insumo WHERE nombre = 'Sal histórica';
                INSERT INTO informe_produccion_diario (uuid_cliente, fecha, quesos_diarios, responsable_id)
                SELECT uuid_generate_v4()::text, '2026-10-01', 12, id FROM usuario WHERE email = 'migracion@example.test';
                INSERT INTO informe_produccion_insumo (informe_id, insumo_id, cantidad)
                SELECT d.id, i.id, 2 FROM informe_produccion_diario d CROSS JOIN insumo i WHERE i.nombre = 'Sal histórica';
                INSERT INTO familia (nombre) VALUES ('Familia migración salidas');
                INSERT INTO producto (nombre, familia_id) SELECT 'Queso migración salidas', id FROM familia WHERE nombre = 'Familia migración salidas';
                INSERT INTO presentacion (producto_id, nombre, unidad_conteo) SELECT id, 'Unidad migración salidas', 'unidad' FROM producto WHERE nombre = 'Queso migración salidas';
                INSERT INTO ajuste_inventario (presentacion_id, usuario_id, cantidad_anterior, cantidad_corregida, motivo)
                SELECT p.id, u.id, 0, 10, 'Existencia anterior' FROM presentacion p CROSS JOIN usuario u WHERE p.nombre = 'Unidad migración salidas' AND u.email = 'migracion@example.test';
                INSERT INTO movimiento_inventario (presentacion_id, tipo_movimiento, cantidad, ajuste_inventario_id)
                SELECT presentacion_id, 'AJUSTE', 10, id FROM ajuste_inventario;
                """);
            Map<String, String> antes = huellas(c);
            configuracion.target("36").load().migrate();
            assertThat(huellas(c)).isEqualTo(antes);
            try (var rs = st.executeQuery("SELECT count(*) FROM salida_inventario")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    private Map<String, String> huellas(Connection c) throws SQLException {
        Map<String, String> resultado = new LinkedHashMap<>();
        for (String tabla : new String[]{"insumo", "movimiento_insumo", "movimiento_inventario", "informe_produccion_diario", "informe_produccion_insumo"}) {
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "SELECT jsonb_agg(to_jsonb(t) - 'salida_id' ORDER BY id)::text FROM " + tabla + " t")) {
                rs.next();
                resultado.put(tabla, rs.getString(1));
            }
        }
        return resultado;
    }
}
