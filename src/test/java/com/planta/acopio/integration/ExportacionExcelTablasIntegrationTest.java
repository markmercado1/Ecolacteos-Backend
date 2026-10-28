package com.planta.acopio.integration;

import com.planta.acopio.dto.UnidadResponse;
import com.planta.acopio.entity.EstadoLote;
import com.planta.acopio.entity.EstadoReserva;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.PrecioLitro;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.RecepcionPlanta;
import com.planta.acopio.entity.Reserva;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.excel.ArchivoExcel;
import com.planta.acopio.excel.ColumnasExportacion;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.FuentePaginada;
import com.planta.acopio.excel.LimitesExcel;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.PrecioLitroRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RecepcionPlantaRepository;
import com.planta.acopio.repository.ReservaRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InventarioService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PrecioLitroService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.ReservaService;
import com.planta.acopio.service.UnidadService;
import com.planta.acopio.service.UsuarioService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 3 contra PostgreSQL real: cada exportacion corre la MISMA consulta (nativa o JPQL) que alimenta su tabla, recorrida
 * por lotes de 5 filas para forzar varias paginas, y el archivo trae todas las filas del filtro en el orden de la tabla.
 */
@SpringBootTest
@Transactional
class ExportacionExcelTablasIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private final ExportacionExcelService excel =
            new ExportacionExcelService(new LimitesExcel(1_000, 5, 2_097_152, 2_000, 10_000, 100, 26_214_400, 30));

    @Autowired private ReservaService reservaService;
    @Autowired private ConciliacionService conciliacionService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private InventarioService inventarioService;
    @Autowired private UsuarioService usuarioService;
    @Autowired private PrecioQuesoService precioQuesoService;
    @Autowired private PrecioLitroService precioLitroService;
    @Autowired private UnidadService unidadService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ReservaRepository reservaRepository;
    @Autowired private RecepcionPlantaRepository recepcionRepository;
    @Autowired private LoteProduccionRepository loteRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private PrecioLitroRepository precioLitroRepository;

    private String s;
    private Rol rol;
    private Usuario responsable;

    @BeforeEach
    void sembrar() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        rol = new Rol();
        rol.setNombre("EXPT_" + s);
        rol = rolRepository.save(rol);
        responsable = usuarioRepository.save(usuario("Niña Quispe " + s, "base" + s + "@planta.com"));
    }

    private Usuario usuario(String nombre, String email) {
        Usuario u = new Usuario();
        u.setNombre(nombre);
        u.setEmail(email);
        u.setPasswordHash("hash-secreto");
        u.setRol(rol);
        return u;
    }

    private static Sheet abrir(ArchivoExcel archivo) throws Exception {
        try (InputStream in = Files.newInputStream(archivo.ruta())) {
            return new XSSFWorkbook(in).getSheetAt(0);
        } finally {
            archivo.descartar();
        }
    }

    private static List<String> texto(Sheet hoja, int columna) {
        List<String> valores = new ArrayList<>();
        for (int i = 1; i <= hoja.getLastRowNum(); i++) valores.add(hoja.getRow(i).getCell(columna).getStringCellValue());
        return valores;
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

    @Test
    void reservasTodasLasFilasConOrdenMasRecientePrimero() throws Exception {
        LocalDateTime base = LocalDateTime.of(2031, 11, 1, 10, 0);
        for (int i = 0; i < 12; i++) {
            Reserva r = new Reserva();
            r.setCodigo(String.format("EX%02d%s", i, s.substring(0, 4)));
            r.setUuidCliente(UUID.randomUUID().toString());
            r.setEstado(EstadoReserva.PENDIENTE);
            r.setNombreCliente("José Muñoz " + i + " " + s);
            r.setTelefono("+519" + String.format("%08d", Integer.parseInt(s.substring(0, 4)) * 1000 + i));
            r.setFechaRetiro(base.toLocalDate().plusDays(1));
            r.setFechaMaximaRetiro(base.toLocalDate().plusDays(3));
            r.setCreadoEn(base.plusMinutes(i));
            r.setExpiraEn(base.plusDays(300));
            reservaRepository.save(r);
        }

        Sheet hoja = abrir(excel.exportar("reservas", "Reservas", ColumnasExportacion.RESERVAS,
                FuentePaginada.deRespuesta(p -> reservaService.listar(null, null, null, null, "jose munoz", p))));

        List<String> clientes = texto(hoja, 1).stream().filter(c -> c.endsWith(s)).toList();
        assertThat(clientes).hasSize(12);
        assertThat(clientes.get(0)).startsWith("José Muñoz 11"); // la creada despues, primero
        assertThat(clientes.get(11)).startsWith("José Muñoz 0 ");
        assertThat(texto(hoja, 3)).contains("PENDIENTE");
    }

    @Test
    void recepcionesConLaPlacaDeLaUnidad() throws Exception {
        Unidad unidad = new Unidad();
        unidad.setPlaca("EXR-" + s);
        unidad.setResponsable(responsable);
        unidad = unidadRepository.save(unidad);
        LocalDate dia = LocalDate.of(2031, 8, 3);
        for (int i = 0; i < 11; i++) {
            RecepcionPlanta r = new RecepcionPlanta();
            r.setFecha(dia);
            r.setUnidad(unidad);
            r.setTurno("T" + i);
            r.setLitrosCampo(new BigDecimal("100"));
            r.setLitrosPlanta(new BigDecimal("99.50"));
            r.setResponsable(responsable);
            r.setCreadoEn(LocalDateTime.of(2031, 8, 3, 6, 0).plusMinutes(i));
            recepcionRepository.save(r);
        }
        Map<UUID, String> placas = unidadService.listar().stream().collect(Collectors.toMap(UnidadResponse::id, UnidadResponse::placa));

        Sheet hoja = abrir(excel.exportar("recepciones", "Recepciones", ColumnasExportacion.recepciones(placas),
                p -> conciliacionService.listar(unidad(placas, "EXR-" + s), dia, null, p)));

        assertThat(hoja.getLastRowNum()).isEqualTo(11);
        assertThat(texto(hoja, 2)).containsOnly("EXR-" + s);
        assertThat(texto(hoja, 1).get(0)).isEqualTo("T10"); // la creada despues, primero
    }

    private UUID unidad(Map<UUID, String> placas, String placa) {
        return placas.entrySet().stream().filter(e -> e.getValue().equals(placa)).findFirst().orElseThrow().getKey();
    }

    @Test
    void lotesDeProduccion() throws Exception {
        Presentacion p = presentacion("Queso Paín " + s, "Pieza " + s);
        for (int i = 0; i < 9; i++) {
            LoteProduccion l = new LoteProduccion();
            l.setUuidCliente(UUID.randomUUID().toString());
            l.setFecha(LocalDate.of(2031, 7, 1));
            l.setProducto(p.getProducto());
            l.setLitrosUsados(new BigDecimal("100"));
            l.setResponsable(responsable);
            l.setEstado(EstadoLote.EN_PRODUCCION);
            l.setIniciadoEn(LocalDateTime.of(2031, 7, 1, 8, 0).plusMinutes(i));
            l.setCreadoEn(LocalDateTime.of(2031, 7, 1, 8, 0).plusMinutes(i));
            loteRepository.save(l);
        }
        Sheet lotes = abrir(excel.exportar("lotes", "Lotes", ColumnasExportacion.LOTES,
                p2 -> loteProduccionService.buscar(null, p.getProducto().getId(), null, "pain " + s, p2)));
        assertThat(lotes.getLastRowNum()).isEqualTo(9);
        assertThat(texto(lotes, 1)).containsOnly("Queso Paín " + s);
        assertThat(texto(lotes, 2)).containsOnly("EN_PRODUCCION");

    }

    @Test
    void stockPreciosYUsuariosPorLotes() throws Exception {
        for (int i = 0; i < 11; i++) {
            presentacion(String.format("Yogurt Fresón %02d %s", i, s), "Botella " + i + " " + s);
        }
        Sheet stock = abrir(excel.exportar("stock", "Stock", ColumnasExportacion.STOCK,
                FuentePaginada.deRespuesta(p -> inventarioService.listarStockPaginado(null, "yogurt freson", p))));
        assertThat(stock.getLastRowNum()).isEqualTo(11);
        assertThat(texto(stock, 2)).allMatch(n -> n.endsWith(s));

        Sheet precios = abrir(excel.exportar("precios", "Precios", ColumnasExportacion.PRECIOS_PRESENTACION,
                FuentePaginada.deRespuesta(p -> precioQuesoService.listarPaginado(null, "yogurt freson", p))));
        assertThat(precios.getLastRowNum()).isEqualTo(11);
        assertThat(texto(precios, 5 + 0)).as("situacion Mayorista").containsOnly("SIN_PRECIO");

        for (int i = 0; i < 9; i++) usuarioRepository.save(usuario("Alfa Usuario " + i + " " + s, "u" + i + s + "@planta.com"));
        Sheet usuarios = abrir(excel.exportar("usuarios", "Usuarios", ColumnasExportacion.USUARIOS,
                p -> usuarioService.buscar(null, null, "alfa usuario", p)));
        assertThat(usuarios.getLastRowNum()).isEqualTo(9);
        assertThat(texto(usuarios, 1)).allMatch(c -> c.contains("@planta.com"));
        // Ninguna celda del archivo contiene el hash de la contrasena.
        for (var fila : usuarios) {
            for (var celda : fila) {
                assertThat(celda.toString()).doesNotContain("hash-secreto");
            }
        }
    }

    @Test
    void historicoDePrecioPorLitroOrdenadoPorVigencia() throws Exception {
        for (int i = 0; i < 8; i++) {
            PrecioLitro pl = new PrecioLitro();
            pl.setPrecio(new BigDecimal("1.000").add(new BigDecimal("0.010").multiply(BigDecimal.valueOf(i))));
            pl.setVigenteDesde(LocalDate.of(2040, 1, 1).plusDays(i));
            precioLitroRepository.save(pl);
        }
        Sheet hoja = abrir(excel.exportar("precios-litro", "Precio por litro", ColumnasExportacion.PRECIOS_LITRO, precioLitroService::listar));

        assertThat(hoja.getLastRowNum()).isGreaterThanOrEqualTo(8);
        assertThat(hoja.getRow(1).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2040, 1, 8)); // vigencia mas reciente primero
        assertThat(hoja.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(1.07);
    }

    @Test
    void unidadesConElNombreDeLaZona() throws Exception {
        Function<List<UnidadResponse>, Integer> contar = List::size;
        Unidad u = new Unidad();
        u.setPlaca("EXU-" + s);
        u.setResponsable(responsable);
        unidadRepository.save(u);

        Sheet hoja = abrir(excel.exportarLista("unidades", "Unidades", ColumnasExportacion.unidades(Map.of()), unidadService.listar(), "exu-" + s));

        assertThat(contar.apply(unidadService.listar())).isGreaterThanOrEqualTo(1);
        assertThat(hoja.getLastRowNum()).isEqualTo(1);
        assertThat(hoja.getRow(1).getCell(0).getStringCellValue()).isEqualTo("EXU-" + s);
        assertThat(hoja.getRow(1).getCell(3).getStringCellValue()).isEqualTo("Niña Quispe " + s);
    }
}
