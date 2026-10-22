package com.planta.acopio.service;

import com.planta.acopio.dto.ComunicadoConfirmacionResponse;
import com.planta.acopio.dto.ComunicadoResponse;
import com.planta.acopio.dto.ConfirmarComunicadoRequest;
import com.planta.acopio.dto.CrearComunicadoRequest;
import com.planta.acopio.entity.Comunicado;
import com.planta.acopio.entity.ComunicadoConfirmacion;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.ComunicadoConfirmacionRepository;
import com.planta.acopio.repository.ComunicadoRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// RF-COM. mejora #3 (CORE_DESIGN.md §6): zonasDestino ahora es una lista de
// UUID de zona (relacion real), no texto libre.
@Service
public class ComunicadoService {

    private final ComunicadoRepository comunicadoRepository;
    private final ZonaRepository zonaRepository;
    private final ProveedorRepository proveedorRepository;
    private final UsuarioRepository usuarioRepository;
    private final ComunicadoConfirmacionRepository confirmacionRepository;

    public ComunicadoService(ComunicadoRepository comunicadoRepository, ZonaRepository zonaRepository,
                              ProveedorRepository proveedorRepository, UsuarioRepository usuarioRepository,
                              ComunicadoConfirmacionRepository confirmacionRepository) {
        this.comunicadoRepository = comunicadoRepository;
        this.zonaRepository = zonaRepository;
        this.proveedorRepository = proveedorRepository;
        this.usuarioRepository = usuarioRepository;
        this.confirmacionRepository = confirmacionRepository;
    }

    @Transactional
    public ComunicadoResponse crear(CrearComunicadoRequest dto) {
        Set<Zona> zonas = new HashSet<>();
        for (UUID zonaId : dto.zonaIds()) {
            zonas.add(zonaRepository.findById(zonaId)
                    .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + zonaId)));
        }

        Comunicado comunicado = new Comunicado();
        comunicado.setMensaje(dto.mensaje());
        comunicado.setZonasDestino(zonas);

        return toResponse(comunicadoRepository.save(comunicado));
    }

    // Flujo end-to-end en vivo (verificacion con BD real): sin @Transactional
    // esto lanzaba LazyInitializationException -- zonasDestino es @ManyToMany
    // LAZY y toResponse() la recorre; open-in-view esta en false (application.yml).
    // Mismo patron de bug que LoteProduccionService/PagoService -- ver comentarios ahi.
    @Transactional(readOnly = true)
    public List<ComunicadoResponse> listarPorZona(UUID zonaId) {
        return comunicadoRepository.findByZonaId(zonaId).stream().map(this::toResponse).toList();
    }

    // Auditoria de bajo riesgo (Fase 1): listado global para ADMIN -- antes
    // solo se podia consultar comunicado por zona. Mismo fix de
    // @Transactional que listarPorZona (zonasDestino es LAZY, ver comentario arriba).
    // Paginacion pendiente: paginado en la BD; solo lo consume la web (la app
    // movil usa listarPorZona).
    @Transactional(readOnly = true)
    public Page<ComunicadoResponse> listar(String busqueda, Pageable pageable) {
        return comunicadoRepository.buscar(BusquedaTexto.patronLike(busqueda), pageable).map(this::toResponse);
    }

    // Segunda auditoria (flujos end-to-end §14-F): antes devolvia void -- el
    // unico POST de creacion de todo el API que no devolvia el recurso
    // creado. Se alinea con el resto (ej. POST /correcciones) devolviendo la
    // confirmacion creada.
    @Transactional
    public ComunicadoConfirmacionResponse confirmar(UUID comunicadoId, ConfirmarComunicadoRequest dto) {
        Comunicado comunicado = comunicadoRepository.findById(comunicadoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Comunicado no encontrado: " + comunicadoId));
        Proveedor proveedor = proveedorRepository.findById(dto.proveedorId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + dto.proveedorId()));

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario acopiador = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        ComunicadoConfirmacion confirmacion = new ComunicadoConfirmacion();
        confirmacion.setComunicado(comunicado);
        confirmacion.setProveedor(proveedor);
        confirmacion.setAcopiador(acopiador);
        confirmacion = confirmacionRepository.save(confirmacion);

        return new ComunicadoConfirmacionResponse(
                confirmacion.getId(), proveedor.getId(), proveedor.getNombre(),
                acopiador.getId(), acopiador.getNombre(), confirmacion.getConfirmadoEn());
    }

    // Auditoria CRUD (prioridad MEDIA): ComunicadoConfirmacionRepository.findByComunicadoId
    // ya existia pero no se exponia -- ADMIN no podia ver quien confirmo la
    // lectura de un comunicado, que es justamente el proposito de registrarla.
    public List<ComunicadoConfirmacionResponse> listarConfirmaciones(UUID comunicadoId) {
        if (!comunicadoRepository.existsById(comunicadoId)) {
            throw new RecursoNoEncontradoException("Comunicado no encontrado: " + comunicadoId);
        }
        return confirmacionRepository.findByComunicadoId(comunicadoId).stream()
                .map(c -> new ComunicadoConfirmacionResponse(
                        c.getId(), c.getProveedor().getId(), c.getProveedor().getNombre(),
                        c.getAcopiador().getId(), c.getAcopiador().getNombre(), c.getConfirmadoEn()))
                .toList();
    }

    private ComunicadoResponse toResponse(Comunicado c) {
        return new ComunicadoResponse(c.getId(), c.getMensaje(), c.getFecha(),
                c.getZonasDestino().stream().map(Zona::getNombre).toList());
    }
}
