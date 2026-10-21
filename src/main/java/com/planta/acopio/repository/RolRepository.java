package com.planta.acopio.repository;

import com.planta.acopio.entity.Rol;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface RolRepository extends JpaRepository<Rol, UUID> {
    Optional<Rol> findByNombre(String nombre);
}
