package com.planta.acopio.service;

import com.planta.acopio.dto.ClienteDtos.AutorizacionComercial;
import com.planta.acopio.dto.ClienteDtos.ClienteRequest;
import com.planta.acopio.dto.ClienteDtos.ClienteResponse;
import com.planta.acopio.dto.ClienteDtos.ClienteUpdateRequest;
import com.planta.acopio.dto.ClienteDtos.ComercialRequest;
import com.planta.acopio.dto.ClienteDtos.ConsentimientoResponse;
import com.planta.acopio.dto.ClienteDtos.EstadoAutorizacion;
import com.planta.acopio.dto.ClienteDtos.FusionResponse;
import com.planta.acopio.dto.ClienteDtos.ResumenReservas;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.entity.Cliente;
import com.planta.acopio.entity.ClienteConsentimiento;
import com.planta.acopio.entity.ClienteFusion;
import com.planta.acopio.entity.ClienteTelefono;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.entity.TipoPersona;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.ClienteConsentimientoRepository;
import com.planta.acopio.repository.ClienteFusionRepository;
import com.planta.acopio.repository.ClienteRepository;
import com.planta.acopio.repository.ClienteTelefonoRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.ReservaRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.VentaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Fase H: padron VOLUNTARIO de clientes.
//  * Nunca se crea una ficha sin la autorizacion del comprador (registro voluntario) ni a partir de una venta o una reserva.
//  * DNI/RUC (opcionales) identifican con certeza: unicos entre fichas vigentes y nunca iguales a los de un proveedor (un proveedor
//    que compra usa SU ficha de proveedor). El telefono solo sugiere: varias fichas pueden compartirlo; si al registrar hay fichas
//    con ese telefono, el trabajador debe confirmar con el comprador que es otra persona (si no, 409).
//  * La autorizacion de comunicaciones comerciales es independiente del registro, nunca se asume y se revoca con la misma facilidad.
//  * Las fichas duplicadas solo se fusionan a mano (ADMIN), con motivo y constancia; nunca automaticamente.
//  * Tipo de cliente (regular o mayorista) y canal habitual (V34) son cosas distintas: el tipo HABILITA el precio MAYORISTA (solo
//    ADMIN, con motivo); el canal habitual es el que el panel PROPONE al vender (PUBLICO o, si es mayorista, MAYORISTA). Cada venta
//    elige su canal y se valida igual que antes.
@Service
public class ClienteService {

    static final Set<String> FORMAS = Set.of("VERBAL_PRESENCIAL", "ESCRITO", "TELEFONICA", "DIGITAL");
    static final Set<String> MEDIOS = Set.of("WHATSAPP", "SMS", "LLAMADA", "CORREO");
    private static final Pattern CORREO = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_NOMBRE = 150;
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final ClienteRepository clienteRepository;
    private final ClienteTelefonoRepository telefonoRepository;
    private final ClienteConsentimientoRepository consentimientoRepository;
    private final ClienteFusionRepository fusionRepository;
    private final ProveedorRepository proveedorRepository;
    private final VentaRepository ventaRepository;
    private final ReservaRepository reservaRepository;
    private final UsuarioRepository usuarioRepository;
    private final PrivacidadService privacidad;
    private final HistorialComprasService historialCompras;

    public ClienteService(ClienteRepository clienteRepository, ClienteTelefonoRepository telefonoRepository,
                          ClienteConsentimientoRepository consentimientoRepository, ClienteFusionRepository fusionRepository,
                          ProveedorRepository proveedorRepository, VentaRepository ventaRepository, ReservaRepository reservaRepository,
                          UsuarioRepository usuarioRepository, PrivacidadService privacidad, HistorialComprasService historialCompras) {
        this.clienteRepository = clienteRepository;
        this.telefonoRepository = telefonoRepository;
        this.consentimientoRepository = consentimientoRepository;
        this.fusionRepository = fusionRepository;
        this.proveedorRepository = proveedorRepository;
        this.ventaRepository = ventaRepository;
        this.reservaRepository = reservaRepository;
        this.usuarioRepository = usuarioRepository;
        this.privacidad = privacidad;
        this.historialCompras = historialCompras;
    }

