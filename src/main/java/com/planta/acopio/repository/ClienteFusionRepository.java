package com.planta.acopio.repository;

import com.planta.acopio.entity.ClienteFusion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClienteFusionRepository extends JpaRepository<ClienteFusion, UUID> {
    List<ClienteFusion> findByDestinoIdOrOrigenIdOrderByCreadoEnDesc(UUID destinoId, UUID origenId);
}
