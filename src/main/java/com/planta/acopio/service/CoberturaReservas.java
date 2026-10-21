package com.planta.acopio.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

// Fase 4: cobertura de reservas (funciones PURAS, sin base de datos).
//
// Una reserva (fechaRetiro, cantidad) solo puede respaldarse con unidades de lotes VENDIBLES (vigentes; nunca vencidos
// ni "sin trazabilidad") cuyo vencimiento llegue a la fecha de retiro: un lote que vence antes no la respalda. Como esos
// conjuntos de lotes estan anidados (a mayor fecha, menos lotes sirven), el conjunto de reservas esta cubierto si y solo
// si, para cada fecha t de retiro:
//        unidades reservadas con retiro >= t   <=   unidades vendibles con vencimiento >= t        (condicion de Hall)
// Basta comprobar las fechas de retiro existentes: entre dos de ellas la demanda no cambia y la oferta solo crece.
//
// FEFO es optimo para esto: una venta consume primero lo que vence antes, que es lo que menos sirve a las reservas
// futuras; si la venta FEFO rompe la cobertura, ninguna otra eleccion de lotes la salvaria.
public final class CoberturaReservas {

    private CoberturaReservas() {}

    /** Lote vendible: vencimiento (ultimo dia valido, inclusive) y unidades. */
    public record Lote(LocalDate vence, int saldo) {}

    /** Unidades comprometidas por UNA reserva en UNA presentacion. */
    public record Demanda(LocalDate retiro, int cantidad) {}

    /** Demanda con identidad y antiguedad de la reserva, para decidir cual quedo sin cobertura. */
    public record DemandaReserva(UUID reservaId, LocalDateTime creadoEn, LocalDate retiro, int cantidad) {}

    /** Los lotes que pueden respaldar reservas: los vendibles (no vencidos), en el orden FEFO de las existencias. */
    public static List<Lote> lotesVendibles(Existencias existencias) {
        List<Lote> lotes = new ArrayList<>();
        for (Existencias.Lote l : existencias.lotes()) {
            if (!l.vencido()) lotes.add(new Lote(l.fechaVencimiento(), l.saldo()));
        }
        return lotes;
    }

    private static long oferta(List<Lote> lotes, LocalDate t) {
        long s = 0;
        for (Lote l : lotes) if (!l.vence().isBefore(t)) s += l.saldo();
        return s;
    }

    private static long demanda(List<Demanda> demandas, LocalDate t) {
        long d = 0;
        for (Demanda x : demandas) if (!x.retiro().isBefore(t)) d += x.cantidad();
        return d;
    }

    private static Set<LocalDate> fechas(List<Demanda> demandas) {
        Set<LocalDate> f = new TreeSet<>();
        for (Demanda d : demandas) f.add(d.retiro());
        return f;
    }

    /** true si todas las reservas pueden respaldarse a la vez con los lotes dados. */
    public static boolean cubre(List<Lote> lotes, List<Demanda> demandas) {
        for (LocalDate t : fechas(demandas)) {
            if (demanda(demandas, t) > oferta(lotes, t)) return false;
        }
        return true;
    }

    /** Unidades que faltan en el peor tramo (0 si todo esta cubierto). */
    public static int faltante(List<Lote> lotes, List<Demanda> demandas) {
        long peor = 0;
        for (LocalDate t : fechas(demandas)) peor = Math.max(peor, demanda(demandas, t) - oferta(lotes, t));
        return (int) peor;
    }

    /**
     * Cuantas unidades MAS se pueden reservar para `fecha` sin dejar sin cobertura a las reservas existentes. Si las
     * reservas ya existentes no estan cubiertas, 0 (no se agrava un faltante).
     */
    public static int disponible(List<Lote> lotes, List<Demanda> demandas, LocalDate fecha) {
        if (!cubre(lotes, demandas)) return 0;
        long holgura = oferta(lotes, fecha) - demanda(demandas, fecha);
        for (LocalDate t : fechas(demandas)) {
            if (!t.isAfter(fecha)) holgura = Math.min(holgura, oferta(lotes, t) - demanda(demandas, t));
        }
        return (int) Math.max(holgura, 0);
    }

    /**
     * ¿Puede salir una venta de `cantidad` unidades (FEFO: de los primeros lotes de la lista, que debe venir en ese
     * orden) sin dejar sin cobertura a las reservas? Una fecha que la venta no toca nunca bloquea; en las que si toca,
     * lo que queda debe seguir cubriendo la demanda.
     */
    public static boolean cubreTrasVender(List<Lote> lotesEnOrdenFefo, List<Demanda> demandas, int cantidad) {
        if (demandas.isEmpty()) return true;
        List<Lote> despues = new ArrayList<>();
        int restante = cantidad;
        for (Lote l : lotesEnOrdenFefo) {
            int toma = Math.min(l.saldo(), restante);
            restante -= toma;
            despues.add(new Lote(l.vence(), l.saldo() - toma));
        }
        for (LocalDate t : fechas(demandas)) {
            long antes = oferta(lotesEnOrdenFefo, t);
            long ahora = oferta(despues, t);
            if (ahora != antes && ahora < demanda(demandas, t)) return false;
        }
        return true;
    }

    /**
     * Unidades que una venta directa puede tomar HOY (FEFO) sin dejar sin cobertura a las reservas: la mayor `q` para la que
     * `cubreTrasVender` es verdadero. Es monotona (vender mas solo reduce la oferta), por lo que basta una busqueda binaria.
     * Sin reservas, todo lo vendible esta libre.
     */
    public static int maximoVendible(List<Lote> lotesEnOrdenFefo, List<Demanda> demandas) {
        int vendible = 0;
        for (Lote l : lotesEnOrdenFefo) vendible += l.saldo();
        if (demandas.isEmpty()) return vendible;
        int bajo = 0, alto = vendible;
        while (bajo < alto) {
            int medio = (bajo + alto + 1) >>> 1;
            if (cubreTrasVender(lotesEnOrdenFefo, demandas, medio)) bajo = medio; else alto = medio - 1;
        }
        return bajo;
    }

    /**
     * Reservas que hoy NO tienen cobertura. Se asigna por antiguedad: la reserva mas antigua tiene prioridad, y una
     * reserva se considera sin cobertura si, sumandola a las anteriores que si la tienen, la condicion deja de cumplirse.
     */
    public static Set<UUID> sinCobertura(List<Lote> lotes, List<DemandaReserva> demandas) {
        List<DemandaReserva> ordenadas = new ArrayList<>(demandas);
        ordenadas.sort(Comparator.comparing(DemandaReserva::creadoEn).thenComparing(DemandaReserva::reservaId));
        List<Demanda> aceptadas = new ArrayList<>();
        Set<UUID> sin = new HashSet<>();
        for (DemandaReserva d : ordenadas) {
            List<Demanda> prueba = new ArrayList<>(aceptadas);
            prueba.add(new Demanda(d.retiro(), d.cantidad()));
            if (cubre(lotes, prueba)) aceptadas = prueba; else sin.add(d.reservaId());
        }
        return sin;
    }
}
