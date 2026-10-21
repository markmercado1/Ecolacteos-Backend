package com.planta.acopio.repository;

import com.planta.acopio.entity.PrediccionProveedor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PrediccionProveedorRepository extends JpaRepository<PrediccionProveedor, UUID> {
    Optional<PrediccionProveedor> findTopByProveedorIdOrderByGeneradoEnDesc(UUID proveedorId);
}
