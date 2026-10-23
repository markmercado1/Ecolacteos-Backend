package com.planta.acopio.repository;

import com.planta.acopio.entity.LineaPago;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LineaPagoRepository extends JpaRepository<LineaPago, UUID> {

    // Una entrega pertenece a lo sumo a un pago (UNIQUE en V28).
    Optional<LineaPago> findByRegistroAcopioId(UUID registroAcopioId);

    List<LineaPago> findByPagoIdOrderByCreadoEnAscIdAsc(UUID pagoId);

    // Fase C: las lineas de varios pagos / de varias entregas con una sola consulta (totales y precios de los listados).
    List<LineaPago> findByPagoIdIn(Collection<UUID> pagoIds);

    List<LineaPago> findByRegistroAcopioIdIn(Collection<UUID> registroAcopioIds);

    // Fase C: fijar el precio de calidad de una entrega bloquea su linea, asi dos ajustes simultaneos se aplican uno tras otro y el
    // "precio anterior" de cada uno es el que dejo el anterior.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM LineaPago l WHERE l.pago.id = :pagoId AND l.registroAcopio.id = :registroId")
    Optional<LineaPago> findByPagoIdAndRegistroParaActualizar(@Param("pagoId") UUID pagoId, @Param("registroId") UUID registroId);
}
