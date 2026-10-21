package com.planta.acopio.repository;

import com.planta.acopio.entity.InformeProduccionDiario;
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
    @Query(value = "SELECT * FROM informe_produccion_diario WHERE " +
           "(CAST(:desde AS date) IS NULL OR fecha >= CAST(:desde AS date)) AND " +
           "(CAST(:hasta AS date) IS NULL OR fecha <= CAST(:hasta AS date)) " +
           "ORDER BY fecha DESC, id DESC",
           countQuery = "SELECT count(*) FROM informe_produccion_diario WHERE " +
           "(CAST(:desde AS date) IS NULL OR fecha >= CAST(:desde AS date)) AND " +
           "(CAST(:hasta AS date) IS NULL OR fecha <= CAST(:hasta AS date))",
           nativeQuery = true)
    Page<InformeProduccionDiario> buscarConFiltros(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta, Pageable pageable);
}
