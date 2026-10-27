package com.planta.acopio.service;

import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionIgvRequest;
import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionIgvResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionLoteRequest;
import com.planta.acopio.dto.ConfiguracionIgvDtos.AfectacionLoteResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.CambioCalculoResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.EstadoIgvResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.FilaAfectacionIgvResponse;
import com.planta.acopio.dto.ConfiguracionIgvDtos.TasaIgvRequest;
import com.planta.acopio.dto.ConfiguracionIgvDtos.TasaIgvResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.entity.AfectacionIgv;
import com.planta.acopio.entity.AfectacionIgvPresentacion;
import com.planta.acopio.entity.CambioCalculoIgv;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.TasaIgv;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AfectacionIgvRepository;
import com.planta.acopio.repository.CambioCalculoIgvRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.TasaIgvRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Fase G: IGV configurable en ventas. La configuracion (tasa con vigencia y afectacion por presentacion con vigencia) la decide
// un ADMIN desde el panel, y el calculo general se ENCIENDE o APAGA tambien desde el panel (V35: cada cambio queda registrado con su
// motivo, quien y cuando; el estado vigente es el mas reciente). Mientras nadie lo haya decidido rige app.tributario.igv.calculo-habilitado
// (apagado por defecto). Con el calculo apagado las ventas se registran exactamente como antes (sin foto tributaria) y el catalogo no
// muestra "Incluye IGV". Cambiar el interruptor NUNCA recalcula ventas ya registradas ni importes de reservas.
//
// Con el calculo encendido, una linea se desglosa solo si su presentacion tiene afectacion vigente a la FECHA DE LA VENTA
// (GRAVADO con una tasa vigente a esa fecha, o EXONERADO/INAFECTO); en cualquier otro caso queda "sin determinar". Nunca se
// recalculan ventas ya registradas ni importes de reservas.
@Service
public class ConfiguracionIgvService {

    private static final int MAX_MOTIVO = 500;

    private final TasaIgvRepository tasaRepository;
    private final AfectacionIgvRepository afectacionRepository;
    private final PresentacionRepository presentacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final CambioCalculoIgvRepository cambioRepository;
    private final boolean calculoPorDefecto;
    private final Clock reloj;

    // Estado del interruptor leido de la BD (null = nadie lo decidio aun: rige el valor por defecto). Se refresca cada pocos segundos para
    // que un cambio hecho en otra instancia tambien se note, y al instante en esta.
    private static final long REFRESCO_NANOS = 15_000_000_000L;
    private volatile Boolean decisionBd;
    private volatile long leidoEn;
    private volatile boolean leido;

    @Autowired
    public ConfiguracionIgvService(TasaIgvRepository tasaRepository, AfectacionIgvRepository afectacionRepository,
                                   PresentacionRepository presentacionRepository, UsuarioRepository usuarioRepository,
                                   CambioCalculoIgvRepository cambioRepository,
                                   @Value("${app.tributario.igv.calculo-habilitado:false}") boolean calculoPorDefecto) {
        this(tasaRepository, afectacionRepository, presentacionRepository, usuarioRepository, cambioRepository, calculoPorDefecto,
                Clock.system(ZoneId.of("America/Lima")));
    }

    // Sin repositorio de cambios: solo rige el valor por defecto (pruebas unitarias del calculo).
    ConfiguracionIgvService(TasaIgvRepository tasaRepository, AfectacionIgvRepository afectacionRepository,
                            PresentacionRepository presentacionRepository, UsuarioRepository usuarioRepository,
                            boolean calculoPorDefecto, Clock reloj) {
        this(tasaRepository, afectacionRepository, presentacionRepository, usuarioRepository, null, calculoPorDefecto, reloj);
    }

