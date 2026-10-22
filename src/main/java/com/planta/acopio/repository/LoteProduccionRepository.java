package com.planta.acopio.repository;

import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.service.BusquedaTexto;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoteProduccionRepository extends JpaRepository<LoteProduccion, UUID> {

    Optional<LoteProduccion> findByUuidCliente(String uuidCliente);

    // Auditoria CRUD (prioridad ALTA): el modulo no tenia ningun listado general.
    List<LoteProduccion> findAllByOrderByFechaDesc();

    // Bloque 5 (confirmado): filtros opcionales combinables, aditivos -- sin
    // parametros se comporta igual que findAllByOrderByFechaDesc.
    // Verificacion en caliente (bloque 12): la version JPQL equivalente
    // fallaba en runtime con SQLState 42P18 cuando `fecha` llegaba no-nulo
    // (mismo problema de inferencia de tipo que RegistroAcopioRepository/
    // VentaRepository/RecepcionPlantaRepository). Nativa con casts
    // explicitos, probada contra Postgres real.
    // Paginacion real (confirmado): countQuery explicito para query nativa.
    // V18 (recetas): filtro opcional por estado, aditivo (null = todos, mismo resultado que antes).
    // creado_en DESC como desempate estable dentro de la misma fecha.
    // Busqueda de texto: nombre del producto o del responsable (sin acentos ni mayusculas), sobre TODOS los lotes del
    // filtro, antes de paginar. LEFT JOIN: un lote sin producto o sin responsable no desaparece del listado.
    String FILTROS_LOTE =
            "FROM lote_produccion l LEFT JOIN producto pd ON pd.id = l.producto_id " +
            "LEFT JOIN usuario u ON u.id = l.responsable_id WHERE " +
            "(CAST(:fecha AS date) IS NULL OR l.fecha = CAST(:fecha AS date)) AND " +
            "(CAST(:productoId AS uuid) IS NULL OR l.producto_id = CAST(:productoId AS uuid)) AND " +
            "(CAST(:estado AS varchar) IS NULL OR l.estado = CAST(:estado AS varchar)) AND " +
            "(" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "pd.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "u.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT l.* " + FILTROS_LOTE + "ORDER BY l.fecha DESC, l.creado_en DESC, l.id DESC",
           countQuery = "SELECT count(*) " + FILTROS_LOTE,
           nativeQuery = true)
    Page<LoteProduccion> buscarConFiltros(@Param("fecha") LocalDate fecha, @Param("productoId") UUID productoId,
                                          @Param("estado") String estado, @Param("busqueda") String busqueda,
                                          Pageable pageable);

    // V18 (recetas): bloqueo pesimista del lote para sus transiciones de estado (iniciar/finalizar/cancelar)
    // -- dos peticiones simultaneas sobre el MISMO lote se serializan aqui, y la segunda ve el estado ya
    // confirmado por la primera (nunca consume ni finaliza dos veces).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM LoteProduccion l WHERE l.id = :id")
    Optional<LoteProduccion> bloquearPorId(@Param("id") UUID id);

    // Decision aprobada: un RegistroAcopio pertenece a un unico LoteProduccion en v1.
    // Se valida aqui (LoteProduccionService) antes de vincular.
    @Query("SELECT CASE WHEN COUNT(l) > 0 THEN true ELSE false END FROM LoteProduccion l " +
           "JOIN l.registrosVinculados r WHERE r.id = :registroAcopioId")
    boolean existeRegistroYaVinculadoAOtroLote(@Param("registroAcopioId") UUID registroAcopioId);

    // Fase 4 PRODUCCION (docs/PROMPTS_FINALIZAR_MOVIL.md): version en bloque de
    // existeRegistroYaVinculadoAOtroLote, para no hacer una consulta por fila
    // al armar una lista de RegistroAcopioResponse/RegistroAcopioResumenResponse
    // (buscar()/historialPorProveedor() de RegistroAcopioService). Devuelve
    // solo los ids de `registroAcopioIds` que YA estan vinculados a algun lote.
    @Query("SELECT r.id FROM LoteProduccion l JOIN l.registrosVinculados r WHERE r.id IN :registroAcopioIds")
    List<UUID> idsYaVinculados(@Param("registroAcopioIds") Collection<UUID> registroAcopioIds);
}
