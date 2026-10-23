package com.planta.acopio.repository;

import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.service.BusquedaTexto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistroAcopioRepository extends JpaRepository<RegistroAcopio, UUID> {

    Optional<RegistroAcopio> findByUuidCliente(String uuidCliente);

    // Fase 1 de jornada: valida "cancelacion solo si la jornada todavia no tiene acopios" (ESPEC §13) y,
    // mas adelante (fuera de esta fase), calcula litros esperados de Recepcion.
    long countByJornadaId(UUID jornadaId);

    // Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md): litros esperados = SUM(RegistroAcopio.litros) de la
    // jornada, autoritativo en backend (nunca un total editable enviado por el movil). La misma lista sirve
    // para tres cosas a la vez en ConciliacionService/JornadaService: el total, la vinculacion automatica
    // (registrosVinculados) y el detalle de acopios/proveedores visitados -- evita 3 consultas separadas.
    List<RegistroAcopio> findByJornadaIdOrderByFechaHoraAsc(UUID jornadaId);

    List<RegistroAcopio> findByProveedorIdOrderByFechaHoraDesc(UUID proveedorId);

    // Usado por AnomaliaService / ScoreConfianzaService (estadistica simple en Java)
    @Query("SELECT r.litros FROM RegistroAcopio r WHERE r.proveedor.id = :proveedorId AND r.fechaHora >= :desde ORDER BY r.fechaHora DESC")
    List<BigDecimal> findLitrosPorProveedorDesde(@Param("proveedorId") UUID proveedorId, @Param("desde") LocalDateTime desde);

    // Igual que la anterior, pero excluye un registro puntual -- usado por
    // AnomaliaService.evaluarNuevoRegistro para no incluir el propio registro
    // recien creado en el calculo de media/desviacion historica (si no, un
    // registro atipico se "normaliza a si mismo").
    @Query("SELECT r.litros FROM RegistroAcopio r WHERE r.proveedor.id = :proveedorId AND r.fechaHora >= :desde " +
           "AND r.id <> :excluirId ORDER BY r.fechaHora DESC")
    List<BigDecimal> findLitrosPorProveedorDesdeExcluyendo(@Param("proveedorId") UUID proveedorId,
                                                            @Param("desde") LocalDateTime desde,
                                                            @Param("excluirId") UUID excluirId);

    // Usado por AnomaliaService.recalcularPrediccionProveedor (media movil ultimas 6 entregas)
    List<RegistroAcopio> findTop6ByProveedorIdOrderByFechaHoraDesc(UUID proveedorId);

    @Query("SELECT SUM(r.litros) FROM RegistroAcopio r WHERE r.unidad.id = :unidadId AND r.fechaHora BETWEEN :inicio AND :fin")
    BigDecimal sumLitrosPorUnidadYFecha(@Param("unidadId") UUID unidadId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    // Fase A: entregas cuya zona historica no se pudo determinar (informe de revision de zonas).
    @Query(value = "SELECT r FROM RegistroAcopio r WHERE r.zona IS NULL ORDER BY r.proveedor.nombre, r.fechaHora",
           countQuery = "SELECT count(r) FROM RegistroAcopio r WHERE r.zona IS NULL")
    Page<RegistroAcopio> findSinZona(Pageable pageable);

    // Base de la liquidacion semanal (PagoService)
    List<RegistroAcopio> findByProveedorIdAndFechaHoraBetween(UUID proveedorId, LocalDateTime inicio, LocalDateTime fin);

    // Bloque 2 (confirmado): bandeja operativa para CALIDAD/ADMIN -- filtros
    // combinables, todos opcionales (parametro null = sin filtrar por ese
    // campo). zonaId filtra via proveedor.zonaActual (registro_acopio no
    // tiene zona propia, igual que el resto del sistema). sinAnalisis usa
    // NOT EXISTS contra analisis_calidad -- registros que todavia no tienen
    // ninguna muestra de laboratorio asociada.
    //
    // Verificacion en caliente (bloque 12): la version JPQL equivalente
    // fallaba en runtime con SQLState 42P18 ("no se pudo determinar el tipo
    // del parametro") -- PostgreSQL no podia inferir el tipo de uno de los
    // parametros al combinarlo con el NOT EXISTS en la misma consulta.
    // Nativa con casts explicitos (mismo patron ya usado en este repositorio
    // para promedioLitrosPorZonaYMes/sumLitrosPorZonaYSemana), probada contra
    // Postgres real.
    @Query(value = "SELECT r.* FROM registro_acopio r JOIN proveedor p ON r.proveedor_id = p.id WHERE " +
           "(CAST(:fechaInicio AS timestamp) IS NULL OR r.fecha_hora >= CAST(:fechaInicio AS timestamp)) AND " +
           "(CAST(:fechaFin AS timestamp) IS NULL OR r.fecha_hora < CAST(:fechaFin AS timestamp)) AND " +
           "(CAST(:zonaId AS uuid) IS NULL OR p.zona_actual_id = CAST(:zonaId AS uuid)) AND " +
           "(:sinAnalisis = false OR NOT EXISTS (SELECT 1 FROM analisis_calidad a WHERE a.registro_acopio_id = r.id)) " +
           "ORDER BY r.fecha_hora DESC", nativeQuery = true)
    List<RegistroAcopio> buscarConFiltros(@Param("fechaInicio") LocalDateTime fechaInicio,
                                           @Param("fechaFin") LocalDateTime fechaFin,
                                           @Param("zonaId") UUID zonaId,
                                           @Param("sinAnalisis") boolean sinAnalisis);

    // Base del forecasting por zona (ForecastingService). Inferencia documentada
    // en CORE_DESIGN.md: se agrupa por proveedor.zonaActual, que es la relacion
    // explicita proveedor<->zona del modelo (no existe zona en registro_acopio
    // directamente).
    @Query("SELECT r.litros FROM RegistroAcopio r WHERE r.proveedor.zonaActual.id = :zonaId AND r.fechaHora BETWEEN :inicio AND :fin")
    List<BigDecimal> findLitrosPorZonaYRango(@Param("zonaId") UUID zonaId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    // Promedio historico de litros de esa zona en un mes calendario especifico
    // (1-12), vs. el promedio general de la zona -- factor estacional (RF-INN-04).
    @Query(value = "SELECT COALESCE(AVG(r.litros), 0) FROM registro_acopio r " +
           "JOIN proveedor p ON r.proveedor_id = p.id " +
           "WHERE p.zona_actual_id = :zonaId AND EXTRACT(MONTH FROM r.fecha_hora) = :mes", nativeQuery = true)
    BigDecimal promedioLitrosPorZonaYMes(@Param("zonaId") UUID zonaId, @Param("mes") int mes);

    @Query(value = "SELECT COALESCE(AVG(r.litros), 0) FROM registro_acopio r " +
           "JOIN proveedor p ON r.proveedor_id = p.id WHERE p.zona_actual_id = :zonaId", nativeQuery = true)
    BigDecimal promedioHistoricoGeneralPorZona(@Param("zonaId") UUID zonaId);

    // Suma real de litros de la zona en una semana, para completar
    // prediccion_zona.litros_reales al cierre de semana (RF-INN-04).
    @Query(value = "SELECT COALESCE(SUM(r.litros), 0) FROM registro_acopio r " +
           "JOIN proveedor p ON r.proveedor_id = p.id " +
           "WHERE p.zona_actual_id = :zonaId AND r.fecha_hora >= :inicio AND r.fecha_hora < :fin", nativeQuery = true)
    BigDecimal sumLitrosPorZonaYSemana(@Param("zonaId") UUID zonaId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    // ---------------------------------------------------------------------
    // Paginacion pendiente (web): variantes paginadas del listado por fecha y
    // del historial por proveedor. Las versiones List<> de arriba se mantienen
    // sin cambios porque las consume la app movil (GET /api/registros-acopio y
    // GET /api/registros-acopio/proveedor/{id}) y la bandeja de CALIDAD.
    //
    // Mismos filtros y mismos casts explicitos que buscarConFiltros (ver el
    // comentario ahi sobre SQLState 42P18). :busqueda llega ya en minusculas,
    // con % alrededor y comodines escapados con '!' (BusquedaTexto), y se
    // compara contra el nombre del proveedor o la placa de la unidad.
    // Orden fijo en la query con desempate por id (orden estable entre paginas).
    String FILTROS_REGISTRO =
            "(CAST(:fechaInicio AS timestamp) IS NULL OR r.fecha_hora >= CAST(:fechaInicio AS timestamp)) AND " +
            "(CAST(:fechaFin AS timestamp) IS NULL OR r.fecha_hora < CAST(:fechaFin AS timestamp)) AND " +
            "(CAST(:zonaId AS uuid) IS NULL OR p.zona_actual_id = CAST(:zonaId AS uuid)) AND " +
            "(:sinAnalisis = false OR NOT EXISTS (SELECT 1 FROM analisis_calidad a WHERE a.registro_acopio_id = r.id)) AND " +
            "(" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "u.placa" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    String DESDE_REGISTRO =
            "FROM registro_acopio r JOIN proveedor p ON r.proveedor_id = p.id JOIN unidad u ON r.unidad_id = u.id WHERE ";

    @Query(value = "SELECT r.* " + DESDE_REGISTRO + FILTROS_REGISTRO + "ORDER BY r.fecha_hora DESC, r.id DESC",
           countQuery = "SELECT count(*) " + DESDE_REGISTRO + FILTROS_REGISTRO,
           nativeQuery = true)
    Page<RegistroAcopio> buscarConFiltrosPaginado(@Param("fechaInicio") LocalDateTime fechaInicio,
                                                   @Param("fechaFin") LocalDateTime fechaFin,
                                                   @Param("zonaId") UUID zonaId,
                                                   @Param("sinAnalisis") boolean sinAnalisis,
                                                   @Param("busqueda") String busqueda,
                                                   Pageable pageable);

    // Vista "Por jornada" de la web: una fila por jornada (o por registro suelto
    // si no tiene jornada), agrupada en SQL sobre TODOS los registros del
    // filtro y recien despues paginada -- agrupar una pagina de registros en el
    // navegador partiria una jornada entre dos paginas y daria totales falsos.
    // Columnas: clave, es_jornada, cantidad, total_litros, fecha (primer acopio
    // cronologico), primer_acopio_id, placa, acopiador_nombre. La placa y el
    // acopiador salen de la jornada (historico real), igual que hacia la web
    // con GET /api/jornadas/{id}; un registro suelto usa la placa de su unidad.
    String GRUPOS_JORNADA =
            "WITH filtrados AS (SELECT r.id, r.jornada_id, r.fecha_hora, r.litros, r.unidad_id " +
            DESDE_REGISTRO + FILTROS_REGISTRO + ") ";

    @Query(value = GRUPOS_JORNADA +
           ", grupos AS (SELECT COALESCE(f.jornada_id, f.id) AS clave, (f.jornada_id IS NOT NULL) AS es_jornada, " +
           "COUNT(*) AS cantidad, SUM(f.litros) AS total_litros, MIN(f.fecha_hora) AS fecha, " +
           "(ARRAY_AGG(f.id ORDER BY f.fecha_hora, f.id))[1] AS primer_acopio_id, " +
           "(ARRAY_AGG(f.unidad_id ORDER BY f.fecha_hora, f.id))[1] AS unidad_id " +
           "FROM filtrados f GROUP BY COALESCE(f.jornada_id, f.id), (f.jornada_id IS NOT NULL)) " +
           "SELECT g.clave, g.es_jornada, g.cantidad, g.total_litros, g.fecha, g.primer_acopio_id, " +
           "COALESCE(uj.placa, ur.placa) AS placa, ac.nombre AS acopiador_nombre " +
           "FROM grupos g " +
           "LEFT JOIN jornada j ON g.es_jornada AND j.id = g.clave " +
           "LEFT JOIN unidad uj ON uj.id = j.unidad_id " +
           "LEFT JOIN unidad ur ON ur.id = g.unidad_id " +
           "LEFT JOIN usuario ac ON ac.id = j.acopiador_id " +
           "ORDER BY g.fecha DESC, g.clave DESC",
           countQuery = GRUPOS_JORNADA + "SELECT COUNT(DISTINCT COALESCE(f.jornada_id, f.id)) FROM filtrados f",
           nativeQuery = true)
    Page<Object[]> agruparPorJornadaPaginado(@Param("fechaInicio") LocalDateTime fechaInicio,
                                             @Param("fechaFin") LocalDateTime fechaFin,
                                             @Param("zonaId") UUID zonaId,
                                             @Param("sinAnalisis") boolean sinAnalisis,
                                             @Param("busqueda") String busqueda,
                                             Pageable pageable);

    // Grafico "Acopio por proveedor" de la web: totales globales del filtro
    // (fecha/zona/sinAnalisis; nunca la busqueda de texto ni la pagina
    // visible). Una fila por proveedor con entregas, acotada por el padron.
    // Columnas: proveedor_id, proveedor_nombre, total_litros, cantidad.
    @Query(value = "SELECT p.id, p.nombre, SUM(r.litros) AS total_litros, COUNT(*) AS cantidad " +
           DESDE_REGISTRO + FILTROS_REGISTRO +
           "GROUP BY p.id, p.nombre ORDER BY total_litros DESC, p.nombre, p.id",
           nativeQuery = true)
    List<Object[]> totalesPorProveedor(@Param("fechaInicio") LocalDateTime fechaInicio,
                                       @Param("fechaFin") LocalDateTime fechaFin,
                                       @Param("zonaId") UUID zonaId,
                                       @Param("sinAnalisis") boolean sinAnalisis,
                                       @Param("busqueda") String busqueda);

    Page<RegistroAcopio> findByProveedorIdOrderByFechaHoraDescIdDesc(UUID proveedorId, Pageable pageable);
}