    ConfiguracionIgvService(TasaIgvRepository tasaRepository, AfectacionIgvRepository afectacionRepository,
                            PresentacionRepository presentacionRepository, UsuarioRepository usuarioRepository,
                            CambioCalculoIgvRepository cambioRepository, boolean calculoPorDefecto, Clock reloj) {
        this.tasaRepository = tasaRepository;
        this.afectacionRepository = afectacionRepository;
        this.presentacionRepository = presentacionRepository;
        this.usuarioRepository = usuarioRepository;
        this.cambioRepository = cambioRepository;
        this.calculoPorDefecto = calculoPorDefecto;
        this.reloj = reloj;
    }

    /** ¿Se desglosa el IGV en las ventas nuevas? Lo decide el ultimo cambio hecho desde el panel o, si no hay ninguno, el valor por defecto. */
    public boolean calculoHabilitado() {
        if (cambioRepository == null) return calculoPorDefecto;
        if (!leido || System.nanoTime() - leidoEn > REFRESCO_NANOS) {
            decisionBd = cambioRepository.findFirstByOrderByCreadoEnDescIdDesc().map(CambioCalculoIgv::isHabilitado).orElse(null);
            leidoEn = System.nanoTime();
            leido = true;
        }
        Boolean d = decisionBd;
        return d != null ? d : calculoPorDefecto;
    }

    /**
     * Enciende o apaga el calculo del IGV (ADMIN). Siempre con motivo; queda registrado. No recalcula ventas ya registradas ni reservas:
     * solo cambia como se registran las ventas NUEVAS y si el catalogo muestra «Incluye IGV». Pedir el estado en el que ya esta es un 409.
     */
    @Transactional
    public EstadoIgvResponse cambiarCalculo(boolean habilitado, String motivoTexto) {
        String m = motivo(motivoTexto);
        if (cambioRepository == null) throw new IllegalStateException("El cambio del calculo no esta disponible");
        leido = false; // se vuelve a leer de la BD: otra instancia pudo cambiarlo hace un momento
        if (calculoHabilitado() == habilitado) {
            throw new ConflictoNegocioException("El cálculo del IGV ya está " + (habilitado ? "activado" : "desactivado") + ".");
        }
        CambioCalculoIgv c = new CambioCalculoIgv();
        c.setHabilitado(habilitado);
        c.setMotivo(m);
        c.setUsuario(usuarioActual());
        cambioRepository.save(c);
        decisionBd = habilitado;
        leidoEn = System.nanoTime();
        leido = true;
        return estado();
    }

    // ------------------------------------------------------------------ calculo (ventas y catalogo)

    /**
     * Foto tributaria de una linea vendida, o null = sin determinar (calculo apagado, presentacion sin clasificar o gravada
     * sin tasa vigente a la fecha). `subtotal` es lo que se cobra: el IGV se desglosa dentro de el.
     */
    public CalculoIgv.Desglose desglosar(UUID presentacionId, LocalDate fecha, BigDecimal subtotal) {
        if (!calculoHabilitado()) return null;
        AfectacionIgv afectacion = afectacionRepository
                .findFirstByPresentacionIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(presentacionId, fecha)
                .map(AfectacionIgvPresentacion::getAfectacion).orElse(AfectacionIgv.SIN_DEFINIR);
        return switch (afectacion) {
            case SIN_DEFINIR -> null;
            case EXONERADO, INAFECTO -> CalculoIgv.sinIgv(afectacion, subtotal);
            case GRAVADO -> tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(fecha)
                    .map(t -> CalculoIgv.gravado(subtotal, t.getTasa())).orElse(null);
        };
    }

    /** De estas presentaciones, las que a esa fecha se venden gravadas con una tasa vigente ("Incluye IGV"). Vacio si el calculo esta apagado. */
    public Set<UUID> gravadasConTasa(Collection<UUID> presentacionIds, LocalDate fecha) {
        if (!calculoHabilitado() || presentacionIds.isEmpty()) return Set.of();
        if (tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(fecha).isEmpty()) return Set.of();
        Set<UUID> gravadas = new HashSet<>();
        porPresentacion(afectacionRepository.findByPresentacionIdIn(presentacionIds)).forEach((id, serie) -> {
            AfectacionIgvPresentacion vigente = vigenteA(serie, fecha);
            if (vigente != null && vigente.getAfectacion() == AfectacionIgv.GRAVADO) gravadas.add(id);
        });
        return gravadas;
    }

