package com.planta.acopio.repository;

import com.planta.acopio.entity.VentaDetalle;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface VentaDetalleRepository extends JpaRepository<VentaDetalle, UUID> {
    List<VentaDetalle> findByVentaId(UUID ventaId);
}
