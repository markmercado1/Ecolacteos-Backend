package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase A (zonas historicas), V27: la migracion es SOLO ADITIVA. Mismo patron que MigracionControlVencimientoIntegrationTest
 * (Flyway directo, sin @SpringBootTest, para sembrar datos "pre-V27"): migra hasta V26, siembra proveedores con periodos de
 * zona del codigo anterior -- incluido el caso real de periodos INVERTIDOS (hasta < desde, reasignaciones el mismo dia) --
 * y entregas, y migra V27.
 *
 * Verifica que desde/hasta y las entregas no cambian, que los periodos invertidos se MARCAN (no se borran ni se adivinan),
 * que cada entrega recibe la zona de su momento SOLO si hay un periodo claro y no cae en un dia de cambio de zona, y que
 * el indice unico impide dos periodos abiertos del mismo proveedor.
 */
class MigracionZonasHistoricasIntegrationTest {

    @Test
    void v27ConservaLosDatosMarcaLosPeriodosInvertidosYRellenaLaZonaSoloCuandoEsClara() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        String url = base[0], user = base[1], pass = base[2];

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").target("26").load().migrate();

        UUID rolId = UUID.randomUUID(), usuarioId = UUID.randomUUID(), unidadId = UUID.randomUUID();
        UUID z1 = UUID.randomUUID(), z2 = UUID.randomUUID(), z3 = UUID.randomUUID();
        UUID normal = UUID.randomUUID(), invertido = UUID.randomUUID(), sinHistoria = UUID.randomUUID();
        Map<String, UUID> entregas = new LinkedHashMap<>();

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rolId + "', 'ACOPIADOR_V27_TEST')");
                st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES "
                        + "('" + usuarioId + "', 'Acopiador V27', 'acopiador-v27@planta.com', 'hash', '" + rolId + "', true)");
                st.execute("INSERT INTO unidad (id, placa, responsable_id) VALUES ('" + unidadId + "', 'V27-001', '" + usuarioId + "')");
                st.execute("INSERT INTO zona (id, nombre) VALUES ('" + z1 + "', 'Chugur'), ('" + z2 + "', 'Bambamarca'), ('" + z3 + "', 'La Encanada')");
                for (UUID p : new UUID[]{normal, invertido, sinHistoria}) {
                    st.execute("INSERT INTO proveedor (id, nombre, dni, zona_actual_id) VALUES ('" + p + "', 'Proveedor " + p.toString().substring(0, 6)
                            + "', '" + p.toString().substring(0, 8) + "', '" + z2 + "')");
                }

                // Proveedor "normal": Chugur hasta 09-09 y Bambamarca desde 09-10 (cambio limpio, con el codigo anterior).
                periodo(st, normal, z1, "2026-01-01", "2026-09-09");
                periodo(st, normal, z2, "2026-09-10", null);
                // Proveedor "invertido" (caso Absalon): Chugur valido; el 09-30 tres reasignaciones el mismo dia
                // (dos invertidas); el 10-22 otra (invertida) y queda Bambamarca abierto.
                periodo(st, invertido, z1, "2026-06-01", "2026-09-29");
                periodo(st, invertido, z1, "2026-09-30", "2026-09-29");   // invertido
                periodo(st, invertido, z2, "2026-09-30", "2026-09-29");   // invertido
                periodo(st, invertido, z3, "2026-09-30", "2026-10-21");   // valido
                periodo(st, invertido, z3, "2026-10-22", "2026-10-21");   // invertido
                periodo(st, invertido, z2, "2026-10-22", null);
                // "sinHistoria": nunca tuvo periodos (proveedor creado fuera del flujo normal).

                entregas.put("normal-antes-de-la-historia", entrega(st, normal, usuarioId, unidadId, "2025-12-31 06:00:00"));
                entregas.put("normal-chugur", entrega(st, normal, usuarioId, unidadId, "2026-02-01 06:00:00"));
                entregas.put("normal-ultimo-dia-chugur", entrega(st, normal, usuarioId, unidadId, "2026-09-09 18:00:00"));
                entregas.put("normal-dia-de-cambio", entrega(st, normal, usuarioId, unidadId, "2026-09-10 05:00:00"));
                entregas.put("normal-bambamarca", entrega(st, normal, usuarioId, unidadId, "2026-09-11 06:00:00"));
                entregas.put("invertido-chugur", entrega(st, invertido, usuarioId, unidadId, "2026-08-01 06:00:00"));
                entregas.put("invertido-dia-de-cambio-1", entrega(st, invertido, usuarioId, unidadId, "2026-09-30 07:00:00"));
                entregas.put("invertido-la-encanada", entrega(st, invertido, usuarioId, unidadId, "2026-10-05 06:00:00"));
                entregas.put("invertido-dia-de-cambio-2", entrega(st, invertido, usuarioId, unidadId, "2026-10-22 06:00:00"));
                entregas.put("invertido-bambamarca", entrega(st, invertido, usuarioId, unidadId, "2026-10-23 06:00:00"));
                entregas.put("sin-historia", entrega(st, sinHistoria, usuarioId, unidadId, "2026-05-01 06:00:00"));
            }
        }

        String huellaAntes;
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            huellaAntes = huella(c);
        }

        Flyway.configure().dataSource(url, user, pass).locations("classpath:db/migration").target("27").load().migrate();

        try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
            // 1. Nada de lo existente cambia: desde/hasta y las entregas (todas las columnas que ya existian).
            assertThat(huella(c)).isEqualTo(huellaAntes);

            // 2. Periodos validos: [desde 00:00, hasta+1 00:00). Abiertos: fin_en nulo.
            assertThat(escalar(st, "SELECT inicio_en || ' / ' || COALESCE(fin_en::text, 'abierto') FROM historial_zona WHERE proveedor_id = '" + normal
                    + "' AND desde = '2026-01-01'")).isEqualTo("2026-01-01 00:00:00 / 2026-09-10 00:00:00");
            assertThat(escalar(st, "SELECT COALESCE(fin_en::text, 'abierto') FROM historial_zona WHERE proveedor_id = '" + normal
                    + "' AND desde = '2026-09-10'")).isEqualTo("abierto");
            assertThat(escalar(st, "SELECT zona_origen_id = '" + z1 + "' FROM historial_zona WHERE proveedor_id = '" + normal
                    + "' AND desde = '2026-09-10'")).isEqualTo("t");

            // 3. Periodos invertidos: se marcan para revision, con duracion cero; NO se borran. Los validos quedan sin marca.
            assertThat(escalar(st, "SELECT count(*) FROM historial_zona WHERE requiere_revision")).isEqualTo("3");
            assertThat(escalar(st, "SELECT count(*) FROM historial_zona WHERE proveedor_id = '" + invertido + "'")).isEqualTo("6");
            assertThat(escalar(st, "SELECT count(*) FROM historial_zona WHERE requiere_revision AND (fin_en <> inicio_en OR nota_revision IS NULL "
                    + "OR hasta >= desde)")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM historial_zona WHERE NOT requiere_revision AND hasta < desde")).isEqualTo("0");

            // 4. Zona de cada entrega: solo cuando hay UN periodo claro y no es un dia de cambio de zona del proveedor.
            assertThat(zonaDe(st, entregas.get("normal-antes-de-la-historia"))).isNull();
            assertThat(zonaDe(st, entregas.get("normal-chugur"))).isEqualTo(z1);
            assertThat(zonaDe(st, entregas.get("normal-ultimo-dia-chugur"))).isEqualTo(z1);
            assertThat(zonaDe(st, entregas.get("normal-dia-de-cambio"))).isNull();    // la hora del cambio no se registraba
            assertThat(zonaDe(st, entregas.get("normal-bambamarca"))).isEqualTo(z2);
            assertThat(zonaDe(st, entregas.get("invertido-chugur"))).isEqualTo(z1);
            assertThat(zonaDe(st, entregas.get("invertido-dia-de-cambio-1"))).isNull();
            assertThat(zonaDe(st, entregas.get("invertido-la-encanada"))).isEqualTo(z3);
            assertThat(zonaDe(st, entregas.get("invertido-dia-de-cambio-2"))).isNull();
            assertThat(zonaDe(st, entregas.get("invertido-bambamarca"))).isEqualTo(z2);
            assertThat(zonaDe(st, entregas.get("sin-historia"))).isNull();
            assertThat(escalar(st, "SELECT count(*) FROM registro_acopio WHERE zona_id IS NULL")).isEqualTo("5");

            // 5. Un solo periodo abierto por proveedor: el indice unico lo exige.
            assertThatThrownBy(() -> st.execute("INSERT INTO historial_zona (proveedor_id, zona_id, desde, inicio_en) VALUES ('" + normal + "', '"
                    + z3 + "', '2026-10-23', '2026-10-23 08:00:00')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_historial_zona_abierto");
            // y un periodo no puede terminar antes de empezar
            assertThatThrownBy(() -> st.execute("INSERT INTO historial_zona (proveedor_id, zona_id, desde, inicio_en, fin_en) VALUES ('" + sinHistoria + "', '"
                    + z3 + "', '2026-10-23', '2026-10-23 08:00:00', '2026-10-23 07:00:00')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_historial_zona_periodo");
        }
    }

    private static void periodo(Statement st, UUID proveedor, UUID zona, String desde, String hasta) throws SQLException {
        st.execute("INSERT INTO historial_zona (proveedor_id, zona_id, desde, hasta) VALUES ('" + proveedor + "', '" + zona + "', '" + desde + "', "
                + (hasta == null ? "NULL" : "'" + hasta + "'") + ")");
    }

    private static UUID entrega(Statement st, UUID proveedor, UUID usuario, UUID unidad, String fechaHora) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute("INSERT INTO registro_acopio (id, uuid_cliente, proveedor_id, acopiador_id, unidad_id, fecha_hora, litros) VALUES ('" + id + "', '"
                + UUID.randomUUID() + "', '" + proveedor + "', '" + usuario + "', '" + unidad + "', '" + fechaHora + "', 100.00)");
        return id;
    }

    private static UUID zonaDe(Statement st, UUID registro) throws SQLException {
        String zona = escalar(st, "SELECT zona_id FROM registro_acopio WHERE id = '" + registro + "'");
        return zona == null ? null : UUID.fromString(zona);
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Huella de lo que V27 NO debe tocar: desde/hasta de todos los periodos y todas las columnas previas de las entregas. */
    private static String huella(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            return escalar(st, "SELECT (SELECT md5(string_agg(proveedor_id || '|' || zona_id || '|' || desde || '|' || COALESCE(hasta::text, ''), ',' "
                    + "ORDER BY proveedor_id, desde, zona_id, COALESCE(hasta::text, ''))) FROM historial_zona) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || uuid_cliente || '|' || proveedor_id || '|' || acopiador_id || '|' || unidad_id || '|' "
                    + "|| fecha_hora || '|' || litros, ',' ORDER BY id)) FROM registro_acopio)");
        }
    }
}