    // ------------------------------------------------------------------ configuracion (ADMIN)

    @Transactional(readOnly = true)
    public EstadoIgvResponse estado() {
        LocalDate hoy = LocalDate.now(reloj);
        TasaIgvResponse vigente = tasaRepository.findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(hoy).map(this::toResponse).orElse(null);
        TasaIgvResponse programada = tasaRepository.findFirstByVigenteDesdeAfterOrderByVigenteDesdeAsc(hoy).map(this::toResponse).orElse(null);

        List<UUID> activas = presentacionRepository.idsActivas();
        Map<UUID, List<AfectacionIgvPresentacion>> series = activas.isEmpty() ? Map.of()
                : porPresentacion(afectacionRepository.findByPresentacionIdIn(activas));
        long gravadas = 0, exoneradas = 0, inafectas = 0;
        for (UUID id : activas) {
            AfectacionIgvPresentacion a = vigenteA(series.getOrDefault(id, List.of()), hoy);
            AfectacionIgv valor = a == null ? AfectacionIgv.SIN_DEFINIR : a.getAfectacion();
            if (valor == AfectacionIgv.GRAVADO) gravadas++;
            else if (valor == AfectacionIgv.EXONERADO) exoneradas++;
            else if (valor == AfectacionIgv.INAFECTO) inafectas++;
        }
        long sinDefinir = activas.size() - gravadas - exoneradas - inafectas;

        boolean calculoHabilitado = calculoHabilitado();
        List<String> avisos = new ArrayList<>();
        if (!calculoHabilitado) {
            avisos.add("El cálculo del IGV está desactivado: las ventas se registran como hasta ahora, sin desglose. Un administrador puede "
                    + "activarlo desde esta pantalla cuando lo decida.");
        }
        if (vigente == null) {
            avisos.add(programada == null ? "No hay una tasa de IGV registrada." : "La tasa de IGV registrada todavía no está vigente.");
        }
        if (sinDefinir > 0) {
            avisos.add(sinDefinir + (sinDefinir == 1 ? " presentación activa no tiene" : " presentaciones activas no tienen")
                    + " afectación al IGV definida: sus ventas quedan «sin determinar».");
        }
        if (gravadas > 0 && vigente == null) {
            avisos.add("Hay presentaciones gravadas pero ninguna tasa vigente: sus ventas quedarían «sin determinar».");
        }
        CambioCalculoResponse ultimo = cambioRepository == null ? null : cambioRepository.findFirstByOrderByCreadoEnDescIdDesc()
                .map(c -> new CambioCalculoResponse(c.isHabilitado(), c.getMotivo(), c.getUsuario() != null ? c.getUsuario().getNombre() : null,
                        c.getCreadoEn())).orElse(null);
        return new EstadoIgvResponse(calculoHabilitado, calculoHabilitado ? "ACTIVO" : "PENDIENTE", vigente, programada,
                activas.size(), sinDefinir, gravadas, exoneradas, inafectas, avisos, ultimo);
    }

