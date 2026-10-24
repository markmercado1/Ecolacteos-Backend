package com.planta.acopio.repository;

import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.service.BusquedaTexto;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProveedorRepository extends JpaRepository<Proveedor, UUID> {
    Optional<Proveedor> findByDni(String dni);
    Optional<Proveedor> findByRuc(String ruc);
    Optional<Proveedor> findByCodigoQr(String codigoQr);

    /** Reasignar zona: serializa los cambios de zona de un mismo proveedor (bloqueo de fila hasta el fin de la transaccion). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Proveedor p WHERE p.id = :id")
    Optional<Proveedor> findByIdParaActualizar(@Param("id") UUID id);

    /** Importacion de Excel: de estos DNI, los que ya estan registrados (activos o no), en una sola consulta. */
    @Query("SELECT p.dni FROM Proveedor p WHERE p.dni IN :dnis")
    List<String> dnisExistentes(@Param("dnis") java.util.Collection<String> dnis);

    /** Importacion de Excel (Fase G): de estos RUC, los que ya estan registrados (activos o no). */
    @Query("SELECT p.ruc FROM Proveedor p WHERE p.ruc IN :rucs")
    List<String> rucsExistentes(@Param("rucs") java.util.Collection<String> rucs);

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
    // nombre es NOT NULL. Desempate por id para un orden estable entre
    // paginas cuando dos proveedores se llaman igual.
    //
    // Busqueda sin acentos ni mayusculas: pasa de JPQL a SQL nativo (TRANSLATE no existe en JPQL), con el mismo patron de
    // casts explicitos que UsuarioRepository. :busqueda llega ya plegado y escapado (BusquedaTexto.patronLike) o null.
    // Fase G: tambien busca por RUC y razon social (dni, ruc y razon social pueden ser NULL: esa parte no coincide).
    String FILTROS_PROVEEDOR =
            "FROM proveedor p WHERE " +
            "(CAST(:zonaId AS uuid) IS NULL OR p.zona_actual_id = CAST(:zonaId AS uuid)) AND " +
            "(CAST(:activo AS boolean) IS NULL OR p.activo = CAST(:activo AS boolean)) AND " +
            "(" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "coalesce(p.dni, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "coalesce(p.ruc, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "coalesce(p.razon_social, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT p.* " + FILTROS_PROVEEDOR + "ORDER BY p.creado_en DESC, p.nombre, p.id",
           countQuery = "SELECT count(*) " + FILTROS_PROVEEDOR,
           nativeQuery = true)
    Page<Proveedor> buscarConFiltros(@Param("zonaId") UUID zonaId, @Param("activo") Boolean activo,
                                     @Param("busqueda") String busqueda, Pageable pageable);
}
