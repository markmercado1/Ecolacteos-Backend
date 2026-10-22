package com.planta.acopio.service;

import com.planta.acopio.dto.HistorialZonaResponse;
import com.planta.acopio.dto.ProveedorAdminResponse;
import com.planta.acopio.dto.ProveedorPublicoResponse;
import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.dto.ProveedorUpdateRequest;
import com.planta.acopio.entity.HistorialZona;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.HistorialZonaRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ProveedorService {

    private final ProveedorRepository proveedorRepository;
    private final ZonaRepository zonaRepository;
    private final HistorialZonaRepository historialZonaRepository;

    public ProveedorService(ProveedorRepository proveedorRepository, ZonaRepository zonaRepository,
                             HistorialZonaRepository historialZonaRepository) {
        this.proveedorRepository = proveedorRepository;
        this.zonaRepository = zonaRepository;
        this.historialZonaRepository = historialZonaRepository;
    }

    @Transactional
    public ProveedorAdminResponse crear(ProveedorRequest dto) {
        if (proveedorRepository.findByDni(dto.dni()).isPresent()) {
            throw new ConflictoNegocioException("Ya existe un proveedor con DNI " + dto.dni());
        }

        Proveedor proveedor = new Proveedor();
        proveedor.setNombre(dto.nombre());
        proveedor.setDni(dto.dni());
        proveedor.setTelefono(dto.telefono());
        proveedor.setCodigoQr(UUID.randomUUID().toString());

        if (dto.zonaActualId() != null) {
            Zona zona = zonaRepository.findById(dto.zonaActualId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + dto.zonaActualId()));
            proveedor.setZonaActual(zona);
            proveedor = proveedorRepository.save(proveedor);
            abrirHistorialZona(proveedor, zona);
        } else {
            proveedor = proveedorRepository.save(proveedor);
        }

        return toAdminResponse(proveedor);
    }

    // Flujo de negocio (CORE_DESIGN.md §11, hallazgo 3.4): reasignar zona no es
    // un simple UPDATE -- cierra el periodo de HistorialZona abierto (si existe)
    // antes de abrir uno nuevo, para que nunca queden dos periodos activos.
    @Transactional
    public ProveedorAdminResponse reasignarZona(UUID proveedorId, UUID nuevaZonaId) {
        Proveedor proveedor = proveedorRepository.findById(proveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + proveedorId));
        Zona nuevaZona = zonaRepository.findById(nuevaZonaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + nuevaZonaId));

        historialZonaRepository.findByProveedorIdAndHastaIsNull(proveedorId)
                .ifPresent(periodoAbierto -> periodoAbierto.setHasta(LocalDate.now().minusDays(1)));

        proveedor.setZonaActual(nuevaZona);
        abrirHistorialZona(proveedor, nuevaZona);

        return toAdminResponse(proveedor);
    }

    // Bloque 5 (confirmado): filtros opcionales combinables -- sin parametros
    // se comporta exactamente igual que antes (no rompe al frontend actual).
    public Page<ProveedorAdminResponse> buscar(UUID zonaId, Boolean activo, String busqueda, Pageable pageable) {
        return proveedorRepository.buscarConFiltros(zonaId, activo, BusquedaTexto.patronLike(busqueda), pageable)
                .map(this::toAdminResponse);
    }

    // Auditoria CRUD (prioridad ALTA): faltaba el detalle individual -- listar()
    // devuelve todos, pero no habia forma de traer un solo proveedor por id.
    public ProveedorAdminResponse obtener(UUID id) {
        return toAdminResponse(proveedorRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + id)));
    }

    // Auditoria CRUD (prioridad ALTA): antes la unica "actualizacion" posible
    // era reasignar zona. dni se puede corregir (typo de digitacion), pero
    // sigue siendo unico -- se revalida excluyendo al propio proveedor.
    @Transactional
    public ProveedorAdminResponse actualizar(UUID id, ProveedorUpdateRequest dto) {
        Proveedor proveedor = proveedorRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + id));

        proveedorRepository.findByDni(dto.dni())
                .filter(otro -> !otro.getId().equals(id))
                .ifPresent(otro -> { throw new ConflictoNegocioException("Ya existe un proveedor con DNI " + dto.dni()); });

        proveedor.setNombre(dto.nombre());
        proveedor.setDni(dto.dni());
        proveedor.setTelefono(dto.telefono());

        return toAdminResponse(proveedor);
    }

    // Auditoria CRUD (prioridad ALTA -- hallazgo critico): Proveedor.activo ya
    // existia (mejora #6) y RegistroAcopioService.registrarOIgnorarSiDuplicado
    // YA valida "if (!proveedor.getActivo()) throw RecursoInactivoException",
    // pero ningun metodo ponia activo=false: era imposible dar de baja a un
    // proveedor desde la API. Baja logica (nunca fisica): preserva el
    // historial de RegistroAcopio/Pago/HistorialZona ya existentes.
    @Transactional
    public void desactivar(UUID id) {
        Proveedor proveedor = proveedorRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + id));
        proveedor.setActivo(false);
    }

    // Auditoria de bajo riesgo (Fase 3): espejo exacto de desactivar() --
    // vuelve a admitir nuevos RegistroAcopio (ver validacion en
    // RegistroAcopioService.registrarOIgnorarSiDuplicado).
    @Transactional
    public ProveedorAdminResponse reactivar(UUID id) {
        Proveedor proveedor = proveedorRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + id));
        proveedor.setActivo(true);
        return toAdminResponse(proveedor);
    }

    // Auditoria CRUD (prioridad BAJA): ProveedorRepository.findByCodigoQr ya
    // existia sin usarse. CORE_DESIGN.md §1 describe la camara del telefono
    // leyendo el QR del proveedor -- este es el endpoint que resuelve esa
    // lectura a los datos del proveedor en campo.
    // Segunda auditoria (flujos end-to-end §14-F, RNF-12): devuelve el DTO
    // publico -- quien escanea un QR en campo es ACOPIADOR, el mismo rol que
    // antes de esta correccion podia ver dni/telefono via este endpoint.
    public ProveedorPublicoResponse obtenerPorCodigoQr(String codigoQr) {
        return toPublicoResponse(proveedorRepository.findByCodigoQr(codigoQr)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado para el codigo QR: " + codigoQr)));
    }

    // Auditoria CRUD (prioridad BAJA): HistorialZonaRepository.findByProveedorIdOrderByDesdeDesc
    // ya existia sin usarse -- lo expone para la ficha de detalle del proveedor.
    public List<HistorialZonaResponse> historialZona(UUID proveedorId) {
        if (!proveedorRepository.existsById(proveedorId)) {
            throw new RecursoNoEncontradoException("Proveedor no encontrado: " + proveedorId);
        }
        return historialZonaRepository.findByProveedorIdOrderByDesdeDesc(proveedorId).stream()
                .map(h -> new HistorialZonaResponse(
                        h.getId(), h.getZona().getId(), h.getZona().getNombre(), h.getDesde(), h.getHasta()))
                .toList();
    }

    // Usado por SyncService (/api/sync/cambios) y por GET /api/proveedores/operativo
    // (RNF-12: sin dni/telefono). Segunda auditoria (§14-F): filtra solo
    // activos, igual que el resto de catalogos operativos (Zona.listarActivas,
    // MotivoObservacion/TipoQueso.listarActivos) -- un proveedor desactivado
    // no deberia ofrecerse como opcion seleccionable en campo.
    public List<ProveedorPublicoResponse> listarPublico() {
        return proveedorRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getActivo()))
                .map(this::toPublicoResponse).toList();
    }

    private void abrirHistorialZona(Proveedor proveedor, Zona zona) {
        HistorialZona historial = new HistorialZona();
        historial.setProveedor(proveedor);
        historial.setZona(zona);
        historial.setDesde(LocalDate.now());
        historialZonaRepository.save(historial);
    }

    private ProveedorAdminResponse toAdminResponse(Proveedor p) {
        return new ProveedorAdminResponse(
                p.getId(), p.getNombre(), p.getDni(), p.getTelefono(),
                p.getZonaActual() != null ? p.getZonaActual().getId() : null,
                p.getZonaActual() != null ? p.getZonaActual().getNombre() : null,
                p.getCodigoQr(), p.getActivo());
    }

    private ProveedorPublicoResponse toPublicoResponse(Proveedor p) {
        return new ProveedorPublicoResponse(
                p.getId(), p.getNombre(),
                p.getZonaActual() != null ? p.getZonaActual().getId() : null,
                p.getZonaActual() != null ? p.getZonaActual().getNombre() : null,
                p.getCodigoQr());
    }
}