    // ------------------------------------------------------------------ registro voluntario

    /** `esAdmin`: ve los datos completos y puede registrar la ficha ya habilitada como mayorista (con motivo). */
    @Transactional
    public ClienteResponse registrar(ClienteRequest dto, boolean esAdmin) {
        if (dto.autorizacionRegistro() == null || !dto.autorizacionRegistro().otorgada()) {
            throw new ValidacionNegocioException("Sin la autorización del comprador no se crea su ficha. La venta puede seguir como «Cliente varios», "
                    + "al mismo precio.");
        }
        String formaRegistro = forma(dto.autorizacionRegistro().forma());
        String telefono = telefono(dto.telefono());
        Documentos doc = documentos(tipo(dto.tipoPersona(), TipoPersona.NATURAL), dto.nombre(), dto.dni(), dto.ruc(), dto.razonSocial());
        String correo = correo(dto.correo());
        boolean mayorista = Boolean.TRUE.equals(dto.mayorista());
        String motivoMayorista = null;
        if (mayorista) {
            if (!esAdmin) throw new ValidacionNegocioException("Solo ADMIN habilita a un cliente como mayorista. Regístralo como regular y pídeselo a ADMIN.");
            motivoMayorista = motivoMayorista(dto.motivoMayorista());
        }
        String canal = canalHabitual(dto.canalHabitual(), mayorista);
        verificarUnicos(doc, null);
        if (!dto.confirmadoSinCoincidencia()) {
            List<String> mismos = fichasConTelefono(telefono);
            if (!mismos.isEmpty()) {
                throw new ConflictoNegocioException("Ese teléfono ya figura en " + String.join(", ", mismos) + ". Confirma con el comprador si es "
                        + "alguna de esas fichas (y elígela) o si es otra persona; compartir un teléfono no prueba que sea la misma.");
            }
        }
        AutorizacionComercial comercial = dto.comunicaciones();
        List<String> medios = comercial != null && comercial.otorgada() ? medios(comercial.medios()) : List.of();
        String formaComercial = comercial != null && comercial.otorgada() ? forma(comercial.forma() != null ? comercial.forma() : formaRegistro) : null;
        UUID usuario = usuarioActual();

        Cliente c = new Cliente();
        aplicar(c, doc);
        c.setCorreo(correo);
        c.setCanalHabitual(canal);
        if (mayorista) {
            c.setMayorista(true);
            c.setMayoristaMotivo(motivoMayorista);
            c.setMayoristaCambiadoPor(usuario);
            c.setMayoristaCambiadoEn(LocalDateTime.now(LIMA));
        }
        c.setCreadoPor(usuario);
        c.setCreadoEn(LocalDateTime.now(LIMA));
        c = clienteRepository.saveAndFlush(c);
        telefonoRepository.save(new ClienteTelefono(c.getId(), telefono));
        consentimientoRepository.save(consentimiento(c.getId(), ClienteConsentimiento.REGISTRO, ClienteConsentimiento.OTORGADO, null,
                formaRegistro, dto.avisoVersion(), usuario, null));
        if (!medios.isEmpty()) {
            consentimientoRepository.save(consentimiento(c.getId(), ClienteConsentimiento.COMERCIAL, ClienteConsentimiento.OTORGADO,
                    String.join(",", medios), formaComercial, dto.avisoVersion(), usuario, null));
        }
        return respuesta(c, esAdmin);
    }

    /** Fichas vigentes con ese telefono, como "nombre (ficha de cliente)" o "nombre (proveedor)". */
    public List<String> fichasConTelefono(String telefono) {
        List<String> nombres = new ArrayList<>();
        clienteRepository.conTelefono(telefono).forEach(c -> nombres.add(c.getNombre() + " (cliente)"));
        proveedorRepository.conTelefonoTerminadoEn(telefono.substring(3)).forEach(p -> nombres.add(p.getNombre() + " (proveedor)"));
        return nombres;
    }

