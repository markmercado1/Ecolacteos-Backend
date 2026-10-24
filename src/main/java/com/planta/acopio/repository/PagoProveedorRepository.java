package com.planta.acopio.repository;

import com.planta.acopio.entity.PagoProveedor;
import com.planta.acopio.service.BusquedaTexto;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PagoProveedorRepository extends JpaRepository<PagoProveedor, UUID> {

    Optional<PagoProveedor> findByUuidCliente(String uuidCliente);

    // Aplicar o anular un desembolso lo bloquea: dos aplicaciones (o una aplicacion y una anulacion) simultaneas se ejecutan una tras otra,
    // asi el monto aplicado nunca supera el monto del desembolso.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PagoProveedor p WHERE p.id = :id")
    Optional<PagoProveedor> findByIdParaActualizar(@Param("id") UUID id);

    // Operacion bancaria ya registrada en un pago VIGENTE (sin distinguir mayusculas ni espacios en los extremos).
    @Query(value = "SELECT * FROM pago_proveedor WHERE estado = 'REGISTRADO' AND lower(btrim(banco)) = lower(btrim(:banco)) "
            + "AND lower(btrim(nro_operacion)) = lower(btrim(:operacion)) LIMIT 1", nativeQuery = true)
    Optional<PagoProveedor> findOperacionVigente(@Param("banco") String banco, @Param("operacion") String operacion);

    // Listado paginado: proveedor y estado opcionales; busqueda por nombre o DNI del proveedor, numero de operacion o de recibo
    // (sin acentos ni mayusculas) antes de paginar. Mas reciente primero.
    String FILTROS =
            "FROM pago_proveedor pp JOIN proveedor pr ON pp.proveedor_id = pr.id "
            + "WHERE (CAST(:proveedorId AS uuid) IS NULL OR pp.proveedor_id = CAST(:proveedorId AS uuid)) "
            + "AND (CAST(:estado AS varchar) IS NULL OR pp.estado = CAST(:estado AS varchar)) "
            + "AND (" + BusquedaTexto.SIN_TERMINO + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "pr.dni" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "coalesce(pp.nro_operacion, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "coalesce(pp.nro_recibo, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT pp.* " + FILTROS + "ORDER BY pp.fecha_hora DESC, pp.id DESC",
           countQuery = "SELECT count(*) " + FILTROS, nativeQuery = true)
    Page<PagoProveedor> buscar(@Param("proveedorId") UUID proveedorId, @Param("estado") String estado,
                               @Param("busqueda") String busqueda, Pageable pageable);

    // Lo desembolsado y aun vigente de un proveedor (para el saldo a favor por adelantos).
    @Query("SELECT COALESCE(SUM(p.monto), 0) FROM PagoProveedor p WHERE p.proveedor.id = :proveedorId AND p.estado = com.planta.acopio.entity.EstadoPagoProveedor.REGISTRADO")
    BigDecimal sumaVigenteDelProveedor(@Param("proveedorId") UUID proveedorId);

    List<PagoProveedor> findByProveedorIdAndEstadoOrderByFechaHoraAscIdAsc(UUID proveedorId, com.planta.acopio.entity.EstadoPagoProveedor estado);
}
