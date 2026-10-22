package com.planta.acopio.repository;

import com.planta.acopio.entity.Comunicado;
import com.planta.acopio.service.BusquedaTexto;
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

    // Paginacion pendiente: listado global paginado para ADMIN (web). La fecha es un instante (timestamp): mas reciente
    // primero; el id solo desempata un orden estable.
    // Busqueda de texto: el mensaje o el nombre de alguna zona destino (sin acentos ni mayusculas), sobre TODOS los
    // comunicados, antes de paginar.
    String FILTROS_COMUNICADO =
            "FROM comunicado c WHERE (" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "c.mensaje" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            "EXISTS (SELECT 1 FROM comunicado_zona cz JOIN zona z ON z.id = cz.zona_id WHERE cz.comunicado_id = c.id AND " +
            BusquedaTexto.PLEGAR_ABRE + "z.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ")) ";

    @Query(value = "SELECT c.* " + FILTROS_COMUNICADO + "ORDER BY c.fecha DESC, c.id DESC",
           countQuery = "SELECT count(*) " + FILTROS_COMUNICADO,
           nativeQuery = true)
    Page<Comunicado> buscar(@Param("busqueda") String busqueda, Pageable pageable);
}