    // ------------------------------------------------------------------ edicion (ADMIN)

    @Transactional
    public ClienteResponse actualizar(UUID id, ClienteUpdateRequest dto) {
        Cliente c = vigente(id);
        Documentos doc = documentos(tipo(dto.tipoPersona(), c.getTipoPersona()), dto.nombre(), dto.dni(), dto.ruc(), dto.razonSocial());
        verificarUnicos(doc, id);
        aplicar(c, doc);
        c.setCorreo(correo(dto.correo()));
        if (dto.canalHabitual() != null) c.setCanalHabitual(canalHabitual(dto.canalHabitual(), c.isMayorista()));
        return respuesta(c, true);
    }

    @Transactional
    public ClienteResponse agregarTelefono(UUID id, String telefonoCrudo, boolean verCompleto) {
        Cliente c = vigente(id);
        String telefono = telefono(telefonoCrudo);
        if (!telefonoRepository.existsByClienteIdAndTelefono(id, telefono)) {
            telefonoRepository.save(new ClienteTelefono(id, telefono));
        }
        return respuesta(c, verCompleto);
    }

    @Transactional
    public ClienteResponse quitarTelefono(UUID id, String telefonoCrudo) {
        Cliente c = vigente(id);
        String telefono = telefono(telefonoCrudo);
        List<ClienteTelefono> actuales = telefonoRepository.findByClienteIdOrderByCreadoEnAsc(id);
        if (actuales.size() <= 1) throw new ValidacionNegocioException("La ficha debe conservar al menos un teléfono.");
        actuales.stream().filter(t -> t.getTelefono().equals(telefono)).findFirst().ifPresent(telefonoRepository::delete);
        return respuesta(c, true);
    }

    /** Solo ADMIN habilita (o quita) el canal MAYORISTA, con motivo; queda quien y cuando. */
    @Transactional
    public ClienteResponse cambiarMayorista(UUID id, boolean mayorista, String motivo) {
        Cliente c = vigente(id);
        String m = motivoMayorista(motivo);
        c.setMayorista(mayorista);
        // sin la habilitacion, el canal que se propone vuelve a PUBLICO (V34: MAYORISTA exige ficha mayorista)
        if (!mayorista) c.setCanalHabitual(TipoClienteVenta.PUBLICO.name());
        c.setMayoristaMotivo(m);
        c.setMayoristaCambiadoPor(usuarioActual());
        c.setMayoristaCambiadoEn(LocalDateTime.now(LIMA));
        return respuesta(c, true);
    }

    @Transactional
    public ClienteResponse cambiarActivo(UUID id, boolean activo) {
        Cliente c = vigente(id);
        c.setActivo(activo);
        return respuesta(c, true);
    }

    // ------------------------------------------------------------------ autorizacion comercial (independiente del registro)

    @Transactional
    public ClienteResponse registrarComercial(UUID id, ComercialRequest dto, boolean verCompleto) {
        Cliente c = vigente(id);
        UUID usuario = usuarioActual();
        String forma = forma(dto.forma());
        String nota = dto.nota() == null || dto.nota().isBlank() ? null : dto.nota().trim();
        if (nota != null && nota.length() > 300) throw new ValidacionNegocioException("La nota no puede superar 300 caracteres.");
        if (dto.otorgada()) {
            if (!c.isActivo()) throw new ValidacionNegocioException("La ficha está desactivada.");
            List<String> medios = medios(dto.medios());
            String version = dto.avisoVersion() == null || dto.avisoVersion().isBlank() ? null : dto.avisoVersion().trim();
            if (version == null) throw new ValidacionNegocioException("Indica la versión del aviso que se le informó.");
            consentimientoRepository.save(consentimiento(id, ClienteConsentimiento.COMERCIAL, ClienteConsentimiento.OTORGADO,
                    String.join(",", medios), forma, version, usuario, nota));
        } else {
            // Revocar: tan simple como otorgar (DS 016-2024-JUS arts. 10.4 y 26.5); sin justificacion. Rige desde ahora.
            consentimientoRepository.save(consentimiento(id, ClienteConsentimiento.COMERCIAL, ClienteConsentimiento.REVOCADO, null,
                    forma, privacidad.version(), usuario, nota));
        }
        return respuesta(c, verCompleto);
    }

