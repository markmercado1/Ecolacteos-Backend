package com.planta.acopio.service;

import com.planta.acopio.dto.KardexResponse;
import com.planta.acopio.dto.MovimientoKardexResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import com.planta.acopio.repository.MovimientoInventarioLecheRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Kardex por cantidades (V19) de leche, insumos y producto terminado. Solo lectura sobre los tres libros
 * mayores append-only que ya alimentan los flujos existentes (recepcion, produccion, ventas, ajustes): no hay
 * un inventario paralelo. El rango [desde, hasta] es por fecha de REGISTRO del movimiento (hasta inclusive);
 * el saldo de cada fila lo calcula la base de datos sobre el libro completo.
 */
@Service
public class KardexService {

    private final MovimientoInventarioLecheRepository lecheRepository;
    private final MovimientoInsumoRepository insumoMovRepository;
    private final MovimientoInventarioRepository productoMovRepository;
    private final InsumoRepository insumoRepository;
    private final PresentacionRepository presentacionRepository;

    public KardexService(MovimientoInventarioLecheRepository lecheRepository, MovimientoInsumoRepository insumoMovRepository,
                         MovimientoInventarioRepository productoMovRepository, InsumoRepository insumoRepository,
                         PresentacionRepository presentacionRepository) {
        this.lecheRepository = lecheRepository;
        this.insumoMovRepository = insumoMovRepository;
        this.productoMovRepository = productoMovRepository;
        this.insumoRepository = insumoRepository;
        this.presentacionRepository = presentacionRepository;
    }

    @Transactional(readOnly = true)
    public KardexResponse leche(LocalDate desde, LocalDate hasta, Pageable pageable) {
        Rango r = rango(desde, hasta);
        return armar("LECHE", null, "Leche", "L", desde, hasta,
                lecheRepository.resumen(r.desde(), r.hasta()), lecheRepository.kardex(r.desde(), r.hasta(), pageable));
    }

    @Transactional(readOnly = true)
    public KardexResponse insumo(UUID insumoId, LocalDate desde, LocalDate hasta, Pageable pageable) {
        Insumo insumo = insumoRepository.findById(insumoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + insumoId));
        Rango r = rango(desde, hasta);
        return armar("INSUMO", insumo.getId(), insumo.getNombre(), insumo.getUnidadMedida(), desde, hasta,
                insumoMovRepository.resumen(insumoId, r.desde(), r.hasta()),
                insumoMovRepository.kardex(insumoId, r.desde(), r.hasta(), pageable));
    }

    @Transactional(readOnly = true)
    public KardexResponse presentacion(UUID presentacionId, LocalDate desde, LocalDate hasta, Pageable pageable) {
        Presentacion p = presentacionRepository.findById(presentacionId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada: " + presentacionId));
        Rango r = rango(desde, hasta);
        return armar("PRESENTACION", p.getId(), p.getProducto().getNombre() + " · " + p.getNombre(), p.getUnidadConteo(),
                desde, hasta, productoMovRepository.resumen(presentacionId, r.desde(), r.hasta()),
                productoMovRepository.kardex(presentacionId, r.desde(), r.hasta(), pageable));
    }

    record Rango(LocalDateTime desde, LocalDateTime hasta) {}

    /** [desde 00:00, hasta+1 00:00): ambos extremos opcionales; hasta inclusive como dia completo. */
    static Rango rango(LocalDate desde, LocalDate hasta) {
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new ValidacionNegocioException("La fecha 'desde' no puede ser posterior a 'hasta'.");
        }
        return new Rango(desde != null ? desde.atStartOfDay() : null,
                hasta != null ? hasta.plusDays(1).atStartOfDay() : null);
    }

    static KardexResponse armar(String tipoItem, UUID itemId, String nombre, String unidad, LocalDate desde,
                                LocalDate hasta, List<Object[]> resumen, Page<Object[]> filas) {
        Object[] totales = resumen.isEmpty() ? new Object[]{0, 0, 0} : resumen.get(0);
        BigDecimal saldoInicial = decimal(totales[0]);
        BigDecimal entradas = decimal(totales[1]);
        BigDecimal salidas = decimal(totales[2]);
        PaginaResponse<MovimientoKardexResponse> movimientos = PaginaResponse.de(filas.map(KardexService::fila));
        return new KardexResponse(tipoItem, itemId, nombre, unidad, desde, hasta, saldoInicial, entradas, salidas,
                saldoInicial.add(entradas).subtract(salidas), movimientos);
    }

    /** Columnas comunes de las tres consultas de kardex (ver MovimientoInsumoRepository.kardex()). */
    static MovimientoKardexResponse fila(Object[] f) {
        BigDecimal cantidad = decimal(f[3]);
        return new MovimientoKardexResponse(
                (UUID) f[0], fechaHora(f[1]), (String) f[2],
                cantidad.signum() > 0 ? cantidad : null,
                cantidad.signum() < 0 ? cantidad.negate() : null,
                decimal(f[4]), (String) f[5], (UUID) f[6], fecha(f[7]), (String) f[8], (String) f[9], (String) f[10]);
    }

    // El driver de Postgres devuelve tipos distintos segun la columna/agregado (int, bigint, numeric): se
    // normaliza todo a BigDecimal (las cantidades de producto terminado son enteras, las de leche/insumo no).
    static BigDecimal decimal(Object valor) {
        if (valor == null) return BigDecimal.ZERO;
        if (valor instanceof BigDecimal b) return b;
        if (valor instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(valor.toString());
    }

    static LocalDateTime fechaHora(Object valor) {
        if (valor == null) return null;
        if (valor instanceof LocalDateTime l) return l;
        if (valor instanceof Timestamp t) return t.toLocalDateTime();
        if (valor instanceof OffsetDateTime o) return o.toLocalDateTime();
        if (valor instanceof java.time.Instant i) return LocalDateTime.ofInstant(i, java.time.ZoneId.systemDefault());
        return LocalDateTime.parse(valor.toString().replace(' ', 'T'));
    }

    static LocalDate fecha(Object valor) {
        if (valor == null) return null;
        if (valor instanceof LocalDate l) return l;
        if (valor instanceof java.sql.Date d) return d.toLocalDate();
        if (valor instanceof LocalDateTime l) return l.toLocalDate();
        if (valor instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        return LocalDate.parse(valor.toString().substring(0, 10));
    }
}
