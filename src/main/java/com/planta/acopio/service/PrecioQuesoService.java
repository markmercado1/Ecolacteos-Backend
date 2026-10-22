package com.planta.acopio.service;

import com.planta.acopio.dto.FilaPrecioPresentacionResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.PrecioModalidadResponse;
import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.dto.PrecioQuesoResponse;
import com.planta.acopio.entity.PrecioQueso;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.PrecioQuesoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

// Fase 2 (Precios por presentacion, V11__precio_presentacion.sql): serie historica append-only por
// PRESENTACION + modalidad -- un precio nuevo no reemplaza al anterior, abre una vigencia distinta. Sin
// editar/eliminar. No se exige configurar las tres modalidades: una presentacion puede tener precio para
// PUBLICO y ninguno para MAYORISTA. Nombre de clase conservado por decision explicita (reutilizar el
// servicio/DTO ya existente en vez de reconstruirlo) aunque ya no referencia Producto directamente.
@Service
public class PrecioQuesoService {

    private final PrecioQuesoRepository repository;
    private final PresentacionRepository presentacionRepository;

    public PrecioQuesoService(PrecioQuesoRepository repository, PresentacionRepository presentacionRepository) {
        this.repository = repository;
        this.presentacionRepository = presentacionRepository;
    }

    @Transactional
    public PrecioQuesoResponse crear(PrecioQuesoRequest dto) {
        Presentacion presentacion = presentacionRepository.findById(dto.presentacionId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentacion no encontrada: " + dto.presentacionId()));
        TipoClienteVenta modalidad = TipoClienteVenta.valueOf(dto.modalidad());

        if (repository.existsByPresentacionIdAndModalidadAndVigenteDesde(presentacion.getId(), modalidad, dto.vigenteDesde())) {
            throw new ConflictoNegocioException("Ya existe un precio de " + presentacion.getNombre() + " para " + modalidad
                    + " con vigencia desde " + dto.vigenteDesde());
        }

        PrecioQueso precio = new PrecioQueso();
        precio.setPresentacion(presentacion);
        precio.setModalidad(modalidad);
        precio.setPrecio(dto.precio());
        precio.setVigenteDesde(dto.vigenteDesde());

        return toResponse(repository.save(precio));
    }

    public List<PrecioQuesoResponse> listar() {
        return repository.findAllByOrderByVigenteDesdeDesc().stream().map(this::toResponse).toList();
    }

    // Fase 2 (ampliacion): historial de una sola presentacion, usado por el dialogo "Ver historico" de
    // /precios -- nunca el historico completo del catalogo para mostrar el de una sola presentacion.
    public List<PrecioQuesoResponse> listarPorPresentacion(UUID presentacionId) {
        if (!presentacionRepository.existsById(presentacionId)) {
            throw new RecursoNoEncontradoException("Presentacion no encontrada: " + presentacionId);
        }
        return repository.findByPresentacionIdOrderByVigenteDesdeDesc(presentacionId).stream().map(this::toResponse).toList();
    }

    // Fase 2 (ampliacion): listado paginado para /admin/precios-queso/presentaciones, una fila por
    // presentacion (ver PresentacionRepository.buscarFilasPrecios para el criterio exacto de filtrado,
    // orden y la fila informativa de "producto sin presentaciones"). familiaId/busqueda se aplican en el
    // WHERE de esa query nativa (antes de paginar), nunca filtrados en memoria sobre una pagina ya traida.
    // Los precios de cada presentacion de la pagina se traen en UN solo fetch en bloque (nunca el historico
    // completo del catalogo, nunca una consulta por celda) y se resuelven en memoria a VIGENTE/PROGRAMADO/
    // SIN_PRECIO con el mismo criterio que buscarVigenteOFallar, sin lanzar excepcion (esto es una vista de
    // catalogo, no una resolucion de venta).
    public PaginaResponse<FilaPrecioPresentacionResponse> listarPaginado(UUID familiaId, String busqueda, Pageable pageable) {
        Page<Object[]> filas = presentacionRepository.buscarFilasPrecios(familiaId, BusquedaTexto.patronLike(busqueda), pageable);

        List<UUID> presentacionIds = filas.getContent().stream()
                .map(fila -> (UUID) fila[4])
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, List<PrecioQueso>> preciosPorPresentacion = presentacionIds.isEmpty()
                ? Map.of()
                : repository.findByPresentacionIdIn(presentacionIds).stream()
                        .collect(Collectors.groupingBy(p -> p.getPresentacion().getId()));

        LocalDate hoy = LocalDate.now();
        Page<FilaPrecioPresentacionResponse> pagina = filas.map(fila -> {
            UUID presentacionId = (UUID) fila[4];
            List<PrecioQueso> precios = presentacionId != null
                    ? preciosPorPresentacion.getOrDefault(presentacionId, List.of())
                    : List.of();
            return new FilaPrecioPresentacionResponse(
                    (UUID) fila[0], (String) fila[1],
                    (UUID) fila[2], (String) fila[3],
                    presentacionId, (String) fila[5],
                    resumenModalidad(precios, TipoClienteVenta.MAYORISTA, hoy),
                    resumenModalidad(precios, TipoClienteVenta.PROVEEDOR, hoy),
                    resumenModalidad(precios, TipoClienteVenta.PUBLICO, hoy)
            );
        });
        return PaginaResponse.de(pagina);
    }

    private PrecioModalidadResponse resumenModalidad(List<PrecioQueso> precios, TipoClienteVenta modalidad, LocalDate hoy) {
        PrecioQueso vigente = precios.stream()
                .filter(p -> p.getModalidad() == modalidad && !p.getVigenteDesde().isAfter(hoy))
                .max(Comparator.comparing(PrecioQueso::getVigenteDesde))
                .orElse(null);
        if (vigente != null) {
            return new PrecioModalidadResponse("VIGENTE", vigente.getPrecio(), vigente.getVigenteDesde());
        }
        PrecioQueso programado = precios.stream()
                .filter(p -> p.getModalidad() == modalidad && p.getVigenteDesde().isAfter(hoy))
                .min(Comparator.comparing(PrecioQueso::getVigenteDesde))
                .orElse(null);
        if (programado != null) {
            return new PrecioModalidadResponse("PROGRAMADO", programado.getPrecio(), programado.getVigenteDesde());
        }
        return new PrecioModalidadResponse("SIN_PRECIO", null, null);
    }

    // Usado por VentaService al confirmar una venta: el backend siempre resuelve el precio, nunca confia
    // en el que envio el cliente (ver comentario en VentaService).
    public BigDecimal resolverPrecioVigente(Presentacion presentacion, TipoClienteVenta modalidad, LocalDate fecha) {
        return buscarVigenteOFallar(presentacion, modalidad, fecha).getPrecio();
    }

    // Para GET /api/ventas/precio-vigente (UX: mostrar el precio antes de confirmar la venta). El llamador
    // ya resolvio que presentacion representa al producto (ver VentaController/PresentacionService) --
    // este metodo trabaja siempre en terminos de presentacion, nunca de producto.
    public PrecioQuesoResponse resolverParaConsulta(UUID presentacionId, String tipoClienteStr, LocalDate fecha) {
        Presentacion presentacion = presentacionRepository.findById(presentacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentacion no encontrada: " + presentacionId));
        TipoClienteVenta modalidad = TipoClienteVenta.valueOf(tipoClienteStr);
        return toResponse(buscarVigenteOFallar(presentacion, modalidad, fecha));
    }

    // Distingue "nunca se configuro un precio" de "hay uno pero recien entra en vigencia despues" (RF Fase
    // 2, punto 7) -- ambos son 400 (ValidacionNegocioException, regla de negocio, no error de servidor),
    // pero con mensajes que no se confunden entre si.
    private PrecioQueso buscarVigenteOFallar(Presentacion presentacion, TipoClienteVenta modalidad, LocalDate fecha) {
        return repository.findVigente(presentacion.getId(), modalidad, fecha)
                .orElseGet(() -> {
                    var programada = repository.findPrimeraProgramada(presentacion.getId(), modalidad, fecha);
                    if (programada.isPresent()) {
                        throw new ValidacionNegocioException(
                                "El precio de " + presentacion.getNombre() + " para " + modalidad
                                        + " esta programado desde el " + programada.get().getVigenteDesde()
                                        + ", pero todavia no esta vigente al " + fecha + ".");
                    }
                    throw new ValidacionNegocioException(
                            "No hay un precio configurado para " + presentacion.getNombre() + " en modalidad " + modalidad
                                    + " vigente al " + fecha + ". Configure uno en /precios antes de vender.");
                });
    }

    private PrecioQuesoResponse toResponse(PrecioQueso p) {
        return new PrecioQuesoResponse(p.getId(), p.getPresentacion().getId(), p.getPresentacion().getNombre(),
                p.getPresentacion().getProducto().getId(), p.getPresentacion().getProducto().getNombre(),
                p.getModalidad().name(), p.getPrecio(), p.getVigenteDesde());
    }
}
