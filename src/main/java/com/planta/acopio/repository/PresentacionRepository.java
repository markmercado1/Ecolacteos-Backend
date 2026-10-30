package com.planta.acopio.repository;

import com.planta.acopio.entity.Presentacion;
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

public interface PresentacionRepository extends JpaRepository<Presentacion, UUID> {
    List<Presentacion> findByProductoIdOrderByNombre(UUID productoId);
    boolean existsByProductoIdAndNombre(UUID productoId, String nombre);

    // Fase 1 (control de vencimiento): lecturas ESCALARES, siempre despues de buscarConBloqueoParaActualizarStock
    // (ver InventarioService): nunca se decide con una entidad ya cargada que podria estar desactualizada.
    @Query("SELECT pr.producto.controlaVencimiento FROM Presentacion pr WHERE pr.id = :id")
    Optional<Boolean> controlaVencimientoDe(@Param("id") UUID id);

    @Query("SELECT pr.saldoSinTrazabilidadInicial FROM Presentacion pr WHERE pr.id = :id")
    Optional<Integer> saldoSinTrazabilidadInicialDe(@Param("id") UUID id);

    // Fase 3 (Produccion con varias presentaciones de salida e Inventario por presentacion): bloqueo
    // pesimista de fila (SELECT ... FOR UPDATE), usado exclusivamente por InventarioService antes de
    // validar/descontar stock -- serializa ventas concurrentes de la MISMA presentacion y evita sobreventa.
    // Reemplaza el bloqueo que antes tomaba ProductoRepository (el stock ahora es por presentacion).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Presentacion p WHERE p.id = :id")
    Optional<Presentacion> buscarConBloqueoParaActualizarStock(@Param("id") UUID id);

    // Fase 3 (ampliacion: pantalla /inventario por presentacion, paginada y organizada por familia --
    // mismo patron que PresentacionRepository.buscarFilasPrecios de Fase 2). Una fila por presentacion:
    // visible si (presentacion Y producto estan activos) O si tiene stock remanente distinto de cero (para
    // no perder trazabilidad de existencias de catalogo desactivado, sin listar cada presentacion inactiva
    // sin movimientos). El stock se calcula agregando movimiento_inventario en un JOIN derivado (una sola
    // pasada, sin N+1 por fila).
    @Query(value = "SELECT f.id AS familia_id, f.nombre AS familia_nombre, " +
            "p.id AS producto_id, p.nombre AS producto_nombre, p.activo AS producto_activo, " +
            "pr.id AS presentacion_id, pr.nombre AS presentacion_nombre, pr.unidad_conteo AS unidad_conteo, pr.activo AS presentacion_activo, " +
            "COALESCE(m.stock, 0) AS stock, p.controla_vencimiento AS controla_vencimiento " +
            "FROM presentacion pr " +
            "JOIN producto p ON p.id = pr.producto_id " +
            "JOIN familia f ON f.id = p.familia_id " +
            "LEFT JOIN (SELECT presentacion_id, SUM(cantidad) AS stock FROM movimiento_inventario GROUP BY presentacion_id) m " +
            "  ON m.presentacion_id = pr.id " +
            "WHERE ((pr.activo = true AND p.activo = true) OR COALESCE(m.stock, 0) <> 0) " +
            "AND (CAST(:familiaId AS uuid) IS NULL OR p.familia_id = CAST(:familiaId AS uuid)) " +
            "AND (" + BusquedaTexto.SIN_TERMINO + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") " +
            "ORDER BY f.nombre, f.id, p.nombre, p.id, pr.nombre, pr.id",
            countQuery = "SELECT count(*) " +
            "FROM presentacion pr " +
            "JOIN producto p ON p.id = pr.producto_id " +
            "JOIN familia f ON f.id = p.familia_id " +
            "LEFT JOIN (SELECT presentacion_id, SUM(cantidad) AS stock FROM movimiento_inventario GROUP BY presentacion_id) m " +
            "  ON m.presentacion_id = pr.id " +
            "WHERE ((pr.activo = true AND p.activo = true) OR COALESCE(m.stock, 0) <> 0) " +
            "AND (CAST(:familiaId AS uuid) IS NULL OR p.familia_id = CAST(:familiaId AS uuid)) " +
            "AND (" + BusquedaTexto.SIN_TERMINO + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ")",
            nativeQuery = true)
    Page<Object[]> buscarFilasStock(@Param("familiaId") UUID familiaId, @Param("busqueda") String busqueda, Pageable pageable);

