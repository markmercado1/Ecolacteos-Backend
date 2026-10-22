package com.planta.acopio.repository;

import com.planta.acopio.entity.Pago;
import com.planta.acopio.service.BusquedaTexto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PagoRepository extends JpaRepository<Pago, UUID> {
    Optional<Pago> findByProveedorIdAndSemanaInicio(UUID proveedorId, LocalDate semanaInicio);
    List<Pago> findByProveedorIdOrderBySemanaInicioDesc(UUID proveedorId);
    // Paginacion pendiente: versiones paginadas para la web. El id desempata
    // pagos con la misma semanaInicio (proveedores distintos) para que el orden
    // sea estable entre paginas. La lista completa por proveedor se conserva
    // porque la consume la app movil (GET /api/pagos/proveedor/{id}).
    Page<Pago> findByProveedorIdOrderBySemanaInicioDescIdDesc(UUID proveedorId, Pageable pageable);

    // Listado global con busqueda por nombre o DNI del proveedor (sin acentos ni mayusculas) sobre TODOS los pagos,
    // antes de paginar. Orden: semana mas reciente primero y, dentro de la misma semana, el pago creado mas
    // recientemente primero (creado_en; el id es un UUID aleatorio y solo desempata lo que queda igual).
    String FILTROS_PAGO =
            "FROM pago p JOIN proveedor pr ON p.proveedor_id = pr.id WHERE (" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "pr.dni" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT p.* " + FILTROS_PAGO + "ORDER BY p.semana_inicio DESC, p.creado_en DESC, p.id DESC",
           countQuery = "SELECT count(*) " + FILTROS_PAGO,
           nativeQuery = true)
    Page<Pago> buscar(@Param("busqueda") String busqueda, Pageable pageable);

    // Segunda auditoria (flujos end-to-end §6/§14-D): UNIQUE(proveedor_id,
    // semana_inicio) solo evita repetir la MISMA fecha de inicio exacta -- no
    // evita que un segundo pago con un rango solapado vuelva a liquidar un
    // RegistroAcopio que ya quedo vinculado a un pago anterior por
    // pago_registro_acopio (mejora #4). PagoService.generar() usa esto para
    // rechazar el solape antes de guardar nada.
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Pago p " +
           "JOIN p.registrosLiquidados r WHERE r.id = :registroAcopioId")
    boolean existeRegistroYaLiquidado(@Param("registroAcopioId") UUID registroAcopioId);

    // Fase 3, decision de negocio 1 (opcion C): resuelve el Pago que liquido un
    // registro dado, para que CorreccionRegistroService pueda generar el
    // AjustePago correspondiente (precio = el de ESTE pago, no el vigente hoy).
    // Un registro pertenece a lo sumo un Pago (existeRegistroYaLiquidado ya lo
    // impide en PagoService.generar), por eso Optional es seguro aqui.
    @Query("SELECT p FROM Pago p JOIN p.registrosLiquidados r WHERE r.id = :registroAcopioId")
    Optional<Pago> findByRegistroLiquidadoId(@Param("registroAcopioId") UUID registroAcopioId);
}
