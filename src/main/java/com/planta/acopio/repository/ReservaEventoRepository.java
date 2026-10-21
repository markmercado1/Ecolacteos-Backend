package com.planta.acopio.repository;

import com.planta.acopio.entity.ReservaEvento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservaEventoRepository extends JpaRepository<ReservaEvento, UUID> {
    List<ReservaEvento> findByReservaIdOrderByCreadoEnAscIdAsc(UUID reservaId);
}
