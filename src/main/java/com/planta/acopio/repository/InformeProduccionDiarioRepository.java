package com.planta.acopio.repository;

import com.planta.acopio.entity.InformeProduccionDiario;
import com.planta.acopio.service.BusquedaTexto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface InformeProduccionDiarioRepository extends JpaRepository<InformeProduccionDiario, UUID> {

    Optional<InformeProduccionDiario> findByUuidCliente(String uuidCliente);

    Optional<InformeProduccionDiario> findByFecha(LocalDate fecha);

    // Mismo criterio que LoteProduccionRepository.buscarConFiltros: query nativa con CAST explicito
    // (la version JPQL equivalente ya fallo en runtime -- SQLState 42P18 -- para filtros opcionales de
    // fecha/uuid en otros repositorios de este proyecto), countQuery explicito para paginacion real.
    // Busqueda de texto: nombre del responsable (sin acentos ni mayusculas), sobre TODOS los informes del filtro, antes
    // de paginar. Hay un informe por fecha, asi que "fecha DESC" ya deja el mas reciente primero.
    String FILTROS_INFORME =
            "FROM informe_produccion_diario i LEFT JOIN usuario u ON u.id = i.responsable_id WHERE " +
            "(CAST(:desde AS date) IS NULL OR i.fecha >= CAST(:desde AS date)) AND " +
            "(CAST(:hasta AS date) IS NULL OR i.fecha <= CAST(:hasta AS date)) AND " +
            "(" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "u.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT i.* " + FILTROS_INFORME + "ORDER BY i.fecha DESC, i.creado_en DESC, i.id DESC",
           countQuery = "SELECT count(*) " + FILTROS_INFORME,
           nativeQuery = true)
    Page<InformeProduccionDiario> buscarConFiltros(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta,
                                                   @Param("busqueda") String busqueda, Pageable pageable);
}
