package com.planta.acopio.integration;

import com.planta.acopio.service.BusquedaTexto;
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
 * V41 (fusion de insumos y unicidad normalizada) desde una base en V40. Con nombres duplicados la migracion NO falla: deja
 * el indice unico pendiente y asegurar_indice_unico_insumo() lo crea cuando ya no quedan duplicados. La clave de la base
 * (norm_texto) coincide con la de Java (BusquedaTexto.clave). El tercer caso corre sobre una COPIA de una base real en V40
 * si se indica su URL en ECO_MIGRACION_COPIA_URL; nunca debe apuntar a la base real.
 */
class MigracionFusionInsumosIntegrationTest {

    private static void migrar(String[] base, String objetivo) {
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target(objetivo).load().migrate();
    }

    @Test
    void conDuplicadosLaMigracionNoFallaYElIndiceSeCreaCuandoYaNoQuedanDuplicados() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        migrar(base, "40");
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO insumo (id, nombre, unidad_medida, stock) VALUES ('00000000-0000-0000-0000-0000000000b1', 'azucar t', 'kg', 112)");
            st.execute("INSERT INTO insumo (id, nombre, unidad_medida, stock) VALUES ('00000000-0000-0000-0000-0000000000b2', 'Azúcar T', 'kg', 98)");
            String antes = escalar(st, "SELECT count(*) || '/' || sum(stock) FROM insumo");

            migrar(base, "41");

            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("41");
            assertThat(escalar(st, "SELECT count(*) || '/' || sum(stock) FROM insumo")).isEqualTo(antes);   // ningun dato cambia
            assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_insumo_nombre_normalizado'")).isEqualTo("0");
            assertThat(escalar(st, "SELECT asegurar_indice_unico_insumo()")).isEqualTo("f");   // aun hay duplicados

            st.execute("UPDATE insumo SET nombre = 'Azúcar rubia' WHERE id = '00000000-0000-0000-0000-0000000000b2'");
            assertThat(escalar(st, "SELECT asegurar_indice_unico_insumo()")).isEqualTo("t");
            assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_insumo_nombre_normalizado'")).isEqualTo("1");
            assertThat(escalar(st, "SELECT asegurar_indice_unico_insumo()")).isEqualTo("t");   // idempotente

            // Con el indice activo la base rechaza el duplicado, pero un insumo fusionado no cuenta.
            try {
                st.execute("INSERT INTO insumo (nombre, unidad_medida) VALUES ('AZUCAR  t', 'kg')");
                throw new AssertionError("el indice unico debia rechazar el duplicado");
            } catch (SQLException esperado) {
                assertThat(esperado.getMessage()).contains("uq_insumo_nombre_normalizado");
            }
        }
    }

    @Test
    void enUnaBaseSinDuplicadosElIndiceQuedaCreadoYLaClaveCoincideConJava() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        migrar(base, "41");
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_insumo_nombre_normalizado'")).isEqualTo("1");
            String[] nombres = {"Azúcar", "  PIÑA   Tropical ", "Cloruro de calcio", "ÁÉÍÓÚÜÑ", "Cultivo láctico (fermento)", "Envase botella 1 L", "Sal\tfina"};
            for (String muestra : nombres) {
                String n = "Z " + muestra;   // los insumos sembrados por V17 (Azúcar, Cloruro de calcio...) ya existen
                st.execute("INSERT INTO insumo (nombre, unidad_medida) VALUES ('" + n.replace("'", "''") + "', 'kg')");
                String enBase = escalar(st, "SELECT nombre_normalizado FROM insumo WHERE nombre = '" + n.replace("'", "''") + "'");
                assertThat(enBase).as(n).isEqualTo(BusquedaTexto.clave(n));
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ECO_MIGRACION_COPIA_URL", matches = ".+")
    void sobreUnaCopiaDeUnaBaseRealEnV40NoCambiaNingunDatoYDejaElIndicePendiente() throws Exception {
        String url = System.getenv("ECO_MIGRACION_COPIA_URL");
        String usuario = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_USUARIO", "postgres");
        String clave = System.getenv().getOrDefault("ECO_MIGRACION_COPIA_CLAVE", "postgres");
        String antes;
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("40");
            antes = huella(st);
        }
        Flyway.configure().dataSource(url, usuario, clave).locations("classpath:db/migration").target("41").load().migrate();
        try (Connection c = DriverManager.getConnection(url, usuario, clave); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success")).isEqualTo("41");
            assertThat(huella(st)).isEqualTo(antes);
            // azucar / Azúcar siguen siendo dos insumos hasta que se fusionen.
            assertThat(escalar(st, "SELECT count(*) FROM insumo WHERE nombre_normalizado = 'azucar'")).isEqualTo("2");
            assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_insumo_nombre_normalizado'")).isEqualTo("0");
        }
    }

    private static String huella(Statement st) throws SQLException {
        return escalar(st, "SELECT (SELECT count(*) FROM insumo) || '/' || (SELECT sum(stock) FROM insumo) || '/' || (SELECT count(*) FROM movimiento_insumo)"
                + " || '/' || (SELECT sum(cantidad) FROM movimiento_insumo)");
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
