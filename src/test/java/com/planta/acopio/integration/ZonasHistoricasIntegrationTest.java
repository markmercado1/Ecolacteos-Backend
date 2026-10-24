package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.dto.RegistroAcopioDTO;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.service.ProveedorService;
import com.planta.acopio.service.RegistroAcopioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase A (zonas historicas) contra PostgreSQL real y la cadena de seguridad real (login con JWT, MockMvc):
 * - varias reasignaciones el MISMO dia no dejan periodos invertidos ni solapados (el defecto original);
 * - reasignaciones simultaneas se aplican una tras otra: exactamente un periodo abierto y una cadena continua;
 * - una entrega conserva la zona que tenia el proveedor cuando se hizo, tambien si se sincroniza despues de un cambio;
 * - el informe de revision es solo para ADMIN.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ZonasHistoricasIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(5000);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ZonaRepository zonaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private RegistroAcopioRepository registroAcopioRepository;
    @Autowired private ProveedorService proveedorService;
    @Autowired private RegistroAcopioService registroAcopioService;
    @Autowired private PasswordEncoder passwordEncoder;

    private int prueba;
    private String sufijo;

    @BeforeEach
    void preparar() {
        prueba = PRUEBAS.incrementAndGet();
        sufijo = String.valueOf(prueba);
        SecurityContextHolder.clearContext();
    }

    private String token(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    private String tokenDeRol(String rolNombre) throws Exception {
        Usuario u = usuario(rolNombre);
        return token(u.getEmail(), "Clave1234");
    }

    private Usuario usuario(String rolNombre) {
        Rol rol = rolRepository.findByNombre(rolNombre).orElseThrow();
        Usuario u = new Usuario();
        u.setNombre("Usuario " + rolNombre + " " + sufijo);
        u.setEmail(rolNombre.toLowerCase() + sufijo + "@planta.com");
        u.setPasswordHash(passwordEncoder.encode("Clave1234"));
        u.setRol(rol);
        u.setActivo(true);
        return usuarioRepository.save(u);
    }

    private Zona zona(String nombre) {
        Zona z = new Zona();
        z.setNombre(nombre + " " + sufijo);
        return zonaRepository.save(z);
    }

    private UUID proveedorEn(Zona zona) {
        return proveedorService.crear(new ProveedorRequest("Proveedor Zona " + sufijo, String.format("9%07d", prueba * 10 + (int) (System.nanoTime() % 10)),
                null, zona.getId())).id();
    }

    private JsonNode historial(String admin, UUID proveedorId) throws Exception {
        MvcResult r = mvc.perform(get("/api/proveedores/" + proveedorId + "/historial-zona").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** Periodos ordenados por inicio: cada uno termina exactamente donde empieza el siguiente; solo el ultimo esta abierto. */
    private void verificarCadenaContinua(JsonNode periodos) {
        List<JsonNode> ordenados = new ArrayList<>();
        periodos.forEach(ordenados::add);
        ordenados.sort((a, b) -> LocalDateTime.parse(a.get("inicioEn").asText()).compareTo(LocalDateTime.parse(b.get("inicioEn").asText())));
        for (int i = 0; i < ordenados.size(); i++) {
            JsonNode p = ordenados.get(i);
            assertThat(p.get("requiereRevision").asBoolean()).isFalse();
            assertThat(p.get("hasta").isNull() || p.get("hasta").asText().compareTo(p.get("desde").asText()) >= 0)
                    .as("hasta no puede ser anterior a desde: %s", p).isTrue();
            if (i < ordenados.size() - 1) {
                assertThat(p.get("finEn").isNull()).as("solo el ultimo periodo esta abierto").isFalse();
                assertThat(p.get("finEn").asText()).as("el periodo termina donde empieza el siguiente")
                        .isEqualTo(ordenados.get(i + 1).get("inicioEn").asText());
            } else {
                assertThat(p.get("finEn").isNull()).as("el ultimo periodo sigue abierto").isTrue();
            }
        }
    }

    @Test
    void variasReasignacionesElMismoDiaNoDejanPeriodosInvertidosNiSolapadosYGuardanOrigenMotivoYUsuario() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        Zona a = zona("A"), b = zona("B"), c = zona("C");
        UUID proveedorId = proveedorEn(a);

        mvc.perform(put("/api/proveedores/" + proveedorId + "/zona").param("zonaId", b.getId().toString()).param("motivo", "Nueva ruta")
                .header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(put("/api/proveedores/" + proveedorId + "/zona").param("zonaId", c.getId().toString())
                .header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(put("/api/proveedores/" + proveedorId + "/zona").param("zonaId", a.getId().toString()).param("motivo", "Vuelve a su zona")
                .header("Authorization", admin)).andExpect(status().isOk());

        JsonNode periodos = historial(admin, proveedorId);
        assertThat(periodos).hasSize(4);
        verificarCadenaContinua(periodos);

        List<JsonNode> porInicio = new ArrayList<>();
        periodos.forEach(porInicio::add);
        porInicio.sort((x, y) -> x.get("inicioEn").asText().compareTo(y.get("inicioEn").asText()));
        assertThat(porInicio.get(0).get("motivo").asText()).isEqualTo("Alta del proveedor");
        assertThat(porInicio.get(1).get("zonaOrigenNombre").asText()).isEqualTo(a.getNombre());
        assertThat(porInicio.get(1).get("zonaNombre").asText()).isEqualTo(b.getNombre());
        assertThat(porInicio.get(1).get("motivo").asText()).isEqualTo("Nueva ruta");
        assertThat(porInicio.get(1).get("usuarioNombre").asText()).isNotBlank(); // quien hizo el cambio
        assertThat(porInicio.get(3).get("zonaNombre").asText()).isEqualTo(a.getNombre());
        assertThat(porInicio.get(3).get("zonaOrigenNombre").asText()).isEqualTo(c.getNombre());
        assertThat(jdbc.queryForObject("SELECT zona_actual_id FROM proveedor WHERE id = ?", UUID.class, proveedorId)).isEqualTo(a.getId());

        // un motivo mas largo que la columna se rechaza (400) y no cambia nada
        mvc.perform(put("/api/proveedores/" + proveedorId + "/zona").param("zonaId", b.getId().toString()).param("motivo", "x".repeat(201))
                .header("Authorization", admin)).andExpect(status().is4xxClientError());
        assertThat(historial(admin, proveedorId)).hasSize(4);
    }

    @Test
    void reasignacionesSimultaneasSeAplicanUnaTrasOtraConUnSoloPeriodoAbierto() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        Zona inicial = zona("Inicial");
        UUID proveedorId = proveedorEn(inicial);
        int hilos = 6;
        List<Zona> destinos = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            destinos.add(zona("Destino" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        for (Zona destino : destinos) {
            resultados.add(pool.submit(() -> {
                salida.await();
                return mvc.perform(put("/api/proveedores/" + proveedorId + "/zona").param("zonaId", destino.getId().toString())
                        .header("Authorization", admin)).andReturn().getResponse().getStatus();
            }));
        }
        salida.countDown();
        for (Future<Integer> r : resultados) {
            assertThat(r.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();

        JsonNode periodos = historial(admin, proveedorId);
        assertThat(periodos).hasSize(1 + hilos);
        verificarCadenaContinua(periodos);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historial_zona WHERE proveedor_id = ? AND fin_en IS NULL", Integer.class, proveedorId))
                .isEqualTo(1);
        // la zona actual del proveedor es la del unico periodo abierto
        assertThat(jdbc.queryForObject("SELECT p.zona_actual_id = h.zona_id FROM proveedor p JOIN historial_zona h ON h.proveedor_id = p.id "
                + "AND h.fin_en IS NULL WHERE p.id = ?", Boolean.class, proveedorId)).isTrue();
    }

    @Test
    void laEntregaConservaLaZonaQueTeniaElProveedorAunqueSeSincroniceDespuesDeUnCambio() {
        Usuario acopiador = usuario("ACOPIADOR");
        Unidad unidad = new Unidad();
        unidad.setPlaca("ZH" + sufijo);
        unidad.setResponsable(acopiador);
        unidad = unidadRepository.save(unidad);
        Zona a = zona("Origen"), b = zona("Intermedia"), c = zona("Actual");
        UUID proveedorId = proveedorEn(a);

        // historia controlada: A desde hace 10 dias, B desde hace 2 dias (periodo abierto)
        LocalDateTime ahora = LocalDateTime.now();
        jdbc.update("DELETE FROM historial_zona WHERE proveedor_id = ?", proveedorId);
        periodo(proveedorId, a, ahora.minusDays(10), ahora.minusDays(2));
        periodo(proveedorId, b, ahora.minusDays(2), null);
        jdbc.update("UPDATE proveedor SET zona_actual_id = ? WHERE id = ?", b.getId(), proveedorId);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(acopiador.getEmail(), null));
        UUID unidadId = unidad.getId();

        UUID enA = registrar(proveedorId, unidadId, ahora.minusDays(5));
        UUID enB = registrar(proveedorId, unidadId, ahora.minusDays(1));
        UUID anteriorALaHistoria = registrar(proveedorId, unidadId, ahora.minusDays(20));
        assertThat(zonaDeEntrega(enA)).isEqualTo(a.getId());
        assertThat(zonaDeEntrega(enB)).isEqualTo(b.getId());
        assertThat(zonaDeEntrega(anteriorALaHistoria)).as("sin periodo que la cubra: sin zona, no se usa la actual").isNull();

        // el proveedor cambia a C y despues llega una entrega de ayer (sincronizada tarde): conserva B, no toma C
        proveedorService.reasignarZona(proveedorId, c.getId(), "Cambio posterior");
        UUID tarde = registrar(proveedorId, unidadId, ahora.minusDays(1).plusMinutes(1));
        UUID despues = registrar(proveedorId, unidadId, LocalDateTime.now().plusSeconds(1));
        assertThat(zonaDeEntrega(tarde)).isEqualTo(b.getId());
        assertThat(zonaDeEntrega(despues)).isEqualTo(c.getId());

        // reintento idempotente: no duplica ni cambia la zona
        long antes = registroAcopioRepository.count();
        String uuidCliente = jdbc.queryForObject("SELECT uuid_cliente FROM registro_acopio WHERE id = ?", String.class, tarde);
        registroAcopioService.registrarOIgnorarSiDuplicado(dto(uuidCliente, proveedorId, unidadId, ahora.minusDays(1).plusMinutes(1)));
        assertThat(registroAcopioRepository.count()).isEqualTo(antes);
        assertThat(zonaDeEntrega(tarde)).isEqualTo(b.getId());
        SecurityContextHolder.clearContext();
    }

    @Test
    void unPeriodoMarcadoParaRevisionNuncaDecideLaZonaDeUnaEntrega() {
        Usuario acopiador = usuario("ACOPIADOR");
        Unidad unidad = new Unidad();
        unidad.setPlaca("MR" + sufijo);
        unidad.setResponsable(acopiador);
        unidad = unidadRepository.save(unidad);
        Zona valida = zona("Valida"), dudosa = zona("Dudosa");
        UUID proveedorId = proveedorEn(valida);

        LocalDateTime ahora = LocalDateTime.now();
        jdbc.update("DELETE FROM historial_zona WHERE proveedor_id = ?", proveedorId);
        periodo(proveedorId, valida, ahora.minusDays(10), null);
        // marcado para revision pero con rango real (no de duracion cero) y solapado con el valido
        jdbc.update("INSERT INTO historial_zona (proveedor_id, zona_id, desde, hasta, inicio_en, fin_en, requiere_revision, nota_revision) "
                        + "VALUES (?, ?, ?, ?, ?, ?, true, 'Dudoso')", proveedorId, dudosa.getId(),
                java.sql.Date.valueOf(ahora.minusDays(5).toLocalDate()), java.sql.Date.valueOf(ahora.minusDays(3).toLocalDate()),
                Timestamp.valueOf(ahora.minusDays(5)), Timestamp.valueOf(ahora.minusDays(3)));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(acopiador.getEmail(), null));
        UUID entrega = registrar(proveedorId, unidad.getId(), ahora.minusDays(4));
        SecurityContextHolder.clearContext();

        assertThat(zonaDeEntrega(entrega)).isEqualTo(valida.getId());
    }

    @Test
    void elInformeDeRevisionListaLoPendienteYSoloLoVeUnAdmin() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        Usuario acopiador = usuario("ACOPIADOR");
        Unidad unidad = new Unidad();
        unidad.setPlaca("RV" + sufijo);
        unidad.setResponsable(acopiador);
        unidad = unidadRepository.save(unidad);
        Zona a = zona("Rev");
        UUID proveedorId = proveedorEn(a);

        // un periodo invertido heredado del codigo anterior (marcado) y una entrega anterior a la historia (sin zona)
        jdbc.update("INSERT INTO historial_zona (proveedor_id, zona_id, desde, hasta, inicio_en, fin_en, requiere_revision, nota_revision) "
                + "VALUES (?, ?, '2026-09-30', '2026-09-29', '2026-09-30 00:00:00', '2026-09-30 00:00:00', true, 'Periodo invertido de prueba')",
                proveedorId, a.getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(acopiador.getEmail(), null));
        UUID sinZona = registrar(proveedorId, unidad.getId(), LocalDateTime.now().minusDays(25));
        SecurityContextHolder.clearContext();

        MvcResult r = mvc.perform(get("/api/admin/zonas/revision").header("Authorization", admin)).andExpect(status().isOk()).andReturn();
        JsonNode informe = json.readTree(r.getResponse().getContentAsString());
        assertThat(informe.get("totalEntregasSinZona").asLong()).isGreaterThanOrEqualTo(1);
        boolean periodoListado = false, entregaListada = false;
        for (JsonNode p : informe.get("periodos")) {
            periodoListado |= p.get("proveedorId").asText().equals(proveedorId.toString()) && p.get("nota").asText().contains("invertido");
        }
        for (JsonNode e : informe.get("entregasSinZona")) {
            if (e.get("registroId").asText().equals(sinZona.toString())) {
                entregaListada = true;
                assertThat(e.get("motivo").asText()).isEqualTo("SIN_PERIODO");
            }
        }
        assertThat(periodoListado).as("el periodo invertido aparece en el informe").isTrue();
        assertThat(entregaListada).as("la entrega sin zona aparece en el informe").isTrue();

        // solo ADMIN: un acopiador y recepcion reciben 403; sin token, 401/403
        List<String> tokensSinPermiso = List.of(token(acopiador.getEmail(), "Clave1234"), tokenDeRol("RECEPCION"), tokenDeRol("VENTAS"));
        for (String token : tokensSinPermiso) {
            mvc.perform(get("/api/admin/zonas/revision").header("Authorization", token)).andExpect(status().isForbidden());
        }
        assertThat(mvc.perform(get("/api/admin/zonas/revision")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    private void periodo(UUID proveedorId, Zona zona, LocalDateTime inicio, LocalDateTime fin) {
        jdbc.update("INSERT INTO historial_zona (proveedor_id, zona_id, desde, hasta, inicio_en, fin_en) VALUES (?, ?, ?, ?, ?, ?)",
                proveedorId, zona.getId(), java.sql.Date.valueOf(inicio.toLocalDate()),
                fin == null ? null : java.sql.Date.valueOf(fin.toLocalDate().minusDays(1)),
                Timestamp.valueOf(inicio), fin == null ? null : Timestamp.valueOf(fin));
    }

    private RegistroAcopioDTO dto(String uuidCliente, UUID proveedorId, UUID unidadId, LocalDateTime fechaHora) {
        return new RegistroAcopioDTO(uuidCliente, proveedorId, unidadId, fechaHora, new BigDecimal("50.00"), null, null, null, false, null, null);
    }

    private UUID registrar(UUID proveedorId, UUID unidadId, LocalDateTime fechaHora) {
        return registroAcopioService.registrarOIgnorarSiDuplicado(dto(UUID.randomUUID().toString(), proveedorId, unidadId, fechaHora)).id();
    }

    private UUID zonaDeEntrega(UUID registroId) {
        return jdbc.queryForObject("SELECT zona_id FROM registro_acopio WHERE id = ?", UUID.class, registroId);
    }


    // Criterio de aceptacion de la Fase A: reasignar a la zona en la que ya esta se rechaza y no ensucia el historial.
    @Test
    void reasignarALaMismaZonaSeRechazaConConflictoYNoAbreOtroPeriodo() throws Exception {
        String admin = token("admin@planta.com", "Admin1234");
        Zona a = zona("Unica");
        UUID proveedorId = proveedorEn(a);

        mvc.perform(put("/api/proveedores/" + proveedorId + "/zona").param("zonaId", a.getId().toString()).header("Authorization", admin))
                .andExpect(status().isConflict());

        JsonNode periodos = historial(admin, proveedorId);
        assertThat(periodos).hasSize(1); // solo el alta
        verificarCadenaContinua(periodos);
        assertThat(jdbc.queryForObject("SELECT zona_actual_id FROM proveedor WHERE id = ?", UUID.class, proveedorId)).isEqualTo(a.getId());
    }
}
