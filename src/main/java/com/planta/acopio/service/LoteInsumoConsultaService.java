package com.planta.acopio.service;

import com.planta.acopio.dto.LoteInsumoResponse;
import com.planta.acopio.entity.DocumentoIngreso;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.LoteInsumo;
import com.planta.acopio.entity.ProveedorCompra;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.LoteInsumoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Consulta de lotes de insumo con su estado: AGOTADO (sin existencia), VENCIDO (su ultimo dia valido ya paso), POR_VENCER (vence en
 * los proximos `dias-alerta` dias, 15 por defecto, configurable con app.insumos.dias-alerta-vencimiento) o VIGENTE. El estado se
 * calcula al consultar con la fecha de negocio de ReglaVencimiento; no se guarda.
 */
@Service
public class LoteInsumoConsultaService {

    private final LoteInsumoRepository repository;
    private final InsumoRepository insumoRepository;
    private final ReglaVencimiento reglaVencimiento;
    private final int diasAlerta;

    public LoteInsumoConsultaService(LoteInsumoRepository repository, InsumoRepository insumoRepository, ReglaVencimiento reglaVencimiento,
                                     @Value("${app.insumos.dias-alerta-vencimiento:15}") int diasAlerta) {
        this.repository = repository;
        this.insumoRepository = insumoRepository;
        this.reglaVencimiento = reglaVencimiento;
        this.diasAlerta = diasAlerta;
    }

    public int diasAlerta() {
        return diasAlerta;
    }

    /** Lotes de un insumo (mas recientes primero). `soloConExistencia`: oculta los agotados. */
    @Transactional(readOnly = true)
    public List<LoteInsumoResponse> listar(UUID insumoId, boolean soloConExistencia) {
        Insumo insumo = insumoRepository.findById(insumoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + insumoId));
        LocalDate hoy = reglaVencimiento.hoy();
        return repository.findByInsumoIdOrderByCreadoEnDescIdDesc(insumo.getId()).stream()
                .filter(l -> !soloConExistencia || l.getCantidadDisponible().signum() > 0)
                .map(l -> aRespuesta(l, hoy)).toList();
    }

    @Transactional(readOnly = true)
    public LoteInsumoResponse obtener(UUID loteId) {
        LoteInsumo l = repository.findById(loteId).orElseThrow(() -> new RecursoNoEncontradoException("Lote no encontrado: " + loteId));
        return aRespuesta(l, reglaVencimiento.hoy());
    }

    /** Estado de un lote a la fecha `hoy`. */
    public String estado(LoteInsumo l, LocalDate hoy) {
        if (l.getCantidadDisponible().signum() == 0) return "AGOTADO";
        if (l.getFechaVencimiento() == null) return "VIGENTE";
        if (l.getFechaVencimiento().isBefore(hoy)) return "VENCIDO";
        return ChronoUnit.DAYS.between(hoy, l.getFechaVencimiento()) <= diasAlerta ? "POR_VENCER" : "VIGENTE";
    }

    public static void exigirPositivo(java.math.BigDecimal cantidad) {
        if (cantidad == null || cantidad.signum() < 0) {
            throw new ValidacionNegocioException("La cantidad contada no puede ser negativa.");
        }
    }

    LoteInsumoResponse aRespuesta(LoteInsumo l, LocalDate hoy) {
        Insumo i = l.getInsumo();
        ProveedorCompra p = l.getProveedor();
        DocumentoIngreso d = l.getDocumentoIngreso();
        Long dias = l.getFechaVencimiento() == null ? null : ChronoUnit.DAYS.between(hoy, l.getFechaVencimiento());
        return new LoteInsumoResponse(l.getId(), i.getId(), i.getNombre(), i.getUnidadMedida(), l.getCodigo(), l.getCodigoProveedor(),
                l.getFechaIngreso(), l.getFechaVencimiento(), p == null ? null : p.getId(), p == null ? null : p.getNombre(),
                p == null ? null : p.getRuc(), d == null ? null : d.getId(), d == null ? null : d.getTipo().name(),
                d == null ? null : d.getNumero(), l.getCantidadInicial(), l.getCantidadDisponible(), estado(l, hoy), dias);
    }
}
