package com.planta.acopio.service;

import com.planta.acopio.dto.ProductoRequest;
import com.planta.acopio.dto.ProductoResponse;
import com.planta.acopio.dto.RangoRendimientoDto;
import com.planta.acopio.entity.CicloCapital;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

// Fase 1 (Producto + Presentacion): generaliza TipoQuesoService. rendimientoEsperadoPct/cicloCapital dejan
// de ser obligatorios -- son atributos de produccion de queso especificamente, no comerciales -- un
// producto de otra familia se crea sin ellos. Crear un producto NO genera presentaciones, stock ni precio
// (operaciones distintas, ver PresentacionService/Precios/Inventario).
@Service
public class ProductoService {

    private final ProductoRepository repository;
    private final FamiliaRepository familiaRepository;
    private final PresentacionRepository presentacionRepository;
    private final FotoCatalogoService fotos;

    public ProductoService(ProductoRepository repository, FamiliaRepository familiaRepository,
                            PresentacionRepository presentacionRepository, FotoCatalogoService fotos) {
        this.repository = repository;
        this.familiaRepository = familiaRepository;
        this.presentacionRepository = presentacionRepository;
        this.fotos = fotos;
    }

    @Transactional
    public ProductoResponse crear(ProductoRequest dto) {
        Familia familia = familiaRepository.findById(dto.familiaId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Familia no encontrada: " + dto.familiaId()));
        String nombre = dto.nombre().trim();
        if (repository.existsByNombreIgnoreCase(nombre)) {
            throw conflictoDeNombre(nombre);
        }
        Producto p = new Producto();
        p.setNombre(nombre);
        p.setFamilia(familia);
        p.setRendimientoEsperadoPct(dto.rendimientoEsperadoPct());
        p.setRangoRendimiento(dto.rangoRendimiento() == null ? null : dto.rangoRendimiento().toEntity());
        p.setCicloCapital(dto.cicloCapital() != null ? CicloCapital.valueOf(dto.cicloCapital()) : null);
        return toResponse(repository.save(p));
    }

    @Transactional(readOnly = true)
    public List<ProductoResponse> listarActivos() {
        return repository.findByActivoTrue(OrdenCatalogo.POR_NOMBRE).stream().map(this::toResponse).toList();
    }

    @Transactional
    public ProductoResponse actualizar(UUID id, ProductoRequest dto) {
        Producto p = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + id));
        Familia familia = familiaRepository.findById(dto.familiaId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Familia no encontrada: " + dto.familiaId()));
        String nombre = dto.nombre().trim();
        if (repository.existsByNombreIgnoreCaseAndIdNot(nombre, id)) {
            throw conflictoDeNombre(nombre);
        }
        p.setNombre(nombre);
        p.setFamilia(familia);
        p.setRendimientoEsperadoPct(dto.rendimientoEsperadoPct());
        p.setRangoRendimiento(dto.rangoRendimiento() == null ? null : dto.rangoRendimiento().toEntity());
        p.setCicloCapital(dto.cicloCapital() != null ? CicloCapital.valueOf(dto.cicloCapital()) : null);
        return toResponse(p);
    }

    @Transactional
    public void desactivar(UUID id) {
        Producto p = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + id));
        p.setActivo(false);
    }

    @Transactional
    public ProductoResponse reactivar(UUID id) {
        Producto p = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + id));
        p.setActivo(true);
        return toResponse(p);
    }

    // El nombre es UNIQUE en BD aunque el producto este desactivado (y el panel no lista los inactivos): el
    // mensaje lo aclara para que el usuario sepa por que no puede reutilizarlo.
    private ConflictoNegocioException conflictoDeNombre(String nombre) {
        return new ConflictoNegocioException("Ya existe un producto llamado \"" + nombre
                + "\" (puede estar desactivado). Usa otro nombre o reactiva el existente.");
    }

    // La foto vieja se borra del disco solo si la transaccion se confirma; la nueva, solo si se revierte.
    @Transactional
    public ProductoResponse reemplazarFoto(UUID id, MultipartFile file) {
        Producto p = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + id));
        String nueva = fotos.guardar(file);
        fotos.eliminarAlRevertir(nueva);
        fotos.eliminarAlConfirmar(p.getFotoArchivo());
        p.setFotoArchivo(nueva);
        return toResponse(p);
    }

    @Transactional
    public ProductoResponse quitarFoto(UUID id) {
        Producto p = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Producto no encontrado: " + id));
        fotos.eliminarAlConfirmar(p.getFotoArchivo());
        p.setFotoArchivo(null);
        return toResponse(p);
    }

    private ProductoResponse toResponse(Producto p) {
        var presentaciones = presentacionRepository.findByProductoIdOrderByNombre(p.getId()).stream()
                .map(pr -> new com.planta.acopio.dto.PresentacionResponse(pr.getId(), pr.getProducto().getId(),
                        pr.getNombre(), pr.getContenidoCantidad(),
                        pr.getContenidoUnidad() != null ? pr.getContenidoUnidad().name() : null,
                        pr.getUnidadConteo(), pr.getActivo(), FotoCatalogoService.urlDe(pr.getFotoArchivo())))
                .toList();
        return new ProductoResponse(p.getId(), p.getNombre(), p.getFamilia().getId(), p.getFamilia().getNombre(),
                p.getRendimientoEsperadoPct(), p.getCicloCapital() != null ? p.getCicloCapital().name() : null,
                p.getActivo(), presentaciones, RangoRendimientoDto.from(p.getRangoRendimiento()),
                FotoCatalogoService.urlDe(p.getFotoArchivo()));
    }
}
