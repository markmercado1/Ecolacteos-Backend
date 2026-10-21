package com.planta.acopio.repository;

import com.planta.acopio.entity.Receta;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RecetaRepository extends JpaRepository<Receta, UUID> {
    List<Receta> findAllByOrderByNombreAsc();
    List<Receta> findByActivoTrueOrderByNombreAsc();
    boolean existsByProductoIdAndNombreIgnoreCase(UUID productoId, String nombre);
    boolean existsByProductoIdAndNombreIgnoreCaseAndIdNot(UUID productoId, String nombre, UUID id);
}
