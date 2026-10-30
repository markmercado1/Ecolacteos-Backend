package com.planta.acopio.repository;

import com.planta.acopio.entity.Venta;
import com.planta.acopio.service.BusquedaTexto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VentaRepository extends JpaRepository<Venta, UUID> {
    Optional<Venta> findByUuidCliente(String uuidCliente);

    // Auditoria CRUD (prioridad ALTA): el modulo no tenia ningun listado general.
    List<Venta> findAllByOrderByFechaDesc();

    // Bloque 5 (confirmado): filtros opcionales combinables, aditivos -- sin
    // parametros se comporta igual que findAllByOrderByFechaDesc (no rompe
    // al frontend actual, que no manda estos query params).
    // Verificacion en caliente (bloque 12): la version JPQL equivalente
    // fallaba en runtime con SQLState 42P18 cuando `fecha` llegaba no-nulo
    // (PostgreSQL no podia inferir el tipo del parametro LocalDate/date en
    // el patron `:fecha IS NULL OR v.fecha = :fecha`). Nativa con casts
    // explicitos, probada contra Postgres real. tipoCliente se recibe como
    // String (ya validado como enum legal en el service antes de llegar aqui).
    // Paginacion real (confirmado): countQuery explicito obligatorio para
    // queries nativas -- Spring Data no puede derivarlo solo de SQL nativo
    // (si lo puede para JPQL). El ORDER BY queda fijo en la query (no se usa
    // el Sort de Pageable) para no depender de que Spring reescriba SQL
    // nativo con un ORDER BY dinamico, que es fragil.
    //
    // Busqueda de texto: nombre del vendedor, o nombre del producto o de la presentacion de cualquiera de sus lineas
    // (sin acentos ni mayusculas), sobre TODAS las ventas del filtro, antes de paginar. Orden: fecha mas reciente
    // primero y, dentro del mismo dia, la venta registrada mas recientemente primero (creado_en, V25); el id (UUID
    // aleatorio) solo desempata lo que queda igual.
    String FILTROS_VENTA =
            "FROM venta v LEFT JOIN usuario u ON u.id = v.vendedor_id WHERE " +
            "(CAST(:desde AS date) IS NULL OR v.fecha >= CAST(:desde AS date)) AND " +
            "(CAST(:hasta AS date) IS NULL OR v.fecha <= CAST(:hasta AS date)) AND " +
            "(CAST(:tipoCliente AS varchar) IS NULL OR v.tipo_cliente = CAST(:tipoCliente AS varchar)) AND " +
            "(" + BusquedaTexto.SIN_TERMINO + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "u.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "coalesce(v.comprador_nombre, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "v.serie || '-' || LPAD(CAST(v.numero AS varchar), 8, '0')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            "EXISTS (SELECT 1 FROM venta_detalle vd JOIN presentacion pr ON pr.id = vd.presentacion_id " +
            "JOIN producto pd ON pd.id = pr.producto_id WHERE vd.venta_id = v.id AND (" +
            BusquedaTexto.PLEGAR_ABRE + "pd.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR " +
            BusquedaTexto.PLEGAR_ABRE + "pr.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + "))) ";

    @Query(value = "SELECT v.* " + FILTROS_VENTA + "ORDER BY v.fecha DESC, v.creado_en DESC, v.id DESC",
           countQuery = "SELECT count(*) " + FILTROS_VENTA,
           nativeQuery = true)
    Page<Venta> buscarConFiltros(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta, @Param("tipoCliente") String tipoCliente,
                                 @Param("busqueda") String busqueda, Pageable pageable);

    // Totales de las ventas que cumplen el mismo filtro que buscarConFiltros: [cantidad, suma del total].
    @Query(value = "SELECT count(*), COALESCE(SUM(v.total), 0) " + FILTROS_VENTA, nativeQuery = true)
    List<Object[]> resumenConFiltros(@Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta, @Param("tipoCliente") String tipoCliente,
                                     @Param("busqueda") String busqueda);

    // Usado por PanelEconomicoService (RF-INN-05, KPI de margen por canal).
    // LIMITACION DOCUMENTADA (CORE_DESIGN.md): la tabla venta no tiene relacion
    // con zona en el SQL aprobado (no referencia proveedor ni unidad), por lo
    // que este margen es GLOBAL de planta, no filtrable por zona. No se agrega
    // una columna de zona a venta porque no fue parte de las mejoras aprobadas.
    @Query("SELECT v.tipoCliente, COALESCE(SUM(v.total), 0) FROM Venta v GROUP BY v.tipoCliente")
    List<Object[]> margenAgrupadoPorTipoCliente();

    // ------------------------------------------------------------------ Fase H: compras de un comprador identificado

    Page<Venta> findByClienteIdOrderByFechaDescIdDesc(UUID clienteId, Pageable pageable);

    Page<Venta> findByProveedorIdOrderByFechaDescIdDesc(UUID proveedorId, Pageable pageable);

    /** [cantidad, monto, ultima fecha, cuantas vienen de una reserva] de las ventas asociadas a la ficha de cliente. */
    @Query("SELECT count(v), coalesce(sum(v.total), 0), max(v.fecha), coalesce(sum(CASE WHEN v.reservaId IS NOT NULL THEN 1 ELSE 0 END), 0) "
            + "FROM Venta v WHERE v.clienteId = :id AND v.fecha BETWEEN :desde AND :hasta")
    List<Object[]> resumenDeCliente(@Param("id") UUID clienteId, @Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta);

    @Query("SELECT count(v), coalesce(sum(v.total), 0), max(v.fecha), coalesce(sum(CASE WHEN v.reservaId IS NOT NULL THEN 1 ELSE 0 END), 0) "
            + "FROM Venta v WHERE v.proveedorId = :id AND v.fecha BETWEEN :desde AND :hasta")
    List<Object[]> resumenDeProveedor(@Param("id") UUID proveedorId, @Param("desde") LocalDate desde, @Param("hasta") LocalDate hasta);

    /** Fusion manual de fichas: las ventas del origen pasan al destino (la copia historica del nombre no cambia). */
    @Modifying
    @Query("UPDATE Venta v SET v.clienteId = :destino WHERE v.clienteId = :origen")
    int moverCliente(@Param("origen") UUID origen, @Param("destino") UUID destino);

    Optional<Venta> findByReservaId(UUID reservaId);

    // V48: siguiente numero de cada serie (secuencias de la BD: dos ventas concurrentes nunca reciben el mismo numero).
    @Query(value = "SELECT nextval('venta_nv01_seq')", nativeQuery = true)
    long siguienteNumeroNotaVenta();

    @Query(value = "SELECT nextval('venta_b001_seq')", nativeQuery = true)
    long siguienteNumeroBoleta();
}
