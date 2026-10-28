package com.planta.acopio.service;

import com.planta.acopio.dto.TipoPrecioDtos.CrearTipoPrecioRequest;
import com.planta.acopio.dto.TipoPrecioDtos.EditarTipoPrecioRequest;
import com.planta.acopio.dto.TipoPrecioDtos.TipoPrecioResponse;
import com.planta.acopio.dto.TipoPrecioDtos.UltimoCambioResponse;
import com.planta.acopio.entity.CambioTipoPrecio;
import com.planta.acopio.entity.TipoPrecio;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.CambioTipoPrecioRepository;
import com.planta.acopio.repository.TipoPrecioRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

// Tipos de precio dinamicos: crear, editar (nombre y descripcion), activar y desactivar. Un tipo desactivado no se acepta en precios ni ventas
// NUEVOS; lo ya registrado no cambia. El codigo es el identificador estable (nunca se edita). Solo se impide desactivar el ultimo tipo activo.
@Service
public class TipoPrecioService {

    private static final Pattern CODIGO = Pattern.compile("^[A-Z][A-Z0-9_]{1,14}$");

    private final TipoPrecioRepository repository;
    private final CambioTipoPrecioRepository cambios;
    private final UsuarioRepository usuarios;

    public TipoPrecioService(TipoPrecioRepository repository, CambioTipoPrecioRepository cambios, UsuarioRepository usuarios) {
        this.repository = repository;
        this.cambios = cambios;
        this.usuarios = usuarios;
    }

    @Transactional(readOnly = true)
    public List<TipoPrecioResponse> listar(boolean soloActivos) {
        return repository.findAllByOrderByCodigoAsc().stream().filter(t -> !soloActivos || t.isActivo()).map(this::toResponse).toList();
    }

    @Transactional
    public TipoPrecioResponse crear(CrearTipoPrecioRequest dto) {
        String motivo = motivo(dto.motivo());
        String nombre = dto.nombre().trim();
        if (nombre.isEmpty()) throw new ValidacionNegocioException("El nombre es obligatorio.");
        String codigo = dto.codigo() == null || dto.codigo().isBlank() ? derivarCodigo(nombre) : normalizarCodigo(dto.codigo());
        if (!CODIGO.matcher(codigo).matches()) {
            throw new ValidacionNegocioException("El código debe empezar con una letra y tener de 2 a 15 caracteres: letras, números o guion bajo.");
        }
        if (repository.existsById(codigo)) throw new ConflictoNegocioException("Ya existe un tipo de precio con el código " + codigo + ".");
        if (repository.existsByNombreIgnoreCase(nombre)) throw new ConflictoNegocioException("Ya existe un tipo de precio llamado «" + nombre + "».");
        TipoPrecio t = new TipoPrecio();
        t.setCodigo(codigo);
        t.setNombre(nombre);
        t.setDescripcion(dto.descripcion() == null ? "" : dto.descripcion().trim());
        t.setReglaComprador(dto.reglaComprador());
        t.setActivo(true);
        repository.save(t);
        registrar(t, "CREACION", motivo);
        return toResponse(t);
    }

    @Transactional
    public TipoPrecioResponse editar(String codigo, EditarTipoPrecioRequest dto) {
        String motivo = motivo(dto.motivo());
        TipoPrecio t = buscar(codigo);
        String nombre = dto.nombre().trim();
        if (nombre.isEmpty()) throw new ValidacionNegocioException("El nombre es obligatorio.");
        if (!nombre.equalsIgnoreCase(t.getNombre()) && repository.existsByNombreIgnoreCase(nombre)) {
            throw new ConflictoNegocioException("Ya existe un tipo de precio llamado «" + nombre + "».");
        }
        t.setNombre(nombre);
        t.setDescripcion(dto.descripcion() == null ? "" : dto.descripcion().trim());
        repository.save(t);
        registrar(t, "EDICION", motivo);
        return toResponse(t);
    }

    @Transactional
    public TipoPrecioResponse cambiarEstado(String codigo, boolean activo, String motivoTexto) {
        String m = motivo(motivoTexto);
        TipoPrecio tipo = buscar(codigo);
        if (!activo && tipo.isActivo() && repository.countByActivoTrue() <= 1) {
            throw new ValidacionNegocioException("No se puede desactivar el último tipo de precio activo: no quedaría ninguno para vender.");
        }
        if (tipo.isActivo() == activo) {
            throw new ConflictoNegocioException("El tipo " + tipo.getNombre() + " ya está " + (activo ? "activo" : "desactivado") + ".");
        }
        tipo.setActivo(activo);
        repository.save(tipo);
        registrar(tipo, "ESTADO", m);
        return toResponse(tipo);
    }

    /** Para operaciones NUEVAS (precio o venta): el tipo debe existir y estar activo. Devuelve el tipo para aplicar su regla de comprador. */
    @Transactional(readOnly = true)
    public TipoPrecio exigirActivo(String codigo) {
        TipoPrecio t = obtener(codigo);
        if (!t.isActivo()) {
            throw new ValidacionNegocioException("El tipo de precio " + t.getNombre() + " está desactivado: no se puede usar en operaciones nuevas.");
        }
        return t;
    }

    /** El tipo para consultas y operaciones sobre lo ya registrado: exista o no activo. */
    @Transactional(readOnly = true)
    public TipoPrecio obtener(String codigo) {
        return repository.findById(codigo == null ? "" : codigo.trim().toUpperCase())
                .orElseThrow(() -> new ValidacionNegocioException("Tipo de precio no reconocido: " + codigo));
    }

    public static String normalizarCodigo(String crudo) {
        return Normalizer.normalize(crudo.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase().replaceAll("[\\s-]+", "_");
    }

    private static String derivarCodigo(String nombre) {
        String c = normalizarCodigo(nombre).replaceAll("[^A-Z0-9_]", "");
        return c.length() > 15 ? c.substring(0, 15) : c;
    }

    private static String motivo(String texto) {
        String m = texto == null ? "" : texto.trim();
        if (m.isEmpty()) throw new ValidacionNegocioException("El motivo es obligatorio.");
        return m;
    }

    private TipoPrecio buscar(String codigo) {
        return repository.findById(codigo == null ? "" : codigo.trim().toUpperCase())
                .orElseThrow(() -> new RecursoNoEncontradoException("Tipo de precio no encontrado: " + codigo));
    }

    private void registrar(TipoPrecio t, String accion, String motivo) {
        CambioTipoPrecio c = new CambioTipoPrecio();
        c.setCodigo(t.getCodigo());
        c.setActivo(t.isActivo());
        c.setAccion(accion);
        c.setMotivo(motivo);
        c.setUsuario(usuarioActual());
        cambios.save(c);
    }

    private TipoPrecioResponse toResponse(TipoPrecio t) {
        UltimoCambioResponse ultimo = cambios.findFirstByCodigoOrderByCreadoEnDescIdDesc(t.getCodigo())
                .map(c -> new UltimoCambioResponse(c.isActivo(), c.getMotivo(), c.getUsuario() == null ? null : c.getUsuario().getNombre(), c.getCreadoEn()))
                .orElse(null);
        return new TipoPrecioResponse(t.getCodigo(), t.getNombre(), t.getDescripcion(), t.isActivo(), t.isProtegido(), t.getReglaComprador(), ultimo);
    }

    private Usuario usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || autenticacion.getName() == null) return null;
        return usuarios.findByEmail(autenticacion.getName()).orElse(null);
    }
}
