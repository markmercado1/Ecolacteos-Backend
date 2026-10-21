package com.planta.acopio.service;

import com.planta.acopio.exception.ValidacionNegocioException;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

// Fase 4: parametros de las reservas (se editan en Catalogos -> Configuracion, solo ADMIN; claves RESERVA_* de
// configuracion_sistema) y las reglas de fecha/limites que se derivan de ellos. Todo es PURO (sin base de datos) para
// poder probarlo con fechas y horas controladas. Las reglas de dia habil, plazo y horario viven aqui, parametrizadas:
// nada de feriados ni horarios fijos en los servicios.
public record ReservaParametros(int plazoDias, int maxUnidades, int maxPendientesTelefono, int horasPendiente,
                                Set<Integer> diasAtencion, LocalTime horaCierre, Set<LocalDate> diasNoLaborables) {

    public static final String PLAZO_DIAS = "RESERVA_PLAZO_DIAS";
    public static final String MAX_UNIDADES = "RESERVA_MAX_UNIDADES";
    public static final String MAX_PENDIENTES_TELEFONO = "RESERVA_MAX_PENDIENTES_TELEFONO";
    public static final String HORAS_PENDIENTE = "RESERVA_HORAS_PENDIENTE";
    public static final String DIAS_ATENCION = "RESERVA_DIAS_ATENCION";
    public static final String HORA_CIERRE = "RESERVA_HORA_CIERRE";
    public static final String DIAS_NO_LABORABLES = "RESERVA_DIAS_NO_LABORABLES";

    public static ReservaParametros porDefecto() {
        return new ReservaParametros(3, 20, 2, 24, Set.of(1, 2, 3, 4, 5, 6), LocalTime.of(18, 0), Set.of());
    }

    /**
     * Construye los parametros desde los valores guardados. Un valor ausente o invalido NO tumba las reservas: se usa
     * el valor por defecto de esa clave y se avisa (la validacion estricta ocurre al guardar la configuracion).
     */
    public static ReservaParametros desde(Map<String, String> valores, Consumer<String> aviso) {
        ReservaParametros d = porDefecto();
        return new ReservaParametros(
                entero(valores, PLAZO_DIAS, 0, 30, d.plazoDias, aviso),
                entero(valores, MAX_UNIDADES, 1, 1000, d.maxUnidades, aviso),
                entero(valores, MAX_PENDIENTES_TELEFONO, 1, 20, d.maxPendientesTelefono, aviso),
                entero(valores, HORAS_PENDIENTE, 1, 168, d.horasPendiente, aviso),
                valor(valores, DIAS_ATENCION, ReservaParametros::dias, d.diasAtencion, aviso),
                valor(valores, HORA_CIERRE, ReservaParametros::hora, d.horaCierre, aviso),
                valor(valores, DIAS_NO_LABORABLES, ReservaParametros::feriados, d.diasNoLaborables, aviso));
    }

    /** Valida un valor de configuracion ANTES de guardarlo. Las claves que no son RESERVA_* no se tocan. */
    public static void validar(String clave, String valor) {
        if (clave == null || !clave.startsWith("RESERVA_")) return;
        try {
            switch (clave) {
                case PLAZO_DIAS -> entero(valor, 0, 30);
                case MAX_UNIDADES -> entero(valor, 1, 1000);
                case MAX_PENDIENTES_TELEFONO -> entero(valor, 1, 20);
                case HORAS_PENDIENTE -> entero(valor, 1, 168);
                case DIAS_ATENCION -> dias(valor);
                case HORA_CIERRE -> hora(valor);
                case DIAS_NO_LABORABLES -> feriados(valor);
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            throw new ValidacionNegocioException("Valor inválido para " + clave + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ reglas de fecha

    /** ¿Es una fecha en la que se atiende (dia de la semana habilitado y no feriado)? */
    public boolean esDiaDeAtencion(LocalDate fecha) {
        return diasAtencion.contains(fecha.getDayOfWeek().getValue()) && !diasNoLaborables.contains(fecha);
    }

    /** Ultima fecha de retiro permitida para una solicitud hecha hoy (inclusive). */
    public LocalDate fechaMaxima(LocalDate hoy) {
        return hoy.plusDays(plazoDias);
    }

    /** Por que una fecha de retiro NO es valida ahora (hora de Lima), o vacio si lo es. */
    public Optional<String> errorDeFecha(LocalDate fecha, LocalDateTime ahora) {
        LocalDate hoy = ahora.toLocalDate();
        if (fecha.isBefore(hoy)) return Optional.of("La fecha de retiro no puede ser anterior a hoy.");
        LocalDate maxima = fechaMaxima(hoy);
        if (fecha.isAfter(maxima)) {
            return Optional.of("La fecha de retiro debe estar dentro de los próximos " + plazoDias + " días (hasta el " + maxima + ").");
        }
        if (!esDiaDeAtencion(fecha)) return Optional.of("El " + fecha + " no es un día de atención.");
        if (fecha.equals(hoy) && !ahora.toLocalTime().isBefore(horaCierre)) {
            return Optional.of("Ya no se aceptan retiros para hoy (la atención cierra a las " + horaCierre + ").");
        }
        return Optional.empty();
    }

    /** La primera fecha de retiro valida a partir de ahora (hoy si todavia se atiende), o vacio si el plazo no tiene ninguna. */
    public Optional<LocalDate> primeraFechaDisponible(LocalDateTime ahora) {
        LocalDate hoy = ahora.toLocalDate();
        for (LocalDate f = hoy; !f.isAfter(fechaMaxima(hoy)); f = f.plusDays(1)) {
            if (errorDeFecha(f, ahora).isEmpty()) return Optional.of(f);
        }
        return Optional.empty();
    }

    /** Todas las fechas de retiro validas a partir de ahora, en orden (las mismas que acepta `errorDeFecha`). */
    public java.util.List<LocalDate> fechasDisponibles(LocalDateTime ahora) {
        LocalDate hoy = ahora.toLocalDate();
        java.util.List<LocalDate> fechas = new java.util.ArrayList<>();
        for (LocalDate f = hoy; !f.isAfter(fechaMaxima(hoy)); f = f.plusDays(1)) {
            if (errorDeFecha(f, ahora).isEmpty()) fechas.add(f);
        }
        return fechas;
    }

    // ------------------------------------------------------------------ unidades

    /**
     * ¿La unidad de conteo es una MEDIDA (peso o volumen) y no una unidad discreta? Las medidas no se suman con
     * unidades (no se mezclan kilogramos con botellas): su limite se aplica por separado, por cada unidad.
     */
    public static boolean esMedida(String unidadConteo) {
        return Set.of("kg", "kilo", "kilogramo", "g", "gr", "gramo", "l", "lt", "litro", "ml", "mililitro")
                .contains(clave(unidadConteo));
    }

    /** Clave de agrupacion de una unidad de conteo: sin acentos, minusculas y sin plural simple. */
    public static String clave(String unidadConteo) {
        String s = Normalizer.normalize(unidadConteo == null ? "" : unidadConteo.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        if (s.length() > 3 && s.endsWith("es")) return s.substring(0, s.length() - 2);
        if (s.length() > 2 && s.endsWith("s")) return s.substring(0, s.length() - 1);
        return s;
    }

    // ------------------------------------------------------------------ parseo

    private static int entero(String v, int min, int max) {
        int n;
        try {
            n = Integer.parseInt(v == null ? "" : v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("debe ser un número entero");
        }
        if (n < min || n > max) throw new IllegalArgumentException("debe estar entre " + min + " y " + max);
        return n;
    }

    private static int entero(Map<String, String> m, String clave, int min, int max, int defecto, Consumer<String> aviso) {
        return valor(m, clave, v -> entero(v, min, max), defecto, aviso);
    }

    private static <T> T valor(Map<String, String> m, String clave, java.util.function.Function<String, T> parser, T defecto,
                               Consumer<String> aviso) {
        String v = m.get(clave);
        if (v == null) return defecto;
        try {
            return parser.apply(v);
        } catch (IllegalArgumentException e) {
            aviso.accept("Configuración " + clave + " inválida ('" + v + "'): " + e.getMessage() + "; se usa el valor por defecto.");
            return defecto;
        }
    }

    private static Set<Integer> dias(String v) {
        Set<Integer> dias = new TreeSet<>();
        for (String parte : v.split(",")) {
            int n;
            try {
                n = Integer.parseInt(parte.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("usa números del 1 (lunes) al 7 (domingo) separados por coma");
            }
            if (n < 1 || n > 7) throw new IllegalArgumentException("los días van del 1 (lunes) al 7 (domingo)");
            dias.add(n);
        }
        if (dias.isEmpty()) throw new IllegalArgumentException("indica al menos un día");
        return Collections.unmodifiableSet(dias);
    }

    private static LocalTime hora(String v) {
        try {
            return LocalTime.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("usa el formato HH:mm (por ejemplo 18:00)");
        }
    }

    private static Set<LocalDate> feriados(String v) {
        if (v.trim().equalsIgnoreCase("NINGUNO")) return Set.of();
        Set<LocalDate> fechas = new LinkedHashSet<>();
        for (String parte : Arrays.asList(v.split(","))) {
            try {
                fechas.add(LocalDate.parse(parte.trim()));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("usa fechas yyyy-MM-dd separadas por coma, o NINGUNO");
            }
        }
        return Collections.unmodifiableSet(fechas);
    }

    /** Nombre del dia en espanol para mensajes. */
    public static String nombreDia(DayOfWeek d) {
        return switch (d) {
            case MONDAY -> "lunes"; case TUESDAY -> "martes"; case WEDNESDAY -> "miércoles"; case THURSDAY -> "jueves";
            case FRIDAY -> "viernes"; case SATURDAY -> "sábado"; case SUNDAY -> "domingo";
        };
    }
}