    // Fase 2 (ampliacion: listado paginado de precios por presentacion, /admin/precios-queso/presentaciones).
    // LEFT JOIN a presentacion (solo activas) sobre producto activo: una fila por presentacion activa, y
    // UNA fila con presentacion_id/nombre NULL para un producto activo sin ninguna presentacion activa --
    // nunca se inventa una. familiaId/busqueda nulos = sin filtrar (mismo patron CAST(...) IS NULL que
    // VentaRepository.buscarConFiltros, evita el error de inferencia de tipo de Postgres con parametros
    // nativos que pueden llegar null). busqueda compara contra el nombre del producto O de la presentacion
    // de esa fila (no un OR global entre todas las presentaciones del producto). countQuery explicito
    // obligatorio para queries nativas paginadas. ORDER BY fijo en la query (no el Sort de Pageable, mismo
    // motivo que buscarConFiltros): familia, producto, presentacion (NULLS FIRST para la fila informativa),
    // con desempate estable por id en cada nivel.
    @Query(value = "SELECT f.id AS familia_id, f.nombre AS familia_nombre, " +
            "p.id AS producto_id, p.nombre AS producto_nombre, " +
            "pr.id AS presentacion_id, pr.nombre AS presentacion_nombre " +
            "FROM producto p " +
            "JOIN familia f ON f.id = p.familia_id " +
            "LEFT JOIN presentacion pr ON pr.producto_id = p.id AND pr.activo = true " +
            "WHERE p.activo = true " +
            "AND (CAST(:familiaId AS uuid) IS NULL OR p.familia_id = CAST(:familiaId AS uuid)) " +
            "AND (" + BusquedaTexto.SIN_TERMINO + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") " +
            "ORDER BY f.nombre, f.id, p.nombre, p.id, pr.nombre NULLS FIRST, pr.id",
            countQuery = "SELECT count(*) " +
            "FROM producto p " +
            "JOIN familia f ON f.id = p.familia_id " +
            "LEFT JOIN presentacion pr ON pr.producto_id = p.id AND pr.activo = true " +
            "WHERE p.activo = true " +
            "AND (CAST(:familiaId AS uuid) IS NULL OR p.familia_id = CAST(:familiaId AS uuid)) " +
            "AND (" + BusquedaTexto.SIN_TERMINO + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ")",
            nativeQuery = true)
    Page<Object[]> buscarFilasPrecios(@Param("familiaId") UUID familiaId, @Param("busqueda") String busqueda, Pageable pageable);

    // Fase G: presentaciones activas de productos activos para la configuracion de afectacion al IGV, filtradas por producto o
    // presentacion (sin acentos ni mayusculas) antes de paginar. Orden fijo: familia, producto, presentacion.
    String FILTROS_AFECTACION_IGV =
            "FROM presentacion pr JOIN producto p ON p.id = pr.producto_id JOIN familia f ON f.id = p.familia_id " +
            "WHERE pr.activo = true AND p.activo = true " +
            "AND (" + BusquedaTexto.SIN_TERMINO + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + ") ";

    @Query(value = "SELECT pr.* " + FILTROS_AFECTACION_IGV + "ORDER BY f.nombre, f.id, p.nombre, p.id, pr.nombre, pr.id",
           countQuery = "SELECT count(*) " + FILTROS_AFECTACION_IGV,
           nativeQuery = true)
    Page<Presentacion> buscarParaAfectacionIgv(@Param("busqueda") String busqueda, Pageable pageable);

    @Query("SELECT pr.id FROM Presentacion pr WHERE pr.activo = true AND pr.producto.activo = true")
    List<UUID> idsActivas();

    // Buscador de la pantalla "Nueva venta" (autocompletado). Presentaciones activas de productos activos cuyo "producto presentacion"
    // o codigo CONTIENEN el termino (sin acentos ni mayusculas). Orden por cercania: 0 = el producto o el codigo EMPIEZAN con el
    // termino, 1 = alguna palabra empieza con el, 2 = solo lo contiene; dentro de cada rango, alfabetico. Los patrones llegan ya
    // plegados y con los comodines escapados (BusquedaTexto.patronLike / patronPrefijo / patronPalabra).
    // La ultima columna es ese rango (0, 1 o 2): el servicio lo usa para poner primero lo que se puede vender dentro de cada rango.
    @Query(value = "SELECT pr.id, p.id, p.nombre, pr.nombre, pr.codigo, " +
            "CASE " +
            "  WHEN " + BusquedaTexto.PLEGAR_ABRE + "p.nombre" + BusquedaTexto.PLEGAR_CIERRA + " LIKE CAST(:prefijo AS varchar) ESCAPE '!' " +
            "    OR " + BusquedaTexto.PLEGAR_ABRE + "pr.codigo" + BusquedaTexto.PLEGAR_CIERRA + " LIKE CAST(:prefijo AS varchar) ESCAPE '!' THEN 0 " +
            "  WHEN " + BusquedaTexto.PLEGAR_ABRE + "p.nombre || ' ' || pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + " LIKE CAST(:palabra AS varchar) ESCAPE '!' THEN 1 " +
            "  ELSE 2 END AS rango " +
            "FROM presentacion pr JOIN producto p ON p.id = pr.producto_id " +
            "WHERE pr.activo = true AND p.activo = true " +
            "AND (" + BusquedaTexto.PLEGAR_ABRE + "p.nombre || ' ' || pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + " LIKE CAST(:contiene AS varchar) ESCAPE '!' " +
            "     OR " + BusquedaTexto.PLEGAR_ABRE + "pr.codigo" + BusquedaTexto.PLEGAR_CIERRA + " LIKE CAST(:contiene AS varchar) ESCAPE '!') " +
            "ORDER BY rango, p.nombre, pr.nombre, pr.id " +
            "LIMIT :limite",
            nativeQuery = true)
    List<Object[]> buscarParaVenta(@Param("contiene") String contiene, @Param("prefijo") String prefijo,
                                   @Param("palabra") String palabra, @Param("limite") int limite);

    // Fase 3 (catalogo publico): presentaciones activas de productos activos que CONTROLAN vencimiento (solo esos
    // se publican), con producto y familia en la misma consulta. Orden estable para el catalogo.
    @Query("SELECT pr FROM Presentacion pr JOIN FETCH pr.producto p JOIN FETCH p.familia f "
            + "WHERE pr.activo = true AND p.activo = true AND p.controlaVencimiento = true "
            + "ORDER BY f.nombre, p.nombre, pr.nombre, pr.id")
    List<Presentacion> listarPublicables();
}
