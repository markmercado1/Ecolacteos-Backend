package com.planta.acopio.repository;

import com.planta.acopio.entity.MotivoObservacion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface MotivoObservacionRepository extends JpaRepository<MotivoObservacion, UUID> {
    List<MotivoObservacion> findByActivoTrue();
    List<MotivoObservacion> findByActivoTrue(org.springframework.data.domain.Sort sort);
}
