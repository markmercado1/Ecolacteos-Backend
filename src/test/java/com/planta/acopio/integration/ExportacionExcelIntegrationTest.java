package com.planta.acopio.integration;

import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.TipoClienteVenta;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Venta;
import com.planta.acopio.entity.VentaDetalle;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.excel.ColumnasExportacion;
import com.planta.acopio.excel.ArchivoExcel;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.LimitesExcel;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.PagoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.VentaDetalleRepository;
import com.planta.acopio.repository.VentaRepository;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.service.PagoService;
import com.planta.acopio.service.ProveedorService;
import com.planta.acopio.service.RegistroAcopioService;
import com.planta.acopio.service.VentaService;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exportacion a Excel contra PostgreSQL real: las MISMAS consultas que alimentan las tablas (nativas, con busqueda sin
 * acentos, filtros y joins), recorridas por lotes pequenos para forzar varias paginas.
 */
@SpringBootTest
@Transactional
class ExportacionExcelIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    /** Lote de 10 para que 25 filas ocupen tres paginas. */
    private final ExportacionExcelService excel =
            new ExportacionExcelService(new LimitesExcel(1_000, 10, 2_097_152, 2_000, 10_000, 100, 26_214_400, 30));

    @Autowired private ProveedorService proveedorService;
    @Autowired private PagoService pagoService;
    @Autowired private VentaService ventaService;
    @Autowired private RegistroAcopioService registroAcopioService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private ZonaRepository zonaRepository;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private PagoRepository pagoRepository;
    @Autowired private RegistroAcopioRepository registroAcopioRepository;
    @Autowired private VentaRepository ventaRepository;
    @Autowired private VentaDetalleRepository ventaDetalleRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;

    private String s;
    private Rol rol;
    private Usuario usuario;
    private Zona zonaNorte;
    private Zona zonaSur;

    @BeforeEach
    void sembrar() {
        s = String.valueOf(System.nanoTime() % 1_000_000_000L);
        rol = new Rol();
        rol.setNombre("EXP_IT_" + s);
        rol = rolRepository.save(rol);
        usuario = new Usuario();
        usuario.setNombre("Responsable " + s);
        usuario.setEmail("exp" + s + "@planta.com");
        usuario.setPasswordHash("hash");
        usuario.setRol(rol);
        usuario = usuarioRepository.save(usuario);
        zonaNorte = zona("Norte " + s);
        zonaSur = zona("Sur " + s);
    }

    private Zona zona(String nombre) {
        Zona z = new Zona();
        z.setNombre(nombre);
        return zonaRepository.save(z);
    }

    private Proveedor proveedor(String nombre, String dni, Zona zona, boolean activo) {
        Proveedor p = new Proveedor();
        p.setNombre(nombre);
        p.setDni(dni);
        p.setTelefono("+51987654321");
        p.setZonaActual(zona);
        p.setActivo(activo);
        p.setCodigoQr(UUID.randomUUID().toString());
        return proveedorRepository.save(p);
    }

    private static Sheet abrir(ArchivoExcel archivo) throws Exception {
        try (InputStream in = Files.newInputStream(archivo.ruta())) {
            return new XSSFWorkbook(in).getSheetAt(0);
        } finally {
            archivo.descartar();
        }
    }

    private static List<String> columna(Sheet hoja, int indice) {
        List<String> valores = new ArrayList<>();
        for (int i = 1; i <= hoja.getLastRowNum(); i++) {
            valores.add(hoja.getRow(i).getCell(indice).getStringCellValue());
        }
        return valores;
    }

    @Test
    void proveedoresTodasLasFilasDelFiltroEnVariasPaginasConDniComoTexto() throws Exception {
        for (int i = 0; i < 25; i++) {
            proveedor(String.format("Niño Pérez %02d %s", i, s), String.format("0%02d%05d", i, Integer.parseInt(s.substring(0, 5))), zonaNorte, true);
        }
        proveedor("Rosa Huamán " + s, "09" + s.substring(0, 6), zonaSur, false);

        // Sin filtros pero acotado por el sufijo: 26 filas (3 paginas de 10).
        Sheet todos = abrir(excel.exportar("proveedores", "Proveedores", ColumnasExportacion.PROVEEDORES,
                pagina -> proveedorService.buscar(null, null, s, pagina)));
        assertThat(todos.getLastRowNum()).isEqualTo(26);
        assertThat(columna(todos, 0)).hasSize(26).doesNotHaveDuplicates();
        // El DNI conserva los ceros iniciales y es TEXTO, no numero.
        assertThat(todos.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
        assertThat(columna(todos, 1)).allMatch(dni -> dni.matches("\\d{8}") && dni.startsWith("0"));
        assertThat(columna(todos, 2)).containsOnly("+51987654321"); // telefono con "+" intacto, no neutralizado

        // Busqueda sin acentos ni mayusculas: solo los 25 "Niño Pérez".
        Sheet buscados = abrir(excel.exportar("proveedores", "Proveedores", ColumnasExportacion.PROVEEDORES,
                pagina -> proveedorService.buscar(null, null, "NINO PEREZ", pagina)));
        assertThat(columna(buscados, 0)).hasSize(25).allMatch(n -> n.startsWith("Niño Pérez"));

        // Filtros combinados: zona + estado.
        Sheet inactivosDelSur = abrir(excel.exportar("proveedores", "Proveedores", ColumnasExportacion.PROVEEDORES,
                pagina -> proveedorService.buscar(zonaSur.getId(), false, null, pagina)));
        assertThat(columna(inactivosDelSur, 0)).containsExactly("Rosa Huamán " + s);
        assertThat(columna(inactivosDelSur, 4)).containsExactly("No");
    }

    @Test
    void pagosExportaTodosLosQueCoincidenConLaBusqueda() throws Exception {
        Proveedor huaman = proveedor("María Huamán " + s, "07" + s.substring(0, 6), zonaNorte, true);
        Proveedor quispe = proveedor("Rosa Quispe " + s, "08" + s.substring(0, 6), zonaNorte, true);
        LocalDate semana = LocalDate.of(2031, 5, 5);
        for (int i = 0; i < 14; i++) {
            pagoRepository.save(pago(i % 2 == 0 ? huaman : quispe, semana.plusWeeks(i), LocalDateTime.of(2031, 5, 1, 9, 0).plusHours(i)));
        }

        Sheet conBusqueda = abrir(excel.exportar("pagos", "Pagos", ColumnasExportacion.PAGOS,
                pagina -> pagoService.listar("huaman " + s, pagina)));
        assertThat(columna(conBusqueda, 0)).hasSize(7).containsOnly("María Huamán " + s);
        assertThat(conBusqueda.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(100.0);

        Sheet todos = abrir(excel.exportar("pagos", "Pagos", ColumnasExportacion.PAGOS,
                pagina -> pagoService.listar(s, pagina)));
        assertThat(todos.getLastRowNum()).isEqualTo(14);
    }

    @Test
    void ventasUnaFilaPorLineaConFiltroDeFechaYCanal() throws Exception {
        Presentacion botella = presentacion("Yogurt Fresón " + s, "Botella " + s);
        Presentacion pieza = presentacion("Queso Andino " + s, "Pieza " + s);
        LocalDate dia = LocalDate.of(2031, 6, 10);
        for (int i = 0; i < 12; i++) {
            venta(dia, "uv" + i + "-" + s, botella, pieza);
        }
        venta(dia.minusDays(1), "uv-otro-" + s, botella);

        Sheet delDia = abrir(excel.exportar("ventas", "Ventas", ColumnasExportacion.VENTAS, pagina -> {
            var ventas = ventaService.buscar(dia, null, s, pagina);
            return new PageImpl<>(ColumnasExportacion.lineasDe(ventas.getContent()), pagina, ventas.getTotalElements());
        }));
        assertThat(delDia.getLastRowNum()).isEqualTo(24); // 12 ventas x 2 lineas
        // El total de la venta aparece una sola vez por venta.
        long conTotal = 0;
        for (int i = 1; i <= delDia.getLastRowNum(); i++) {
            if (delDia.getRow(i).getCell(7).getCellType() == CellType.NUMERIC) conTotal++;
        }
        assertThat(conTotal).isEqualTo(12);

        Sheet porProducto = abrir(excel.exportar("ventas", "Ventas", ColumnasExportacion.VENTAS, pagina -> {
            var ventas = ventaService.buscar(null, "PUBLICO", "freson " + s, pagina);
            return new PageImpl<>(ColumnasExportacion.lineasDe(ventas.getContent()), pagina, ventas.getTotalElements());
        }));
        assertThat(porProducto.getLastRowNum()).isEqualTo(25); // 12 ventas de 2 lineas + la del dia anterior (1 linea)
    }

    @Test
    void acopiosFiltrosPorFechaZonaYBusqueda() throws Exception {
        Proveedor norte = proveedor("Ana Pérez " + s, "05" + s.substring(0, 6), zonaNorte, true);
        Proveedor sur = proveedor("Luis Mamani " + s, "06" + s.substring(0, 6), zonaSur, true);
        Unidad unidad = new Unidad();
        unidad.setPlaca("EXP-" + s);
        unidad.setResponsable(usuario);
        unidad = unidadRepository.save(unidad);
        LocalDateTime hoy = LocalDateTime.of(2031, 9, 1, 6, 30);
        for (int i = 0; i < 13; i++) {
            registro(norte, unidad, hoy.plusMinutes(i), "10." + i);
        }
        for (int i = 0; i < 4; i++) {
            registro(sur, unidad, hoy.plusDays(1).plusMinutes(i), "20.5");
        }

        Sheet todos = abrir(excel.exportar("acopios", "Acopios", ColumnasExportacion.ACOPIOS,
                pagina -> registroAcopioService.buscarPaginado(null, null, null, s, pagina)));
        assertThat(todos.getLastRowNum()).isEqualTo(17);

        Sheet porZona = abrir(excel.exportar("acopios", "Acopios", ColumnasExportacion.ACOPIOS,
                pagina -> registroAcopioService.buscarPaginado(null, zonaNorte.getId(), null, s, pagina)));
        assertThat(columna(porZona, 1)).hasSize(13).containsOnly("Ana Pérez " + s);

        Sheet porFechaYBusqueda = abrir(excel.exportar("acopios", "Acopios", ColumnasExportacion.ACOPIOS,
                pagina -> registroAcopioService.buscarPaginado(hoy.toLocalDate().plusDays(1), null, null, "MAMANI", pagina)));
        assertThat(columna(porFechaYBusqueda, 1)).hasSize(4).containsOnly("Luis Mamani " + s);
        assertThat(porFechaYBusqueda.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(20.5);
    }

    // ------------------------------------------------------------------------------------------ datos

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

    private void venta(LocalDate fecha, String uuid, Presentacion... lineas) {
        Venta v = new Venta();
        v.setUuidCliente(uuid);
        v.setFecha(fecha);
        v.setTipoCliente(TipoClienteVenta.PUBLICO);
        v.setTotal(new BigDecimal("10.00").multiply(BigDecimal.valueOf(lineas.length)));
        v.setVendedor(usuario);
        v = ventaRepository.saveAndFlush(v);
        for (Presentacion pr : lineas) {
            VentaDetalle d = new VentaDetalle();
            d.setVenta(v);
            d.setPresentacion(pr);
            d.setCantidad(1);
            d.setPrecioUnitario(new BigDecimal("10.00"));
            ventaDetalleRepository.saveAndFlush(d);
        }
    }

    private void registro(Proveedor proveedor, Unidad unidad, LocalDateTime fechaHora, String litros) {
        RegistroAcopio r = new RegistroAcopio();
        r.setUuidCliente(UUID.randomUUID().toString());
        r.setProveedor(proveedor);
        r.setAcopiador(usuario);
        r.setUnidad(unidad);
        r.setFechaHora(fechaHora);
        r.setLitros(new BigDecimal(litros));
        // Fase E: la zona de una entrega es la que tenia el proveedor cuando entrego (se guarda al registrarla); los reportes por zona la usan.
        r.setZona(proveedor.getZonaActual());
        registroAcopioRepository.save(r);
    }
}
