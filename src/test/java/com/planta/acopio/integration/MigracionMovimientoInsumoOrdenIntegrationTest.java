package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V40 (columna `orden` de movimiento_insumo) desde una base en V39, con datos: las filas existentes reciben su numero
 * segun el orden que el kardex YA mostraba (creado_en, id), asi que ningun saldo acumulado cambia; los movimientos nuevos
 * siguen despues. El segundo caso corre la misma comprobacion sobre una COPIA de una base real en V39 si se indica su URL
 * en ECO_MIGRACION_COPIA_URL (jdbc:postgresql://host:puerto/copia); nunca debe apuntar a la base real.
 */
class MigracionMovimientoInsumoOrdenIntegrationTest {

    @Test
    void lasFilasExistentesConservanElOrdenQueElKardexYaMostraba() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("39").load().migrate();
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO insumo (id, nombre, unidad_medida, stock) VALUES ('00000000-0000-0000-0000-0000000000a1', 'Azucar V40', 'kg', 0)");
            // Insertados en desorden y con empates de creado_en: el orden esperado es (creado_en, id).
            String[][] filas = {
                    {"00000000-0000-0000-0000-00000000000e", "2026-10-02 10:00:00"},
                    {"00000000-0000-0000-0000-00000000000b", "2026-10-01 09:00:00"},
                    {"00000000-0000-0000-0000-00000000000d", "2026-10-02 10:00:00"},
                    {"00000000-0000-0000-0000-00000000000a", "2026-10-01 09:00:00"},
                    {"00000000-0000-0000-0000-00000000000c", "2026-09-30 23:59:59"},
            };
            for (String[] f : filas) {
                st.execute("INSERT INTO movimiento_insumo (id, insumo_id, tipo_movimiento, cantidad, creado_en) VALUES ('" + f[0]
                        + "', '00000000-0000-0000-0000-0000000000a1', 'SALDO_INICIAL', 10, '" + f[1] + "')");
            }
            // La base en V39 ya trae los saldos iniciales sembrados por V19: se comprueba el insumo de la prueba y la numeracion global.
            String propios = "WHERE insumo_id = '00000000-0000-0000-0000-0000000000a1'";
            List<String> esperado = lista(st, "SELECT id FROM movimiento_insumo " + propios + " ORDER BY creado_en, id");
            String sumaAntes = escalar(st, "SELECT sum(cantidad) FROM movimiento_insumo");
            int total = Integer.parseInt(escalar(st, "SELECT count(*) FROM movimiento_insumo"));

            Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("40").load().migrate();

            assertThat(lista(st, "SELECT id FROM movimiento_insumo " + propios + " ORDER BY orden")).isEqualTo(esperado);
            // Numeracion global: 1..N sin huecos ni repetidos.
            assertThat(escalar(st, "SELECT min(orden) || '-' || max(orden) || '-' || count(DISTINCT orden) FROM movimiento_insumo"))
                    .isEqualTo("1-" + total + "-" + total);
            assertThat(escalar(st, "SELECT sum(cantidad) FROM movimiento_insumo")).isEqualTo(sumaAntes);

            // Un movimiento nuevo recibe el siguiente numero, sin indicarlo.
            st.execute("INSERT INTO movimiento_insumo (insumo_id, tipo_movimiento, cantidad) VALUES ('00000000-0000-0000-0000-0000000000a1', 'SALDO_INICIAL', 1)");
            assertThat(escalar(st, "SELECT max(orden) FROM movimiento_insumo")).isEqualTo(String.valueOf(total + 1));
            // El numero es unico.
            assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_movimiento_insumo_orden'")).isEqualTo("1");
        }
    }

    @Test
    void conElLibroVacioLaSecuenciaEmpiezaEnUno() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("40").load().migrate();
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO insumo (id, nombre, unidad_medida, stock) VALUES ('00000000-0000-0000-0000-0000000000a2', 'Sal V40', 'kg', 0)");
            st.execute("INSERT INTO movimiento_insumo (insumo_id, tipo_movimiento, cantidad) VALUES ('00000000-0000-0000-0000-0000000000a2', 'SALDO_INICIAL', 1)");
            assertThat(escalar(st, "SELECT min(orden) FROM movimiento_insumo")).isEqualTo("1");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ECO_MIGRACION_COPIA_URL", matches = ".+")
    void sobreUnaCopiaDeUnaBaseRealEnV39NingunSaldoAcumuladoCambia() throws Exception {
        String url = System.getenv("ECO_MIGRACION_COPIA_URL");
        String usuario = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_USUARIO", "postgres");
        String clave = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_CLAVE", "postgres");
        // Saldo acumulado de cada movimiento segun el orden que mostraba el kardex (creado_en, id).
        String saldosAntes;
        String conteo;
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("39");
            saldosAntes = escalar(st, "SELECT string_agg(id || ':' || saldo, ',' ORDER BY insumo_id, creado_en, id) FROM ("
                    + "SELECT id, insumo_id, creado_en, SUM(cantidad) OVER (PARTITION BY insumo_id ORDER BY creado_en, id) AS saldo FROM movimiento_insumo) t");
            conteo = escalar(st, "SELECT count(*) || '/' || sum(cantidad) FROM movimiento_insumo");
        }
        Flyway.configure().dataSource(url, usuario, clave).locations("classpath:db/migration").target("40").load().migrate();
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("40");
            assertThat(escalar(st, "SELECT count(*) || '/' || sum(cantidad) FROM movimiento_insumo")).isEqualTo(conteo);
            assertThat(escalar(st, "SELECT count(*) FROM movimiento_insumo WHERE orden IS NULL")).isEqualTo("0");
            String saldosDespues = escalar(st, "SELECT string_agg(id || ':' || saldo, ',' ORDER BY insumo_id, orden) FROM ("
                    + "SELECT id, insumo_id, orden, SUM(cantidad) OVER (PARTITION BY insumo_id ORDER BY orden) AS saldo FROM movimiento_insumo) t");
            assertThat(saldosDespues).isEqualTo(saldosAntes);
            // El libro sigue cuadrando con el stock de cada insumo.
            assertThat(escalar(st, "SELECT count(*) FROM insumo i JOIN (SELECT insumo_id, SUM(cantidad) s FROM movimiento_insumo GROUP BY 1) m ON m.insumo_id = i.id WHERE i.stock <> m.s"))
                    .isEqualTo("0");
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> lista(Statement st, String sql) throws SQLException {
        List<String> r = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) r.add(rs.getString(1));
        }
        return r;
    }
}
