package com.planta.acopio.repository;

import com.planta.acopio.entity.RecepcionPlanta;
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
import java.time.LocalDate;

public interface RecepcionPlantaRepository extends JpaRepository<RecepcionPlanta, UUID> {
    Optional<RecepcionPlanta> findByFechaAndUnidadIdAndTurno(LocalDate fecha, UUID unidadId, String turno);
    List<RecepcionPlanta> findByUnidadIdOrderByFechaDesc(UUID unidadId);

    // Auditoria CRUD (prioridad ALTA): el modulo no tenia ningun listado general.
    List<RecepcionPlanta> findAllByOrderByFechaDesc();

    // Bloque 5 (confirmado): agrega filtro por fecha, combinable con el de
    // unidad ya existente. Sin parametros, mismo resultado que antes.
    // Verificacion en caliente (bloque 12): la version JPQL equivalente
    // fallaba en runtime con SQLState 42P18 cuando `fecha` llegaba no-nulo
    // (mismo problema de inferencia de tipo que en los demas repositorios
    // con filtro por fecha). Nativa con casts explicitos, probada contra
    // Postgres real.
    // Paginacion real (confirmado): countQuery explicito para query nativa.
    // Busqueda de texto: placa de la unidad o nombre del responsable (sin acentos ni mayusculas), sobre TODAS las
    // recepciones del filtro, antes de paginar. Orden: fecha mas reciente primero y, dentro del mismo dia, la
    // recepcion creada mas recientemente primero (creado_en; el id es un UUID aleatorio).
    String FILTROS_RECEPCION =
            "FROM recepcion_planta r JOIN unidad un ON un.id = r.unidad_id " +
            "LEFT JOIN usuario u ON u.id = r.responsable_id WHERE " +
            "(CAST(:unidadId AS uuid) IS NULL OR r.unidad_id = CAST(:unidadId AS uuid)) AND " +
            "(CAST(:fecha AS date) IS NULL OR r.fecha = CAST(:fecha AS date)) AND " +
            "(" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "un.placa" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "u.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT r.* " + FILTROS_RECEPCION + "ORDER BY r.fecha DESC, r.creado_en DESC, r.id DESC",
           countQuery = "SELECT count(*) " + FILTROS_RECEPCION,
           nativeQuery = true)
    Page<RecepcionPlanta> buscarConFiltros(@Param("unidadId") UUID unidadId, @Param("fecha") LocalDate fecha,
                                           @Param("busqueda") String busqueda, Pageable pageable);

    // Fix trazabilidad (confirmado): mismo patron que PagoRepository.existeRegistroYaLiquidado
    // / LoteProduccionRepository.existeRegistroYaVinculadoAOtroLote -- evita que
    // el mismo RegistroAcopio quede vinculado a mas de una RecepcionPlanta.
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM RecepcionPlanta rp " +
           "JOIN rp.registrosVinculados r WHERE r.id = :registroAcopioId")
    boolean existeRegistroYaVinculado(@Param("registroAcopioId") UUID registroAcopioId);

    // Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md): idempotencia del flujo de jornada -- mismo patron que
    // RegistroAcopioRepository.findByUuidCliente/JornadaRepository.findByUuidCliente.
    Optional<RecepcionPlanta> findByUuidCliente(String uuidCliente);

    // Verificacion previa (mensaje legible en el caso normal, sin carrera) -- el cerrojo real contra
    // condiciones de carrera entre dos dispositivos es el indice parcial unico de V4__recepcion_jornada.sql.
    boolean existsByJornadaId(UUID jornadaId);

    // Refactor de Produccion, fase 2 (V9, FIFO de consumo de leche): candidatas con saldo remanente
    // (litrosPlanta menos lo ya consumido en lote_produccion_recepcion_consumo), mas antiguas primero.
    // Lectura NO bloqueada -- es solo el primer paso de LoteProduccionService.planificarConsumo(); el
    // bloqueo real ocurre despues, en bloquearPorIds(), exactamente sobre estas filas.
    @Query("SELECT r FROM RecepcionPlanta r WHERE r.litrosPlanta > COALESCE(" +
           "(SELECT SUM(c.litrosConsumidos) FROM LoteProduccionRecepcionConsumo c WHERE c.recepcionPlanta = r), 0) " +
           "ORDER BY r.fecha ASC, r.creadoEn ASC")
    List<RecepcionPlanta> buscarConSaldoDisponibleOrdenadasPorAntiguedad();

    // Bloqueo pesimista de fila (SELECT ... FOR UPDATE) sobre las recepciones candidatas exactas: mientras
    // esta transaccion no termine (commit o rollback), cualquier otra produccion concurrente que quiera
    // consumir de la MISMA recepcion queda bloqueada en este mismo punto -- al desbloquearse, relee el
    // consumo ya aplicado (READ COMMITTED). Asi dos producciones nunca consumen el mismo litro dos veces.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RecepcionPlanta r WHERE r.id IN :ids")
    List<RecepcionPlanta> bloquearPorIds(@Param("ids") List<UUID> ids);
}
