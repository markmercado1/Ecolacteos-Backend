package com.planta.acopio.repository;

import com.planta.acopio.entity.Familia;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface FamiliaRepository extends JpaRepository<Familia, UUID> {
    List<Familia> findByActivoTrue();
}
