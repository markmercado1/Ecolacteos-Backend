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
 * V43 (lotes de insumo) desde una base en V42 con datos: cada insumo con historial recibe un lote INICIAL con su stock vigente y
 * TODOS sus movimientos quedan enlazados a el, de modo que ningun saldo ni kardex cambia. Tambien prueba el script de reversa
 * documentado en la migracion. El ultimo caso corre sobre una COPIA de una base real en V42 (ECO_MIGRACION_COPIA_URL); nunca
 * debe apuntar a la base real.
 */
class MigracionLotesInsumoIntegrationTest {

    private static final String A = "00000000-0000-0000-0000-0000000000c1";   // con historial y stock
    private static final String B = "00000000-0000-0000-0000-0000000000c2";   // con historial y stock 0
    private static final String C = "00000000-0000-0000-0000-0000000000c3";   // sin movimientos

    private static String[] preparar() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("42").load().migrate();
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO insumo (id, nombre, unidad_medida, stock) VALUES ('" + A + "', 'Azucar V43', 'kg', 112), ('" + B + "', 'Sal V43', 'kg', 0), ('" + C + "', 'Vacio V43', 'kg', 0)");
            st.execute("INSERT INTO rol (id, nombre) VALUES ('00000000-0000-0000-0000-0000000000d1', 'ROL_V43')");
            st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id) VALUES ('00000000-0000-0000-0000-0000000000d2', 'U', 'u43@x.com', 'h', '00000000-0000-0000-0000-0000000000d1')");
            // A: 11 + 100 + 100 - 50 - 11 - 38 = 112. B: 20 - 20 = 0.
            String[][] movs = {
                    {A, "INGRESO", "11", "2026-10-06 21:20:00"}, {A, "INGRESO", "100", "2026-10-21 19:48:00"}, {A, "INGRESO", "100", "2026-10-27 00:51:00"},
                    {A, "AJUSTE", "-50", "2026-10-27 00:53:00"}, {A, "AJUSTE", "-11", "2026-10-29 14:52:00"}, {A, "AJUSTE", "-38", "2026-10-29 14:53:00"},
                    {B, "INGRESO", "20", "2026-10-02 10:00:00"}, {B, "AJUSTE", "-20", "2026-10-03 10:00:00"}};
            for (String[] m : movs) {
                String extra = m[1].equals("AJUSTE") ? ", motivo" : "";
                String extraV = m[1].equals("AJUSTE") ? ", 'conteo'" : "";
                st.execute("INSERT INTO movimiento_insumo (insumo_id, tipo_movimiento, cantidad, usuario_id, creado_en" + extra + ") VALUES ('" + m[0] + "', '" + m[1] + "', " + m[2]
                        + ", '00000000-0000-0000-0000-0000000000d2', '" + m[3] + "'" + extraV + ")");
            }
        }
        return base;
    }

    private static String saldos(Statement st) throws SQLException {
        return escalar(st, "SELECT string_agg(insumo_id || ':' || saldo, ',' ORDER BY insumo_id, orden) FROM ("
                + "SELECT insumo_id, orden, SUM(cantidad) OVER (PARTITION BY insumo_id ORDER BY orden) AS saldo FROM movimiento_insumo) t");
    }

    @Test
    void cadaInsumoConHistorialRecibeSuLoteInicialYElKardexNoCambia() throws Exception {
        String[] base = preparar();
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            String saldosAntes = saldos(st);
            String conteoAntes = escalar(st, "SELECT count(*) || '/' || sum(cantidad) FROM movimiento_insumo");

            Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("43").load().migrate();

            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("43");
            // Lote INICIAL: con el stock de hoy (112 / 0), sin proveedor ni vencimiento; el insumo sin historial no recibe lote.
            assertThat(escalar(st, "SELECT cantidad_inicial || '/' || cantidad_disponible || '/' || origen || '/' || coalesce(fecha_vencimiento::text, 'sin-venc') FROM lote_insumo WHERE insumo_id = '" + A + "'"))
                    .isEqualTo("112.00/112.00/INICIAL/sin-venc");
            assertThat(escalar(st, "SELECT cantidad_inicial || '/' || cantidad_disponible FROM lote_insumo WHERE insumo_id = '" + B + "'")).isEqualTo("0.00/0.00");
            assertThat(escalar(st, "SELECT count(*) FROM lote_insumo WHERE insumo_id = '" + C + "'")).isEqualTo("0");
            // Todos los movimientos, enlazados; el kardex (saldo acumulado) y los totales, intactos.
            assertThat(escalar(st, "SELECT count(*) FROM movimiento_insumo WHERE lote_insumo_id IS NULL")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM movimiento_insumo m JOIN lote_insumo l ON l.id = m.lote_insumo_id WHERE l.insumo_id <> m.insumo_id")).isEqualTo("0");
            assertThat(saldos(st)).isEqualTo(saldosAntes);
            assertThat(escalar(st, "SELECT count(*) || '/' || sum(cantidad) FROM movimiento_insumo")).isEqualTo(conteoAntes);
            // Invariantes: stock del insumo = suma de lotes = suma de movimientos.
            assertThat(escalar(st, "SELECT count(*) FROM insumo i WHERE i.stock <> (SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo l WHERE l.insumo_id = i.id)")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM lote_insumo l WHERE l.cantidad_disponible <> (SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_insumo m WHERE m.lote_insumo_id = l.id)")).isEqualTo("0");
        }
    }

    @Test
    void elScriptDeReversaDeshaceLaMigracionSinPerderMovimientos() throws Exception {
        String[] base = preparar();
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            String saldosAntes = saldos(st);
            Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("43").load().migrate();

            // Reversa documentada en V43 (valida mientras ningun insumo tenga mas de un lote).
            st.execute("DROP INDEX uk_movimiento_insumo_salida_lote");
            st.execute("CREATE UNIQUE INDEX uk_movimiento_insumo_salida_lote ON movimiento_insumo (lote_produccion_id, insumo_id) WHERE tipo_movimiento = 'SALIDA_PRODUCCION'");
            st.execute("DROP INDEX uk_movimiento_insumo_retiro");
            st.execute("CREATE UNIQUE INDEX uk_movimiento_insumo_retiro ON movimiento_insumo (salida_id) WHERE salida_id IS NOT NULL");
            st.execute("DROP INDEX idx_movimiento_insumo_lote_insumo");
            st.execute("ALTER TABLE movimiento_insumo DROP COLUMN lote_insumo_id");
            st.execute("DROP TABLE lote_insumo");
            st.execute("ALTER TABLE fusion_insumo DROP COLUMN lotes");
            st.execute("DELETE FROM flyway_schema_history WHERE version = '43'");

            assertThat(saldos(st)).isEqualTo(saldosAntes);
            assertThat(escalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'movimiento_insumo' AND column_name = 'lote_insumo_id'")).isEqualTo("0");
            // Y se puede volver a aplicar.
            Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("43").load().migrate();
            assertThat(escalar(st, "SELECT count(*) FROM lote_insumo")).isEqualTo(escalar(st, "SELECT count(DISTINCT insumo_id) FROM movimiento_insumo"));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ECO_MIGRACION_COPIA_URL", matches = ".+")
    void sobreUnaCopiaDeUnaBaseRealEnV42ElAzucarQuedaEnSuLoteInicialYNingunSaldoCambia() throws Exception {
        String url = System.getenv("ECO_MIGRACION_COPIA_URL");
        String usuario = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_USUARIO", "postgres");
        String clave = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_CLAVE", "postgres");
        String saldosAntes;
        String stockAzucar;
        // La copia puede venir de una version anterior: se lleva hasta V42 (idempotente) y desde ahi se prueba V43.
        Flyway.configure().dataSource(url, usuario, clave).locations("classpath:db/migration").target("42").load().migrate();
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("42");
            saldosAntes = saldos(st);
            stockAzucar = escalar(st, "SELECT stock FROM insumo WHERE nombre = 'azucar'");
        }
        Flyway.configure().dataSource(url, usuario, clave).locations("classpath:db/migration").target("43").load().migrate();
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(saldos(st)).isEqualTo(saldosAntes);
            assertThat(escalar(st, "SELECT l.codigo || '/' || l.cantidad_disponible || '/' || count(m.id) FROM lote_insumo l JOIN insumo i ON i.id = l.insumo_id "
                    + "JOIN movimiento_insumo m ON m.lote_insumo_id = l.id WHERE i.nombre = 'azucar' GROUP BY l.codigo, l.cantidad_disponible"))
                    .isEqualTo("INICIAL/" + stockAzucar + "/8");
            assertThat(escalar(st, "SELECT count(*) FROM insumo i WHERE i.stock <> (SELECT COALESCE(SUM(cantidad_disponible), 0) FROM lote_insumo l WHERE l.insumo_id = i.id)")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM movimiento_insumo WHERE lote_insumo_id IS NULL")).isEqualTo("0");
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
