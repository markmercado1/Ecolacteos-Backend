package com.planta.acopio.repository;

import com.planta.acopio.entity.CategoriaProveedor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoriaProveedorRepository extends JpaRepository<CategoriaProveedor, UUID> {
    Optional<CategoriaProveedor> findByCodigo(String codigo);
    List<CategoriaProveedor> findByActivoTrueOrderByNombreAsc();
}
