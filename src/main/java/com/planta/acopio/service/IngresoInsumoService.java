package com.planta.acopio.service;

import com.planta.acopio.dto.DocumentoIngresoResponse;
import com.planta.acopio.dto.IngresoDocumentoRequest;
import com.planta.acopio.entity.DocumentoIngreso;
import com.planta.acopio.entity.LoteInsumo;
import com.planta.acopio.entity.ProveedorCompra;
import com.planta.acopio.entity.TipoDocumentoIngreso;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.DocumentoIngresoRepository;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.LoteInsumoRepository;
import com.planta.acopio.repository.ProveedorCompraRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ingreso de insumos con su documento de respaldo (V44): una cabecera (proveedor, tipo, numero, fecha) y N lineas, cada una un
 * lote de un insumo. Todo en una transaccion: o entra todo el documento o no entra nada. El stock cambia SOLO a traves de
 * InventarioInsumoService (un movimiento INGRESO por lote en el kardex), nunca directamente.
 *
 * Reenviar el mismo `uuidCliente` devuelve el documento ya registrado sin duplicar; el mismo numero de documento del mismo
 * proveedor y tipo no se puede cargar dos veces.
 */
@Service
public class IngresoInsumoService {

    private final DocumentoIngresoRepository documentoRepository;
    private final ProveedorCompraRepository proveedorRepository;
    private final InsumoRepository insumoRepository;
    private final LoteInsumoRepository loteRepository;
    private final UsuarioRepository usuarioRepository;
    private final InventarioInsumoService inventarioInsumoService;
    private final ReglaVencimiento reglaVencimiento;

    public IngresoInsumoService(DocumentoIngresoRepository documentoRepository, ProveedorCompraRepository proveedorRepository,
                                InsumoRepository insumoRepository, LoteInsumoRepository loteRepository,
                                UsuarioRepository usuarioRepository, InventarioInsumoService inventarioInsumoService,
                                ReglaVencimiento reglaVencimiento) {
        this.documentoRepository = documentoRepository;
        this.proveedorRepository = proveedorRepository;
        this.insumoRepository = insumoRepository;
        this.loteRepository = loteRepository;
        this.usuarioRepository = usuarioRepository;
        this.inventarioInsumoService = inventarioInsumoService;
        this.reglaVencimiento = reglaVencimiento;
    }

    @Transactional
    public DocumentoIngresoResponse registrar(IngresoDocumentoRequest dto) {
        if (dto.uuidCliente() != null) {
            var existente = documentoRepository.findByUuidCliente(dto.uuidCliente());
            if (existente.isPresent()) {
                return obtener(existente.get().getId());
            }
        }
        TipoDocumentoIngreso tipo = dto.tipoDocumento() == null ? TipoDocumentoIngreso.SIN_DOCUMENTO : dto.tipoDocumento();
        String numero = tipo == TipoDocumentoIngreso.SIN_DOCUMENTO ? null : limpiar(dto.numeroDocumento());
        if (tipo != TipoDocumentoIngreso.SIN_DOCUMENTO && numero == null) {
            throw new ValidacionNegocioException("Indica el número del documento (" + tipo.etiqueta() + ").");
        }
        if (dto.fechaDocumento() != null && dto.fechaDocumento().isAfter(reglaVencimiento.hoy())) {
            throw new ValidacionNegocioException("La fecha del documento no puede ser futura.");
        }
        ProveedorCompra proveedor = null;
        if (dto.proveedorId() != null) {
            proveedor = proveedorRepository.findById(dto.proveedorId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + dto.proveedorId()));
            if (!Boolean.TRUE.equals(proveedor.getActivo())) {
                throw new RecursoInactivoException("El proveedor «" + proveedor.getNombre() + "» está desactivado.");
            }
            if (numero != null && documentoRepository.existsByProveedorIdAndTipoAndNumeroIgnoreCase(proveedor.getId(), tipo, numero)) {
                throw new ConflictoNegocioException("El documento " + tipo.etiqueta() + " " + numero + " de «" + proveedor.getNombre()
                        + "» ya fue cargado. Si es otro ingreso, revisa el número.");
            }
        }

        // Bloqueo de los insumos del documento en orden de id: dos ingresos o producciones simultaneas nunca se cruzan.
        dto.lineas().stream().map(IngresoDocumentoRequest.Linea::insumoId).distinct().sorted()
                .forEach(id -> insumoRepository.bloquearStock(id).orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id)));

        Usuario usuario = usuarioAutenticado();
        DocumentoIngreso doc = new DocumentoIngreso();
        doc.setProveedor(proveedor);
        doc.setTipo(tipo);
        doc.setNumero(numero);
        doc.setFechaDocumento(dto.fechaDocumento());
        doc.setObservacion(vacioANulo(dto.observacion()));
        doc.setUsuario(usuario);
        doc.setUuidCliente(dto.uuidCliente());
        documentoRepository.save(doc);

        String referencia = numero == null ? null : recortar(tipo.etiqueta() + " " + numero, 100);
        List<DocumentoIngresoResponse.Linea> lineas = new ArrayList<>();
        for (IngresoDocumentoRequest.Linea l : dto.lineas()) {
            InventarioInsumoService.IngresoRegistrado r = inventarioInsumoService.registrarIngresoLote(l.insumoId(), l.cantidad(),
                    l.codigoLote(), l.loteProveedor(), l.fechaVencimiento(), proveedor, doc, referencia, doc.getObservacion());
            LoteInsumo lote = r.movimiento().getLoteInsumo();
            lineas.add(new DocumentoIngresoResponse.Linea(l.insumoId(), lote.getInsumo().getNombre(), lote.getInsumo().getUnidadMedida(),
                    l.cantidad(), lote.getId(), lote.getCodigo(), lote.getCodigoProveedor(), lote.getFechaVencimiento(),
                    r.movimiento().getId(), r.saldoAnterior(), r.saldoResultante()));
        }
        return respuesta(doc, lineas);
    }

    @Transactional(readOnly = true)
    public DocumentoIngresoResponse obtener(UUID id) {
        DocumentoIngreso doc = documentoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Documento de ingreso no encontrado: " + id));
        List<DocumentoIngresoResponse.Linea> lineas = loteRepository.findByDocumentoIngresoIdOrderByCreadoEnAscIdAsc(id).stream()
                .map(l -> new DocumentoIngresoResponse.Linea(l.getInsumo().getId(), l.getInsumo().getNombre(), l.getInsumo().getUnidadMedida(),
                        l.getCantidadInicial(), l.getId(), l.getCodigo(), l.getCodigoProveedor(), l.getFechaVencimiento(), null, null, null))
                .toList();
        return respuesta(doc, lineas);
    }

    private static DocumentoIngresoResponse respuesta(DocumentoIngreso d, List<DocumentoIngresoResponse.Linea> lineas) {
        ProveedorCompra p = d.getProveedor();
        return new DocumentoIngresoResponse(d.getId(), p == null ? null : p.getId(), p == null ? null : p.getNombre(),
                p == null ? null : p.getRuc(), d.getTipo().name(), d.getNumero(), d.getFechaDocumento(), d.getObservacion(),
                d.getUsuario().getNombre(), d.getCreadoEn(), lineas);
    }

    private Usuario usuarioAutenticado() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }

    private static String limpiar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim().replaceAll("\\s+", " ");
    }

    private static String vacioANulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    private static String recortar(String texto, int max) {
        return texto.length() <= max ? texto : texto.substring(0, max);
    }
}
