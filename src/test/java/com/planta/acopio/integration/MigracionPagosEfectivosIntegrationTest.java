package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pagos efectivos, V30: la migracion es SOLO ADITIVA. Migra hasta V29, siembra un pago con sus lineas y ajustes, migra V30 y verifica que nada
 * de lo existente cambia, que las tablas nuevas nacen vacias y que sus restricciones se cumplen en la base (monto positivo, banco y operacion
 * obligatorios fuera del efectivo, anulacion siempre con motivo/usuario/fecha, operacion bancaria unica entre pagos vigentes).
 */
class MigracionPagosEfectivosIntegrationTest {

    @Test
    void v30NoTocaNadaLasTablasNacenVaciasYSusRestriccionesSeCumplen() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("29").load().migrate();

        UUID rol = UUID.randomUUID(), usuario = UUID.randomUUID(), unidad = UUID.randomUUID(), proveedor = UUID.randomUUID();
        UUID pago = UUID.randomUUID(), r1 = UUID.randomUUID();
        String huellaAntes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO rol (id, nombre) VALUES ('" + rol + "', 'ACOPIADOR_V30_TEST')");
            st.execute("INSERT INTO usuario (id, nombre, email, password_hash, rol_id, activo) VALUES ('" + usuario + "', 'Usuario V30', 'v30@planta.com', 'hash', '" + rol + "', true)");
            st.execute("INSERT INTO unidad (id, placa, responsable_id) VALUES ('" + unidad + "', 'V30-001', '" + usuario + "')");
            st.execute("INSERT INTO proveedor (id, nombre, dni) VALUES ('" + proveedor + "', 'Proveedor V30', '30303030')");
            st.execute("INSERT INTO registro_acopio (id, uuid_cliente, proveedor_id, acopiador_id, unidad_id, fecha_hora, litros) VALUES ('" + r1 + "', '"
                    + UUID.randomUUID() + "', '" + proveedor + "', '" + usuario + "', '" + unidad + "', '2026-10-12 06:00:00', 100.00)");
            st.execute("INSERT INTO pago (id, proveedor_id, semana_inicio, semana_fin, litros_totales, precio_litro, creado_en) VALUES ('" + pago + "', '" + proveedor
                    + "', '2026-10-12', '2026-10-18', 100.00, 1.000, '2026-10-19 10:00:00')");
            st.execute("INSERT INTO pago_registro_acopio (pago_id, registro_acopio_id) VALUES ('" + pago + "', '" + r1 + "')");
            st.execute("INSERT INTO pago_linea (pago_id, registro_acopio_id, litros_originales, litros_liquidados, precio_base) VALUES ('" + pago + "', '" + r1 + "', 100.00, 100.00, 1.000)");
        }
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2])) {
            huellaAntes = huella(c);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("30").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(huella(c)).isEqualTo(huellaAntes);
            assertThat(escalar(st, "SELECT count(*) FROM pago_proveedor")).isEqualTo("0");
            assertThat(escalar(st, "SELECT count(*) FROM pago_proveedor_aplicacion")).isEqualTo("0");

            // un efectivo valido (sin banco) y una transferencia valida entran
            st.execute(insertar("u-1", proveedor, "EFECTIVO", "50.00", null, null, usuario));
            st.execute(insertar("u-2", proveedor, "TRANSFERENCIA", "70.00", "BCP", "OP-1", usuario));

            // restricciones de la base
            assertThatThrownBy(() -> st.execute(insertar("u-3", proveedor, "EFECTIVO", "0", null, null, usuario))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_pago_proveedor_monto");
            assertThatThrownBy(() -> st.execute(insertar("u-4", proveedor, "CHEQUE", "10", null, null, usuario))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_pago_proveedor_modalidad");
            assertThatThrownBy(() -> st.execute(insertar("u-5", proveedor, "TRANSFERENCIA", "10", "BCP", null, usuario))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_pago_proveedor_operacion");
            assertThatThrownBy(() -> st.execute(insertar("u-6", proveedor, "DEPOSITO", "10", "  ", "OP-9", usuario))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_pago_proveedor_operacion");
            assertThatThrownBy(() -> st.execute(insertar("u-1", proveedor, "EFECTIVO", "10", null, null, usuario))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("pago_proveedor_uuid_cliente_key"); // mismo uuid_cliente
            assertThatThrownBy(() -> st.execute(insertar("u-7", proveedor, "TRANSFERENCIA", "10", " bcp ", "op-1 ", usuario))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_pago_proveedor_operacion"); // misma operacion vigente (sin distinguir mayusculas ni espacios)

            // anulacion: solo con motivo, usuario y fecha; al anular, la operacion queda libre para volver a registrarla
            assertThatThrownBy(() -> st.execute("UPDATE pago_proveedor SET estado = 'ANULADO' WHERE uuid_cliente = 'u-2'")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_pago_proveedor_anulacion");
            assertThatThrownBy(() -> st.execute("UPDATE pago_proveedor SET estado = 'ANULADO', anulado_motivo = '  ', anulado_por = '" + usuario
                    + "', anulado_en = now() WHERE uuid_cliente = 'u-2'")).isInstanceOf(SQLException.class);
            st.execute("UPDATE pago_proveedor SET estado = 'ANULADO', anulado_motivo = 'Monto mal digitado', anulado_por = '" + usuario
                    + "', anulado_en = now() WHERE uuid_cliente = 'u-2'");
            st.execute(insertar("u-8", proveedor, "TRANSFERENCIA", "70.00", "BCP", "OP-1", usuario));
            assertThatThrownBy(() -> st.execute("UPDATE pago_proveedor SET anulado_motivo = 'x' WHERE uuid_cliente = 'u-1'")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_pago_proveedor_anulacion"); // motivo sin estado anulado

            // aplicaciones: monto positivo y referencias validas
            UUID desembolso = UUID.fromString(escalar(st, "SELECT id FROM pago_proveedor WHERE uuid_cliente = 'u-1'"));
            st.execute("INSERT INTO pago_proveedor_aplicacion (pago_proveedor_id, pago_id, monto, usuario_id) VALUES ('" + desembolso + "', '" + pago + "', 20.00, '" + usuario + "')");
            assertThatThrownBy(() -> st.execute("INSERT INTO pago_proveedor_aplicacion (pago_proveedor_id, pago_id, monto, usuario_id) VALUES ('" + desembolso
                    + "', '" + pago + "', 0, '" + usuario + "')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO pago_proveedor_aplicacion (pago_proveedor_id, pago_id, monto, usuario_id) VALUES ('" + desembolso
                    + "', '" + UUID.randomUUID() + "', 5, '" + usuario + "')")).isInstanceOf(SQLException.class);
            assertThat(escalar(st, "SELECT count(*) FROM pago_proveedor")).isEqualTo("3");
            assertThat(escalar(st, "SELECT count(*) FROM pago_proveedor_aplicacion")).isEqualTo("1");
        }
    }

    private static String insertar(String uuidCliente, UUID proveedor, String modalidad, String monto, String banco, String operacion, UUID usuario) {
        return "INSERT INTO pago_proveedor (uuid_cliente, proveedor_id, modalidad, monto, banco, nro_operacion, usuario_id) VALUES ('" + uuidCliente + "', '" + proveedor
                + "', '" + modalidad + "', " + monto + ", " + (banco == null ? "NULL" : "'" + banco + "'") + ", " + (operacion == null ? "NULL" : "'" + operacion + "'")
                + ", '" + usuario + "')";
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Huella de lo que V30 NO debe tocar. */
    private static String huella(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            return escalar(st, "SELECT (SELECT md5(string_agg(id || '|' || litros_totales || '|' || precio_litro, ',' ORDER BY id)) FROM pago) || '/' || "
                    + "(SELECT md5(string_agg(id || '|' || pago_id || '|' || registro_acopio_id || '|' || litros_liquidados || '|' || precio_base, ',' ORDER BY id)) FROM pago_linea)");
        }
    }
}
