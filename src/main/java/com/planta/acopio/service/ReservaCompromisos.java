package com.planta.acopio.service;

import com.planta.acopio.repository.ReservaDetalleRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

// Fase 4: unidades COMPROMETIDAS por reservas, por presentacion. Es el unico lugar donde se decide que una reserva
// "retiene" stock (ver ReservaDetalleRepository.compromisosActivos): pendientes que no han expirado y confirmadas cuya
// fecha de retiro no ha terminado. Las usan la creacion y confirmacion de reservas, la validacion de ventas
// (InventarioService) y la disponibilidad publica, siempre bajo el bloqueo de la presentacion cuando deciden una escritura.
@Component
public class ReservaCompromisos {

    private final ReservaDetalleRepository detalleRepository;
    private final ReglaVencimiento regla;

    public ReservaCompromisos(ReservaDetalleRepository detalleRepository, ReglaVencimiento regla) {
        this.detalleRepository = detalleRepository;
        this.regla = regla;
    }

    /** Compromisos con identidad (para decidir cual reserva quedo sin cobertura), por presentacion. */
    public Map<UUID, List<CoberturaReservas.DemandaReserva>> compromisosDe(Collection<UUID> presentacionIds) {
        Map<UUID, List<CoberturaReservas.DemandaReserva>> porPresentacion = new HashMap<>();
        if (presentacionIds.isEmpty()) return porPresentacion;
        LocalDateTime ahora = regla.ahora();
        LocalDate hoy = ahora.toLocalDate();
        for (Object[] fila : detalleRepository.compromisosActivos(presentacionIds, ahora, hoy)) {
            porPresentacion.computeIfAbsent((UUID) fila[0], k -> new ArrayList<>()).add(new CoberturaReservas.DemandaReserva(
                    (UUID) fila[1], (LocalDateTime) fila[2], (LocalDate) fila[3], ((Number) fila[4]).intValue()));
        }
        return porPresentacion;
    }

    public List<CoberturaReservas.DemandaReserva> compromisosDe(UUID presentacionId) {
        return compromisosDe(List.of(presentacionId)).getOrDefault(presentacionId, List.of());
    }

    public static List<CoberturaReservas.Demanda> demandas(List<CoberturaReservas.DemandaReserva> compromisos) {
        return compromisos.stream().map(c -> new CoberturaReservas.Demanda(c.retiro(), c.cantidad())).toList();
    }

    /** Las demandas (sin identidad) de una presentacion. */
    public List<CoberturaReservas.Demanda> demandasDe(UUID presentacionId) {
        return demandas(compromisosDe(presentacionId));
    }
}
