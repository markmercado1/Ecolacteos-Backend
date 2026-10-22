package com.planta.acopio.integration;

import com.planta.acopio.entity.*;
import com.planta.acopio.repository.*;
import com.planta.acopio.service.BusquedaTexto;
import com.planta.acopio.service.ZonaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Busqueda de texto y orden "mas reciente primero" de las tablas: verificacion real (PostgreSQL via Testcontainers) de
 * lo que un test con Mockito no puede cubrir -- el SQL nativo: TRANSLATE/LOWER para ignorar acentos y mayusculas,
 * LIKE ... ESCAPE '!', los casts de parametros opcionales, los JOIN/EXISTS de cada busqueda, el orden con desempate
 * por fecha de creacion y que el count de cada pagina coincida con el filtro. Requiere Docker.
 *
 * Todo se siembra con un sufijo propio para no depender de los datos semilla, y se revierte (@Transactional).
 */
@Testcontainers
@SpringBootTest
@Transactional
class BusquedaYOrdenTablasIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("acopio_lechero_test")
            .withUsername("acopio")
            .withPassword("acopio");

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final Pageable PAGINA = PageRequest.of(0, 50);

    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ZonaRepository zonaRepository;
    @Autowired private ZonaService zonaService;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private PagoRepository pagoRepository;
    @Autowired private VentaRepository ventaRepository;
    @Autowired private VentaDetalleRepository ventaDetalleRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private LoteProduccionRepository loteRepository;
    @Autowired private RecepcionPlantaRepository recepcionRepository;
    @Autowired private InformeProduccionDiarioRepository informeRepository;
    @Autowired private ComunicadoRepository comunicadoRepository;
    @Autowired private ReservaRepository reservaRepository;

    private String s;          // sufijo unico de esta prueba
    private Rol rol;
    private Usuario ana;       // "Ana Pérez <s>"
    private Usuario nina;      // "Niña Quispe <s>"
    private Zona zona;

    @BeforeEach
    void sembrarBase() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        rol = new Rol();
        rol.setNombre("BUS_IT_" + s);
        rol = rolRepository.save(rol);
        ana = usuarioRepository.save(usuario("Ana Pérez " + s, "ana" + s + "@planta.com"));
        nina = usuarioRepository.save(usuario("Niña Quispe " + s, "nina" + s + "@planta.com"));
        zona = new Zona();
        zona.setNombre("Zona Búsqueda " + s);
        zona = zonaRepository.save(zona);
    }

    // ----------------------------------------------------------------------------------------------- texto

    @Test
    void elPatronIgnoraAcentosYMayusculasEnLaColumnaYEnElTermino() {
        // "Pérez" (con acento) se encuentra escribiendo "perez", "PEREZ" o "pérez"; "Niña" con "nina".
        for (String termino : List.of("perez", "PEREZ", "pérez", "  PÉREZ  ")) {
            assertThat(nombres(usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike(termino.trim() + " " + s), PAGINA).getContent()))
                    .as(termino).containsExactly("Ana Pérez " + s);
        }
        assertThat(nombres(usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("nina quispe " + s), PAGINA).getContent()))
                .containsExactly("Niña Quispe " + s);
        // Y al reves: escribir la letra acentuada encuentra el nombre sin acento.
        assertThat(nombres(usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("NÍÑA QUISPE " + s), PAGINA).getContent()))
                .as("tilde y ñ en el termino").containsExactly("Niña Quispe " + s);
    }

    @Test
    void elTextoEsContenidoNoSoloPrefijo() {
        assertThat(nombres(usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("rez " + s), PAGINA).getContent()))
                .containsExactly("Ana Pérez " + s);
    }

    @Test
    void losComodinesDelUsuarioSeBuscanLiteralmente() {
        usuarioRepository.save(usuario("Cien%Por_Ciento " + s, "cien" + s + "@planta.com"));
        assertThat(usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("cien%por_ciento " + s), PAGINA).getTotalElements()).isEqualTo(1);
        // "%" y "_" solos NO funcionan como comodin: no devuelven todo.
        assertThat(usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("%" + s), PAGINA).getTotalElements()).isZero();
        assertThat(usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("a_a " + s), PAGINA).getTotalElements()).isZero();
    }

    @Test
    void sinTerminoNoFiltraYElTotalCoincideConLaBusqueda() {
        long todos = usuarioRepository.buscarConFiltros(null, null, null, PAGINA).getTotalElements();
        long conVacio = usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("   "), PAGINA).getTotalElements();
        assertThat(conVacio).isEqualTo(todos).isGreaterThanOrEqualTo(2);
        var pagina = usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike(s), PageRequest.of(0, 1));
        assertThat(pagina.getTotalElements()).isEqualTo(2);
        assertThat(pagina.getTotalPages()).isEqualTo(2);
        assertThat(pagina.getContent()).hasSize(1);
    }

    // ------------------------------------------------------------------------------- proveedores y usuarios

    @Test
    void proveedoresBuscanPorNombreODniSinAcentosYSalenMasRecientesPrimero() {
        LocalDateTime t = LocalDateTime.of(2031, 1, 1, 8, 0);
        Proveedor viejo = proveedorRepository.save(proveedor("María Huamán " + s, "1" + s.substring(0, 7), t));
        Proveedor nuevo = proveedorRepository.save(proveedor("Maria Quispe " + s, "2" + s.substring(0, 7), t.plusHours(2)));

        var porNombre = proveedorRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("MARIA"), PAGINA).getContent()
                .stream().filter(p -> p.getNombre().endsWith(s)).toList();
        assertThat(porNombre).extracting(Proveedor::getId).containsExactly(nuevo.getId(), viejo.getId()); // el creado despues, primero
        assertThat(proveedorRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("huaman"), PAGINA).getContent())
                .extracting(Proveedor::getId).contains(viejo.getId()).doesNotContain(nuevo.getId());
        assertThat(proveedorRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike(viejo.getDni()), PAGINA).getContent())
                .extracting(Proveedor::getId).containsExactly(viejo.getId());
        // Filtros existentes siguen funcionando junto a la busqueda.
        assertThat(proveedorRepository.buscarConFiltros(zona.getId(), true, BusquedaTexto.patronLike("quispe " + s), PAGINA).getTotalElements()).isEqualTo(1);
        assertThat(proveedorRepository.buscarConFiltros(UUID.randomUUID(), null, BusquedaTexto.patronLike("quispe " + s), PAGINA).getTotalElements()).isZero();
    }

    @Test
    void usuariosSalenMasRecientesPrimero() {
        Usuario antiguo = usuario("Zeta Antiguo " + s, "zeta" + s + "@planta.com");
        antiguo.setCreadoEn(LocalDateTime.of(2020, 1, 1, 0, 0));
        Usuario reciente = usuario("Alfa Reciente " + s, "alfa" + s + "@planta.com");
        reciente.setCreadoEn(LocalDateTime.of(2032, 1, 1, 0, 0));
        usuarioRepository.save(antiguo);
        usuarioRepository.save(reciente);
        var orden = usuarioRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike(s), PAGINA).getContent();
        assertThat(nombres(orden)).first().isEqualTo("Alfa Reciente " + s); // pese a que "Alfa" < "Zeta" y a "Ana"/"Niña"
        assertThat(nombres(orden)).last().isEqualTo("Zeta Antiguo " + s);
    }

    // ----------------------------------------------------------------------------------------------- pagos

    @Test
    void pagosBuscanPorProveedorYSalenPorSemanaYLuegoPorCreacionDescendente() {
        LocalDateTime t = LocalDateTime.of(2031, 5, 1, 9, 0);
        Proveedor huaman = proveedorRepository.save(proveedor("María Huamán " + s, "3" + s.substring(0, 7), t));
        Proveedor quispe = proveedorRepository.save(proveedor("Rosa Quispe " + s, "4" + s.substring(0, 7), t));
        LocalDate semana = LocalDate.of(2031, 5, 5);
        Pago pagoHuaman = pagoRepository.save(pago(huaman, semana, t));                    // misma semana, creado antes
        Pago pagoQuispe = pagoRepository.save(pago(quispe, semana, t.plusHours(3)));      // misma semana, creado despues
        Pago pagoAnterior = pagoRepository.save(pago(huaman, semana.minusDays(7), t.plusDays(5))); // semana previa pero creado al final

        var todos = pagoRepository.buscar(BusquedaTexto.patronLike(s), PAGINA).getContent();
        // Semana mas reciente primero; dentro de ella, el creado despues primero; la semana previa al final.
        assertThat(todos).extracting(Pago::getId).containsExactly(pagoQuispe.getId(), pagoHuaman.getId(), pagoAnterior.getId());

        assertThat(pagoRepository.buscar(BusquedaTexto.patronLike("HUAMAN"), PAGINA).getContent())
                .extracting(Pago::getId).contains(pagoHuaman.getId(), pagoAnterior.getId()).doesNotContain(pagoQuispe.getId());
        assertThat(pagoRepository.buscar(BusquedaTexto.patronLike("3" + s.substring(0, 7)), PAGINA).getContent())
                .as("tambien por DNI").extracting(Pago::getId).contains(pagoHuaman.getId());
        assertThat(pagoRepository.buscar(BusquedaTexto.patronLike(s), PageRequest.of(0, 2)).getTotalElements()).isEqualTo(3);
    }

    // ---------------------------------------------------------------------------------------------- ventas

    @Test
    void ventasBuscanPorVendedorProductoOPresentacionYSalenMasRecientesPrimeroDentroDelDia() {
        Presentacion botella = presentacion("Yogurt Fresón " + s, "Botella Grande " + s);
        Presentacion pieza = presentacion("Queso Andino " + s, "Pieza " + s);
        LocalDate dia = LocalDate.of(2031, 6, 10);
        // Tres ventas del mismo dia, insertadas en este orden: la ultima debe salir primero (creado_en = clock_timestamp()).
        Venta primera = venta(dia, "u1-" + s, ana, botella);
        Venta segunda = venta(dia, "u2-" + s, nina, pieza);
        Venta tercera = venta(dia, "u3-" + s, ana, pieza);
        Venta otroDia = venta(dia.minusDays(1), "u4-" + s, ana, botella);

        var delDia = ventaRepository.buscarConFiltros(dia, null, BusquedaTexto.patronLike(s), PAGINA).getContent();
        assertThat(delDia).extracting(Venta::getId).containsExactly(tercera.getId(), segunda.getId(), primera.getId());

        // Producto con acento ("Frescón") encontrado como "freson"; presentacion; vendedor ("Niña" como "nina").
        assertThat(ids(ventaRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("FRESON " + s), PAGINA)))
                .containsExactlyInAnyOrder(primera.getId(), otroDia.getId());
        assertThat(ids(ventaRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("pieza " + s), PAGINA)))
                .containsExactlyInAnyOrder(segunda.getId(), tercera.getId());
        assertThat(ids(ventaRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("nina quispe " + s), PAGINA)))
                .containsExactly(segunda.getId());
        // Una venta con varias lineas no se duplica en el resultado ni en el total.
        var combinada = ventaRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike(s), PAGINA);
        assertThat(combinada.getTotalElements()).isEqualTo(4);
        // Los filtros de siempre siguen funcionando.
        assertThat(ids(ventaRepository.buscarConFiltros(dia, "PUBLICO", null, PAGINA))).contains(primera.getId());
        assertThat(ids(ventaRepository.buscarConFiltros(dia, "MAYORISTA", BusquedaTexto.patronLike(s), PAGINA))).isEmpty();
    }

    // ------------------------------------------------------------------------------------ lotes, recepciones

    @Test
    void lotesBuscanPorProductoOResponsable() {
        Presentacion p = presentacion("Queso Paín " + s, "Pieza " + s);
        Presentacion otra = presentacion("Yogurt " + s, "Vaso " + s);
        LoteProduccion lote1 = loteRepository.save(lote(p.getProducto(), ana, LocalDate.of(2031, 7, 1), LocalDateTime.of(2031, 7, 1, 8, 0)));
        LoteProduccion lote2 = loteRepository.save(lote(otra.getProducto(), nina, LocalDate.of(2031, 7, 1), LocalDateTime.of(2031, 7, 1, 9, 0)));

        var porProducto = loteRepository.buscarConFiltros(null, null, null, BusquedaTexto.patronLike("PAIN " + s), PAGINA);
        assertThat(porProducto.getContent()).extracting(LoteProduccion::getId).containsExactly(lote1.getId());
        var porResponsable = loteRepository.buscarConFiltros(null, null, null, BusquedaTexto.patronLike("nina quispe " + s), PAGINA);
        assertThat(porResponsable.getContent()).extracting(LoteProduccion::getId).containsExactly(lote2.getId());
        // Orden: mismo dia, el creado despues primero.
        assertThat(loteRepository.buscarConFiltros(LocalDate.of(2031, 7, 1), null, null, BusquedaTexto.patronLike(s), PAGINA).getContent())
                .extracting(LoteProduccion::getId).containsExactly(lote2.getId(), lote1.getId());
        assertThat(loteRepository.buscarConFiltros(null, p.getProducto().getId(), null, BusquedaTexto.patronLike(s), PAGINA).getTotalElements()).isEqualTo(1);
    }

    @Test
    void recepcionesBuscanPorPlacaOResponsableYSalenMasRecientesPrimeroDentroDelDia() {
        Unidad unidadA = unidadRepository.save(unidad("ABC-" + s, ana));
        Unidad unidadB = unidadRepository.save(unidad("XYZ-" + s, nina));
        LocalDate dia = LocalDate.of(2031, 8, 3);
        RecepcionPlanta antes = recepcionRepository.save(recepcion(unidadA, ana, dia, LocalDateTime.of(2031, 8, 3, 7, 0)));
        RecepcionPlanta despues = recepcionRepository.save(recepcion(unidadB, nina, dia, LocalDateTime.of(2031, 8, 3, 15, 0)));

        assertThat(recepcionRepository.buscarConFiltros(null, dia, BusquedaTexto.patronLike(s), PAGINA).getContent())
                .extracting(RecepcionPlanta::getId).containsExactly(despues.getId(), antes.getId());
        assertThat(recepcionRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("abc-" + s), PAGINA).getContent())
                .extracting(RecepcionPlanta::getId).containsExactly(antes.getId());
        assertThat(recepcionRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("NIÑA QUISPE " + s), PAGINA).getContent())
                .extracting(RecepcionPlanta::getId).containsExactly(despues.getId());
        assertThat(recepcionRepository.buscarConFiltros(unidadA.getId(), null, BusquedaTexto.patronLike(s), PAGINA).getTotalElements()).isEqualTo(1);
    }

    @Test
    void informesBuscanPorResponsable() {
        InformeProduccionDiario i1 = informeRepository.save(informe(ana, LocalDate.of(2031, 9, 1)));
        InformeProduccionDiario i2 = informeRepository.save(informe(nina, LocalDate.of(2031, 9, 2)));
        assertThat(informeRepository.buscarConFiltros(null, null, BusquedaTexto.patronLike("PEREZ " + s), PAGINA).getContent())
                .extracting(InformeProduccionDiario::getId).containsExactly(i1.getId());
        assertThat(informeRepository.buscarConFiltros(LocalDate.of(2031, 9, 1), LocalDate.of(2031, 9, 30), BusquedaTexto.patronLike(s), PAGINA).getContent())
                .extracting(InformeProduccionDiario::getId).containsExactly(i2.getId(), i1.getId()); // fecha descendente
    }

    // ------------------------------------------------------------------------------- comunicados, reservas

    @Test
    void comunicadosBuscanPorMensajeOZonaDestino() {
        Comunicado c1 = comunicado("Reunión de proveedores " + s, Set.of(zona), LocalDateTime.of(2031, 10, 1, 8, 0));
        Comunicado c2 = comunicado("Cambio de horario " + s, Set.of(), LocalDateTime.of(2031, 10, 2, 8, 0));
        assertThat(comunicadoRepository.buscar(BusquedaTexto.patronLike("REUNION DE PROVEEDORES " + s), PAGINA).getContent())
                .extracting(Comunicado::getId).containsExactly(c1.getId());
        assertThat(comunicadoRepository.buscar(BusquedaTexto.patronLike("busqueda " + s), PAGINA).getContent())
                .as("por el nombre de la zona destino (Búsqueda)").extracting(Comunicado::getId).containsExactly(c1.getId());
        assertThat(comunicadoRepository.buscar(BusquedaTexto.patronLike(s), PAGINA).getContent())
                .extracting(Comunicado::getId).containsExactly(c2.getId(), c1.getId()); // mas reciente primero
        assertThat(comunicadoRepository.buscar(BusquedaTexto.patronLike(s), PageRequest.of(0, 1)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void reservasBuscanPorClienteTelefonoOCodigoSinPerderLosFiltrosExactos() {
        LocalDateTime ahora = LocalDateTime.of(2031, 11, 1, 10, 0);
        String tel1 = "+519" + s.substring(0, 8);
        String tel2 = "+519" + new StringBuilder(s.substring(0, 8)).reverse();
        Reserva r1 = reservaRepository.save(reserva("José Muñoz", tel1, "AB" + s.substring(0, 6), ahora));
        Reserva r2 = reservaRepository.save(reserva("Lucía Ramos", tel2, "CD" + s.substring(0, 6), ahora.plusMinutes(5)));

        assertThat(ids(reservaRepository.buscar(null, null, null, null, BusquedaTexto.patronLike("JOSE MUNOZ"), ahora, ahora.toLocalDate(), PAGINA)))
                .contains(r1.getId()).doesNotContain(r2.getId());
        assertThat(ids(reservaRepository.buscar(null, null, null, null, BusquedaTexto.patronLike("lucía"), ahora, ahora.toLocalDate(), PAGINA)))
                .contains(r2.getId());
        assertThat(ids(reservaRepository.buscar(null, null, null, null, BusquedaTexto.patronLike(s.substring(0, 6).toLowerCase()), ahora, ahora.toLocalDate(), PAGINA)))
                .as("por codigo (sin distinguir mayusculas)").contains(r1.getId(), r2.getId());
        assertThat(ids(reservaRepository.buscar(null, null, tel1, null, BusquedaTexto.patronLike("ramos"), ahora, ahora.toLocalDate(), PAGINA)))
                .as("el filtro exacto de telefono sigue aplicando junto a la busqueda").isEmpty();
        assertThat(ids(reservaRepository.buscar(null, null, null, null, null, ahora, ahora.toLocalDate(), PAGINA)))
                .as("sin busqueda: sin cambios").contains(r1.getId(), r2.getId());
    }

    // ------------------------------------------------------------------ inventario y precios (ya buscaban)

    @Test
    void inventarioYPreciosBuscanSinAcentos() {
        presentacion("Yogurt Fresón " + s, "Botella " + s);
        var stock = presentacionRepository.buscarFilasStock(null, BusquedaTexto.patronLike("FRESON " + s), PAGINA);
        assertThat(stock.getTotalElements()).isEqualTo(1);
        var precios = presentacionRepository.buscarFilasPrecios(null, BusquedaTexto.patronLike("freson " + s), PAGINA);
        assertThat(precios.getTotalElements()).isEqualTo(1);
        assertThat(presentacionRepository.buscarFilasStock(null, null, PAGINA).getTotalElements()).isGreaterThanOrEqualTo(1);
    }

    // --------------------------------------------------------------------------------------------- catalogos

    @Test
    void losCatalogosSeListanEnOrdenAlfabeticoSinDistinguirMayusculas() {
        for (String n : List.of("zeta " + s, "Alfa " + s, "bravo " + s)) {
            Zona z = new Zona();
            z.setNombre(n);
            zonaRepository.save(z);
        }
        var nombres = zonaService.listarActivas().stream().map(r -> r.nombre()).filter(n -> n.endsWith(s) && !n.startsWith("Zona B")).toList();
        assertThat(nombres).containsExactly("Alfa " + s, "bravo " + s, "zeta " + s);
    }

    // ================================================================================================ datos

    private Usuario usuario(String nombre, String email) {
        Usuario u = new Usuario();
        u.setNombre(nombre);
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Unidad unidad(String placa, Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca(placa);
        u.setResponsable(responsable);
        return u;
    }

    private Proveedor proveedor(String nombre, String dni, LocalDateTime creado) {
        Proveedor p = new Proveedor();
        p.setNombre(nombre);
        p.setDni(dni);
        p.setZonaActual(zona);
        p.setCreadoEn(creado);
        return p;
    }

    private Pago pago(Proveedor proveedor, LocalDate semana, LocalDateTime creado) {
        Pago p = new Pago();
        p.setProveedor(proveedor);
        p.setSemanaInicio(semana);
        p.setSemanaFin(semana.plusDays(6));
        p.setLitrosTotales(new BigDecimal("100"));
        p.setPrecioLitro(new BigDecimal("1.000"));
        p.setCreadoEn(creado);
        return p;
    }

    private Presentacion presentacion(String producto, String nombre) {
        Familia f = new Familia();
        f.setNombre("Familia " + producto);
        f = familiaRepository.save(f);
        Producto p = new Producto();
        p.setNombre(producto);
        p.setFamilia(f);
        p = productoRepository.save(p);
        Presentacion pr = new Presentacion();
        pr.setNombre(nombre);
        pr.setProducto(p);
        pr.setUnidadConteo("unidad");
        return presentacionRepository.save(pr);
    }

    private Venta venta(LocalDate fecha, String uuid, Usuario vendedor, Presentacion pr) {
        Venta v = new Venta();
        v.setUuidCliente(uuid);
        v.setFecha(fecha);
        v.setTipoCliente(TipoClienteVenta.PUBLICO);
        v.setTotal(new BigDecimal("10.00"));
        v.setVendedor(vendedor);
        v = ventaRepository.saveAndFlush(v);
        VentaDetalle d = new VentaDetalle();
        d.setVenta(v);
        d.setPresentacion(pr);
        d.setCantidad(1);
        d.setPrecioUnitario(new BigDecimal("10.00"));
        ventaDetalleRepository.saveAndFlush(d);
        return v;
    }

    private LoteProduccion lote(Producto producto, Usuario responsable, LocalDate fecha, LocalDateTime creado) {
        LoteProduccion l = new LoteProduccion();
        l.setUuidCliente(UUID.randomUUID().toString());
        l.setFecha(fecha);
        l.setProducto(producto);
        l.setLitrosUsados(new BigDecimal("100"));
        l.setResponsable(responsable);
        l.setEstado(EstadoLote.EN_PRODUCCION);
        l.setIniciadoEn(creado);
        l.setCreadoEn(creado);
        return l;
    }

    private RecepcionPlanta recepcion(Unidad unidad, Usuario responsable, LocalDate fecha, LocalDateTime creado) {
        RecepcionPlanta r = new RecepcionPlanta();
        r.setFecha(fecha);
        r.setUnidad(unidad);
        r.setTurno("UNICO");
        r.setLitrosCampo(new BigDecimal("100"));
        r.setLitrosPlanta(new BigDecimal("100"));
        r.setResponsable(responsable);
        r.setCreadoEn(creado);
        return r;
    }

    private InformeProduccionDiario informe(Usuario responsable, LocalDate fecha) {
        InformeProduccionDiario i = new InformeProduccionDiario();
        i.setUuidCliente(UUID.randomUUID().toString());
        i.setFecha(fecha);
        i.setQuesosDiarios(10);
        i.setResponsable(responsable);
        return i;
    }

    private Comunicado comunicado(String mensaje, Set<Zona> zonas, LocalDateTime fecha) {
        Comunicado c = new Comunicado();
        c.setMensaje(mensaje);
        c.setFecha(fecha);
        c.setZonasDestino(new HashSet<>(zonas));
        return comunicadoRepository.save(c);
    }

    private Reserva reserva(String cliente, String telefono, String codigo, LocalDateTime creado) {
        Reserva r = new Reserva();
        r.setCodigo(codigo);
        r.setUuidCliente(UUID.randomUUID().toString());
        r.setEstado(EstadoReserva.PENDIENTE);
        r.setNombreCliente(cliente);
        r.setTelefono(telefono);
        r.setFechaRetiro(creado.toLocalDate().plusDays(1));
        r.setFechaMaximaRetiro(creado.toLocalDate().plusDays(3));
        r.setCreadoEn(creado);
        r.setExpiraEn(creado.plusDays(1));
        return r;
    }

    private static List<String> nombres(List<Usuario> usuarios) {
        return usuarios.stream().map(Usuario::getNombre).collect(Collectors.toList());
    }

    private static List<UUID> ids(org.springframework.data.domain.Page<? extends Object> pagina) {
        return pagina.getContent().stream().map(BusquedaYOrdenTablasIntegrationTest::idDe).collect(Collectors.toList());
    }

    private static UUID idDe(Object e) {
        if (e instanceof Venta v) return v.getId();
        if (e instanceof Reserva r) return r.getId();
        throw new IllegalArgumentException("Tipo no soportado: " + e);
    }
}
