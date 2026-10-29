package com.planta.acopio.repository;

import com.planta.acopio.entity.DocumentoIngreso;
import com.planta.acopio.entity.TipoDocumentoIngreso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentoIngresoRepository extends JpaRepository<DocumentoIngreso, UUID> {
    Optional<DocumentoIngreso> findByUuidCliente(UUID uuidCliente);

    boolean existsByProveedorIdAndTipoAndNumeroIgnoreCase(UUID proveedorId, TipoDocumentoIngreso tipo, String numero);
}
