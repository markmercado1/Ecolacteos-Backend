package com.planta.acopio.repository;

import com.planta.acopio.entity.Reserva;
import com.planta.acopio.service.BusquedaTexto;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ReservaRepository extends JpaRepository<Reserva, UUID> {

    Optional<Reserva> findByUuidCliente(String uuidCliente);

    Optional<Reserva> findByCodigo(String codigo);

    boolean existsByCodigo(String codigo);

    // Bloqueo de fila: toda operacion que cambia el estado de UNA reserva (confirmar, rechazar, cancelar, retirar...)
    // la toma primero, y despues (si hace falta) las presentaciones en orden de id. Orden global de bloqueos:
    // telefono -> reserva -> presentaciones.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reserva r WHERE r.id = :id")
    Optional<Reserva> bloquearPorId(@Param("id") UUID id);

    // Serializa las solicitudes de UN mismo telefono normalizado (limite de pendientes ante concurrencia). El bloqueo
    // dura hasta el fin de la transaccion y se toma ANTES de los bloqueos de presentacion.
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:clave))) t", nativeQuery = true)
    Integer bloquearTelefono(@Param("clave") String clave);

    // Pendientes que aun retienen unidades (las que superaron su expira_en ya no cuentan).
    @Query("SELECT COUNT(r) FROM Reserva r WHERE r.telefono = :telefono AND r.estado = com.planta.acopio.entity.EstadoReserva.PENDIENTE "
            + "AND r.expiraEn > :ahora")
    long contarPendientesVigentes(@Param("telefono") String telefono, @Param("ahora") LocalDateTime ahora);

    // Listado para el personal. `estado` es el estado EFECTIVO (una pendiente pasada de hora o una confirmada con la
    // fecha de retiro vencida cuentan como VENCIDA aunque la fila no lo diga). Todos los filtros son opcionales.
    String FILTRO = "FROM reserva r WHERE "
            + "(CAST(:telefono AS varchar) IS NULL OR r.telefono = CAST(:telefono AS varchar)) "
            + "AND (CAST(:fecha AS date) IS NULL OR r.fecha_retiro = CAST(:fecha AS date)) "
            + "AND (CAST(:codigo AS varchar) IS NULL OR r.codigo = CAST(:codigo AS varchar)) "
            + "AND (CAST(:estado AS varchar) IS NULL "
            + "  OR (CAST(:estado AS varchar) = 'VENCIDA' AND (r.estado = 'VENCIDA' "
            + "       OR (r.estado = 'PENDIENTE' AND r.expira_en <= :ahora) OR (r.estado = 'CONFIRMADA' AND r.fecha_retiro < :hoy))) "
            + "  OR (CAST(:estado AS varchar) = 'PENDIENTE' AND r.estado = 'PENDIENTE' AND r.expira_en > :ahora) "
            + "  OR (CAST(:estado AS varchar) = 'CONFIRMADA' AND r.estado = 'CONFIRMADA' AND r.fecha_retiro >= :hoy) "
            + "  OR (CAST(:estado AS varchar) IN ('RECHAZADA', 'CANCELADA', 'RETIRADA') AND r.estado = CAST(:estado AS varchar))) "
            // Busqueda de texto (sin acentos ni mayusculas): nombre del cliente, telefono o codigo, ademas de los filtros exactos.
            + "AND (" + BusquedaTexto.SIN_TERMINO + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "r.nombre_cliente" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "r.telefono" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "r.codigo" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ")";

    @Query(value = "SELECT r.* " + FILTRO + " ORDER BY r.creado_en DESC, r.id", countQuery = "SELECT count(*) " + FILTRO, nativeQuery = true)
    Page<Reserva> buscar(@Param("estado") String estado, @Param("fecha") LocalDate fecha, @Param("telefono") String telefono,
                         @Param("codigo") String codigo, @Param("busqueda") String busqueda, @Param("ahora") LocalDateTime ahora,
                         @Param("hoy") LocalDate hoy, Pageable pageable);

    // ------------------------------------------------------------------ Fase H

    java.util.List<Reserva> findByClienteIdOrderByCreadoEnDesc(UUID clienteId);

    java.util.List<Reserva> findByProveedorIdOrderByCreadoEnDesc(UUID proveedorId);

    /** Refactor clientes: reservas vinculadas a las fichas de una pagina del padron (resumen en la tabla). */
    java.util.List<Reserva> findByClienteIdIn(java.util.Collection<UUID> clienteIds);

    @Modifying
    @Query("UPDATE Reserva r SET r.clienteId = :destino WHERE r.clienteId = :origen")
    int moverCliente(@Param("origen") UUID origen, @Param("destino") UUID destino);
}
