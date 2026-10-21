package com.planta.acopio.repository;

import com.planta.acopio.entity.CorreccionRegistro;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CorreccionRegistroRepository extends JpaRepository<CorreccionRegistro, UUID> {
    List<CorreccionRegistro> findByRegistroAcopioIdOrderByCreadoEnDesc(UUID registroAcopioId);
}
