package com.planta.acopio.repository;

import com.planta.acopio.entity.Usuario;
import com.planta.acopio.service.BusquedaTexto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository extends JpaRepository<Usuario, UUID> {
    Optional<Usuario> findByEmail(String email);
    boolean existsByEmail(String email);

    // Paginacion pendiente: listado ADMIN paginado (usuarios y acopiadores en
    // la web). Nativa con casts explicitos, mismo patron probado que
    // VentaRepository.buscarConFiltros para parametros opcionales. :busqueda
    // llega en minusculas con comodines escapados con '!' (BusquedaTexto).
    // Orden por nombre con desempate por id. El GET /api/admin/usuarios sin
    // paginar se mantiene para los selectores de responsable de unidad.
    String FILTROS_USUARIO =
            "FROM usuario u JOIN rol r ON u.rol_id = r.id WHERE " +
            "(CAST(:rol AS varchar) IS NULL OR r.nombre = CAST(:rol AS varchar)) AND " +
            "(CAST(:activo AS boolean) IS NULL OR u.activo = CAST(:activo AS boolean)) AND " +
            "(" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "u.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "u.email" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT u.* " + FILTROS_USUARIO + "ORDER BY u.creado_en DESC, u.nombre, u.id",
           countQuery = "SELECT count(*) " + FILTROS_USUARIO,
           nativeQuery = true)
    Page<Usuario> buscarConFiltros(@Param("rol") String rol, @Param("activo") Boolean activo,
                                   @Param("busqueda") String busqueda, Pageable pageable);
}
