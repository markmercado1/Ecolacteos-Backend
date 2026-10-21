package com.planta.acopio.repository;

import com.planta.acopio.entity.Proveedor;
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
    @Query("SELECT p FROM Proveedor p WHERE " +
           "(:zonaId IS NULL OR p.zonaActual.id = :zonaId) AND " +
           "(:activo IS NULL OR p.activo = :activo) AND " +
           "(LOWER(p.nombre) LIKE :busqueda ESCAPE '!' OR LOWER(p.dni) LIKE :busqueda ESCAPE '!') " +
           "ORDER BY p.nombre, p.id")
    Page<Proveedor> buscarConFiltros(@Param("zonaId") UUID zonaId, @Param("activo") Boolean activo,
                                     @Param("busqueda") String busqueda, Pageable pageable);
}
