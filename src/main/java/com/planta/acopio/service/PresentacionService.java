package com.planta.acopio.service;

import com.planta.acopio.dto.PresentacionRequest;
import com.planta.acopio.dto.PresentacionResponse;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.UnidadContenido;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

// Fase 1 (Producto + Presentacion): se administra DENTRO de la ficha del producto (no es un modulo propio),
// por eso todo metodo recibe productoId. Crear una presentacion NO genera stock ni precio -- ambos siguen
// siendo operaciones distintas (ver Precios/Inventario), y en esta fase ninguna otra tabla referencia
// todavia presentacion_id (esa reconexion es una fase posterior, fuera de alcance aqui).
@Service
public class PresentacionService {

    private final PresentacionRepository repository;
    private final ProductoRepository productoRepository;
    private final FotoCatalogoService fotos;

    public PresentacionService(PresentacionRepository repository, ProductoRepository productoRepository,
                                FotoCatalogoService fotos) {
        this.repository = repository;
        this.productoRepository = productoRepository;
        this.fotos = fotos;
    }

    @Transactional
    public PresentacionResponse crear(UUID productoId, PresentacionRequest dto) {
        Producto producto = productoRepository.findById(productoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + productoId));
        validarContenido(dto);
        String nombre = dto.nombre().trim();
        if (repository.existsByProductoIdAndNombre(productoId, nombre)) {
            throw new ConflictoNegocioException(
                    "Ya existe una presentacion \"" + nombre + "\" para " + producto.getNombre());
        }

        Presentacion p = new Presentacion();
        p.setProducto(producto);
        aplicar(p, dto);
        Presentacion guardada = repository.save(p);
        // Fase 1 (control de vencimiento): una presentacion nueva de un producto controlado nace sin stock, es
        // decir con saldo inicial 0 (sin existencias "sin trazabilidad").
        if (productoRepository.controlaVencimiento(productoId).orElse(false)) {
            guardada.setSaldoSinTrazabilidadInicial(0);
        }
        return toResponse(guardada);
    }

    public List<PresentacionResponse> listarPorProducto(UUID productoId) {
        return repository.findByProductoIdOrderByNombre(productoId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PresentacionResponse actualizar(UUID productoId, UUID presentacionId, PresentacionRequest dto) {
        Presentacion p = obtenerDeProducto(productoId, presentacionId);
        validarContenido(dto);
        aplicar(p, dto);
        return toResponse(p);
    }

    @Transactional
    public void desactivar(UUID productoId, UUID presentacionId) {
        Presentacion p = obtenerDeProducto(productoId, presentacionId);
        p.setActivo(false);
    }

    @Transactional
    public PresentacionResponse reactivar(UUID productoId, UUID presentacionId) {
        Presentacion p = obtenerDeProducto(productoId, presentacionId);
        p.setActivo(true);
        return toResponse(p);
    }

    // Misma politica que ProductoService.reemplazarFoto: sin huerfanos ni fotos colgando tras un rollback.
    @Transactional
    public PresentacionResponse reemplazarFoto(UUID productoId, UUID presentacionId, MultipartFile file) {
        Presentacion p = obtenerDeProducto(productoId, presentacionId);
        String nueva = fotos.guardar(file);
        fotos.eliminarAlRevertir(nueva);
        fotos.eliminarAlConfirmar(p.getFotoArchivo());
        p.setFotoArchivo(nueva);
        return toResponse(p);
    }

    @Transactional
    public PresentacionResponse quitarFoto(UUID productoId, UUID presentacionId) {
        Presentacion p = obtenerDeProducto(productoId, presentacionId);
        fotos.eliminarAlConfirmar(p.getFotoArchivo());
        p.setFotoArchivo(null);
        return toResponse(p);
    }

    private Presentacion obtenerDeProducto(UUID productoId, UUID presentacionId) {
        Presentacion p = repository.findById(presentacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentacion no encontrada: " + presentacionId));
        if (!p.getProducto().getId().equals(productoId)) {
            throw new RecursoNoEncontradoException(
                    "La presentacion " + presentacionId + " no pertenece al producto " + productoId);
        }
        return p;
    }

    // Regla cruzada que las anotaciones del DTO no pueden expresar: contenido y unidad vienen juntos o ninguno
    // (una cantidad sin unidad, o al reves, es un dato incompleto que luego rompe el calculo de rendimiento).
    private void validarContenido(PresentacionRequest dto) {
        boolean hayCantidad = dto.contenidoCantidad() != null;
        boolean hayUnidad = dto.contenidoUnidad() != null;
        if (hayCantidad != hayUnidad) {
            throw new ValidacionNegocioException(hayCantidad
                    ? "Indica la unidad del contenido (ML, L, G o KG) o deja vacío el contenido."
                    : "Indica la cantidad del contenido o deja sin unidad.");
        }
    }

    private void aplicar(Presentacion p, PresentacionRequest dto) {
        p.setNombre(dto.nombre().trim());
        p.setContenidoCantidad(dto.contenidoCantidad());
        p.setContenidoUnidad(dto.contenidoUnidad() != null ? UnidadContenido.valueOf(dto.contenidoUnidad()) : null);
        p.setUnidadConteo(dto.unidadConteo().trim());
    }

    private PresentacionResponse toResponse(Presentacion p) {
        return new PresentacionResponse(p.getId(), p.getProducto().getId(), p.getNombre(),
                p.getContenidoCantidad(), p.getContenidoUnidad() != null ? p.getContenidoUnidad().name() : null,
                p.getUnidadConteo(), p.getActivo(), FotoCatalogoService.urlDe(p.getFotoArchivo()));
    }
}
