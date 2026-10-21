package com.planta.acopio.repository;

import com.planta.acopio.entity.ReservaDetalle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReservaDetalleRepository extends JpaRepository<ReservaDetalle, UUID> {

    List<ReservaDetalle> findByReservaIdOrderByPresentacionId(UUID reservaId);

    List<ReservaDetalle> findByReservaIdIn(Collection<UUID> reservaIds);

    // Unidades COMPROMETIDAS por reservas, por presentacion: las pendientes que no han llegado a su expira_en y las
    // confirmadas cuya fecha de retiro no ha terminado. Es el UNICO criterio de "retiene stock": una reserva vencida
    // deja de comprometer unidades aunque su fila siga diciendo PENDIENTE/CONFIRMADA. Filas: presentacion_id,
    // reserva_id, creado_en, fecha_retiro, cantidad (ordenadas por antiguedad de la reserva).
    @Query("SELECT d.presentacion.id, r.id, r.creadoEn, r.fechaRetiro, d.cantidad FROM ReservaDetalle d JOIN d.reserva r "
            + "WHERE d.presentacion.id IN :presentacionIds AND ("
            + "(r.estado = com.planta.acopio.entity.EstadoReserva.PENDIENTE AND r.expiraEn > :ahora) "
            + "OR (r.estado = com.planta.acopio.entity.EstadoReserva.CONFIRMADA AND r.fechaRetiro >= :hoy)) "
            + "ORDER BY r.creadoEn, r.id")
    List<Object[]> compromisosActivos(@Param("presentacionIds") Collection<UUID> presentacionIds,
                                       @Param("ahora") LocalDateTime ahora, @Param("hoy") LocalDate hoy);
}
