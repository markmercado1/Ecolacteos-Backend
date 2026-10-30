package com.planta.acopio.service;

import com.planta.acopio.dto.PresentacionVentaResponse;
import com.planta.acopio.repository.PresentacionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Buscador con autocompletado de la pantalla "Nueva venta". Devuelve presentaciones activas de productos activos que coinciden con
// lo escrito (los que EMPIEZAN con el texto primero), cada una con su stock vendible y el precio vigente para el canal y la fecha de
// la venta. Es solo una ayuda para elegir: la venta vuelve a resolver precio y stock en el servidor al registrarse (VentaService).
@Service
public class BuscadorPresentacionesVentaService {

    public static final int LIMITE_POR_DEFECTO = 10;
    public static final int LIMITE_MAXIMO = 20;

    private final PresentacionRepository presentacionRepository;
    private final InventarioService inventarioService;
    private final PrecioQuesoService precioQuesoService;
    private final TipoPrecioService tipoPrecioService;

    public BuscadorPresentacionesVentaService(PresentacionRepository presentacionRepository, InventarioService inventarioService,
                                              PrecioQuesoService precioQuesoService, TipoPrecioService tipoPrecioService) {
        this.presentacionRepository = presentacionRepository;
        this.inventarioService = inventarioService;
        this.precioQuesoService = precioQuesoService;
        this.tipoPrecioService = tipoPrecioService;
    }

    public List<PresentacionVentaResponse> buscar(String texto, String tipoCliente, LocalDate fecha, Integer limite) {
        // El canal se valida siempre (un codigo inexistente es un 404/400 del catalogo de tipos), aunque no haya texto.
        String modalidad = tipoPrecioService.obtener(tipoCliente).getCodigo();
        String contiene = BusquedaTexto.patronLike(texto);
        if (contiene == null) return List.of();

        int tope = limite == null ? LIMITE_POR_DEFECTO : Math.min(Math.max(limite, 1), LIMITE_MAXIMO);
        // Dentro de cada rango de cercania (empieza / palabra / contiene) lo que se puede vender va primero, lo agotado o sin precio despues
        // (sigue visible, con su motivo): la primera fila es la mejor opcion para vender. El orden alfabetico de la consulta se conserva (sort estable).
        record Candidata(int rango, PresentacionVentaResponse respuesta) {}
        List<Candidata> candidatas = new ArrayList<>();
        for (Object[] fila : presentacionRepository.buscarParaVenta(contiene, BusquedaTexto.patronPrefijo(texto),
                BusquedaTexto.patronPalabra(texto), tope)) {
            UUID presentacionId = (UUID) fila[0];
            int stock = Math.max(inventarioService.obtenerExistencias(presentacionId).stockVendible(), 0);
            BigDecimal precio = precioQuesoService.precioVigenteSiExiste(presentacionId, modalidad, fecha).orElse(null);
            // Sin stock manda sobre sin precio: es lo primero que el vendedor debe saber.
            String motivo = stock <= 0 ? "SIN_STOCK" : precio == null ? "SIN_PRECIO" : null;
            candidatas.add(new Candidata(((Number) fila[5]).intValue(), new PresentacionVentaResponse(presentacionId, (UUID) fila[1],
                    (String) fila[2], (String) fila[3], (String) fila[4], stock, precio, motivo == null, motivo)));
        }
        return candidatas.stream()
                .sorted(java.util.Comparator.comparingInt(Candidata::rango).thenComparing(c -> !c.respuesta().disponible()))
                .map(Candidata::respuesta)
                .toList();
    }
}
