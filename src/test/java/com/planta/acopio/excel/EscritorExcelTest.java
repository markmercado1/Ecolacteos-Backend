package com.planta.acopio.excel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

// Inyeccion de formulas: un texto que Excel/CSV podria interpretar como formula se escribe con una comilla simple
// delante; un texto normal, un numero o un telefono con signo no se tocan.
class EscritorExcelTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "=SUM(A1:A9)",
            "=HYPERLINK(\"http://malo.example\",\"clic\")",
            "+cmd|' /C calc'!A0",
            "-2+3",
            "-1+cmd|' /C calc'!A0",
            "@SUM(1)",
            "=",
            "\t=1+1",
            "\r=1+1",
            " =1+1",
            "\t texto con tabulador inicial"
    })
    void neutralizaLoQueSePodriaLeerComoFormula(String entrada) {
        assertThat(EscritorExcel.neutralizarFormula(entrada)).isEqualTo("'" + entrada);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Juan Pérez",
            "Queso fresco 500 g",
            "a=b",
            "Zona Norte (2)",
            "+51 987 654 321",
            "+51987654321",
            "-12.5",
            "-12,50",
            "(01) 234-5678",
            "987654321",
            "00004567",
            "0 = cero"
    })
    void noTocaTextosNumerosNiTelefonosNormales(String entrada) {
        assertThat(EscritorExcel.neutralizarFormula(entrada)).isEqualTo(entrada);
    }

    @Test
    void nuloYVacioPasanIntactos() {
        assertThat(EscritorExcel.neutralizarFormula(null)).isNull();
        assertThat(EscritorExcel.neutralizarFormula("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ventas 2026/10", "Reporte: [A]", "Un nombre de hoja demasiado largo para Excel que pasa de treinta y un caracteres", "a?b*c\\d"})
    void elNombreDeHojaEsValidoParaExcel(String solicitado) {
        assertThat(EscritorExcel.nombreHoja(solicitado))
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(31)
                .doesNotContain("/", "\\", "?", "*", "[", "]", ":");
    }

    @Test
    void sinNombreDeHojaUsaDatos() {
        assertThat(EscritorExcel.nombreHoja(null)).isEqualTo("Datos");
        assertThat(EscritorExcel.nombreHoja("  ")).isEqualTo("Datos");
    }
}
