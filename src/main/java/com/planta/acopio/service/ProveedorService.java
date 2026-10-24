package com.planta.acopio.service;

import com.planta.acopio.dto.HistorialZonaResponse;
import com.planta.acopio.dto.ProveedorAdminResponse;
import com.planta.acopio.dto.ProveedorPublicoResponse;
import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.dto.ProveedorUpdateRequest;
import com.planta.acopio.entity.HistorialZona;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.TipoPersona;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.HistorialZonaRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProveedorService {

    private final ProveedorRepository proveedorRepository;
    private final ZonaRepository zonaRepository;
    private final HistorialZonaRepository historialZonaRepository;
    private final UsuarioRepository usuarioRepository;
    private final Clock reloj;

    private static final int MAX_MOTIVO = 200;
    private static final int MAX_NOMBRE = 150;

    @Autowired
    public ProveedorService(ProveedorRepository proveedorRepository, ZonaRepository zonaRepository,
                             HistorialZonaRepository historialZonaRepository, UsuarioRepository usuarioRepository) {
        this(proveedorRepository, zonaRepository, historialZonaRepository, usuarioRepository,
                Clock.system(ZoneId.of("America/Lima")));
    }

    ProveedorService(ProveedorRepository proveedorRepository, ZonaRepository zonaRepository,
                     HistorialZonaRepository historialZonaRepository, UsuarioRepository usuarioRepository, Clock reloj) {
        this.proveedorRepository = proveedorRepository;
        this.zonaRepository = zonaRepository;
        this.historialZonaRepository = historialZonaRepository;
        this.usuarioRepository = usuarioRepository;
        this.reloj = reloj;
    }

    @Transactional
    public ProveedorAdminResponse crear(ProveedorRequest dto) {
        Documentos doc = validarDocumentos(tipoPersona(dto.tipoPersona(), TipoPersona.NATURAL),
                dto.nombre(), dto.dni(), dto.ruc(), dto.razonSocial(), null);
        verificarDocumentosUnicos(doc, null);

        Proveedor proveedor = new Proveedor();
        aplicar(proveedor, doc);
        proveedor.setTelefono(dto.telefono());
        proveedor.setCodigoQr(UUID.randomUUID().toString());

        if (dto.zonaActualId() != null) {
            Zona zona = zonaRepository.findById(dto.zonaActualId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + dto.zonaActualId()));
            proveedor.setZonaActual(zona);
            proveedor = proveedorRepository.save(proveedor);
            abrirHistorialZona(proveedor, zona, null, "Alta del proveedor", LocalDateTime.now(reloj));
        } else {
            proveedor = proveedorRepository.save(proveedor);
        }

        return toAdminResponse(proveedor);
    }

    // Flujo de negocio (CORE_DESIGN.md §11, hallazgo 3.4): reasignar zona no es
    // un simple UPDATE -- cierra el periodo de HistorialZona abierto (si existe)
    // antes de abrir uno nuevo, para que nunca queden dos periodos activos.
    //
    // Fase A: el cambio queda con su INSTANTE (America/Lima), la zona de la que venia, el motivo y quien lo hizo. El
    // periodo anterior termina exactamente cuando empieza el nuevo, asi que varios cambios el mismo dia son validos y
    // la zona de una entrega se puede decidir por hora. Antes el periodo viejo se cerraba con hasta = hoy - 1, que
    // quedaba invertido (hasta < desde) si el periodo se habia abierto hoy. Se bloquea la fila del proveedor para que
    // dos cambios simultaneos se apliquen uno tras otro.
    @Transactional
    public ProveedorAdminResponse reasignarZona(UUID proveedorId, UUID nuevaZonaId, String motivo) {
        String motivoLimpio = motivo == null || motivo.isBlank() ? null : motivo.trim();
        if (motivoLimpio != null && motivoLimpio.length() > MAX_MOTIVO) {
            throw new ValidacionNegocioException("El motivo no puede superar " + MAX_MOTIVO + " caracteres");
        }
        Proveedor proveedor = proveedorRepository.findByIdParaActualizar(proveedorId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + proveedorId));
        Zona nuevaZona = zonaRepository.findById(nuevaZonaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + nuevaZonaId));

        Zona zonaAnterior = proveedor.getZonaActual();
        // Reasignar a la zona en la que ya esta no cambia nada: abriria un periodo identico y ensuciaria el historial.
        if (zonaAnterior != null && zonaAnterior.getId().equals(nuevaZona.getId())) {
            throw new ConflictoNegocioException("El proveedor ya está en la zona " + nuevaZona.getNombre());
        }
        LocalDateTime ahora = LocalDateTime.now(reloj);
        historialZonaRepository.findByProveedorIdAndFinEnIsNull(proveedorId)
                .ifPresent(periodoAbierto -> cerrarPeriodo(periodoAbierto, ahora));
        historialZonaRepository.flush(); // el indice unico parcial exige cerrar el abierto antes de insertar el nuevo

        proveedor.setZonaActual(nuevaZona);
        abrirHistorialZona(proveedor, nuevaZona, zonaAnterior, motivoLimpio, ahora);

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
    //
    // Fase G: las reglas de documentos dependen del tipo de persona (ver validarDocumentos). Un DNI o RUC que no cambia
    // se conserva tal cual aunque no cumpla el formato actual (registros anteriores): se informa en la revision de
    // documentos, no bloquea la edicion del resto de la ficha.
    @Transactional
    public ProveedorAdminResponse actualizar(UUID id, ProveedorUpdateRequest dto) {
        Proveedor proveedor = proveedorRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + id));

        Documentos doc = validarDocumentos(tipoPersona(dto.tipoPersona(), proveedor.getTipoPersona()),
                dto.nombre(), dto.dni(), dto.ruc(), dto.razonSocial(), proveedor);
        verificarDocumentosUnicos(doc, id);

        aplicar(proveedor, doc);
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
                        h.getId(), h.getZona().getId(), h.getZona().getNombre(), h.getDesde(), h.getHasta(),
                        h.getInicioEn(), h.getFinEn(),
                        h.getZonaOrigen() != null ? h.getZonaOrigen().getNombre() : null,
                        h.getMotivo(),
                        h.getUsuario() != null ? h.getUsuario().getNombre() : null,
                        Boolean.TRUE.equals(h.getRequiereRevision()), h.getNotaRevision()))
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

    // ------------------------------------------------------------------ Fase G: documentos

    /** Datos de identificacion ya validados y normalizados. */
    public record Documentos(TipoPersona tipo, String nombre, String dni, String ruc, String razonSocial) {}

    static TipoPersona tipoPersona(String valor, TipoPersona porDefecto) {
        if (valor == null || valor.isBlank()) return porDefecto;
        try {
            return TipoPersona.valueOf(valor.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidacionNegocioException("Tipo de persona no válido: «" + valor + "». Usa NATURAL o EMPRESA.");
        }
    }

    // Persona natural: nombre y DNI (8 digitos) obligatorios, RUC opcional (10 + su DNI + verificador), sin razon social.
    // Empresa: RUC (20... con verificador) y razon social obligatorios, sin DNI; nombre opcional (por defecto la razon social).
    // `actual` = ficha que se edita (null al crear): un DNI o RUC igual al guardado no se revalida.
    public static Documentos validarDocumentos(TipoPersona tipo, String nombre, String dni, String ruc, String razonSocial,
                                        Proveedor actual) {
        String nombreLimpio = nombre == null ? "" : nombre.trim();
        String razonLimpia = razonSocial == null || razonSocial.isBlank() ? null : razonSocial.trim();
        String dniGuardado = actual == null ? null : actual.getDni();
        String rucGuardado = actual == null ? null : actual.getRuc();
        String dniFinal = sinCambio(dniGuardado, dni) ? dniGuardado : DocumentoIdentidad.limpiar(dni);
        String rucFinal = sinCambio(rucGuardado, ruc) ? rucGuardado : DocumentoIdentidad.limpiar(ruc);
        boolean dniNuevo = actual == null || !Objects.equals(dniFinal, dniGuardado);
        boolean rucNuevo = actual == null || !Objects.equals(rucFinal, rucGuardado);

        if (tipo == TipoPersona.NATURAL) {
            if (nombreLimpio.isEmpty()) throw new ValidacionNegocioException("El nombre es obligatorio.");
            if (dniFinal == null) throw new ValidacionNegocioException("El DNI es obligatorio para una persona natural.");
            if (dniNuevo && !DocumentoIdentidad.dniValido(dniFinal)) {
                throw new ValidacionNegocioException("El DNI «" + dniFinal + "» no es válido: debe tener exactamente 8 dígitos.");
            }
            if (razonLimpia != null) {
                throw new ValidacionNegocioException(
                        "La razón social solo se registra para una empresa; una persona natural se identifica con su nombre.");
            }
            if (rucFinal != null && (rucNuevo || dniNuevo)) {
                validarRuc(rucFinal, DocumentoIdentidad.PREFIJO_RUC_NATURAL, "una persona natural");
                if (!rucFinal.substring(2, 10).equals(dniFinal)) {
                    throw new ValidacionNegocioException("El RUC " + rucFinal + " no corresponde al DNI " + dniFinal
                            + ": el RUC de una persona natural es 10 + su DNI + un dígito verificador.");
                }
            }
        } else {
            if (dniFinal != null) {
                throw new ValidacionNegocioException("Una empresa no registra DNI: se identifica con su RUC.");
            }
            if (rucFinal == null) throw new ValidacionNegocioException("El RUC es obligatorio para una empresa.");
            if (rucNuevo) validarRuc(rucFinal, DocumentoIdentidad.PREFIJO_RUC_EMPRESA, "una empresa");
            if (razonLimpia == null) throw new ValidacionNegocioException("La razón social es obligatoria para una empresa.");
            if (razonLimpia.length() > MAX_NOMBRE) {
                throw new ValidacionNegocioException("La razón social no puede superar " + MAX_NOMBRE + " caracteres.");
            }
            if (nombreLimpio.isEmpty()) nombreLimpio = razonLimpia;
        }
        if (nombreLimpio.length() > MAX_NOMBRE) {
            throw new ValidacionNegocioException("El nombre no puede superar " + MAX_NOMBRE + " caracteres.");
        }
        return new Documentos(tipo, nombreLimpio, dniFinal, rucFinal, razonLimpia);
    }

    private static boolean sinCambio(String guardado, String enviado) {
        return guardado != null && enviado != null && guardado.equals(enviado.trim());
    }

    private static void validarRuc(String ruc, String prefijo, String quien) {
        if (!DocumentoIdentidad.rucValido(ruc)) {
            throw new ValidacionNegocioException(
                    "El RUC «" + ruc + "» no es válido: debe tener 11 dígitos y un dígito verificador correcto.");
        }
        if (!ruc.startsWith(prefijo)) {
            throw new ValidacionNegocioException("El RUC de " + quien + " empieza con " + prefijo + ".");
        }
    }

    private void verificarDocumentosUnicos(Documentos doc, UUID propioId) {
        if (doc.dni() != null) {
            proveedorRepository.findByDni(doc.dni())
                    .filter(otro -> !otro.getId().equals(propioId))
                    .ifPresent(otro -> { throw new ConflictoNegocioException("Ya existe un proveedor con DNI " + doc.dni()); });
        }
        if (doc.ruc() != null) {
            proveedorRepository.findByRuc(doc.ruc())
                    .filter(otro -> !otro.getId().equals(propioId))
                    .ifPresent(otro -> {
                        throw new ConflictoNegocioException("Ya existe un proveedor con RUC " + doc.ruc() + " (" + otro.getNombre() + ")");
                    });
        }
    }

    private static void aplicar(Proveedor proveedor, Documentos doc) {
        proveedor.setTipoPersona(doc.tipo());
        proveedor.setNombre(doc.nombre());
        proveedor.setDni(doc.dni());
        proveedor.setRuc(doc.ruc());
        proveedor.setRazonSocial(doc.razonSocial());
    }

    private void cerrarPeriodo(HistorialZona periodo, LocalDateTime ahora) {
        LocalDateTime fin = ahora.isBefore(periodo.getInicioEn()) ? periodo.getInicioEn() : ahora;
        periodo.setFinEn(fin);
        // hasta = ultimo DIA completo del periodo (como antes), pero nunca anterior a desde
        LocalDate ultimoDiaCompleto = fin.toLocalDate().minusDays(1);
        periodo.setHasta(ultimoDiaCompleto.isBefore(periodo.getDesde()) ? periodo.getDesde() : ultimoDiaCompleto);
    }

    private void abrirHistorialZona(Proveedor proveedor, Zona zona, Zona zonaOrigen, String motivo, LocalDateTime ahora) {
        HistorialZona historial = new HistorialZona();
        historial.setProveedor(proveedor);
        historial.setZona(zona);
        historial.setZonaOrigen(zonaOrigen);
        historial.setDesde(ahora.toLocalDate());
        historial.setInicioEn(ahora);
        historial.setMotivo(motivo);
        historial.setUsuario(usuarioActual());
        historialZonaRepository.save(historial);
    }

    // El cambio de zona lo hace una persona autenticada; fuera de una peticion (pruebas, tareas internas) queda sin usuario.
    private Usuario usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || autenticacion.getName() == null) {
            return null;
        }
        return usuarioRepository.findByEmail(autenticacion.getName()).orElse(null);
    }

    private ProveedorAdminResponse toAdminResponse(Proveedor p) {
        return new ProveedorAdminResponse(
                p.getId(), p.getNombre(), p.getDni(), p.getTelefono(),
                p.getZonaActual() != null ? p.getZonaActual().getId() : null,
                p.getZonaActual() != null ? p.getZonaActual().getNombre() : null,
                p.getCodigoQr(), p.getActivo(),
                p.getTipoPersona() != null ? p.getTipoPersona().name() : null, p.getRuc(), p.getRazonSocial());
    }

    private ProveedorPublicoResponse toPublicoResponse(Proveedor p) {
        return new ProveedorPublicoResponse(
                p.getId(), p.getNombre(),
                p.getZonaActual() != null ? p.getZonaActual().getId() : null,
                p.getZonaActual() != null ? p.getZonaActual().getNombre() : null,
                p.getCodigoQr());
    }
}
