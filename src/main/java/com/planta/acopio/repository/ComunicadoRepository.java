package com.planta.acopio.repository;

import com.planta.acopio.entity.Comunicado;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface ComunicadoRepository extends JpaRepository<Comunicado, UUID> {

    @Query("SELECT c FROM Comunicado c JOIN c.zonasDestino z WHERE z.id = :zonaId ORDER BY c.fecha DESC")
    List<Comunicado> findByZonaId(@Param("zonaId") UUID zonaId);

    // Paginacion pendiente: listado global paginado para ADMIN (web). El id
    // desempata comunicados con la misma fecha para un orden estable.
    Page<Comunicado> findAllByOrderByFechaDescIdDesc(Pageable pageable);
}
