package com.planta.acopio.repository;

import com.planta.acopio.entity.AjustePrecioPago;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AjustePrecioPagoRepository extends JpaRepository<AjustePrecioPago, UUID> {
    List<AjustePrecioPago> findByPagoIdOrderByCreadoEnDescIdDesc(UUID pagoId);

    // El ajuste mas reciente de un pago define su precio efectivo.
    Optional<AjustePrecioPago> findFirstByPagoIdOrderByCreadoEnDescIdDesc(UUID pagoId);

    // Una sola consulta para varios pagos (listados paginados): evita una consulta por fila.
    List<AjustePrecioPago> findByPagoIdInOrderByCreadoEnAscIdAsc(Collection<UUID> pagoIds);
}
