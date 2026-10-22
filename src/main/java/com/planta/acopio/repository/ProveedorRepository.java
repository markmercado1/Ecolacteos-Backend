package com.planta.acopio.repository;

import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.service.BusquedaTexto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProveedorRepository extends JpaRepository<Proveedor, UUID> {
    Optional<Proveedor> findByDni(String dni);
    Optional<Proveedor> findByCodigoQr(String codigoQr);

    // Bloque 5 (confirmado): filtros opcionales combinables para el listado
    // ADMIN (distinto de listarPublico(), que ya filtra activo=true siempre
    // para el catalogo operativo). Sin parametros, mismo resultado que
    // findAll() (no rompe al frontend actual).
    // Paginacion real (confirmado): JPQL (no nativa) -- Spring Data deriva el
    // countQuery solo, sin filtro de fecha involucrado (zonaId/activo ya
    // probados sin el bug de inferencia de tipo que si afecta a LocalDate).
    //
    // Paginacion pendiente: busqueda de texto en el servidor (antes la web solo
    // filtraba la pagina descargada). :busqueda nunca es null -- llega "%"
    // (todos) o el patron ya escapado con '!' (BusquedaTexto.patronLikeOTodos);
    // nombre y dni son NOT NULL. Desempate por id para un orden estable entre
    // paginas cuando dos proveedores se llaman igual.
    //
    // Busqueda sin acentos ni mayusculas: pasa de JPQL a SQL nativo (TRANSLATE no existe en JPQL), con el mismo patron de
    // casts explicitos que UsuarioRepository. :busqueda llega ya plegado y escapado (BusquedaTexto.patronLike) o null.
    String FILTROS_PROVEEDOR =
            "FROM proveedor p WHERE " +
            "(CAST(:zonaId AS uuid) IS NULL OR p.zona_actual_id = CAST(:zonaId AS uuid)) AND " +
            "(CAST(:activo AS boolean) IS NULL OR p.activo = CAST(:activo AS boolean)) AND " +
            "(" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "p.dni" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT p.* " + FILTROS_PROVEEDOR + "ORDER BY p.creado_en DESC, p.nombre, p.id",
           countQuery = "SELECT count(*) " + FILTROS_PROVEEDOR,
           nativeQuery = true)
    Page<Proveedor> buscarConFiltros(@Param("zonaId") UUID zonaId, @Param("activo") Boolean activo,
                                     @Param("busqueda") String busqueda, Pageable pageable);
}
