package com.planta.acopio.repository;

import com.planta.acopio.entity.LoteProduccionInsumo;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LoteProduccionInsumoRepository extends JpaRepository<LoteProduccionInsumo, UUID> {
    List<LoteProduccionInsumo> findByLoteId(UUID loteId);
}