    public List<ConsentimientoResponse> consentimientos(UUID id) {
        existente(id);
        Map<UUID, String> nombres = new java.util.HashMap<>();
        return consentimientoRepository.findByClienteIdOrderByRegistradoEnDescIdDesc(id).stream()
                .map(k -> new ConsentimientoResponse(k.getFinalidad(), k.getEstado(), lista(k.getMedios()), k.getForma(), k.getAvisoVersion(),
                        k.isAvisoCompleto(), nombres.computeIfAbsent(k.getRegistradoPor(),
                                u -> usuarioRepository.findById(u).map(Usuario::getNombre).orElse(null)),
                        k.getRegistradoEn(), k.getNota()))
                .toList();
    }

    /** true solo si la ULTIMA decision comercial de la ficha es OTORGADO (y, si se pide un medio, lo incluye). */
    public boolean puedeRecibirComunicaciones(UUID clienteId, String medio) {
        return consentimientoRepository.findFirstByClienteIdAndFinalidadOrderByRegistradoEnDescIdDesc(clienteId, ClienteConsentimiento.COMERCIAL)
                .filter(k -> ClienteConsentimiento.OTORGADO.equals(k.getEstado()))
                .map(k -> medio == null || lista(k.getMedios()).contains(medio))
                .orElse(false);
    }

    // ------------------------------------------------------------------ fusion manual (ADMIN)

    /**
     * Une `origen` en `destino`: sus ventas y reservas pasan al destino, sus telefonos se agregan y sus documentos se copian solo si el
     * destino no los tiene. Se rechaza si los DNI o RUC no coinciden (serian personas distintas). Las copias historicas de las ventas
     * no cambian, la condicion de mayorista del destino no cambia y las autorizaciones del origen NO se transfieren (son decisiones
     * de esa ficha). El origen queda inactivo y apuntando al destino; nada se borra.
     */
    @Transactional
    public FusionResponse fusionar(UUID origenId, UUID destinoId, String motivo) {
        if (origenId.equals(destinoId)) throw new ValidacionNegocioException("Elige dos fichas distintas.");
        String m = motivo == null ? "" : motivo.trim();
        if (m.isEmpty()) throw new ValidacionNegocioException("El motivo de la fusión es obligatorio.");
        // bloqueo en orden estable: dos fusiones cruzadas no se interbloquean
        UUID primero = origenId.compareTo(destinoId) < 0 ? origenId : destinoId;
        UUID segundo = primero.equals(origenId) ? destinoId : origenId;
        clienteRepository.bloquear(primero).orElseThrow(() -> new RecursoNoEncontradoException("Cliente no encontrado: " + primero));
        clienteRepository.bloquear(segundo).orElseThrow(() -> new RecursoNoEncontradoException("Cliente no encontrado: " + segundo));
        Cliente origen = vigente(origenId);
        Cliente destino = vigente(destinoId);
        if (origen.getDni() != null && destino.getDni() != null && !origen.getDni().equals(destino.getDni())) {
            throw new ConflictoNegocioException("Las fichas tienen DNI distintos: no son la misma persona.");
        }
        if (origen.getRuc() != null && destino.getRuc() != null && !origen.getRuc().equals(destino.getRuc())) {
            throw new ConflictoNegocioException("Las fichas tienen RUC distintos: no son la misma persona.");
        }
        if (origen.getTipoPersona() != destino.getTipoPersona()) {
            throw new ConflictoNegocioException("Una ficha es de persona natural y la otra de empresa: no se fusionan.");
        }
        UUID usuario = usuarioActual();
        int ventas = ventaRepository.moverCliente(origenId, destinoId);
        int reservas = reservaRepository.moverCliente(origenId, destinoId);
        int telefonos = 0;
        for (ClienteTelefono t : telefonoRepository.findByClienteIdOrderByCreadoEnAsc(origenId)) {
            if (!telefonoRepository.existsByClienteIdAndTelefono(destinoId, t.getTelefono())) {
                telefonoRepository.save(new ClienteTelefono(destinoId, t.getTelefono()));
                telefonos++;
            }
        }
        String dni = origen.getDni(), ruc = origen.getRuc();
        origen.setActivo(false);
        origen.setFusionadoEnId(destinoId);
        clienteRepository.saveAndFlush(origen); // libera sus documentos de los indices unicos de fichas vigentes
        if (destino.getDni() == null && dni != null) destino.setDni(dni);
        if (destino.getRuc() == null && ruc != null) destino.setRuc(ruc);
        if (destino.getCorreo() == null && origen.getCorreo() != null) destino.setCorreo(origen.getCorreo());
        ClienteFusion f = fusionRepository.save(new ClienteFusion(origenId, destinoId, m, usuario, ventas, reservas, telefonos));
        return new FusionResponse(f.getOrigenId(), f.getDestinoId(), f.getMotivo(), nombreUsuario(usuario), ventas, reservas, telefonos, f.getCreadoEn());
    }

