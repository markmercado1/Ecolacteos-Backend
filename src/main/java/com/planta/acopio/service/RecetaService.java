package com.planta.acopio.service;

import com.planta.acopio.dto.DisponibilidadProduccionResponse;
import com.planta.acopio.dto.DisponibilidadProduccionResponse.Linea;
import com.planta.acopio.dto.RecetaInsumoRequest;
import com.planta.acopio.dto.RecetaRequest;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Receta;
import com.planta.acopio.entity.RecetaInsumo;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RecetaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Recetas de produccion (V18). Catalogo administrable (mismo patron crear/listar/obtener/actualizar/
// desactivar/reactivar que Producto/Insumo) mas el calculo proporcional contra el stock actual, que es el
// unico punto donde se escala una receta para mostrar (ver CalculoReceta).
@Service
public class RecetaService {

    private final RecetaRepository repository;
    private final ProductoRepository productoRepository;
    private final PresentacionRepository presentacionRepository;
    private final InsumoRepository insumoRepository;
    private final InventarioLecheService inventarioLecheService;

    public RecetaService(RecetaRepository repository, ProductoRepository productoRepository,
                         PresentacionRepository presentacionRepository, InsumoRepository insumoRepository,
                         InventarioLecheService inventarioLecheService) {
        this.repository = repository;
        this.productoRepository = productoRepository;
        this.presentacionRepository = presentacionRepository;
        this.insumoRepository = insumoRepository;
        this.inventarioLecheService = inventarioLecheService;
    }

    @Transactional
    public RecetaResponse crear(RecetaRequest dto) {
        Producto producto = productoRepository.findById(dto.productoId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + dto.productoId()));
        if (!Boolean.TRUE.equals(producto.getActivo())) {
            throw new RecursoInactivoException("El producto '" + producto.getNombre() + "' está desactivado");
        }
        Presentacion presentacion = presentacionRepository.findById(dto.presentacionId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada: " + dto.presentacionId()));
        if (!presentacion.getProducto().getId().equals(producto.getId())) {
            throw new ValidacionNegocioException("La presentación '" + presentacion.getNombre()
                    + "' no pertenece al producto '" + producto.getNombre() + "'.");
        }
        if (!Boolean.TRUE.equals(presentacion.getActivo())) {
            throw new RecursoInactivoException("La presentación '" + presentacion.getNombre() + "' está desactivada");
        }
        String nombre = dto.nombre().trim();
        if (repository.existsByProductoIdAndNombreIgnoreCase(producto.getId(), nombre)) {
            throw new ConflictoNegocioException("Ya existe una receta '" + nombre + "' para " + producto.getNombre() + ".");
        }

        Receta receta = new Receta();
        receta.setNombre(nombre);
        receta.setProducto(producto);
        receta.setPresentacion(presentacion);
        receta.setLitrosBase(dto.litrosBase());
        receta.setRendimientoBase(dto.rendimientoBase());
        reemplazarInsumos(receta, dto.insumos());
        return toResponse(repository.save(receta));
    }

    @Transactional(readOnly = true)
    public List<RecetaResponse> listar(boolean incluirInactivas) {
        List<Receta> recetas = incluirInactivas
                ? repository.findAllByOrderByNombreAsc()
                : repository.findByActivoTrueOrderByNombreAsc();
        return recetas.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RecetaResponse obtener(UUID id) {
        return toResponse(buscar(id));
    }

    // Editables: nombre, litrosBase, rendimientoBase e insumos. Producto y presentacion de salida NO: un lote
    // planificado conserva su receta_id y debe seguir resolviendo la misma salida -- para fabricar otra cosa
    // se crea otra receta. Los lotes ya planificados no cambian (su plan es un snapshot).
    @Transactional
    public RecetaResponse actualizar(UUID id, RecetaRequest dto) {
        Receta receta = buscar(id);
        if (!receta.getProducto().getId().equals(dto.productoId())
                || !receta.getPresentacion().getId().equals(dto.presentacionId())) {
            throw new ValidacionNegocioException("El producto y la presentación de una receta no se pueden cambiar. "
                    + "Crea una receta nueva para otro producto o presentación.");
        }
        String nombre = dto.nombre().trim();
        if (repository.existsByProductoIdAndNombreIgnoreCaseAndIdNot(receta.getProducto().getId(), nombre, id)) {
            throw new ConflictoNegocioException("Ya existe una receta '" + nombre + "' para "
                    + receta.getProducto().getNombre() + ".");
        }
        receta.setNombre(nombre);
        receta.setLitrosBase(dto.litrosBase());
        receta.setRendimientoBase(dto.rendimientoBase());
        receta.setActualizadoEn(LocalDateTime.now());
        reemplazarInsumos(receta, dto.insumos());
        return toResponse(repository.saveAndFlush(receta));
    }

    @Transactional
    public void desactivar(UUID id) {
        buscar(id).setActivo(false);
    }

    @Transactional
    public RecetaResponse reactivar(UUID id) {
        Receta receta = buscar(id);
        receta.setActivo(true);
        return toResponse(receta);
    }

    /**
     * Vista previa de una produccion de `litros` con esta receta contra el stock ACTUAL (sin bloqueo): factor,
     * insumos escalados, salida esperada, estado por linea y, si algo falta, los litros maximos producibles.
     * No modifica nada y nunca ajusta la cantidad solicitada.
     */
    @Transactional(readOnly = true)
    public DisponibilidadProduccionResponse calcular(UUID id, BigDecimal litros) {
        if (litros == null || litros.signum() <= 0) {
            throw new ValidacionNegocioException("Indica una cantidad de leche mayor que 0.");
        }
        Receta receta = buscar(id);
        BigDecimal lecheDisponible = inventarioLecheService.obtenerSaldoDisponibleLitros();
        Linea leche = CalculoReceta.lineaLeche(litros, lecheDisponible);
        List<Linea> insumos = CalculoReceta.lineasInsumos(CalculoReceta.requerimientos(receta, litros));
        BigDecimal litrosMaximos = CalculoReceta.litrosMaximos(receta, lecheDisponible);
        return CalculoReceta.respuesta(litros, CalculoReceta.factor(litros, receta.getLitrosBase()), leche, insumos,
                CalculoReceta.cantidadEsperada(receta, litros), receta.getPresentacion().getNombre(),
                receta.getPresentacion().getUnidadConteo(), litrosMaximos,
                CalculoReceta.cantidadEsperadaEntera(receta, litrosMaximos));
    }

    Receta buscar(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Receta no encontrada: " + id));
    }

    // Reemplazo completo (no parche): insumos existentes y activos, sin repetir. La lista vacia es valida
    // (receta solo de leche, ej. leche pasteurizada). Las filas de un insumo que se conserva se ACTUALIZAN en
    // su lugar (no delete+insert): Hibernate ejecuta los INSERT antes que los DELETE de huerfanos al hacer
    // flush, y reinsertar el mismo insumo violaria UNIQUE(receta_id, insumo_id).
    private void reemplazarInsumos(Receta receta, List<RecetaInsumoRequest> filas) {
        Map<UUID, RecetaInsumo> actuales = new HashMap<>();
        for (RecetaInsumo ri : receta.getInsumos()) actuales.put(ri.getInsumo().getId(), ri);
        Set<UUID> vistos = new HashSet<>();
        List<RecetaInsumo> resultado = new ArrayList<>();
        for (RecetaInsumoRequest fila : filas) {
            if (fila.cantidad() == null || fila.cantidad().signum() <= 0) {
                throw new ValidacionNegocioException("La cantidad de cada insumo de la receta debe ser mayor que 0.");
            }
            if (!vistos.add(fila.insumoId())) {
                throw new ValidacionNegocioException("Un insumo aparece más de una vez en la receta.");
            }
            Insumo insumo = insumoRepository.findById(fila.insumoId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + fila.insumoId()));
            if (!Boolean.TRUE.equals(insumo.getActivo())) {
                throw new RecursoInactivoException("El insumo '" + insumo.getNombre() + "' está desactivado");
            }
            RecetaInsumo ri = actuales.get(insumo.getId());
            if (ri == null) {
                ri = new RecetaInsumo();
                ri.setReceta(receta);
                ri.setInsumo(insumo);
            }
            ri.setCantidad(fila.cantidad());
            resultado.add(ri);
        }
        receta.getInsumos().retainAll(resultado);
        for (RecetaInsumo ri : resultado) {
            if (!receta.getInsumos().contains(ri)) receta.getInsumos().add(ri);
        }
    }

    RecetaResponse toResponse(Receta r) {
        List<RecetaResponse.Insumo> insumos = r.getInsumos().stream()
                .map(ri -> new RecetaResponse.Insumo(ri.getInsumo().getId(), ri.getInsumo().getNombre(),
                        ri.getInsumo().getUnidadMedida(), ri.getCantidad(), ri.getInsumo().getActivo()))
                .toList();
        Presentacion p = r.getPresentacion();
        return new RecetaResponse(r.getId(), r.getNombre(), r.getProducto().getId(), r.getProducto().getNombre(),
                p.getId(), p.getNombre(), p.getUnidadConteo(), r.getLitrosBase(), r.getRendimientoBase(),
                r.getActivo(), insumos);
    }
}
