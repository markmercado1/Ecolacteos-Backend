package com.planta.acopio.repository;

import com.planta.acopio.entity.PagoProveedorAplicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PagoProveedorAplicacionRepository extends JpaRepository<PagoProveedorAplicacion, UUID> {

    List<PagoProveedorAplicacion> findByPagoProveedorIdInOrderByCreadoEnAscIdAsc(Collection<UUID> pagoProveedorIds);

    List<PagoProveedorAplicacion> findByPagoIdOrderByCreadoEnAscIdAsc(UUID pagoId);

    // Lo aplicado a cada liquidacion por desembolsos NO anulados: [pagoId, suma].
    @Query("SELECT a.pago.id, SUM(a.monto) FROM PagoProveedorAplicacion a "
            + "WHERE a.pago.id IN :pagoIds AND a.pagoProveedor.estado = com.planta.acopio.entity.EstadoPagoProveedor.REGISTRADO GROUP BY a.pago.id")
    List<Object[]> sumaVigentePorPago(@Param("pagoIds") Collection<UUID> pagoIds);

    // Lo ya aplicado de un desembolso (anulado o no: el tope de aplicacion se mide contra su monto).
    @Query("SELECT COALESCE(SUM(a.monto), 0) FROM PagoProveedorAplicacion a WHERE a.pagoProveedor.id = :pagoProveedorId")
    BigDecimal sumaDelDesembolso(@Param("pagoProveedorId") UUID pagoProveedorId);

    // Lo aplicado de los desembolsos vigentes de un proveedor.
    @Query("SELECT COALESCE(SUM(a.monto), 0) FROM PagoProveedorAplicacion a "
            + "WHERE a.pagoProveedor.proveedor.id = :proveedorId AND a.pagoProveedor.estado = com.planta.acopio.entity.EstadoPagoProveedor.REGISTRADO")
    BigDecimal sumaVigenteDelProveedor(@Param("proveedorId") UUID proveedorId);
}
