package com.planta.acopio.service;

import com.planta.acopio.dto.TipoPrecioDtos.TipoPrecioResponse;
import com.planta.acopio.dto.TipoPrecioDtos.UltimoCambioResponse;
import com.planta.acopio.entity.CambioTipoPrecio;
import com.planta.acopio.entity.TipoClienteVenta;
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

import java.util.List;

// Tipos de precio administrables, etapa 1: activar o desactivar MAYORISTA, PROVEEDOR y PUBLICO. Un tipo desactivado no se acepta en precios
// ni ventas NUEVOS; lo ya registrado no cambia y se sigue mostrando con su nombre. Ningun tipo esta protegido: solo se impide desactivar el ultimo.
@Service
public class TipoPrecioService {

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
        return repository.findAllByOrderByCodigoAsc().stream()
                .filter(t -> !soloActivos || t.isActivo())
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TipoPrecioResponse cambiarEstado(String codigo, boolean activo, String motivo) {
        String m = motivo == null ? "" : motivo.trim();
        if (m.isEmpty()) throw new ValidacionNegocioException("El motivo es obligatorio.");
        TipoPrecio tipo = repository.findById(codigo == null ? "" : codigo.trim().toUpperCase())
                .orElseThrow(() -> new RecursoNoEncontradoException("Tipo de precio no encontrado: " + codigo));
        if (!activo && tipo.isActivo() && repository.countByActivoTrue() <= 1) {
            throw new ValidacionNegocioException("No se puede desactivar el último tipo de precio activo: no quedaría ninguno para vender.");
        }
        if (tipo.isActivo() == activo) {
            throw new ConflictoNegocioException("El tipo " + tipo.getNombre() + " ya está " + (activo ? "activo" : "desactivado") + ".");
        }
        tipo.setActivo(activo);
        repository.save(tipo);
        CambioTipoPrecio c = new CambioTipoPrecio();
        c.setCodigo(tipo.getCodigo());
        c.setActivo(activo);
        c.setMotivo(m);
        c.setUsuario(usuarioActual());
        cambios.save(c);
        return toResponse(tipo);
    }

    /** Para operaciones NUEVAS (precio o venta): rechaza un tipo desactivado. Lo historico nunca pasa por aqui. */
    @Transactional(readOnly = true)
    public void exigirActivo(TipoClienteVenta tipo) {
        TipoPrecio t = repository.findById(tipo.name())
                .orElseThrow(() -> new ValidacionNegocioException("Tipo de precio no reconocido: " + tipo));
        if (!t.isActivo()) {
            throw new ValidacionNegocioException("El tipo de precio " + t.getNombre() + " está desactivado: no se puede usar en operaciones nuevas.");
        }
    }

    private TipoPrecioResponse toResponse(TipoPrecio t) {
        UltimoCambioResponse ultimo = cambios.findFirstByCodigoOrderByCreadoEnDescIdDesc(t.getCodigo())
                .map(c -> new UltimoCambioResponse(c.isActivo(), c.getMotivo(), c.getUsuario() == null ? null : c.getUsuario().getNombre(), c.getCreadoEn()))
                .orElse(null);
        return new TipoPrecioResponse(t.getCodigo(), t.getNombre(), t.getDescripcion(), t.isActivo(), t.isProtegido(), ultimo);
    }

    private Usuario usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || autenticacion.getName() == null) return null;
        return usuarios.findByEmail(autenticacion.getName()).orElse(null);
    }
}
