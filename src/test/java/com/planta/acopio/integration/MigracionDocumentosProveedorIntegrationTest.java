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
 * Documentos del proveedor, V31: la migracion es ADITIVA. Migra hasta V30, siembra proveedores con DNI de formas anteriores (corto, con
 * espacios, con forma de RUC), migra V31 y verifica que ningun valor cambia, que todos quedan como persona natural y que las nuevas
 * restricciones se cumplen en la base (natural con DNI, empresa con RUC y razon social y sin DNI, RUC de 11 digitos y unico).
 */
class MigracionDocumentosProveedorIntegrationTest {

    @Test
    void v31ConservaLosDocumentosAnterioresYSusRestriccionesSeCumplen() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("30").load().migrate();

        String huellaAntes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO proveedor (id, nombre, dni, telefono) VALUES ('" + UUID.randomUUID() + "', 'Normal', '04567890', '+51987654321')");
            st.execute("INSERT INTO proveedor (id, nombre, dni) VALUES ('" + UUID.randomUUID() + "', 'Corto', '12345')");
            st.execute("INSERT INTO proveedor (id, nombre, dni) VALUES ('" + UUID.randomUUID() + "', 'Con espacios', '1111 1111')");
            st.execute("INSERT INTO proveedor (id, nombre, dni, activo) VALUES ('" + UUID.randomUUID() + "', 'Parece RUC', '10045678909', false)");
            huellaAntes = huella(st);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("31").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(huella(st)).isEqualTo(huellaAntes);
            assertThat(escalar(st, "SELECT count(*) FROM proveedor WHERE tipo_persona = 'NATURAL' AND ruc IS NULL AND razon_social IS NULL")).isEqualTo("4");

            // validos: persona natural con DNI y RUC, empresa con RUC y razon social
            st.execute("INSERT INTO proveedor (nombre, dni, ruc) VALUES ('Rosa', '22222222', '10222222220')");
            st.execute("INSERT INTO proveedor (nombre, tipo_persona, ruc, razon_social) VALUES ('ACME', 'EMPRESA', '20601234565', 'ACME S.A.C.')");
            // dos empresas sin DNI no chocan con la unicidad del DNI
            st.execute("INSERT INTO proveedor (nombre, tipo_persona, ruc, razon_social) VALUES ('Otra', 'EMPRESA', '20481234566', 'Otra S.R.L.')");

            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre) VALUES ('Sin DNI')")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_proveedor_documentos");
            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre, dni, razon_social) VALUES ('Natural con razon', '33333333', 'X')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_proveedor_documentos");
            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre, tipo_persona, razon_social) VALUES ('Empresa sin RUC', 'EMPRESA', 'X')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_proveedor_documentos");
            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre, tipo_persona, ruc) VALUES ('Empresa sin razon', 'EMPRESA', '20100000009')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_proveedor_documentos");
            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre, tipo_persona, dni, ruc, razon_social) VALUES ('Empresa con DNI', 'EMPRESA', '44444444', '20100000009', 'X')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_proveedor_documentos");
            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre, tipo_persona, ruc, razon_social) VALUES ('Tipo raro', 'SOCIEDAD', '20100000009', 'X')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_proveedor_"); // tipo fuera de la lista (y por tanto ni natural ni empresa)
            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre, dni, ruc) VALUES ('RUC corto', '55555555', '1055555555')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_proveedor_ruc_forma");
            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre, tipo_persona, ruc, razon_social) VALUES ('RUC repetido', 'EMPRESA', '20601234565', 'X')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_proveedor_ruc");
            assertThatThrownBy(() -> st.execute("INSERT INTO proveedor (nombre, dni) VALUES ('DNI repetido', '04567890')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String huella(Statement st) throws SQLException {
        return escalar(st, "SELECT md5(string_agg(id || '|' || nombre || '|' || dni || '|' || coalesce(telefono, '') || '|' || activo, ',' ORDER BY id)) FROM proveedor");
    }
}
