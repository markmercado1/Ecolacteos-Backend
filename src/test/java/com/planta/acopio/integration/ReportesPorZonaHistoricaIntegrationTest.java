package com.planta.acopio.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.dto.RegistroAcopioDTO;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.AlertaAnomaliaRepository;
import com.planta.acopio.repository.AnalisisCalidadRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase E (reportes por zona historica) contra PostgreSQL real. Un proveedor que cambio de zona (Norte -> Sur) con tres entregas en Norte, dos en Sur y una
 * anterior a toda zona, y otro proveedor que siempre estuvo en Norte. Los listados, totales, alertas, calidad y bases del forecasting por zona usan la zona
 * de CADA ENTREGA: la historia del proveedor no se mueve a su zona actual, y una entrega sin zona determinada no entra en ninguna zona (se ve en la
 * pantalla de revision de zonas).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportesPorZonaHistoricaIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(9500);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UnidadRepository unidadRepository;
    @Autowired private ProveedorService proveedorService;
    @Autowired private RegistroAcopioService registroAcopioService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RegistroAcopioRepository registroRepository;
    @Autowired private AlertaAnomaliaRepository alertaRepository;
    @Autowired private AnalisisCalidadRepository analisisRepository;

    private int prueba;
    private String sufijo;
    private Usuario acopiador;
    private UUID unidadId;
    private LocalDate hoy;
    private String admin;

    @BeforeEach
    void preparar() throws Exception {
        prueba = PRUEBAS.incrementAndGet();
        sufijo = String.valueOf(prueba);
        hoy = LocalDate.now();
        SecurityContextHolder.clearContext();
        acopiador = usuario("ACOPIADOR");
        Unidad unidad = new Unidad();
        unidad.setPlaca("RZ" + sufijo);
        unidad.setResponsable(acopiador);
        unidadId = unidadRepository.save(unidad).getId();
        admin = token("admin@planta.com", "Admin1234");
    }

    private record Escenario(UUID norte, UUID sur, UUID p, UUID q, List<UUID> entregasP, UUID entregaQ) {}

    /** P: Norte [hoy-7, T) y Sur desde T; entregas: 3 en Norte (60 + 100 + 100 L), 2 en Sur (100 L) y 1 de hace 21 dias sin zona (100 L). Q siempre en Norte: 1 entrega de 50 L. */
    private Escenario escenario() {
        UUID norte = zona("Zona Norte " + sufijo);
        UUID sur = zona("Zona Sur " + sufijo);
        UUID p = proveedorService.crear(new ProveedorRequest("Proveedor Cambiante " + sufijo, dni(1), null, sur)).id();
        UUID q = proveedorService.crear(new ProveedorRequest("Proveedor Fijo " + sufijo, dni(2), null, norte)).id();
        Timestamp inicioSur = jdbc.queryForObject("SELECT inicio_en FROM historial_zona WHERE proveedor_id = ? AND fin_en IS NULL", Timestamp.class, p);
        jdbc.update("INSERT INTO historial_zona (proveedor_id, zona_id, desde, hasta, inicio_en, fin_en) VALUES (?, ?, ?, ?, ?, ?)",
                p, norte, hoy.minusDays(7), hoy, Timestamp.valueOf(hoy.minusDays(7).atStartOfDay()), inicioSur);
        jdbc.update("UPDATE historial_zona SET inicio_en = ?, desde = ? WHERE proveedor_id = ? AND fin_en IS NULL",
                Timestamp.valueOf(hoy.minusDays(30).atStartOfDay()), hoy.minusDays(30), q);

        List<UUID> entregasP = new ArrayList<>();
        UUID entregaQ;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(acopiador.getEmail(), null));
        try {
            for (int i = 0; i < 3; i++) {
                entregasP.add(registrar(p, LocalDateTime.of(hoy.minusDays(5 - i), LocalTime.of(6, 0)), i == 0 ? "60.00" : "100.00"));
            }
            entregasP.add(registrar(p, LocalDateTime.now(), "100.00"));
            entregasP.add(registrar(p, LocalDateTime.now().plusSeconds(1), "100.00"));
            entregasP.add(registrar(p, LocalDateTime.of(hoy.minusDays(21), LocalTime.of(6, 0)), "100.00"));
            entregaQ = registrar(q, LocalDateTime.of(hoy.minusDays(4), LocalTime.of(7, 0)), "50.00");
        } finally {
            SecurityContextHolder.clearContext();
        }
        // la primera entrega de Norte (60 L) se mueve a hace un minuto: asi cae en el mismo mes que las de Sur y distingue el criterio en el promedio por mes
        jdbc.update("UPDATE registro_acopio SET fecha_hora = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), entregasP.get(0));
        return new Escenario(norte, sur, p, q, entregasP, entregaQ);
    }

    // ------------------------------------------------------------------ listados y totales

    @Test
    void losListadosPorZonaUsanLaZonaDeCadaEntregaNoLaActualDelProveedor() throws Exception {
        Escenario e = escenario();
        // Norte: las 3 de P de cuando estaba en Norte + la de Q
        assertThat(ids(lista("/api/registros-acopio?zonaId=" + e.norte()))).containsExactlyInAnyOrder(e.entregasP().get(0), e.entregasP().get(1), e.entregasP().get(2), e.entregaQ());
        // Sur: SOLO las 2 de P posteriores al cambio (antes habria devuelto las 6 de P, por su zona actual)
        assertThat(ids(lista("/api/registros-acopio?zonaId=" + e.sur()))).containsExactlyInAnyOrder(e.entregasP().get(3), e.entregasP().get(4));
        // la entrega sin zona determinada no entra en ninguna zona...
        UUID sinZona = e.entregasP().get(5);
        assertThat(ids(lista("/api/registros-acopio?zonaId=" + e.norte()))).doesNotContain(sinZona);
        assertThat(ids(lista("/api/registros-acopio?zonaId=" + e.sur()))).doesNotContain(sinZona);
        // ...pero sin filtro de zona se sigue viendo
        assertThat(ids(lista("/api/registros-acopio"))).contains(sinZona);

        // la variante paginada y su total (conteo) cuadran con la lista
        JsonNode pagina = json.readTree(mvc.perform(get("/api/registros-acopio/paginado?zonaId=" + e.sur() + "&size=10").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pagina.get("totalElementos").asInt()).isEqualTo(2);
        JsonNode paginaNorte = json.readTree(mvc.perform(get("/api/registros-acopio/paginado?zonaId=" + e.norte() + "&size=10").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(paginaNorte.get("totalElementos").asInt()).isEqualTo(4);
    }

    @Test
    void elResumenPorProveedorDeUnaZonaSumaSoloLoEntregadoEnEsaZona() throws Exception {
        Escenario e = escenario();
        JsonNode norte = json.readTree(mvc.perform(get("/api/registros-acopio/resumen-por-proveedor?zonaId=" + e.norte()).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(norte).hasSize(2);
        assertThat(totalDe(norte, e.p())).isEqualByComparingTo("260.00");
        assertThat(totalDe(norte, e.q())).isEqualByComparingTo("50.00");
        JsonNode sur = json.readTree(mvc.perform(get("/api/registros-acopio/resumen-por-proveedor?zonaId=" + e.sur()).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(sur).hasSize(1);
        assertThat(totalDe(sur, e.p())).isEqualByComparingTo("200.00");
    }

    // ------------------------------------------------------------------ alertas, calidad y bases del forecasting

    @Test
    void lasAlertasYLosLitrosEnAlertaSeAtribuyenALaZonaDeLaEntrega() throws Exception {
        Escenario e = escenario();
        UUID enNorte = e.entregasP().get(0);
        UUID enSur = e.entregasP().get(3);
        jdbc.update("INSERT INTO alerta_anomalia (registro_acopio_id, tipo, severidad) VALUES (?, 'RIESGO_ADULTERACION', 'ALTA')", enNorte);
        jdbc.update("INSERT INTO alerta_anomalia (registro_acopio_id, tipo, severidad) VALUES (?, 'VOLUMEN_ATIPICO', 'MEDIA')", enSur);

        Set<UUID> alertasNorte = registrosDeAlertas("/api/innovacion/alertas?zonaId=" + e.norte());
        Set<UUID> alertasSur = registrosDeAlertas("/api/innovacion/alertas?zonaId=" + e.sur());
        assertThat(alertasNorte).containsExactly(enNorte); // antes: la de Sur tambien se contaba bajo Sur y la de Norte bajo Sur
        assertThat(alertasSur).containsExactly(enSur);

        LocalDateTime desde = LocalDateTime.now().minusDays(30);
        assertThat(alertaRepository.sumarLitrosEnAlertaPorZonaDesde(e.norte(), desde)).isEqualByComparingTo("60.00");
        assertThat(alertaRepository.sumarLitrosEnAlertaPorZonaDesde(e.sur(), desde)).isEqualByComparingTo("100.00");
    }

    @Test
    void elRechazoPorSemanaYLasBasesDelForecastingSeCalculanConLaZonaDeLaEntrega() throws Exception {
        Escenario e = escenario();
        // un rechazado en una entrega de Norte y un aprobado en una de Sur
        analisis(e.entregasP().get(0), "RECHAZADO");
        analisis(e.entregasP().get(3), "APROBADO");
        List<Object[]> norte = analisisRepository.pctRechazoPorSemanaZona(e.norte());
        List<Object[]> sur = analisisRepository.pctRechazoPorSemanaZona(e.sur());
        assertThat(norte).hasSize(1);
        assertThat(((Number) norte.get(0)[1]).doubleValue()).isEqualTo(100.0);
        assertThat(sur).hasSize(1);
        assertThat(((Number) sur.get(0)[1]).doubleValue()).isEqualTo(0.0);

        // forecasting: litros de la zona en un rango, promedio historico y suma semanal
        LocalDateTime inicio = LocalDateTime.now().minusDays(30);
        LocalDateTime fin = LocalDateTime.now().plusDays(1);
        assertThat(registroRepository.findLitrosPorZonaYRango(e.norte(), inicio, fin)).hasSize(4).allMatch(l -> l.signum() > 0);
        assertThat(registroRepository.findLitrosPorZonaYRango(e.sur(), inicio, fin)).hasSize(2);
        assertThat(registroRepository.promedioHistoricoGeneralPorZona(e.sur())).isEqualByComparingTo("100.00");
        assertThat(registroRepository.promedioHistoricoGeneralPorZona(e.norte())).isEqualByComparingTo("77.50"); // (60 + 100 + 100 + 50) / 4
        assertThat(registroRepository.sumLitrosPorZonaYSemana(e.sur(), inicio, fin)).isEqualByComparingTo("200.00");
        assertThat(registroRepository.sumLitrosPorZonaYSemana(e.norte(), inicio, fin)).isEqualByComparingTo("310.00");
        int mes = LocalDate.now().getMonthValue();
        // Sur en este mes: solo sus 2 entregas de 100 L (con la zona actual de P habria entrado tambien la de 60 L de Norte y el promedio seria menor)
        assertThat(registroRepository.promedioLitrosPorZonaYMes(e.sur(), mes)).isEqualByComparingTo("100.00");
    }

    @Test
    void laSumaPorZonaMasLasEntregasSinZonaEsElTotalSinFiltroYNoSeRellenaNada() throws Exception {
        Escenario e = escenario();
        LocalDateTime inicio = LocalDateTime.now().minusDays(60);
        LocalDateTime fin = LocalDateTime.now().plusDays(1);
        BigDecimal norte = registroRepository.sumLitrosPorZonaYSemana(e.norte(), inicio, fin);
        BigDecimal sur = registroRepository.sumLitrosPorZonaYSemana(e.sur(), inicio, fin);
        BigDecimal sinZona = jdbc.queryForObject("SELECT litros FROM registro_acopio WHERE id = ? AND zona_id IS NULL", BigDecimal.class, e.entregasP().get(5));
        BigDecimal total = jdbc.queryForObject("SELECT SUM(litros) FROM registro_acopio WHERE proveedor_id IN (?, ?)", BigDecimal.class, e.p(), e.q());
        assertThat(norte.add(sur).add(sinZona)).isEqualByComparingTo(total); // 310 + 200 + 100 = 610: nada se pierde ni se cuenta dos veces
        assertThat(jdbc.queryForObject("SELECT zona_id FROM registro_acopio WHERE id = ?", UUID.class, e.entregasP().get(5))).isNull(); // la sin zona sigue sin zona
    }

    // ------------------------------------------------------------------ ayudas

    private BigDecimal totalDe(JsonNode lista, UUID proveedorId) {
        for (JsonNode f : lista) {
            if (f.get("proveedorId").asText().equals(proveedorId.toString())) return f.get("totalLitros").decimalValue();
        }
        throw new AssertionError("No esta el proveedor " + proveedorId);
    }

    private JsonNode lista(String url) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Ids de los registros de la lista que pertenecen a ESTA prueba (la base de pruebas se comparte con otras). */
    private Set<UUID> ids(JsonNode lista) {
        Set<UUID> ids = new HashSet<>();
        for (JsonNode f : lista) ids.add(UUID.fromString(f.get("id").asText()));
        ids.removeIf(id -> !suyo(id));
        return ids;
    }

    private boolean suyo(UUID registroId) {
        return jdbc.queryForObject("SELECT p.nombre LIKE ? FROM registro_acopio r JOIN proveedor p ON p.id = r.proveedor_id WHERE r.id = ?", Boolean.class, "%" + sufijo, registroId);
    }

    private Set<UUID> registrosDeAlertas(String url) throws Exception {
        Set<UUID> ids = new HashSet<>();
        for (JsonNode f : lista(url)) {
            UUID id = UUID.fromString(f.get("registroAcopioId").asText());
            if (suyo(id)) ids.add(id);
        }
        return ids;
    }

    private UUID registrar(UUID proveedorId, LocalDateTime fechaHora, String litros) {
        return registroAcopioService.registrarOIgnorarSiDuplicado(new RegistroAcopioDTO(UUID.randomUUID().toString(), proveedorId, unidadId,
                fechaHora, new BigDecimal(litros), null, null, null, false, null, null)).id();
    }

    private void analisis(UUID registroId, String resultado) {
        jdbc.update("INSERT INTO analisis_calidad (id, uuid_cliente, registro_acopio_id, folio_muestra, resultado) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID().toString(), registroId, "E-" + UUID.randomUUID().toString().substring(0, 6), resultado);
    }

    private UUID zona(String nombre) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zona (id, nombre) VALUES (?, ?)", id, nombre);
        return id;
    }

    private String dni(int n) {
        return String.format("%d%07d", 3 + n, (prueba * 17 + n * 1013 + (int) (System.nanoTime() % 100000)) % 10_000_000);
    }

    private String token(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(login.getResponse().getContentAsString()).get("token").asText();
    }

    private Usuario usuario(String rolNombre) {
        Rol rol = rolRepository.findByNombre(rolNombre).orElseThrow();
        Usuario u = new Usuario();
        u.setNombre("Usuario " + rolNombre + " " + sufijo);
        u.setEmail(rolNombre.toLowerCase() + sufijo + "-" + UUID.randomUUID().toString().substring(0, 6) + "@planta.com");
        u.setPasswordHash(passwordEncoder.encode("Clave1234"));
        u.setRol(rol);
        u.setActivo(true);
        return usuarioRepository.save(u);
    }
}
