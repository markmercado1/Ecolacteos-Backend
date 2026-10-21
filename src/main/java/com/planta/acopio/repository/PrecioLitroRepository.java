package com.planta.acopio.repository;

import com.planta.acopio.entity.PrecioLitro;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PrecioLitroRepository extends JpaRepository<PrecioLitro, UUID> {

    // Regla estandar de consulta (CORE_DESIGN.md §6, mejoras menores):
    // el precio vigente es el de vigente_desde mas reciente <= hoy.
    @Query("SELECT p FROM PrecioLitro p WHERE p.vigenteDesde <= :hoy ORDER BY p.vigenteDesde DESC LIMIT 1")
    Optional<PrecioLitro> findVigente(LocalDate hoy);

    default Optional<PrecioLitro> findVigenteHoy() {
        return findVigente(LocalDate.now());
    }

    // Auditoria CRUD (prioridad ALTA): usados por el nuevo PrecioLitroService.
    // Paginacion pendiente: historico paginado para la web. vigenteDesde ya es
    // unica (existsByVigenteDesde la protege), el id solo garantiza el orden
    // total aunque esa regla cambiara.
    Page<PrecioLitro> findAllByOrderByVigenteDesdeDescIdDesc(Pageable pageable);

    boolean existsByVigenteDesde(LocalDate vigenteDesde);
}
