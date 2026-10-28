package com.planta.acopio.repository;

import com.planta.acopio.entity.CambioTipoPrecio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CambioTipoPrecioRepository extends JpaRepository<CambioTipoPrecio, UUID> {

    Optional<CambioTipoPrecio> findFirstByCodigoOrderByCreadoEnDescIdDesc(String codigo);
}
