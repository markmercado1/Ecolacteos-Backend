package com.planta.acopio.repository;

import com.planta.acopio.entity.ProveedorCompra;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProveedorCompraRepository extends JpaRepository<ProveedorCompra, UUID> {
    Optional<ProveedorCompra> findByRuc(String ruc);
}
