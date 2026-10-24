package com.planta.acopio.repository;

import com.planta.acopio.entity.AnalisisCalidad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalisisCalidadRepository extends JpaRepository<AnalisisCalidad, UUID> {

    Optional<AnalisisCalidad> findByUuidCliente(String uuidCliente);

    // RF-CAL-02: contraste de doble muestra por folio
    List<AnalisisCalidad> findByFolioMuestra(String folioMuestra);

    @Query("SELECT a FROM AnalisisCalidad a JOIN FETCH a.registroAcopio r JOIN FETCH r.proveedor " +
           "WHERE r.fechaHora >= :inicio AND r.fechaHora < :fin " +
           "ORDER BY r.fechaHora DESC, a.creadoEn DESC")
    List<AnalisisCalidad> listarBandejaPorFechaDeEntrega(@Param("inicio") LocalDateTime inicio,
                                                        @Param("fin") LocalDateTime fin);

    // Auditoria CRUD (prioridad MEDIA): ver los analisis de un registro de
    // acopio puntual (vista de detalle antes de anexar una correccion).
    List<AnalisisCalidad> findByRegistroAcopioIdOrderByCreadoEnDesc(UUID registroAcopioId);

    // Usado por ScoreConfianzaService.componenteCalidad
    @Query("SELECT COUNT(a) FROM AnalisisCalidad a WHERE a.registroAcopio.proveedor.id = :proveedorId " +
           "AND a.creadoEn >= :desde AND a.resultado = com.planta.acopio.entity.ResultadoCalidad.APROBADO")
    long contarAprobadosPorProveedorDesde(@Param("proveedorId") UUID proveedorId, @Param("desde") LocalDateTime desde);

    @Query("SELECT COUNT(a) FROM AnalisisCalidad a WHERE a.registroAcopio.proveedor.id = :proveedorId AND a.creadoEn >= :desde")
    long contarTotalPorProveedorDesde(@Param("proveedorId") UUID proveedorId, @Param("desde") LocalDateTime desde);

    // Usado por PanelEconomicoService (simulador "que pasa si", RF-INN-05):
    // % de rechazo por semana calendario, para el eje Y de la regresion lineal.
    // Object[] = {semana (Date, lunes de esa semana), pctRechazo (Numeric)}.
    @Query(value = "SELECT date_trunc('week', a.creado_en)::date AS semana, " +
           "100.0 * SUM(CASE WHEN a.resultado = 'RECHAZADO' THEN 1 ELSE 0 END) / COUNT(*) AS pct_rechazo " +
           "FROM analisis_calidad a JOIN registro_acopio r ON a.registro_acopio_id = r.id " +
           "WHERE r.zona_id = :zonaId GROUP BY semana ORDER BY semana", nativeQuery = true)
    List<Object[]> pctRechazoPorSemanaZona(@Param("zonaId") UUID zonaId);

    // Ultimo agua_anadida=true reciente del proveedor -- usado por AnomaliaService
    // para RIESGO_ADULTERACION (heuristica simplificada, ver comentario en el service).
    @Query("SELECT COUNT(a) > 0 FROM AnalisisCalidad a WHERE a.registroAcopio.proveedor.id = :proveedorId " +
           "AND a.aguaAnadida = true AND a.creadoEn >= :desde")
    boolean existeAguaAnadidaRecienteParaProveedor(@Param("proveedorId") UUID proveedorId, @Param("desde") LocalDateTime desde);

    // Fase D: todos los analisis de las entregas de un proveedor (cronologico: el ultimo de cada entrega pisa a los anteriores), y los de
    // las entregas de una pagina.
    List<AnalisisCalidad> findByRegistroAcopioProveedorIdOrderByCreadoEnAscIdAsc(UUID proveedorId);

    List<AnalisisCalidad> findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(java.util.Collection<UUID> registroAcopioIds);
}
