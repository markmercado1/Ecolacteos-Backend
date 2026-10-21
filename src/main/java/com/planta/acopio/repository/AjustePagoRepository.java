package com.planta.acopio.repository;

import com.planta.acopio.entity.AjustePago;
import com.planta.acopio.entity.EstadoAjustePago;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