    public List<FusionResponse> fusiones(UUID id) {
        existente(id);
        return fusionRepository.findByDestinoIdOrOrigenIdOrderByCreadoEnDesc(id, id).stream()
                .map(f -> new FusionResponse(f.getOrigenId(), f.getDestinoId(), f.getMotivo(), nombreUsuario(f.getUsuarioId()), f.getVentasMovidas(),
                        f.getReservasMovidas(), f.getTelefonosAgregados(), f.getCreadoEn()))
                .toList();
    }

    // ------------------------------------------------------------------ consulta

    public PaginaResponse<ClienteResponse> buscar(Boolean activo, Boolean mayorista, Boolean conComercial, String busqueda, Pageable pageable,
                                                  boolean verCompleto) {
        String patron = BusquedaTexto.patronLike(busqueda);
        String digitos = busqueda == null ? "" : busqueda.replaceAll("[^0-9]", "");
        String telefono = digitos.length() >= 3 ? "%" + digitos + "%" : null;
        Page<Cliente> pagina = clienteRepository.buscar(activo, mayorista, conComercial, patron, patron == null ? null : telefono, pageable);
        List<UUID> ids = pagina.getContent().stream().map(Cliente::getId).toList();
        Map<UUID, List<String>> telefonos = telefonoRepository.findByClienteIdIn(ids).stream()
                .collect(Collectors.groupingBy(ClienteTelefono::getClienteId, Collectors.mapping(ClienteTelefono::getTelefono, Collectors.toList())));
        Map<UUID, ResumenReservas> reservas = historialCompras.reservasPorCliente(ids);
        return PaginaResponse.de(pagina.map(c -> respuesta(c, telefonos.getOrDefault(c.getId(), List.of()),
                reservas.getOrDefault(c.getId(), ResumenReservas.VACIO), verCompleto)));
    }

    public ClienteResponse obtener(UUID id, boolean verCompleto) {
        return respuesta(existente(id), verCompleto);
    }

    Cliente existente(UUID id) {
        return clienteRepository.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Cliente no encontrado: " + id));
    }

    /** Ficha no fusionada (una fusionada solo se consulta: sus datos viven en la ficha destino). */
    Cliente vigente(UUID id) {
        Cliente c = existente(id);
        if (c.getFusionadoEnId() != null) {
            throw new ConflictoNegocioException("Esta ficha se fusionó en otra (" + c.getFusionadoEnId() + "); usa la ficha destino.");
        }
        return c;
    }

    // ------------------------------------------------------------------ reglas de documentos

    public record Documentos(TipoPersona tipo, String nombre, String dni, String ruc, String razonSocial) {}

    static TipoPersona tipo(String valor, TipoPersona porDefecto) {
        return ProveedorService.tipoPersona(valor, porDefecto);
    }