    public List<TasaIgvResponse> listarTasas() {
        return tasaRepository.findAllByOrderByVigenteDesdeDesc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public TasaIgvResponse registrarTasa(TasaIgvRequest dto) {
        BigDecimal tasa = dto.tasa();
        if (tasa.signum() <= 0 || tasa.compareTo(new BigDecimal("100")) >= 0) {
            throw new ValidacionNegocioException("La tasa debe ser un porcentaje mayor que 0 y menor que 100 (por ejemplo, 18).");
        }
        if (tasa.stripTrailingZeros().scale() > 2) {
            throw new ValidacionNegocioException("La tasa admite hasta 2 decimales.");
        }
        if (tasaRepository.existsByVigenteDesde(dto.vigenteDesde())) {
            throw new ConflictoNegocioException("Ya hay una tasa de IGV con vigencia desde el " + dto.vigenteDesde()
                    + ". Registra la nueva tasa con otra fecha.");
        }
        TasaIgv t = new TasaIgv();
        t.setTasa(tasa.setScale(2));
        t.setVigenteDesde(dto.vigenteDesde());
        t.setMotivo(motivo(dto.motivo()));
        t.setUsuario(usuarioActual());
        return toResponse(tasaRepository.save(t));
    }

    public PaginaResponse<FilaAfectacionIgvResponse> listarAfectaciones(String busqueda, Pageable pageable) {
        LocalDate hoy = LocalDate.now(reloj);
        Page<Presentacion> pagina = presentacionRepository.buscarParaAfectacionIgv(BusquedaTexto.patronLike(busqueda), pageable);
        List<UUID> ids = pagina.getContent().stream().map(Presentacion::getId).toList();
        Map<UUID, List<AfectacionIgvPresentacion>> series = ids.isEmpty() ? Map.of()
                : porPresentacion(afectacionRepository.findByPresentacionIdIn(ids));
        return PaginaResponse.de(pagina.map(pr -> {
            List<AfectacionIgvPresentacion> serie = series.getOrDefault(pr.getId(), List.of());
            AfectacionIgvPresentacion vigente = vigenteA(serie, hoy);
            AfectacionIgvPresentacion programada = serie.stream().filter(a -> a.getVigenteDesde().isAfter(hoy))
                    .min(Comparator.comparing(AfectacionIgvPresentacion::getVigenteDesde)).orElse(null);
            var producto = pr.getProducto();
            return new FilaAfectacionIgvResponse(pr.getId(), pr.getNombre(), producto.getId(), producto.getNombre(),
                    producto.getFamilia() != null ? producto.getFamilia().getNombre() : null,
                    vigente == null ? AfectacionIgv.SIN_DEFINIR.name() : vigente.getAfectacion().name(),
                    vigente == null ? null : vigente.getVigenteDesde(),
                    programada == null ? null : programada.getAfectacion().name(),
                    programada == null ? null : programada.getVigenteDesde());
        }));
    }

    public List<AfectacionIgvResponse> historialAfectacion(UUID presentacionId) {
        if (!presentacionRepository.existsById(presentacionId)) {
            throw new RecursoNoEncontradoException("Presentación no encontrada: " + presentacionId);
        }
        return afectacionRepository.findByPresentacionIdOrderByVigenteDesdeDesc(presentacionId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public AfectacionIgvResponse registrarAfectacion(UUID presentacionId, AfectacionIgvRequest dto) {
        Presentacion presentacion = presentacionRepository.findById(presentacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada: " + presentacionId));
        AfectacionIgv afectacion;
        try {
            afectacion = AfectacionIgv.valueOf(dto.afectacion().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidacionNegocioException("Afectación no válida: «" + dto.afectacion() + "». Usa GRAVADO, EXONERADO, INAFECTO o SIN_DEFINIR.");
        }
        if (afectacionRepository.existsByPresentacionIdAndVigenteDesde(presentacionId, dto.vigenteDesde())) {
            throw new ConflictoNegocioException("La presentación " + presentacion.getNombre() + " ya tiene una afectación con vigencia desde el "
                    + dto.vigenteDesde() + ". Registra el cambio con otra fecha.");
        }
        AfectacionIgvPresentacion a = new AfectacionIgvPresentacion();
        a.setPresentacion(presentacion);
        a.setAfectacion(afectacion);
        a.setVigenteDesde(dto.vigenteDesde());
        a.setMotivo(motivo(dto.motivo()));
        a.setUsuario(usuarioActual());
        return toResponse(afectacionRepository.save(a));
    }

    /**
     * Clasifica de una vez las presentaciones activas. Por defecto solo las que no tienen afectacion definida a esa fecha (nunca pisa una
     * decision ya tomada); con soloSinDefinir=false tambien cambia las ya clasificadas. Una presentacion que ya tiene un registro con
     * esa misma fecha se omite. Cada una queda con su propio registro, que se puede corregir despues una a una.
     */
    @Transactional
    public AfectacionLoteResponse registrarAfectacionEnLote(AfectacionLoteRequest dto) {
        AfectacionIgv afectacion;
        try {
            afectacion = AfectacionIgv.valueOf(dto.afectacion().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidacionNegocioException("Afectación no válida: «" + dto.afectacion() + "». Usa GRAVADO, EXONERADO o INAFECTO.");
        }
        if (afectacion == AfectacionIgv.SIN_DEFINIR) {
            throw new ValidacionNegocioException("Para clasificar en lote usa GRAVADO, EXONERADO o INAFECTO.");
        }
        String m = motivo(dto.motivo());
        boolean soloSinDefinir = dto.soloSinDefinir() == null || dto.soloSinDefinir();
        Usuario usuario = usuarioActual();
        List<UUID> activas = presentacionRepository.idsActivas();
        Map<UUID, List<AfectacionIgvPresentacion>> series = activas.isEmpty() ? Map.of() : porPresentacion(afectacionRepository.findByPresentacionIdIn(activas));
        int aplicadas = 0;
        int omitidas = 0;
        for (UUID id : activas) {
            List<AfectacionIgvPresentacion> serie = series.getOrDefault(id, List.of());
            AfectacionIgvPresentacion vigente = vigenteA(serie, dto.vigenteDesde());
            boolean definida = vigente != null && vigente.getAfectacion() != AfectacionIgv.SIN_DEFINIR;
            boolean mismaFecha = serie.stream().anyMatch(a -> a.getVigenteDesde().equals(dto.vigenteDesde()));
            if ((soloSinDefinir && definida) || mismaFecha || (vigente != null && vigente.getAfectacion() == afectacion)) {
                omitidas++;
                continue;
            }
            AfectacionIgvPresentacion a = new AfectacionIgvPresentacion();
            a.setPresentacion(presentacionRepository.getReferenceById(id));
            a.setAfectacion(afectacion);
            a.setVigenteDesde(dto.vigenteDesde());
            a.setMotivo(m);
            a.setUsuario(usuario);
            afectacionRepository.save(a);
            aplicadas++;
        }
        return new AfectacionLoteResponse(aplicadas, omitidas);
    }

    // ------------------------------------------------------------------ auxiliares

    private static Map<UUID, List<AfectacionIgvPresentacion>> porPresentacion(List<AfectacionIgvPresentacion> filas) {
        return filas.stream().collect(Collectors.groupingBy(a -> a.getPresentacion().getId()));
    }

    static AfectacionIgvPresentacion vigenteA(List<AfectacionIgvPresentacion> serie, LocalDate fecha) {
        return serie.stream().filter(a -> !a.getVigenteDesde().isAfter(fecha))
                .max(Comparator.comparing(AfectacionIgvPresentacion::getVigenteDesde)).orElse(null);
    }

    private static String motivo(String motivo) {
        String limpio = motivo == null ? "" : motivo.trim();
        if (limpio.isEmpty()) throw new ValidacionNegocioException("El motivo es obligatorio.");
        if (limpio.length() > MAX_MOTIVO) throw new ValidacionNegocioException("El motivo no puede superar " + MAX_MOTIVO + " caracteres.");
        return limpio;
    }

    private Usuario usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || autenticacion.getName() == null) return null;
        return usuarioRepository.findByEmail(autenticacion.getName()).orElse(null);
    }

    private TasaIgvResponse toResponse(TasaIgv t) {
        return new TasaIgvResponse(t.getId(), t.getTasa(), t.getVigenteDesde(), t.getMotivo(),
                t.getUsuario() != null ? t.getUsuario().getNombre() : null, t.getCreadoEn());
    }

    private AfectacionIgvResponse toResponse(AfectacionIgvPresentacion a) {
        return new AfectacionIgvResponse(a.getId(), a.getPresentacion().getId(), a.getAfectacion().name(), a.getVigenteDesde(),
                a.getMotivo(), a.getUsuario() != null ? a.getUsuario().getNombre() : null, a.getCreadoEn());
    }
}
