package com.planta.acopio.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Busqueda unica del sistema: "contiene", sin distinguir mayusculas ni acentos, con comodines escapados.
class BusquedaTextoTest {

    @Test
    void sinTerminoNoHayFiltro() {
        assertThat(BusquedaTexto.patronLike(null)).isNull();
        assertThat(BusquedaTexto.patronLike("")).isNull();
        assertThat(BusquedaTexto.patronLike("   ")).isNull();
    }

    @Test
    void conTerminoBuscaCualquierPosicionEnMinusculas() {
        assertThat(BusquedaTexto.patronLike("  Hual ")).isEqualTo("%hual%");
    }

    @Test
    void ignoraAcentosYMayusculasEnAmbosSentidos() {
        // Lo que escribe el usuario se pliega igual que la columna en la BD (LOWER(TRANSLATE(...))).
        assertThat(BusquedaTexto.patronLike("HUAMÁN")).isEqualTo("%huaman%");
        assertThat(BusquedaTexto.patronLike("huaman")).isEqualTo("%huaman%");
        assertThat(BusquedaTexto.patronLike("Peña")).isEqualTo("%pena%");
        assertThat(BusquedaTexto.patronLike("pingüino")).isEqualTo("%pinguino%");
        assertThat(BusquedaTexto.patronLike("ÁÉÍÓÚÜÑ")).isEqualTo("%aeiouun%");
    }

    @Test
    void aceptaAcentosEnFormaDescompuesta() {
        // "a" + acento combinado (NFD), como pegan algunos teclados/sistemas.
        assertThat(BusquedaTexto.patronLike("Huamán")).isEqualTo("%huaman%");
    }

    @Test
    void escapaLosComodinesDeLike() {
        assertThat(BusquedaTexto.patronLike("50%_!")).isEqualTo("%50!%!_!!%");
    }

    @Test
    void laTablaDeLetrasDeJavaYDeLaBdTieneLaMismaLongitud() {
        // Si no coincidieran, TRANSLATE y plegar() plegarian letras distintas.
        assertThat(BusquedaTexto.CON_ACENTO).hasSameSizeAs(BusquedaTexto.SIN_ACENTO);
        assertThat(BusquedaTexto.PLEGAR_CIERRA)
                .contains(BusquedaTexto.CON_ACENTO)
                .contains(BusquedaTexto.SIN_ACENTO);
    }

    @Test
    void patronLikeOTodosDevuelvePorcentajeSinTermino() {
        assertThat(BusquedaTexto.patronLikeOTodos(null)).isEqualTo("%");
        assertThat(BusquedaTexto.patronLikeOTodos("Ab")).isEqualTo("%ab%");
    }

    @Test
    void claveIgualaMayusculasTildesYEspacios() {
        assertThat(BusquedaTexto.clave("Azúcar")).isEqualTo("azucar");
        assertThat(BusquedaTexto.clave("  AZUCAR ")).isEqualTo("azucar");
        assertThat(BusquedaTexto.clave("Leche   en	polvo")).isEqualTo("leche en polvo");
        assertThat(BusquedaTexto.clave("Piña")).isEqualTo("pina");
        assertThat(BusquedaTexto.clave(null)).isEmpty();
        assertThat(BusquedaTexto.clave("Sal")).isNotEqualTo(BusquedaTexto.clave("Sal fina"));
    }
}
