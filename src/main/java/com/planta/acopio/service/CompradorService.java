package com.planta.acopio.service;

import com.planta.acopio.dto.ClienteDtos.CoincidenciasRequest;
import com.planta.acopio.dto.ClienteDtos.CompradorRef;
import com.planta.acopio.dto.ClienteDtos.CompradorSugerencia;
import com.planta.acopio.entity.Cliente;
import com.planta.acopio.entity.ClienteTelefono;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.entity.TipoComprador;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.ClienteRepository;
import com.planta.acopio.repository.ClienteTelefonoRepository;
import com.planta.acopio.repository.ProveedorRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Fase H: quien compra. Una persona tiene UNA identidad: si ya es proveedor se usa su ficha de proveedor (nunca una ficha de cliente
// paralela); si no, su ficha voluntaria de cliente; si no se identifica, es "cliente varios".
//
// Sugerencias: el sistema PROPONE, el trabajador confirma con el comprador y elige. DNI/RUC exacto = misma persona; un telefono
// compartido o un nombre parecido solo son pistas (puede ser otra persona). Nunca se une ni se asocia nada solo.
//
// Reglas de canal (decision del usuario), solo para las ventas del panel; la app movil sigue enviando ventas sin comprador:
//   PUBLICO: comprador opcional (cliente, proveedor o nadie). MAYORISTA: un CLIENTE habilitado como mayorista por ADMIN.
//   PROVEEDOR: un PROVEEDOR del padron. El canal sigue decidiendo el precio; registrarse no da descuentos.
@Service
public class CompradorService {

    private static final int LIMITE = 10;

    /** Comprador resuelto y validado para una operacion: lo que se copia en la venta. */
    public record Comprador(TipoComprador tipo, UUID id, String nombre, String ruc, boolean mayorista, boolean activo) {}

    private final ClienteRepository clienteRepository;
    private final ClienteTelefonoRepository telefonoRepository;
    private final ProveedorRepository proveedorRepository;

    public CompradorService(ClienteRepository clienteRepository, ClienteTelefonoRepository telefonoRepository,
                            ProveedorRepository proveedorRepository) {
        this.clienteRepository = clienteRepository;
        this.telefonoRepository = telefonoRepository;
        this.proveedorRepository = proveedorRepository;
    }

    // ------------------------------------------------------------------ sugerencias

    public List<CompradorSugerencia> sugerencias(CoincidenciasRequest dto) {
        Map<String, CompradorSugerencia> resultado = new LinkedHashMap<>();
        String dni = DocumentoIdentidad.limpiar(dto.dni());
        String ruc = DocumentoIdentidad.limpiar(dto.ruc());
        if (dni != null) {
            clienteRepository.findVigenteByDni(dni).ifPresent(c -> agregar(resultado, cliente(c, "DOCUMENTO")));
            proveedorRepository.findByDni(dni).ifPresent(p -> agregar(resultado, proveedor(p, "DOCUMENTO")));
        }
        if (ruc != null) {
            clienteRepository.findVigenteByRuc(ruc).ifPresent(c -> agregar(resultado, cliente(c, "DOCUMENTO")));
            proveedorRepository.findByRuc(ruc).ifPresent(p -> agregar(resultado, proveedor(p, "DOCUMENTO")));
        }
        if (dto.telefono() != null && !dto.telefono().isBlank()) {
            String telefono = TelefonoPeru.normalizar(dto.telefono()).orElse(null);
            if (telefono != null) {
                clienteRepository.conTelefono(telefono).forEach(c -> agregar(resultado, cliente(c, "TELEFONO")));
                proveedorRepository.conTelefonoTerminadoEn(telefono.substring(3)).forEach(p -> agregar(resultado, proveedor(p, "TELEFONO")));
            }
        }
        String nombre = dto.nombre() == null ? "" : dto.nombre().trim();
        if (nombre.length() >= 3) {
            String patron = BusquedaTexto.patronLike(nombre);
            clienteRepository.buscar(null, null, null, patron, null, PageRequest.of(0, LIMITE))
                    .forEach(c -> agregar(resultado, cliente(c, "NOMBRE")));
            proveedorRepository.buscarConFiltros(null, null, patron, PageRequest.of(0, LIMITE))
                    .forEach(p -> agregar(resultado, proveedor(p, "NOMBRE")));
        }
        return new ArrayList<>(resultado.values()).subList(0, Math.min(resultado.size(), 2 * LIMITE));
    }

    /** Conserva la coincidencia mas fuerte (DOCUMENTO > TELEFONO > NOMBRE) si una misma ficha aparece varias veces. */
    private static void agregar(Map<String, CompradorSugerencia> resultado, CompradorSugerencia s) {
        resultado.putIfAbsent(s.tipo() + ":" + s.id(), s);
    }

    private CompradorSugerencia cliente(Cliente c, String coincidencia) {
        List<ClienteTelefono> telefonos = telefonoRepository.findByClienteIdOrderByCreadoEnAsc(c.getId());
        String telefono = telefonos.isEmpty() ? null : telefonos.get(0).getTelefono();
        return new CompradorSugerencia(TipoComprador.CLIENTE.name(), c.getId(), c.getNombre(), Enmascarar.telefono(telefono),
                Enmascarar.documento(c.getRuc() != null ? c.getRuc() : c.getDni()), c.isMayorista(), c.isActivo(), coincidencia);
    }

    private static CompradorSugerencia proveedor(Proveedor p, String coincidencia) {
        return new CompradorSugerencia(TipoComprador.PROVEEDOR.name(), p.getId(), p.getNombre(), Enmascarar.telefono(p.getTelefono()),
                Enmascarar.documento(p.getRuc() != null ? p.getRuc() : p.getDni()), false, Boolean.TRUE.equals(p.getActivo()), coincidencia);
    }

    // ------------------------------------------------------------------ resolver y validar

    public Comprador resolver(CompradorRef ref) {
        if (ref == null) return null;
        TipoComprador tipo;
        try {
            tipo = TipoComprador.valueOf(ref.tipo().trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new ValidacionNegocioException("Tipo de comprador no válido: usa CLIENTE o PROVEEDOR.");
        }
        if (tipo == TipoComprador.CLIENTE) {
            Cliente c = clienteRepository.findById(ref.id()).orElseThrow(() -> new RecursoNoEncontradoException("Cliente no encontrado: " + ref.id()));
            if (c.getFusionadoEnId() != null) {
                throw new ValidacionNegocioException("Esa ficha se fusionó en otra: elige la ficha destino.");
            }
            return new Comprador(tipo, c.getId(), c.getNombre(), c.getRuc(), c.isMayorista(), c.isActivo());
        }
        Proveedor p = proveedorRepository.findById(ref.id()).orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + ref.id()));
        return new Comprador(tipo, p.getId(), p.getNombre(), p.getRuc(), false, Boolean.TRUE.equals(p.getActivo()));
    }

    /** Para mostrar un vinculo ya guardado: no valida estado (la ficha pudo desactivarse o fusionarse despues). */
    public Comprador resolverAunqueFusionado(TipoComprador tipo, UUID id) {
        if (tipo == TipoComprador.CLIENTE) {
            return clienteRepository.findById(id).map(c -> new Comprador(tipo, c.getId(), c.getNombre(), c.getRuc(), c.isMayorista(), c.isActivo())).orElse(null);
        }
        return proveedorRepository.findById(id).map(p -> new Comprador(tipo, p.getId(), p.getNombre(), p.getRuc(), false, Boolean.TRUE.equals(p.getActivo())))
                .orElse(null);
    }

    /**
     * Reglas de comprador por canal para una venta del panel. `comprador` null = cliente varios. Un comprador con la ficha
     * desactivada no se usa (se puede vender como cliente varios).
     */
    public void validarParaCanal(TipoClienteVenta canal, Comprador comprador) {
        if (comprador != null && !comprador.activo()) {
            throw new ValidacionNegocioException("La ficha de " + comprador.nombre() + " está desactivada: reactívala o vende como «Cliente varios».");
        }
        switch (canal) {
            case PUBLICO -> { /* comprador opcional: cliente, proveedor o nadie */ }
            case MAYORISTA -> {
                if (comprador == null || comprador.tipo() != TipoComprador.CLIENTE) {
                    throw new ValidacionNegocioException("Una venta MAYORISTA necesita un cliente habilitado como mayorista.");
                }
                if (!comprador.mayorista()) {
                    throw new ValidacionNegocioException(comprador.nombre() + " no está habilitado como mayorista (lo habilita ADMIN en su ficha).");
                }
            }
            case PROVEEDOR -> {
                if (comprador == null || comprador.tipo() != TipoComprador.PROVEEDOR) {
                    throw new ValidacionNegocioException("Una venta al canal PROVEEDOR necesita elegir al proveedor del padrón que compra.");
                }
            }
        }
    }
}
