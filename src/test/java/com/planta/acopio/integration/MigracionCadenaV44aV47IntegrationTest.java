package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cadena V44 a V47 (documento de ingreso, salidas y conteos por lote, adjuntos, orden del kardex de leche y producto) sobre una base en V43
 * y sobre una COPIA de una base real si se indica su URL en ECO_MIGRACION_COPIA_URL (nunca la real). Comprueba que las migraciones se
 * aplican en orden, que los datos existentes no cambian y que ningun saldo acumulado del kardex de leche ni de producto terminado se altera.
 */
class MigracionCadenaV44aV47IntegrationTest {

    private static final String SALDOS_LECHE = "SELECT string_agg(id || ':' || saldo, ',' ORDER BY n) FROM (SELECT id, "
            + "row_number() OVER (ORDER BY %s) n, SUM(cantidad) OVER (ORDER BY %s) saldo FROM movimiento_inventario_leche) t";
    private static final String SALDOS_PRODUCTO = "SELECT string_agg(id || ':' || saldo, ',' ORDER BY presentacion_id, n) FROM (SELECT id, presentacion_id, "
            + "row_number() OVER (PARTITION BY presentacion_id ORDER BY %s) n, SUM(cantidad) OVER (PARTITION BY presentacion_id ORDER BY %s) saldo FROM movimiento_inventario) t";

    private static void migrar(String url, String usuario, String clave, String objetivo) {
        Flyway.configure().dataSource(url, usuario, clave).locations("classpath:db/migration").target(objetivo).load().migrate();
    }

    @Test
    void sobreUnaBaseEnV43LaCadenaSeAplicaEnOrdenYLasTablasNuevasQuedanListas() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        migrar(base[0], base[1], base[2], "43");
        migrar(base[0], base[1], base[2], "47");
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::int >= 44 AND success")).isEqualTo("44,45,46,47");
            for (String t : new String[]{"documento_ingreso", "adjunto", "movimiento_inventario_leche", "movimiento_inventario"}) {
                assertThat(escalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_name = '" + t + "'")).as(t).isEqualTo("1");
            }
            assertThat(escalar(st, "SELECT count(*) FROM information_schema.columns WHERE column_name = 'orden' AND table_name IN ('movimiento_inventario_leche', 'movimiento_inventario', 'movimiento_insumo')")).isEqualTo("3");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ECO_MIGRACION_COPIA_URL", matches = ".+")
    void sobreUnaCopiaDeUnaBaseRealLosSaldosDelKardexDeLecheYProductoNoCambian() throws Exception {
        String url = System.getenv("ECO_MIGRACION_COPIA_URL");
        String usuario = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_USUARIO", "postgres");
        String clave = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_CLAVE", "postgres");
        migrar(url, usuario, clave, "46");
        String leche;
        String producto;
        String insumos;
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            leche = escalar(st, String.format(SALDOS_LECHE, "creado_en, id", "creado_en, id"));
            producto = escalar(st, String.format(SALDOS_PRODUCTO, "creado_en, id", "creado_en, id"));
            insumos = escalar(st, "SELECT count(*) || '/' || sum(stock) FROM insumo");
        }
        migrar(url, usuario, clave, "47");
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("47");
            assertThat(escalar(st, String.format(SALDOS_LECHE, "orden", "orden"))).isEqualTo(leche);
            assertThat(escalar(st, String.format(SALDOS_PRODUCTO, "orden", "orden"))).isEqualTo(producto);
            assertThat(escalar(st, "SELECT count(*) || '/' || sum(stock) FROM insumo")).isEqualTo(insumos);
            assertThat(escalar(st, "SELECT count(*) FROM movimiento_inventario_leche WHERE orden IS NULL")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM movimiento_inventario WHERE orden IS NULL")).isEqualTo("0");
            // Con las 4 migraciones sobre datos reales, el invariante de insumos sigue: stock = suma de lotes = suma de movimientos.
            assertThat(escalar(st, "SELECT count(*) FROM insumo i WHERE i.stock <> (SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo l WHERE l.insumo_id = i.id)")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM insumo i WHERE i.stock <> (SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo m WHERE m.insumo_id = i.id)")).isEqualTo("0");
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
