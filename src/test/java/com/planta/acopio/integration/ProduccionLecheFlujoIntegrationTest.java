package com.planta.acopio.integration;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.FamiliaRepository;
import com.planta.acopio.repository.LoteProduccionRecepcionConsumoRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.MovimientoInventarioLecheRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InventarioLecheService;
import com.planta.acopio.service.LoteProduccionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verificación pedida explícitamente por el usuario antes de dar la Fase 2 del refactor de Producción
 * (ACOPIO -> RECEPCIÓN -> EXISTENCIA DE LECHE -> PRODUCCIÓN) por cerrada: PostgreSQL 15 real vía
 * Testcontainers (mismo patrón que SchemaIntegrationTest/RegistroAcopioAuthorizationIntegrationTest),
 * V8/V9 aplicadas por Flyway de verdad, flujo completo Recepción→Producción contra tablas reales
 * (movimiento_inventario_leche, lote_produccion_recepcion_consumo), y verificación de que un intento
 * rechazado por saldo insuficiente no escribe nada en la base de datos real.
 *
 * @Transactional a nivel de clase: cada @Test corre en su propia transacción con rollback automático
 * (mismo patrón que RegistroAcopioAuthorizationIntegrationTest) -- por eso NO incluye los casos de
 * concurrencia real ni de carrera simultánea de uuidCliente (esos necesitan transacciones/conexiones JDBC
 * genuinamente independientes por hilo, ver ProduccionLecheConcurrenciaIntegrationTest).
 */
@Testcontainers
@SpringBootTest
@Transactional
class ProduccionLecheFlujoIntegrationTest {

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

    @Autowired private ConciliacionService conciliacionService;
    @Autowired private LoteProduccionService loteProduccionService;
    @Autowired private InventarioLecheService inventarioLecheService;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProductoRepository productoRepository;
    @Autowired private PresentacionRepository presentacionRepository;
    @Autowired private FamiliaRepository familiaRepository;
    @Autowired private LoteProduccionRepository loteProduccionRepository;
    @Autowired private LoteProduccionRecepcionConsumoRepository consumoRepository;
    @Autowired private MovimientoInventarioLecheRepository movimientoLecheRepository;

    private Usuario responsable;
    private Unidad unidad;
    private Producto producto;
    private Presentacion presentacion;

