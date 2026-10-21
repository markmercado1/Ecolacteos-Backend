package com.planta.acopio.repository;

import com.planta.acopio.entity.AjusteInventario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AjusteInventarioRepository extends JpaRepository<AjusteInventario, UUID> {
    // Paginacion pendiente: el historial de ajustes por presentacion se pagina
    // en la BD (unico consumidor: dialogo de historial de la web). Desempate
    // por id para un orden estable entre paginas.
    Page<AjusteInventario> findByPresentacionIdOrderByCreadoEnDescIdDesc(UUID presentacionId, Pageable pageable);
}