    // Persona natural: nombre obligatorio; DNI (8 digitos) y RUC (10 + DNI si hay DNI) opcionales. Empresa: RUC 20... y razon social
    // obligatorios, sin DNI; nombre opcional (por defecto la razon social). Mismas formas que los proveedores (Fase G).
    public static Documentos documentos(TipoPersona tipo, String nombre, String dni, String ruc, String razonSocial) {
        String n = nombre == null ? "" : nombre.trim().replaceAll("\\s+", " ");
        String d = DocumentoIdentidad.limpiar(dni);
        String r = DocumentoIdentidad.limpiar(ruc);
        String razon = razonSocial == null || razonSocial.isBlank() ? null : razonSocial.trim();
        if (tipo == TipoPersona.NATURAL) {
            if (n.isEmpty()) throw new ValidacionNegocioException("El nombre es obligatorio.");
            if (razon != null) throw new ValidacionNegocioException("La razón social solo se registra para una empresa.");
            if (d != null && !DocumentoIdentidad.dniValido(d)) throw new ValidacionNegocioException("El DNI debe tener exactamente 8 dígitos.");
            if (r != null) {
                if (!DocumentoIdentidad.rucValido(r)) throw new ValidacionNegocioException("El RUC debe tener 11 dígitos y un dígito verificador correcto.");
                if (!r.startsWith(DocumentoIdentidad.PREFIJO_RUC_NATURAL)) throw new ValidacionNegocioException("El RUC de una persona natural empieza con 10.");
                if (d != null && !r.substring(2, 10).equals(d)) {
                    throw new ValidacionNegocioException("El RUC " + r + " no corresponde al DNI " + d + ": es 10 + DNI + un dígito verificador.");
                }
            }
        } else {
            if (d != null) throw new ValidacionNegocioException("Una empresa no registra DNI: se identifica con su RUC.");
            if (r == null) throw new ValidacionNegocioException("El RUC es obligatorio para una empresa.");
            if (!DocumentoIdentidad.rucValido(r)) throw new ValidacionNegocioException("El RUC debe tener 11 dígitos y un dígito verificador correcto.");
            if (!r.startsWith(DocumentoIdentidad.PREFIJO_RUC_EMPRESA)) throw new ValidacionNegocioException("El RUC de una empresa empieza con 20.");
            if (razon == null) throw new ValidacionNegocioException("La razón social es obligatoria para una empresa.");
            if (razon.length() > MAX_NOMBRE) throw new ValidacionNegocioException("La razón social no puede superar " + MAX_NOMBRE + " caracteres.");
            if (n.isEmpty()) n = razon;
        }
        if (n.length() > MAX_NOMBRE) throw new ValidacionNegocioException("El nombre no puede superar " + MAX_NOMBRE + " caracteres.");
        return new Documentos(tipo, n, d, r, razon);
    }

    /**
     * Importacion de Excel: por que esos documentos no pueden ser de una ficha NUEVA (las mismas reglas que registrar: DNI/RUC de otra
     * ficha vigente o de un proveedor), o vacio si pueden. Sin efectos.
     */
    public java.util.Optional<String> conflictoDeDocumentos(Documentos doc) {
        try {
            verificarUnicos(doc, null);
            return java.util.Optional.empty();
        } catch (ConflictoNegocioException e) {
            return java.util.Optional.of(e.getMessage());
        }
    }

