package com.planta.acopio.service;

import com.planta.acopio.exception.ValidacionNegocioException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Fase 4: reglas de fecha, limites y parametros (puros). Las fechas se evaluan en America/Lima: la prueba de "cambio de
// dia" fija instantes cuyo dia en UTC difiere del de Lima.
class ReservaParametrosTest {

    // 2026-10-11 es domingo; lunes 12 ... sabado 17; domingo 18.
    private static final LocalDate DOMINGO = LocalDate.of(2026, 10, 11);
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final ReservaParametros p = ReservaParametros.porDefecto(); // plazo 3, lunes a sabado, cierre 18:00

    private static LocalDateTime lima(int dia, int hora, int minuto) {
        return LocalDateTime.of(2026, 10, dia, hora, minuto);
    }

    // ------------------------------------------------------------------ valores iniciales

    @Test
    void losValoresInicialesSonLosAcordados() {
        assertThat(p.plazoDias()).isEqualTo(3);
        assertThat(p.maxUnidades()).isEqualTo(20);
        assertThat(p.maxPendientesTelefono()).isEqualTo(2);
        assertThat(p.horasPendiente()).isEqualTo(24);
        assertThat(p.diasAtencion()).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
        assertThat(p.horaCierre()).isEqualTo(LocalTime.of(18, 0));
        assertThat(p.diasNoLaborables()).isEmpty();
    }

    // ------------------------------------------------------------------ fechas

