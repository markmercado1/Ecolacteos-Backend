package com.planta.acopio.repository;

import com.planta.acopio.entity.CorreccionRegistro;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CorreccionRegistroRepository extends JpaRepository<CorreccionRegistro, UUID> {
    List<CorreccionRegistro> findByRegistroAcopioIdOrderByCreadoEnDesc(UUID registroAcopioId);

    // Fase B: las correcciones de varias entregas en orden cronologico (litros efectivos de un pago, con una sola consulta).
    List<CorreccionRegistro> findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(Collection<UUID> registroAcopioIds);
}
