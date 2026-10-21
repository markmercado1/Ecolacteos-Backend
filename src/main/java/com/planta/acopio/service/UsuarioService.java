package com.planta.acopio.service;

import com.planta.acopio.dto.UsuarioRequest;
import com.planta.acopio.dto.UsuarioResponse;
import com.planta.acopio.dto.UsuarioUpdateRequest;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// CRUD administrativo simple (CORE_DESIGN.md §11): sin flujo de negocio propio
// mas alla de validar unicidad de email y resolver el rol.
@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public UsuarioService(UsuarioRepository usuarioRepository, RolRepository rolRepository,
                           PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService) {
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public UsuarioResponse crear(UsuarioRequest dto) {
        if (usuarioRepository.existsByEmail(dto.email())) {
            throw new ConflictoNegocioException("Ya existe un usuario con email " + dto.email());
        }
        Rol rol = rolRepository.findByNombre(dto.rolNombre())
                .orElseThrow(() -> new RecursoNoEncontradoException("Rol no encontrado: " + dto.rolNombre()));

        Usuario usuario = new Usuario();
        usuario.setNombre(dto.nombre());
        usuario.setEmail(dto.email());
        usuario.setPasswordHash(passwordEncoder.encode(dto.password()));
        usuario.setRol(rol);

        return toResponse(usuarioRepository.save(usuario));
    }

    public List<UsuarioResponse> listar() {
        return usuarioRepository.findAll().stream().map(this::toResponse).toList();
    }

    // Paginacion pendiente: rol, estado y texto (nombre/correo) se aplican en la
    // BD antes de paginar. rolNombre en blanco = todos los roles.
    @Transactional(readOnly = true)
    public Page<UsuarioResponse> buscar(String rolNombre, Boolean activo, String busqueda, Pageable pageable) {
        String rol = rolNombre == null || rolNombre.isBlank() ? null : rolNombre.trim().toUpperCase(java.util.Locale.ROOT);
        return usuarioRepository.buscarConFiltros(rol, activo, BusquedaTexto.patronLike(busqueda), pageable)
                .map(this::toResponse);
    }

    public UsuarioResponse obtener(UUID id) {
        return toResponse(usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado: " + id)));
    }

    // Auditoria CRUD (prioridad ALTA): antes no habia forma de corregir un
    // typo, cambiar el rol o resetear la contraseña de un usuario ya creado
    // salvo por SQL directo. nuevaPassword es opcional: si viene null/blank,
    // no se toca el password_hash existente.
    @Transactional
    public UsuarioResponse actualizar(UUID id, UsuarioUpdateRequest dto) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado: " + id));

        usuarioRepository.findByEmail(dto.email())
                .filter(otro -> !otro.getId().equals(id))
                .ifPresent(otro -> { throw new ConflictoNegocioException("Ya existe un usuario con email " + dto.email()); });

        Rol rol = rolRepository.findByNombre(dto.rolNombre())
                .orElseThrow(() -> new RecursoNoEncontradoException("Rol no encontrado: " + dto.rolNombre()));

        usuario.setNombre(dto.nombre());
        usuario.setEmail(dto.email());
        usuario.setRol(rol);
        if (dto.nuevaPassword() != null && !dto.nuevaPassword().isBlank()) {
            usuario.setPasswordHash(passwordEncoder.encode(dto.nuevaPassword()));
        }

        return toResponse(usuario);
    }

    @Transactional
    public void desactivar(UUID id) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado: " + id));
        usuario.setActivo(false);
        // Bloque "Token offline" (confirmado): un usuario desactivado no debe
        // poder recuperar sesion offline con un refresh token viejo.
        refreshTokenService.revocarTodosDeUsuario(id);
    }

    // Auditoria de bajo riesgo (Fase 3): espejo exacto de desactivar() --
    // restaura el acceso al sistema.
    @Transactional
    public UsuarioResponse reactivar(UUID id) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado: " + id));
        usuario.setActivo(true);
        return toResponse(usuario);
    }

    private UsuarioResponse toResponse(Usuario u) {
        return new UsuarioResponse(u.getId(), u.getNombre(), u.getEmail(), u.getRol().getNombre(), u.getActivo());
    }
}