    @BeforeEach
    void seedComun() {
        Rol rol = rolRepository.save(rolCon("PROD_FLUJO_IT_" + (System.nanoTime() % 1_000_000_000L)));
        responsable = usuarioRepository.save(usuarioCon(rol));
        unidad = unidadRepository.save(unidadCon(responsable));
        producto = productoRepository.save(productoCon());
        presentacion = presentacionRepository.save(presentacionCon(producto));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(responsable.getEmail(), null));
    }

    // Caso 1/2 del pedido original, contra Postgres real: Recepción 300 L -> Producción 150 L -> saldo 150 L,
    // con el desglose FIFO (lote_produccion_recepcion_consumo) y el libro mayor de leche
    // (movimiento_inventario_leche) verificados como filas reales, no como mocks.
    @Test
    void v9AplicaYElFlujoRecepcionProduccionActualizaElSaldoRealEnPostgres() {
        var recepcion = conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), BigDecimal.valueOf(300), BigDecimal.valueOf(300), List.of()));

        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(300));

        var lote = loteProduccionService.crear(new CrearLoteRequest(
                UUID.randomUUID().toString(), LocalDate.now(), producto.getId(), BigDecimal.valueOf(150),
                List.of(new DetalleSalidaRequest(presentacion.getId(), 20))));

        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(150));

        var filasConsumo = consumoRepository.findByLoteProduccionId(lote.id());
        assertThat(filasConsumo).hasSize(1);
        assertThat(filasConsumo.get(0).getRecepcionPlanta().getId()).isEqualTo(recepcion.id());
        assertThat(filasConsumo.get(0).getLitrosConsumidos()).isEqualByComparingTo(BigDecimal.valueOf(150));

        // Un movimiento de entrada (recepcion) + uno de salida (lote), como exigen los CHECK e indices
        // unicos parciales de V8 -- si alguno hubiera fallado, esta linea nunca se alcanzaria.
        assertThat(movimientoLecheRepository.count()).isEqualTo(2);
    }

    // Producción posterior sobre la misma recepción (Casos 2/4/5): el saldo sigue bajando correctamente.
    @Test
    void unaSegundaProduccionSobreLaMismaRecepcionSigueReduciendoElSaldoReal() {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), BigDecimal.valueOf(300), BigDecimal.valueOf(300), List.of()));

        loteProduccionService.crear(new CrearLoteRequest(
                UUID.randomUUID().toString(), LocalDate.now(), producto.getId(), BigDecimal.valueOf(150),
                List.of(new DetalleSalidaRequest(presentacion.getId(), 20))));
        loteProduccionService.crear(new CrearLoteRequest(
                UUID.randomUUID().toString(), LocalDate.now(), producto.getId(), BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacion.getId(), 15))));

        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(loteProduccionRepository.count()).isEqualTo(2);
    }

    // Caso 3 contra Postgres real: rechaza por saldo insuficiente y no escribe absolutamente nada -- ni el
    // lote, ni el consumo, ni el movimiento de leche (la transaccion completa se revierte).
    @Test
    void rechazaSiExcedeElSaldoYNoEscribeNadaEnLaBdReal() {
        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), producto.getId(),
                BigDecimal.valueOf(80), List.of(new DetalleSalidaRequest(presentacion.getId(), 10)));
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), BigDecimal.valueOf(50), BigDecimal.valueOf(50), List.of()));

        assertThatThrownBy(() -> loteProduccionService.crear(dto)).isInstanceOf(ValidacionNegocioException.class);

        assertThat(loteProduccionRepository.findByUuidCliente(dto.uuidCliente())).isEmpty();
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(50)); // sin cambios
    }

    // Idempotencia secuencial (Caso 9) contra Postgres real: reenviar el mismo uuidCliente en una llamada
    // NUEVA (tras un intento ya confirmado) devuelve el mismo lote, sin segunda fila ni segundo movimiento.
    @Test
    void reenviarElMismoUuidClienteNoDuplicaElLoteNiElMovimientoDeLecheReal() {
        conciliacionService.registrarYConciliar(new RecepcionPlantaRequest(
                LocalDate.now(), null, unidad.getId(), BigDecimal.valueOf(300), BigDecimal.valueOf(300), List.of()));
        String uuidCliente = UUID.randomUUID().toString();
        var dto = new CrearLoteRequest(uuidCliente, LocalDate.now(), producto.getId(), BigDecimal.valueOf(150),
                List.of(new DetalleSalidaRequest(presentacion.getId(), 20)));

        var primero = loteProduccionService.crear(dto);
        var segundo = loteProduccionService.crear(dto); // mismo uuidCliente, "reintento"

        assertThat(segundo.id()).isEqualTo(primero.id());
        assertThat(loteProduccionRepository.count()).isEqualTo(1);
        assertThat(movimientoLecheRepository.count()).isEqualTo(2); // 1 entrada + 1 salida, nunca 2 salidas
        assertThat(inventarioLecheService.obtenerSaldoDisponibleLitros()).isEqualByComparingTo(BigDecimal.valueOf(150));
    }

    private Rol rolCon(String nombre) {
        Rol r = new Rol();
        r.setNombre(nombre);
        return r;
    }

    private Usuario usuarioCon(Rol rol) {
        Usuario u = new Usuario();
        u.setNombre("Produccion Flujo Test");
        u.setEmail("produccion-flujo-" + System.nanoTime() + "@planta.com");
        u.setPasswordHash("hash");
        u.setRol(rol);
        return u;
    }

    private Unidad unidadCon(Usuario responsable) {
        Unidad u = new Unidad();
        u.setPlaca("FLUJ-" + (System.nanoTime() % 1_000_000_000L)); // placa VARCHAR(20)
        u.setResponsable(responsable);
        return u;
    }

    private Producto productoCon() {
        Familia familia = new Familia();
        familia.setNombre("Queso Flujo Test " + System.nanoTime());
        Producto t = new Producto();
        t.setNombre("Queso Flujo Test " + System.nanoTime());
        t.setFamilia(familiaRepository.save(familia));
        t.setRendimientoEsperadoPct(BigDecimal.valueOf(12));
        return t;
    }

    private Presentacion presentacionCon(Producto producto) {
        Presentacion p = new Presentacion();
        p.setNombre("Unidad");
        p.setProducto(producto);
        p.setUnidadConteo("unidad");
        return p;
    }
}
