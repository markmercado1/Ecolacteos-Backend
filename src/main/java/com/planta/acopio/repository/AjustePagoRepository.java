package com.planta.acopio.repository;

import com.planta.acopio.entity.AjustePago;
import com.planta.acopio.entity.EstadoAjustePago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AjustePagoRepository extends JpaRepository<AjustePago, UUID> {
    List<AjustePago> findByPagoIdOrderByCreadoEnDesc(UUID pagoId);
    List<AjustePago> findByEstadoAndPago_Proveedor_IdOrderByCreadoEnDesc(EstadoAjustePago estado, UUID proveedorId);

    // Fix correcciones multiples (confirmado): el ajuste mas reciente de un
    // registro es el baseline economico ya contemplado para el siguiente
    // ajuste -- evita sumar cada correccion contra el original inmutable
    // (que duplicaria el ajuste ya hecho).
    Optional<AjustePago> findFirstByRegistroAcopio_IdOrderByCreadoEnDesc(UUID registroAcopioId);

    // Fase B: lo que ya sumaron los ajustes de una entrega (se suma a lo liquidado para obtener los litros contabilizados).
    @Query("SELECT COALESCE(SUM(a.litrosDiferencia), 0) FROM AjustePago a WHERE a.registroAcopio.id = :registroAcopioId")
    BigDecimal sumaLitrosDiferenciaDelRegistro(@Param("registroAcopioId") UUID registroAcopioId);

    List<AjustePago> findByPagoId(UUID pagoId);

    // Pagos efectivos: los ajustes de litros de varias liquidaciones, para el saldo.
    List<AjustePago> findByPagoIdIn(Collection<UUID> pagoIds);
}