    @Test
    void elPlazoVaDesdeHoyHastaHoyMasTresInclusive() {
        LocalDateTime ahora = lima(12, 9, 0); // lunes
        assertThat(p.fechaMaxima(ahora.toLocalDate())).isEqualTo(LocalDate.of(2026, 10, 15));

        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 12), ahora)).as("hoy").isEmpty();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 15), ahora)).as("hoy + 3 inclusive").isEmpty();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 16), ahora)).as("hoy + 4").hasValueSatisfying(m -> assertThat(m).contains("próximos 3 días"));
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 11), ahora)).as("ayer").hasValueSatisfying(m -> assertThat(m).contains("anterior a hoy"));
    }

    @Test
    void lasFechasDisponiblesSonExactamenteLasQueAceptaErrorDeFecha() {
        // Jueves 15 a las 9:00: hoy, viernes y sabado; el domingo (hoy + 3) no se atiende.
        LocalDateTime jueves = lima(15, 9, 0);
        assertThat(p.fechasDisponibles(jueves)).containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17));
        // Pasada la hora de cierre ya no se acepta el retiro de hoy.
        assertThat(p.fechasDisponibles(lima(15, 18, 0))).containsExactly(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17));
        // Para cualquier momento, cada fecha listada es valida y ninguna otra dentro del plazo lo es.
        for (int dia = 11; dia <= 20; dia++) {
            LocalDateTime ahora = lima(dia, 10, 0);
            List<LocalDate> fechas = p.fechasDisponibles(ahora);
            for (LocalDate f = ahora.toLocalDate(); !f.isAfter(p.fechaMaxima(ahora.toLocalDate())); f = f.plusDays(1)) {
                assertThat(fechas.contains(f)).as("dia " + dia + " fecha " + f).isEqualTo(p.errorDeFecha(f, ahora).isEmpty());
            }
        }
    }

    @Test
    void soloSeAdmitenDiasDeAtencionYElDomingoNoLoEsPorDefecto() {
        LocalDateTime ahora = lima(15, 9, 0); // jueves
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 17), ahora)).as("sabado").isEmpty();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 18), ahora)).as("domingo (hoy+3)").hasValueSatisfying(m -> assertThat(m).contains("no es un día de atención"));
    }

    @Test
    void losDiasNoLaborablesYFeriadosSeLeenDeLaConfiguracionNoDelCodigo() {
        ReservaParametros conFeriado = new ReservaParametros(3, 20, 2, 24, Set.of(1, 2, 3, 4, 5, 6), LocalTime.of(18, 0),
                Set.of(LocalDate.of(2026, 10, 13)));
        LocalDateTime ahora = lima(12, 9, 0);

        assertThat(conFeriado.errorDeFecha(LocalDate.of(2026, 10, 13), ahora)).hasValueSatisfying(m -> assertThat(m).contains("no es un día de atención"));
        assertThat(conFeriado.errorDeFecha(LocalDate.of(2026, 10, 14), ahora)).isEmpty();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 13), ahora)).as("sin ese feriado configurado, el martes si se atiende").isEmpty();
    }

    @Test
    void elRetiroDelMismoDiaSoloVaAntesDeLaHoraDeCierre() {
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 12), lima(12, 17, 59))).isEmpty();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 12), lima(12, 18, 0))).hasValueSatisfying(m -> assertThat(m).contains("cierra a las 18:00"));
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 12), lima(12, 23, 30))).isPresent();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 13), lima(12, 23, 30))).as("manana si").isEmpty();
    }

    @Test
    void elCambioDeDiaSeEvaluaEnHoraDeLimaNoEnUtc() {
        // 2026-10-12 23:30 en Lima (UTC-5) son las 04:30 UTC del 13. Hoy es lunes 12 en Lima.
        ZonedDateTime instante = ZonedDateTime.of(lima(12, 23, 30), LIMA);
        assertThat(instante.withZoneSameInstant(ZoneId.of("UTC")).toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        LocalDateTime ahoraLima = instante.toLocalDateTime();

        assertThat(p.fechaMaxima(ahoraLima.toLocalDate())).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 12), ahoraLima)).as("el 12 ya cerro pero sigue siendo 'hoy' en Lima").isPresent();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 15), ahoraLima)).as("hoy(Lima)+3").isEmpty();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 16), ahoraLima)).as("con UTC este seria valido; en Lima no").isPresent();
        // Pasada la medianoche de Lima, el dia cambia.
        LocalDateTime pasadaLaMedianoche = lima(13, 0, 5);
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 12), pasadaLaMedianoche)).isPresent();
        assertThat(p.errorDeFecha(LocalDate.of(2026, 10, 16), pasadaLaMedianoche)).isEmpty();
    }

    @Test
    void laPrimeraFechaDisponibleSaltaLosDiasSinAtencionYElHorarioCerrado() {
        assertThat(p.primeraFechaDisponible(lima(12, 9, 0))).contains(LocalDate.of(2026, 10, 12));      // lunes por la manana
        assertThat(p.primeraFechaDisponible(lima(12, 19, 0))).contains(LocalDate.of(2026, 10, 13));     // lunes tras el cierre
        assertThat(p.primeraFechaDisponible(lima(17, 19, 0))).as("sabado tras el cierre: domingo no atiende, lunes esta fuera del plazo? (17+3=20)")
                .contains(LocalDate.of(2026, 10, 19));
        ReservaParametros sinDias = new ReservaParametros(3, 20, 2, 24, Set.of(7), LocalTime.of(18, 0), Set.of());
        assertThat(sinDias.primeraFechaDisponible(lima(12, 9, 0))).as("ningun domingo dentro del plazo").isEmpty();
    }

    // ------------------------------------------------------------------ unidades

    @Test
    void lasMedidasSeReconocenPorSuUnidadDeConteoYLasUnidadesDiscretasNo() {
        for (String medida : List.of("kg", "KG", "Kilogramos", "kilo", "g", "gramos", "L", "litros", "ml", " mililitro ")) {
            assertThat(ReservaParametros.esMedida(medida)).as(medida).isTrue();
        }
        for (String discreta : List.of("unidad", "unidades", "botella", "botellas", "pieza", "bolsa", "paquete", "")) {
            assertThat(ReservaParametros.esMedida(discreta)).as(discreta).isFalse();
        }
        assertThat(ReservaParametros.esMedida(null)).isFalse();
    }

    @Test
    void laClaveDeUnidadAgrupaPluralesYAcentos() {
        assertThat(ReservaParametros.clave("Kilogramos")).isEqualTo(ReservaParametros.clave("kilogramo"));
        assertThat(ReservaParametros.clave("Botellas")).isEqualTo(ReservaParametros.clave("botella"));
        assertThat(ReservaParametros.clave("Unidades")).isEqualTo(ReservaParametros.clave("unidad"));
    }

    // ------------------------------------------------------------------ lectura y validacion de la configuracion

    @Test
    void sinValoresGuardadosSeUsanLosPorDefecto() {
        assertThat(ReservaParametros.desde(Map.of(), m -> { })).isEqualTo(ReservaParametros.porDefecto());
    }

    @Test
    void losValoresGuardadosSeAplican() {
        Map<String, String> v = new HashMap<>();
        v.put("RESERVA_PLAZO_DIAS", "5");
        v.put("RESERVA_MAX_UNIDADES", "12");
        v.put("RESERVA_MAX_PENDIENTES_TELEFONO", "1");
        v.put("RESERVA_HORAS_PENDIENTE", "48");
        v.put("RESERVA_DIAS_ATENCION", "1,3,5");
        v.put("RESERVA_HORA_CIERRE", "17:30");
        v.put("RESERVA_DIAS_NO_LABORABLES", "2026-12-25, 2027-01-01");

        ReservaParametros r = ReservaParametros.desde(v, m -> { });

        assertThat(r.plazoDias()).isEqualTo(5);
        assertThat(r.maxUnidades()).isEqualTo(12);
        assertThat(r.maxPendientesTelefono()).isEqualTo(1);
        assertThat(r.horasPendiente()).isEqualTo(48);
        assertThat(r.diasAtencion()).containsExactlyInAnyOrder(1, 3, 5);
        assertThat(r.horaCierre()).isEqualTo(LocalTime.of(17, 30));
        assertThat(r.diasNoLaborables()).containsExactlyInAnyOrder(LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 1));
    }

    @Test
    void unValorInvalidoNoTumbaLasReservasUsaElPorDefectoYAvisa() {
        List<String> avisos = new ArrayList<>();
        Map<String, String> v = Map.of("RESERVA_PLAZO_DIAS", "abc", "RESERVA_DIAS_ATENCION", "9", "RESERVA_HORA_CIERRE", "25:00");

        ReservaParametros r = ReservaParametros.desde(v, avisos::add);

        assertThat(r).isEqualTo(ReservaParametros.porDefecto());
        assertThat(avisos).hasSize(3);
    }

    @Test
    void validarAceptaLosValoresCorrectosYNoTocaClavesAjenas() {
        ReservaParametros.validar("RESERVA_PLAZO_DIAS", "3");
        ReservaParametros.validar("RESERVA_DIAS_ATENCION", "1,2,3,4,5,6,7");
        ReservaParametros.validar("RESERVA_HORA_CIERRE", "07:05");
        ReservaParametros.validar("RESERVA_DIAS_NO_LABORABLES", "NINGUNO");
        ReservaParametros.validar("RESERVA_DIAS_NO_LABORABLES", "2026-12-25");
        ReservaParametros.validar("UMBRAL_CONCILIACION_PCT", "cualquier cosa"); // no es RESERVA_*
    }

    @Test
    void validarRechazaLosValoresFueraDeRangoOMalFormados() {
        for (String[] par : new String[][]{
                {"RESERVA_PLAZO_DIAS", "-1"}, {"RESERVA_PLAZO_DIAS", "31"}, {"RESERVA_PLAZO_DIAS", "tres"}, {"RESERVA_PLAZO_DIAS", ""},
                {"RESERVA_MAX_UNIDADES", "0"}, {"RESERVA_MAX_UNIDADES", "1001"}, {"RESERVA_MAX_UNIDADES", "2.5"},
                {"RESERVA_MAX_PENDIENTES_TELEFONO", "0"}, {"RESERVA_MAX_PENDIENTES_TELEFONO", "21"},
                {"RESERVA_HORAS_PENDIENTE", "0"}, {"RESERVA_HORAS_PENDIENTE", "169"},
                {"RESERVA_DIAS_ATENCION", "0"}, {"RESERVA_DIAS_ATENCION", "8"}, {"RESERVA_DIAS_ATENCION", "lunes"}, {"RESERVA_DIAS_ATENCION", ""},
                {"RESERVA_HORA_CIERRE", "6pm"}, {"RESERVA_HORA_CIERRE", "24:00"}, {"RESERVA_HORA_CIERRE", ""},
                {"RESERVA_DIAS_NO_LABORABLES", "25/12/2026"}, {"RESERVA_DIAS_NO_LABORABLES", "2026-13-01"}, {"RESERVA_DIAS_NO_LABORABLES", ""}}) {
            assertThatThrownBy(() -> ReservaParametros.validar(par[0], par[1]))
                    .as(par[0] + "=" + par[1]).isInstanceOf(ValidacionNegocioException.class).hasMessageContaining(par[0]);
        }
    }
}