    private void verificarUnicos(Documentos doc, UUID propio) {
        if (doc.dni() != null) {
            clienteRepository.findVigenteByDni(doc.dni()).filter(o -> !o.getId().equals(propio)).ifPresent(o -> {
                throw new ConflictoNegocioException("Ya hay una ficha de cliente con ese DNI (" + o.getNombre() + "): elígela en vez de crear otra.");
            });
            proveedorRepository.findByDni(doc.dni()).ifPresent(p -> {
                throw new ConflictoNegocioException("Esa persona ya es proveedor (" + p.getNombre() + "): elígela como proveedor; no se crea una ficha de cliente.");
            });
        }
        if (doc.ruc() != null) {
            clienteRepository.findVigenteByRuc(doc.ruc()).filter(o -> !o.getId().equals(propio)).ifPresent(o -> {
                throw new ConflictoNegocioException("Ya hay una ficha de cliente con ese RUC (" + o.getNombre() + "): elígela en vez de crear otra.");
            });
            proveedorRepository.findByRuc(doc.ruc()).ifPresent(p -> {
                throw new ConflictoNegocioException("Ese RUC es de un proveedor (" + p.getNombre() + "): elígelo como proveedor; no se crea una ficha de cliente.");
            });
            String dniDelRuc = DocumentoIdentidad.dniDelRuc(doc.ruc());
            if (dniDelRuc != null && doc.dni() == null) {
                proveedorRepository.findByDni(dniDelRuc).ifPresent(p -> {
                    throw new ConflictoNegocioException("Ese RUC contiene el DNI del proveedor " + p.getNombre() + ": elígelo como proveedor.");
                });
            }
        }
    }

    private static void aplicar(Cliente c, Documentos doc) {
        c.setTipoPersona(doc.tipo());
        c.setNombre(doc.nombre());
        c.setDni(doc.dni());
        c.setRuc(doc.ruc());
        c.setRazonSocial(doc.razonSocial());
    }

    static String telefono(String crudo) {
        return TelefonoPeru.normalizar(crudo).orElseThrow(() -> new ValidacionNegocioException(
                "El teléfono debe ser un celular peruano: 9 dígitos que empiezan con 9, con o sin +51."));
    }

    private static String correo(String crudo) {
        if (crudo == null || crudo.isBlank()) return null;
        String c = crudo.trim().toLowerCase();
        if (c.length() > 120 || !CORREO.matcher(c).matches()) throw new ValidacionNegocioException("El correo no es válido.");
        return c;
    }

    private static String motivoMayorista(String motivo) {
        String m = motivo == null ? "" : motivo.trim();
        if (m.isEmpty()) throw new ValidacionNegocioException("El motivo es obligatorio.");
        if (m.length() > 300) throw new ValidacionNegocioException("El motivo no puede superar 300 caracteres.");
        return m;
    }

    /** Canal habitual: PUBLICO (por defecto) o MAYORISTA (solo una ficha mayorista). PROVEEDOR es de las fichas de proveedor. */
    public static String canalHabitual(String crudo, boolean mayorista) {
        String canal = crudo == null || crudo.isBlank() ? TipoClienteVenta.PUBLICO.name() : crudo.trim().toUpperCase();
        if (TipoClienteVenta.PROVEEDOR.name().equals(canal)) {
            throw new ValidacionNegocioException("El canal PROVEEDOR es solo para proveedores del padrón: elige al proveedor como comprador, no una ficha de cliente.");
        }
        if (!TipoClienteVenta.PUBLICO.name().equals(canal) && !TipoClienteVenta.MAYORISTA.name().equals(canal)) {
            throw new ValidacionNegocioException("Canal no válido: «" + crudo + "». Usa PUBLICO o MAYORISTA.");
        }
        if (TipoClienteVenta.MAYORISTA.name().equals(canal) && !mayorista) {
            throw new ValidacionNegocioException("El canal MAYORISTA es solo para clientes habilitados como mayoristas.");
        }
        return canal;
    }

    public static String forma(String crudo) {
        String f = crudo == null ? "" : crudo.trim().toUpperCase();
        if (!FORMAS.contains(f)) {
            throw new ValidacionNegocioException("Indica cómo se obtuvo la autorización: VERBAL_PRESENCIAL, ESCRITO, TELEFONICA o DIGITAL.");
        }
        return f;
    }

