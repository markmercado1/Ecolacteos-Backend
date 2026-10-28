package com.planta.acopio.integration;

import com.planta.acopio.dto.*;
import com.planta.acopio.entity.*;
import com.planta.acopio.repository.*;
import com.planta.acopio.service.*;
import com.planta.acopio.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SalidasInventarioIntegrationTest {
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry r) {
        PostgresDePrueba.registrar(r);
        r.add("app.vencimiento.activacion-habilitada", () -> "true");
    }
    @Autowired SalidaInventarioService salidas;
    @Autowired InsumoService insumos;
    @Autowired InventarioService inventario;
    @Autowired AjusteInventarioService ajustes;
    @Autowired ControlVencimientoService control;
    @Autowired KardexService kardex;
    @Autowired FamiliaRepository familias;
    @Autowired ProductoRepository productos;
    @Autowired PresentacionRepository presentaciones;
    @Autowired UsuarioRepository usuarios;
    @Autowired RolRepository roles;
    @Autowired ReglaVencimiento reloj;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private Usuario usuario;
    private UUID insumo;
    private Presentacion presentacion;
    private UUID vencido;
    private UUID vigente;

    @BeforeEach void preparar() {
        String nombre = UUID.randomUUID().toString();
        Rol rol = new Rol(); rol.setNombre("SAL_" + nombre.substring(0, 8)); rol = roles.save(rol);
        Usuario u = new Usuario(); u.setNombre("Operador de prueba"); u.setEmail(nombre + "@example.test"); u.setPasswordHash("hash"); u.setRol(rol); usuario = usuarios.save(u);
        autenticar();
        insumo = insumos.crear(new InsumoRequest("Sal " + nombre, "kg", new BigDecimal("20"))).id();
        Familia f = new Familia(); f.setNombre(nombre); f = familias.save(f);
        Producto pr = new Producto(); pr.setNombre(nombre); pr.setFamilia(f); pr = productos.save(pr);
        Presentacion p = new Presentacion(); p.setNombre("Unidad"); p.setUnidadConteo("unidad"); p.setProducto(pr); presentacion = presentaciones.save(p);
        ajustes.crear(new AjusteInventarioRequest(presentacion.getId(), 20, "Preparación de prueba"));
        control.activar(pr.getId());
        vencido = control.regularizar(presentacion.getId(), new RegularizacionRequest(8, reloj.hoy().minusDays(1), "Lote vencido de prueba")).id();
        vigente = control.regularizar(presentacion.getId(), new RegularizacionRequest(12, reloj.hoy().plusDays(3), "Lote vigente de prueba")).id();
    }
    @AfterEach void limpiar() { SecurityContextHolder.clearContext(); }
    private void autenticar() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(usuario.getEmail(), null)); }
    private SalidaInventarioRequest request(int cantidad, MotivoSalida tipo, UUID lote) {
        return new SalidaInventarioRequest(UUID.randomUUID(), BigDecimal.valueOf(cantidad), tipo, "Retiro físico verificado", lote == null ? null : "REGULARIZACION", lote);
    }
    @Test void retiraVencidoUnaVezYConservaTrazaEnKardex() {
        var dto = request(8, MotivoSalida.VENCIMIENTO, vencido);
        var r = salidas.registrar(presentacion.getId(), false, dto);
        assertThat(salidas.registrar(presentacion.getId(), false, dto)).isEqualTo(r);
        var e = inventario.obtenerExistencias(presentacion.getId());
        assertThat(e.stockFisico()).isEqualTo(12); assertThat(e.stockVendible()).isEqualTo(12); assertThat(e.stockVencido()).isZero();
        var k = kardex.presentacion(presentacion.getId(), null, null, PageRequest.of(0, 200));
        var m = k.movimientos().contenido().stream().filter(x -> x.id().equals(r.movimientoId())).findFirst().orElseThrow();
        assertThat(m.salida()).isEqualByComparingTo("8"); assertThat(m.tipoMovimiento()).isEqualTo("SALIDA_VENCIMIENTO");
        assertThat(m.referencia()).contains(vencido.toString(), reloj.hoy().minusDays(1).toString());
        assertThat(m.usuarioNombre()).isEqualTo(usuario.getNombre()); assertThat(m.motivo()).isEqualTo(dto.motivo());
        assertThat(jdbc.queryForObject("select count(*) from regularizacion_trazabilidad where id = ?", Integer.class, vencido)).isEqualTo(1);
    }
    @Test void rechazaLoteVigenteComoVencidoYNoDescuenta() {
        assertThatThrownBy(() -> salidas.registrar(presentacion.getId(), false, request(2, MotivoSalida.VENCIMIENTO, vigente))).isInstanceOf(ValidacionNegocioException.class);
        assertThat(inventario.obtenerExistencias(presentacion.getId()).stockFisico()).isEqualTo(20);
    }
    @Test void rechazaExcesoYFraccionesDeProducto() {
        assertThatThrownBy(() -> salidas.registrar(presentacion.getId(), false, request(9, MotivoSalida.DETERIORO, vencido))).isInstanceOf(ValidacionNegocioException.class);
        var d = new SalidaInventarioRequest(UUID.randomUUID(), new BigDecimal("1.5"), MotivoSalida.DETERIORO, "Rotura", "REGULARIZACION", vigente);
        assertThatThrownBy(() -> salidas.registrar(presentacion.getId(), false, d)).isInstanceOf(ValidacionNegocioException.class);
        assertThat(inventario.obtenerExistencias(presentacion.getId()).stockFisico()).isEqualTo(20);
    }
    @Test void insumoMantieneInvarianteYRechazaReutilizarClave() {
        var d = request(3, MotivoSalida.DETERIORO, null);
        var r = salidas.registrar(insumo, true, d);
        assertThat(salidas.registrar(insumo, true, d)).isEqualTo(r);
        assertThat(jdbc.queryForObject("select stock from insumo where id = ?", BigDecimal.class, insumo)).isEqualByComparingTo("17");
        var libro = kardex.insumo(insumo, null, null, PageRequest.of(0, 200));
        assertThat(libro.saldoFinal()).isEqualByComparingTo("17");
        var movimiento = libro.movimientos().contenido().stream().filter(m -> m.id().equals(r.movimientoId())).findFirst().orElseThrow();
        assertThat(movimiento.documentoId()).isEqualTo(r.id());
        assertThat(movimiento.documentoTipo()).isEqualTo("SALIDA");
        assertThat(movimiento.usuarioNombre()).isEqualTo(usuario.getNombre());
        assertThat(movimiento.motivo()).isEqualTo(d.motivo());
        var distinto = new SalidaInventarioRequest(d.uuidCliente(), BigDecimal.ONE, d.tipo(), d.motivo(), null, null);
        assertThatThrownBy(() -> salidas.registrar(insumo, true, distinto)).isInstanceOf(ConflictoNegocioException.class);
        assertThatThrownBy(() -> salidas.registrar(insumo, true, request(18, MotivoSalida.OTRA, null))).isInstanceOf(ValidacionNegocioException.class);
    }
    @Test void reintentoConcurrenteNoDuplicaSalida() throws Exception {
        var d = request(8, MotivoSalida.VENCIMIENTO, vencido);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrera = new CyclicBarrier(2);
        Callable<UUID> llamada = () -> { autenticar(); try { barrera.await(10, TimeUnit.SECONDS); return salidas.registrar(presentacion.getId(), false, d).movimientoId(); } finally { SecurityContextHolder.clearContext(); } };
        try {
            Future<UUID> a = pool.submit(llamada), b = pool.submit(llamada);
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
            assertThat(inventario.obtenerExistencias(presentacion.getId()).stockFisico()).isEqualTo(12);
        } finally { pool.shutdownNow(); }
    }
    @Test void permisosImpidenBajasAProduccionYVentas() throws Exception {
        for (String rol : new String[]{"PRODUCCION", "VENTAS", "RECEPCION"}) {
            mvc.perform(post("/api/inventario/insumos/" + insumo + "/salidas")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(usuario.getEmail()).roles(rol))
                .contentType("application/json").content("{\"uuidCliente\":\"" + UUID.randomUUID() + "\",\"cantidad\":1,\"tipo\":\"OTRA\",\"motivo\":\"Prueba\"}"))
                .andExpect(status().isForbidden());
        }
    }
}
