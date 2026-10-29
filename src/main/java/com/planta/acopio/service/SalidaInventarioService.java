package com.planta.acopio.service;

import com.planta.acopio.dto.SalidaInventarioRequest;
import com.planta.acopio.dto.SalidaInventarioResponse;
import com.planta.acopio.entity.SalidaInventario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.SalidaInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;
import java.util.UUID;

@Service
public class SalidaInventarioService {
    private final SalidaInventarioRepository salidas;
    private final PresentacionRepository presentaciones;
    private final InsumoRepository insumos;
    private final UsuarioRepository usuarios;
    private final InventarioService inventario;
    private final InventarioInsumoService inventarioInsumo;
    private final MovimientoInventarioRepository movimientos;
    private final MovimientoInsumoRepository movimientosInsumo;
    private final ReglaVencimiento reloj;

    public SalidaInventarioService(SalidaInventarioRepository salidas, PresentacionRepository presentaciones,
            InsumoRepository insumos, UsuarioRepository usuarios, InventarioService inventario,
            InventarioInsumoService inventarioInsumo, MovimientoInventarioRepository movimientos,
            MovimientoInsumoRepository movimientosInsumo, ReglaVencimiento reloj) {
        this.salidas = salidas;
        this.presentaciones = presentaciones;
        this.insumos = insumos;
        this.usuarios = usuarios;
        this.inventario = inventario;
        this.inventarioInsumo = inventarioInsumo;
        this.movimientos = movimientos;
        this.movimientosInsumo = movimientosInsumo;
        this.reloj = reloj;
    }

    @Transactional
    public SalidaInventarioResponse registrar(UUID itemId, boolean esInsumo, SalidaInventarioRequest dto) {
        // El mismo bloqueo que producción/ventas/ajustes: verificar reintento DESPUÉS de adquirirlo.
        SalidaInventario nueva = new SalidaInventario();
        if (esInsumo) {
            insumos.bloquearStock(itemId).orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado"));
            nueva.setInsumo(insumos.findById(itemId).orElseThrow());
            if (nueva.getInsumo().estaFusionado()) {
                throw new ConflictoNegocioException("El insumo fue fusionado en otro insumo: registra la salida en el insumo que lo absorbió.");
            }
            if (dto.loteId() != null || dto.origenLote() != null) {
                throw new ValidacionNegocioException("Los insumos no tienen lotes de ingreso registrados.");
            }
        } else {
            nueva.setPresentacion(presentaciones.buscarConBloqueoParaActualizarStock(itemId)
                    .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada")));
        }
        if ((dto.loteId() == null) != (dto.origenLote() == null)) {
            throw new ValidacionNegocioException("Indica juntos el lote y su origen.");
        }
        var existente = salidas.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            SalidaInventario s = existente.get();
            UUID anterior = esInsumo ? (s.getInsumo() == null ? null : s.getInsumo().getId())
                    : (s.getPresentacion() == null ? null : s.getPresentacion().getId());
            if (!itemId.equals(anterior) || s.getCantidad().compareTo(dto.cantidad()) != 0
                    || s.getTipo() != dto.tipo() || !s.getMotivo().equals(dto.motivo().trim())
                    || !Objects.equals(s.getLoteId(), dto.loteId()) || !Objects.equals(s.getOrigenLote(), dto.origenLote())) {
                throw new ConflictoNegocioException("El identificador ya fue utilizado para otra salida.");
            }
            UUID movimientoId = esInsumo ? movimientosInsumo.findBySalidaIdOrderByOrdenAsc(s.getId()).get(0).getId()
                    : movimientos.findBySalidaId(s.getId()).orElseThrow().getId();
            return respuesta(s, movimientoId);
        }
        nueva.setUuidCliente(dto.uuidCliente());
        nueva.setCantidad(dto.cantidad().setScale(2, java.math.RoundingMode.UNNECESSARY));
        nueva.setTipo(dto.tipo());
        nueva.setMotivo(dto.motivo().trim());
        nueva.setOrigenLote(dto.origenLote());
        nueva.setLoteId(dto.loteId());
        nueva.setCreadoEn(reloj.ahora().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        nueva.setUsuario(usuarios.findByEmail(SecurityContextHolder.getContext().getAuthentication().getName())
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado")));
        salidas.save(nueva);
        UUID movimientoId = esInsumo ? inventarioInsumo.registrarRetiro(nueva) : inventario.registrarRetiro(nueva);
        return respuesta(nueva, movimientoId);
    }

    private static SalidaInventarioResponse respuesta(SalidaInventario s, UUID movimientoId) {
        return new SalidaInventarioResponse(s.getId(), movimientoId, s.getCantidad().setScale(2, java.math.RoundingMode.UNNECESSARY), s.getTipo().name(),
                s.getMotivo(), s.getOrigenLote(), s.getLoteId(), s.getFechaVencimiento(), s.getUsuario().getNombre(), s.getCreadoEn());
    }
}
