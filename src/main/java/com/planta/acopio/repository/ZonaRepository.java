package com.planta.acopio.repository;

import com.planta.acopio.entity.Zona;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ZonaRepository extends JpaRepository<Zona, UUID> {
    List<Zona> findByActivaTrue();
    List<Zona> findByActivaTrue(org.springframework.data.domain.Sort sort);
}