    public static List<String> medios(List<String> crudos) {
        Set<String> medios = new LinkedHashSet<>();
        if (crudos != null) crudos.forEach(m -> { if (m != null && !m.isBlank()) medios.add(m.trim().toUpperCase()); });
        if (medios.isEmpty()) throw new ValidacionNegocioException("Elige al menos un medio autorizado (WHATSAPP, SMS, LLAMADA o CORREO).");
        for (String m : medios) {
            if (!MEDIOS.contains(m)) throw new ValidacionNegocioException("Medio no válido: «" + m + "». Usa WHATSAPP, SMS, LLAMADA o CORREO.");
        }
        return medios.stream().sorted().toList();
    }

    private ClienteConsentimiento consentimiento(UUID clienteId, String finalidad, String estado, String medios, String forma, String version,
                                                 UUID usuario, String nota) {
        if (usuario == null) throw new ValidacionNegocioException("No se pudo identificar al usuario que registra la autorización.");
        String v = version == null ? "" : version.trim();
        if (v.isEmpty() || v.length() > 20) throw new ValidacionNegocioException("Versión del aviso no válida.");
        ClienteConsentimiento k = new ClienteConsentimiento();
        k.setClienteId(clienteId);
        k.setFinalidad(finalidad);
        k.setEstado(estado);
        k.setMedios(medios);
        k.setForma(forma);
        k.setAvisoVersion(v);
        // si se registro con la version vigente, se guarda si ese aviso estaba completo; con otra version no se puede afirmar
        k.setAvisoCompleto(v.equals(privacidad.version()) && privacidad.completo());
        k.setRegistradoPor(usuario);
        k.setNota(nota);
        return k;
    }

    // ------------------------------------------------------------------ respuesta

    ClienteResponse respuesta(Cliente c, boolean verCompleto) {
        List<String> telefonos = telefonoRepository.findByClienteIdOrderByCreadoEnAsc(c.getId()).stream().map(ClienteTelefono::getTelefono).toList();
        ResumenReservas reservas = historialCompras.reservasPorCliente(List.of(c.getId())).getOrDefault(c.getId(), ResumenReservas.VACIO);
        return respuesta(c, telefonos, reservas, verCompleto);
    }

    private ClienteResponse respuesta(Cliente c, List<String> telefonos, ResumenReservas reservas, boolean verCompleto) {
        return new ClienteResponse(c.getId(), c.getTipoPersona().name(), c.getNombre(), c.getRazonSocial(),
                verCompleto ? c.getDni() : Enmascarar.documento(c.getDni()),
                verCompleto ? c.getRuc() : Enmascarar.documento(c.getRuc()),
                verCompleto ? c.getCorreo() : Enmascarar.correo(c.getCorreo()),
                verCompleto ? telefonos : telefonos.stream().map(Enmascarar::telefono).toList(),
                c.isMayorista(), c.isActivo(), c.getFusionadoEnId(), c.getCreadoEn(), verCompleto,
                estado(c.getId(), ClienteConsentimiento.REGISTRO), estado(c.getId(), ClienteConsentimiento.COMERCIAL), c.getCanalHabitual(),
                reservas);
    }

    private EstadoAutorizacion estado(UUID clienteId, String finalidad) {
        return consentimientoRepository.findFirstByClienteIdAndFinalidadOrderByRegistradoEnDescIdDesc(clienteId, finalidad)
                .map(k -> new EstadoAutorizacion(ClienteConsentimiento.OTORGADO.equals(k.getEstado()), k.getEstado(), lista(k.getMedios()),
                        k.getForma(), k.getRegistradoEn(), k.getAvisoVersion(), k.isAvisoCompleto()))
                .orElse(new EstadoAutorizacion(false, null, List.of(), null, null, null, false));
    }

    private static List<String> lista(String medios) {
        return medios == null || medios.isBlank() ? List.of() : Arrays.stream(medios.split(",")).map(String::trim).toList();
    }

    UUID usuarioActual() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null) return null;
        return usuarioRepository.findByEmail(a.getName()).map(Usuario::getId).orElse(null);
    }

    private String nombreUsuario(UUID id) {
        return id == null ? null : usuarioRepository.findById(id).map(Usuario::getNombre).orElse(null);
    }
}
