package com.planta.acopio.repository;

import com.planta.acopio.entity.Venta;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
    @Query(value = "SELECT * FROM venta WHERE " +
           "(CAST(:fecha AS date) IS NULL OR fecha = CAST(:fecha AS date)) AND " +
           "(CAST(:tipoCliente AS varchar) IS NULL OR tipo_cliente = CAST(:tipoCliente AS varchar)) " +
           "ORDER BY fecha DESC, id DESC",
           countQuery = "SELECT count(*) FROM venta WHERE " +
           "(CAST(:fecha AS date) IS NULL OR fecha = CAST(:fecha AS date)) AND " +
           "(CAST(:tipoCliente AS varchar) IS NULL OR tipo_cliente = CAST(:tipoCliente AS varchar))",
           nativeQuery = true)
    Page<Venta> buscarConFiltros(@Param("fecha") LocalDate fecha, @Param("tipoCliente") String tipoCliente, Pageable pageable);

    // Usado por PanelEconomicoService (RF-INN-05, KPI de margen por canal).
    // LIMITACION DOCUMENTADA (CORE_DESIGN.md): la tabla venta no tiene relacion
    // con zona en el SQL aprobado (no referencia proveedor ni unidad), por lo
    // que este margen es GLOBAL de planta, no filtrable por zona. No se agrega
    // una columna de zona a venta porque no fue parte de las mejoras aprobadas.
    @Query("SELECT v.tipoCliente, COALESCE(SUM(v.total), 0) FROM Venta v GROUP BY v.tipoCliente")
    List<Object[]> margenAgrupadoPorTipoCliente();
}
