package com.planta.acopio.service;

import com.planta.acopio.dto.ProveedorCompraRequest;
import com.planta.acopio.dto.ProveedorCompraResponse;
import com.planta.acopio.entity.CategoriaProveedor;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.ProveedorCompra;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.CategoriaProveedorRepository;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.ProveedorCompraRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Proveedores de compra (V42): a quienes se compran insumos y leche. Distintos de los proveedores de leche del acopio.
 * RUC obligatorio, de 11 digitos con digito verificador valido (misma validacion que los demas documentos del sistema)
 * y unico. Clasificacion flexible en categorias; insumos habituales solo para sugerir.
 */
@Service
public class ProveedorCompraService {

    static final String CATEGORIA_POR_DEFECTO = "OTROS_INSUMOS";

    private final ProveedorCompraRepository repository;
    private final CategoriaProveedorRepository categoriaRepository;
    private final InsumoRepository insumoRepository;

    public ProveedorCompraService(ProveedorCompraRepository repository, CategoriaProveedorRepository categoriaRepository,
                                  InsumoRepository insumoRepository) {
        this.repository = repository;
        this.categoriaRepository = categoriaRepository;
        this.insumoRepository = insumoRepository;
    }

    /** Busqueda por nombre o RUC (sin mayusculas ni tildes). `soloActivos`: para el selector del ingreso. */
    @Transactional(readOnly = true)
    public List<ProveedorCompraResponse> listar(String busqueda, boolean soloActivos) {
        String clave = busqueda == null ? "" : BusquedaTexto.clave(busqueda);
        return repository.findAll().stream()
                .filter(p -> !soloActivos || Boolean.TRUE.equals(p.getActivo()))
                .filter(p -> clave.isEmpty() || BusquedaTexto.clave(p.getNombre()).contains(clave) || p.getRuc().contains(clave))
                .sorted(Comparator.comparing((ProveedorCompra p) -> BusquedaTexto.clave(p.getNombre())).thenComparing(ProveedorCompra::getRuc))
                .map(ProveedorCompraService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProveedorCompraResponse obtener(UUID id) {
        return toResponse(buscar(id));
    }

    @Transactional(readOnly = true)
    public List<ProveedorCompraResponse.Categoria> categorias() {
        return categoriaRepository.findByActivoTrueOrderByNombreAsc().stream()
                .map(c -> new ProveedorCompraResponse.Categoria(c.getId(), c.getCodigo(), c.getNombre())).toList();
    }

    @Transactional
    public ProveedorCompraResponse crear(ProveedorCompraRequest dto) {
        String ruc = rucValido(dto.ruc());
        if (repository.findByRuc(ruc).isPresent()) {
            throw rucRepetido(repository.findByRuc(ruc).get());
        }
        ProveedorCompra p = new ProveedorCompra();
        aplicarDatos(p, dto, ruc);
        List<String> codigos = dto.categorias() == null || dto.categorias().isEmpty() ? List.of(CATEGORIA_POR_DEFECTO) : dto.categorias();
        p.getCategorias().addAll(resolverCategorias(codigos));
        if (dto.insumosHabituales() != null) {
            p.getInsumosHabituales().addAll(resolverInsumos(dto.insumosHabituales()));
        }
        return toResponse(repository.save(p));
    }

    @Transactional
    public ProveedorCompraResponse actualizar(UUID id, ProveedorCompraRequest dto) {
        ProveedorCompra p = buscar(id);
        String ruc = rucValido(dto.ruc());
        repository.findByRuc(ruc).filter(otro -> !otro.getId().equals(id)).ifPresent(otro -> { throw rucRepetido(otro); });
        aplicarDatos(p, dto, ruc);
        if (dto.categorias() != null) {
            if (dto.categorias().isEmpty()) {
                throw new ValidacionNegocioException("El proveedor debe tener al menos una categoría.");
            }
            p.getCategorias().clear();
            p.getCategorias().addAll(resolverCategorias(dto.categorias()));
        }
        if (dto.insumosHabituales() != null) {
            p.getInsumosHabituales().clear();
            p.getInsumosHabituales().addAll(resolverInsumos(dto.insumosHabituales()));
        }
        return toResponse(p);
    }

    @Transactional
    public ProveedorCompraResponse desactivar(UUID id) {
        ProveedorCompra p = buscar(id);
        p.setActivo(false);
        return toResponse(p);
    }

    @Transactional
    public ProveedorCompraResponse reactivar(UUID id) {
        ProveedorCompra p = buscar(id);
        p.setActivo(true);
        return toResponse(p);
    }

    // ------------------------------------------------------------------------------------------------ apoyo

    private ProveedorCompra buscar(UUID id) {
        return repository.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + id));
    }

