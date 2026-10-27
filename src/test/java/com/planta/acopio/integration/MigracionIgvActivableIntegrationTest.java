package com.planta.acopio.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IGV activable desde el panel, V35: migracion SOLO ADITIVA (una tabla nueva). Migra hasta V34, guarda una huella de la configuracion del IGV, migra
 * V35 y verifica que nada cambia, que la tabla nace vacia (rige el valor por defecto) y que la base exige el motivo de cada cambio.
 */
class MigracionIgvActivableIntegrationTest {

    @Test
    void v35NoTocaLaConfiguracionExistenteLaTablaNaceVaciaYExigeMotivo() throws Exception {
        String[] base = PostgresDePrueba.baseNueva();
        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("34").load().migrate();
        String huellaAntes;
        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tasa_igv (tasa, vigente_desde, motivo) VALUES (18.00, '2026-10-24', 'Tasa general')");
            huellaAntes = huella(st);
        }

        Flyway.configure().dataSource(base[0], base[1], base[2]).locations("classpath:db/migration").target("35").load().migrate();

        try (Connection c = DriverManager.getConnection(base[0], base[1], base[2]); Statement st = c.createStatement()) {
            assertThat(huella(st)).isEqualTo(huellaAntes);                                      // nada existente cambia
            assertThat(escalar(st, "SELECT count(*) FROM cambio_calculo_igv")).isEqualTo("0"); // nace vacia: rige el valor por defecto (apagado)
            st.execute("INSERT INTO cambio_calculo_igv (habilitado, motivo) VALUES (true, 'El administrador decidio desglosar el IGV')");
            assertThatThrownBy(() -> st.execute("INSERT INTO cambio_calculo_igv (habilitado, motivo) VALUES (false, '   ')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO cambio_calculo_igv (habilitado, motivo) VALUES (false, NULL)")).isInstanceOf(SQLException.class);
            assertThat(escalar(st, "SELECT count(*) FROM cambio_calculo_igv")).isEqualTo("1");
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String huella(Statement st) throws SQLException {
        return escalar(st, "SELECT md5(string_agg(id || '|' || tasa || '|' || vigente_desde, ',' ORDER BY id)) FROM tasa_igv");
    }
}
