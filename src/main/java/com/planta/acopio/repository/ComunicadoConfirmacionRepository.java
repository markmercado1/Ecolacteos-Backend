package com.planta.acopio.repository;

import com.planta.acopio.entity.ComunicadoConfirmacion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ComunicadoConfirmacionRepository extends JpaRepository<ComunicadoConfirmacion, UUID> {
    List<ComunicadoConfirmacion> findByComunicadoId(UUID comunicadoId);
}
