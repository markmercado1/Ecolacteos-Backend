package com.planta.acopio.repository;

import com.planta.acopio.entity.Insumo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsumoRepository extends JpaRepository<Insumo, UUID> {
    List<Insumo> findByActivoTrue();

    // Consumo de insumos al producir (V18, recetas): descuento ATOMICO y condicionado en una sola sentencia
    // -- el UPDATE toma el bloqueo de fila, relee el stock ya confirmado por otras transacciones (READ
    // COMMITTED) y solo descuenta si alcanza. Devuelve 0 filas si el stock no alcanza en ese instante: quien
    // llama lanza la excepcion y toda la transaccion (leche, lote, otros insumos) se revierte. Nunca deja
    // stock negativo (ademas del CHECK stock >= 0 de V17) ni descuenta dos veces la misma cantidad por una
    // lectura desactualizada de la entidad.
    @Modifying
    @Query("UPDATE Insumo i SET i.stock = i.stock - :cantidad WHERE i.id = :id AND i.stock >= :cantidad")
    int descontarStock(@Param("id") UUID id, @Param("cantidad") BigDecimal cantidad);

    // V19 (kardex): ingreso/ajuste. SELECT ... FOR UPDATE del stock ya confirmado (escalar, no la entidad:
    // una entidad Insumo ya cargada en el contexto de persistencia no se refrescaria con el bloqueo). Mientras
    // dure la transaccion, cualquier produccion/ingreso/ajuste concurrente del MISMO insumo espera aqui.
    @Query(value = "SELECT stock FROM insumo WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<BigDecimal> bloquearStock(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Insumo i SET i.stock = :stock WHERE i.id = :id")
    int fijarStock(@Param("id") UUID id, @Param("stock") BigDecimal stock);

    // Lectura escalar del stock confirmado (no la de una entidad posiblemente desactualizada).
    @Query("SELECT i.stock FROM Insumo i WHERE i.id = :id")
    Optional<BigDecimal> stockActual(@Param("id") UUID id);

    // Inventario de insumos: activos, mas inactivos que todavia conservan stock (no se pierde de vista una
    // existencia real por desactivar el catalogo).
    @Query("SELECT i FROM Insumo i WHERE i.activo = true OR i.stock <> 0 ORDER BY i.nombre")
    List<Insumo> listarParaInventario();
}
