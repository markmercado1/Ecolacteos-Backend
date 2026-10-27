package com.planta.acopio.repository;

import com.planta.acopio.entity.CambioCalculoIgv;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CambioCalculoIgvRepository extends JpaRepository<CambioCalculoIgv, UUID> {

    /** El cambio mas reciente: el que manda. */
    Optional<CambioCalculoIgv> findFirstByOrderByCreadoEnDescIdDesc();
}
