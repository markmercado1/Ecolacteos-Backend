package com.planta.acopio.repository;

import com.planta.acopio.entity.PrecioQueso;
import com.planta.acopio.entity.TipoClienteVenta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrecioQuesoRepository extends JpaRepository<PrecioQueso, UUID> {

    // Mismo criterio que PrecioLitroRepository.findVigente: el precio vigente es el de vigente_desde
    // mas reciente <= la fecha dada, ahora filtrado por presentacion + modalidad (Fase 2).
    @Query("SELECT p FROM PrecioQueso p WHERE p.presentacion.id = :presentacionId AND p.modalidad = :modalidad "
            + "AND p.vigenteDesde <= :fecha ORDER BY p.vigenteDesde DESC LIMIT 1")
    Optional<PrecioQueso> findVigente(@Param("presentacionId") UUID presentacionId,
                                       @Param("modalidad") TipoClienteVenta modalidad,
                                       @Param("fecha") LocalDate fecha);

    // Distingue "nunca hubo precio" de "hay uno pero todavia no entra en vigencia" (RF Fase 2, punto 7):
    // la primera vigencia programada DESPUES de la fecha consultada, si existe.
    @Query("SELECT p FROM PrecioQueso p WHERE p.presentacion.id = :presentacionId AND p.modalidad = :modalidad "
            + "AND p.vigenteDesde > :fecha ORDER BY p.vigenteDesde ASC LIMIT 1")
    Optional<PrecioQueso> findPrimeraProgramada(@Param("presentacionId") UUID presentacionId,
                                                  @Param("modalidad") TipoClienteVenta modalidad,
                                                  @Param("fecha") LocalDate fecha);

    boolean existsByPresentacionIdAndModalidadAndVigenteDesde(UUID presentacionId, TipoClienteVenta modalidad, LocalDate vigenteDesde);

    List<PrecioQueso> findAllByOrderByVigenteDesdeDesc();

    // Fase 2 (ampliacion): historial de UNA presentacion (dialogo "Ver historico" de /precios). Evita traer
    // el historico completo del catalogo cuando solo hace falta el de una presentacion puntual.
    List<PrecioQueso> findByPresentacionIdOrderByVigenteDesdeDesc(UUID presentacionId);

    // Fase 2 (ampliacion: listado paginado de precios por presentacion). Fetch en bloque, UNA sola consulta
    // para todas las presentaciones de la pagina actual (nunca el historico completo de todo el catalogo,
    // ni una consulta por celda/presentacion): PrecioQuesoService.listarPaginado agrupa el resultado en
    // memoria por presentacion+modalidad para resolver vigente/programado/sin-precio de cada una.
    List<PrecioQueso> findByPresentacionIdIn(List<UUID> presentacionIds);

    // Fase 3 (catalogo publico): en UNA consulta, los precios de una modalidad ya vigentes a una fecha, para
    // varias presentaciones; el llamador se queda con el de vigente_desde mas reciente por presentacion (mismo
    // criterio que findVigente). Evita una consulta por presentacion en un endpoint publico.
    @Query("SELECT p FROM PrecioQueso p WHERE p.presentacion.id IN :presentacionIds AND p.modalidad = :modalidad "
            + "AND p.vigenteDesde <= :fecha")
    List<PrecioQueso> findVigentesHasta(@Param("presentacionIds") List<UUID> presentacionIds,
                                         @Param("modalidad") TipoClienteVenta modalidad,
                                         @Param("fecha") LocalDate fecha);
}
