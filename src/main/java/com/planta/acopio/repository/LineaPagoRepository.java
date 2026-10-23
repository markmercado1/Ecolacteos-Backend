package com.planta.acopio.repository;

import com.planta.acopio.entity.LineaPago;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LineaPagoRepository extends JpaRepository<LineaPago, UUID> {

    // Una entrega pertenece a lo sumo a un pago (UNIQUE en V28).
    Optional<LineaPago> findByRegistroAcopioId(UUID registroAcopioId);

    List<LineaPago> findByPagoIdOrderByCreadoEnAscIdAsc(UUID pagoId);
}
