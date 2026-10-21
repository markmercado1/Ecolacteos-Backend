package com.planta.acopio.service;

import com.planta.acopio.dto.GenerarPagoRequest;
import com.planta.acopio.dto.PagoResponse;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.PrecioLitro;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.PagoRepository;
import com.planta.acopio.repository.PrecioLitroRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// RF-PAG (CORE_DESIGN.md §11, flujo 4). mejora #4 (decision aprobada §18 punto
// 4): cada Pago queda vinculado exactamente a los RegistroAcopio que liquida,
// via pago_registro_acopio -- no solo un acumulado de litros. Esto es lo que
// permite reconstruir un pago ante una disputa (RNF-03/05).
@Service
public class PagoService {

    private final PagoRepository pagoRepository;
    private final ProveedorRepository proveedorRepository;
    private final RegistroAcopioRepository registroAcopioRepository;
    private final PrecioLitroRepository precioLitroRepository;
    private final EntityManager entityManager;

    public PagoService(PagoRepository pagoRepository, ProveedorRepository proveedorRepository,
                        RegistroAcopioRepository registroAcopioRepository, PrecioLitroRepository precioLitroRepository,
                        EntityManager entityManager) {
        this.pagoRepository = pagoRepository;
        this.proveedorRepository = proveedorRepository;
        this.registroAcopioRepository = registroAcopioRepository;
        this.precioLitroRepository = precioLitroRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public PagoResponse generar(GenerarPagoRequest dto) {
        pagoRepository.findByProveedorIdAndSemanaInicio(dto.proveedorId(), dto.semanaInicio())
                .ifPresent(p -> { throw new ConflictoNegocioException(
                        "Ya existe un pago para el proveedor " + dto.proveedorId() + " en la semana " + dto.semanaInicio()); });

        Proveedor proveedor = proveedorRepository.findById(dto.proveedorId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + dto.proveedorId()));

        LocalDateTime inicio = dto.semanaInicio().atStartOfDay();
        LocalDateTime fin = dto.semanaFin().atTime(LocalTime.MAX);

        List<RegistroAcopio> registros = registroAcopioRepository
                .findByProveedorIdAndFechaHoraBetween(dto.proveedorId(), inicio, fin);

        if (registros.isEmpty()) {
            throw new ValidacionNegocioException(
                    "No hay registros de acopio para el proveedor " + dto.proveedorId() + " en el rango indicado");
        }

        // Segunda auditoria (flujos end-to-end §6/§14-G): el UNIQUE(proveedor_id,
        // semana_inicio) de mas abajo solo bloquea repetir la misma fecha de
        // inicio; sin este chequeo, un rango que se solapa con un pago previo
        // (con otra semanaInicio) volveria a liquidar los mismos registros.
        List<UUID> yaLiquidados = registros.stream()
                .map(RegistroAcopio::getId)
                .filter(pagoRepository::existeRegistroYaLiquidado)
                .toList();
        if (!yaLiquidados.isEmpty()) {
            throw new ConflictoNegocioException(
                    "El rango indicado incluye " + yaLiquidados.size() + " registro(s) de acopio que ya fueron "
                            + "liquidados en un pago anterior (ej. " + yaLiquidados.get(0) + "); ajuste el rango de fechas");
        }

        BigDecimal litrosTotales = registros.stream()
                .map(RegistroAcopio::getLitros)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PrecioLitro precioVigente = precioLitroRepository.findVigenteHoy()
                .orElseThrow(() -> new RecursoNoEncontradoException("No hay un precio por litro vigente configurado"));

        Pago pago = new Pago();
        pago.setProveedor(proveedor);
        pago.setSemanaInicio(dto.semanaInicio());
        pago.setSemanaFin(dto.semanaFin());
        pago.setLitrosTotales(litrosTotales);
        pago.setPrecioLitro(precioVigente.getPrecio());
        pago.setRegistrosLiquidados(Set.copyOf(registros)); // mejora #4

        pago = pagoRepository.save(pago);
        // total es columna GENERATED en PostgreSQL: se relee tras el INSERT
        // para devolverla calculada en la respuesta (mismo patron que
        // ConciliacionService con diferencia_pct).
        entityManager.flush();
        entityManager.refresh(pago);

        return toResponse(pago);
    }

    // Flujo end-to-end en vivo (verificacion con BD real): sin @Transactional
    // esto lanzaba LazyInitializationException -- registrosLiquidados es
    // @ManyToMany LAZY y toResponse() la recorre; open-in-view esta en false
    // (application.yml). Bug preexistente (ya estaba en el diseño original de
    // Fase 2, nunca antes ejecutado contra una BD real). Confirmado en
    // caliente: GET /api/pagos/proveedor/{id} devolvia 500 antes de este fix.
    @Transactional(readOnly = true)
    public List<PagoResponse> historialPorProveedor(java.util.UUID proveedorId) {
        return pagoRepository.findByProveedorIdOrderBySemanaInicioDesc(proveedorId).stream()
                .map(this::toResponse).toList();
    }

    // Auditoria de bajo riesgo (Fase 1): antes solo se podia listar pago por
    // pago via historialPorProveedor -- ADMIN no tenia forma de ver todos los
    // pagos sin conocer de antemano cada proveedorId. Mismo fix de
    // @Transactional que historialPorProveedor (ver comentario arriba).
    //
    // Paginacion pendiente: el listado global ahora es paginado en la BD (la
    // tabla pago crece cada semana por proveedor). Solo lo consume la web.
    @Transactional(readOnly = true)
    public Page<PagoResponse> listar(Pageable pageable) {
        return pagoRepository.findAllByOrderBySemanaInicioDescIdDesc(pageable).map(this::toResponse);
    }

    // Paginacion pendiente: historial por proveedor paginado para la web. La
    // version sin paginar (historialPorProveedor) se mantiene para la app movil.
    @Transactional(readOnly = true)
    public Page<PagoResponse> historialPorProveedor(java.util.UUID proveedorId, Pageable pageable) {
        return pagoRepository.findByProveedorIdOrderBySemanaInicioDescIdDesc(proveedorId, pageable)
                .map(this::toResponse);
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba el detalle individual de un pago.
    // Mismo fix de @Transactional que historialPorProveedor -- ver comentario arriba.
    @Transactional(readOnly = true)
    public PagoResponse obtener(java.util.UUID id) {
        return toResponse(pagoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago no encontrado: " + id)));
    }

    // Auditoria CRUD (prioridad MEDIA): Pago.comprobanteGenerado existia en la
    // entidad desde el diseño original (RF-PAG-02) pero ningun codigo lo ponia
    // en true -- no habia forma de registrar que el comprobante ya se imprimio
    // o se mostro en pantalla al proveedor.
    @Transactional
    public PagoResponse marcarComprobanteGenerado(java.util.UUID id) {
        Pago pago = pagoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago no encontrado: " + id));
        pago.setComprobanteGenerado(true);
        return toResponse(pago);
    }

    private PagoResponse toResponse(Pago p) {
        return new PagoResponse(
                p.getId(), p.getProveedor().getId(), p.getProveedor().getNombre(),
                p.getSemanaInicio(), p.getSemanaFin(), p.getLitrosTotales(), p.getPrecioLitro(),
                p.getTotal(), p.getComprobanteGenerado(),
                p.getRegistrosLiquidados().stream().map(RegistroAcopio::getId).collect(Collectors.toList()));
    }
}