    private static void aplicarDatos(ProveedorCompra p, ProveedorCompraRequest dto, String ruc) {
        p.setNombre(dto.nombre().trim().replaceAll("\\s+", " "));
        p.setRuc(ruc);
        p.setTelefono(vacioANulo(dto.telefono()));
        p.setContacto(vacioANulo(dto.contacto()));
    }

    private static String vacioANulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    private static String rucValido(String crudo) {
        String ruc = DocumentoIdentidad.limpiar(crudo);
        if (ruc == null || !ruc.matches("\\d{11}")) {
            throw new ValidacionNegocioException("El RUC debe tener exactamente 11 dígitos.");
        }
        if (!DocumentoIdentidad.rucValido(ruc)) {
            throw new ValidacionNegocioException("El RUC no es válido (el dígito verificador no coincide). Revisa los 11 dígitos.");
        }
        return ruc;
    }

    private static ConflictoNegocioException rucRepetido(ProveedorCompra existente) {
        return new ConflictoNegocioException("Ya existe un proveedor con ese RUC: «" + existente.getNombre() + "»"
                + (Boolean.TRUE.equals(existente.getActivo()) ? "." : " (desactivado)."));
    }

    private Set<CategoriaProveedor> resolverCategorias(List<String> codigos) {
        Set<CategoriaProveedor> resueltas = new LinkedHashSet<>();
        for (String codigo : codigos) {
            CategoriaProveedor c = categoriaRepository.findByCodigo(codigo == null ? "" : codigo.trim().toUpperCase())
                    .orElseThrow(() -> new ValidacionNegocioException("La categoría «" + codigo + "» no existe."));
            if (!Boolean.TRUE.equals(c.getActivo())) {
                throw new ValidacionNegocioException("La categoría «" + c.getNombre() + "» está desactivada.");
            }
            resueltas.add(c);
        }
        return resueltas;
    }

    private Set<Insumo> resolverInsumos(List<UUID> ids) {
        Set<Insumo> resueltos = new LinkedHashSet<>();
        for (UUID id : ids) {
            Insumo i = insumoRepository.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id));
            if (i.estaFusionado()) {
                throw new ValidacionNegocioException("El insumo «" + i.getNombre() + "» fue fusionado en otro insumo.");
            }
            resueltos.add(i);
        }
        return resueltos;
    }

    static ProveedorCompraResponse toResponse(ProveedorCompra p) {
        return new ProveedorCompraResponse(p.getId(), p.getNombre(), p.getRuc(), p.getTelefono(), p.getContacto(), p.getActivo(),
                p.getCategorias().stream().sorted(Comparator.comparing(CategoriaProveedor::getNombre))
                        .map(c -> new ProveedorCompraResponse.Categoria(c.getId(), c.getCodigo(), c.getNombre())).toList(),
                p.getInsumosHabituales().stream().sorted(Comparator.comparing(i -> BusquedaTexto.clave(i.getNombre())))
                        .map(i -> new ProveedorCompraResponse.InsumoResumen(i.getId(), i.getNombre(), i.getUnidadMedida())).toList());
    }
}
