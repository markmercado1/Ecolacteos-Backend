package com.planta.acopio.integration;

import com.planta.acopio.dto.ProveedorPublicoResponse;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.excel.ArchivoExcel;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.importacion.EstadoImportacion;
import com.planta.acopio.importacion.ExcelDePrueba;
import com.planta.acopio.importacion.ImportacionExcel;
import com.planta.acopio.importacion.ImportacionExcelRepository;
import com.planta.acopio.importacion.ImportacionService;
import com.planta.acopio.importacion.PlantillaExcel;
import com.planta.acopio.importacion.RegistroImportaciones;
import com.planta.acopio.importacion.ResultadoImportacionResponse;
import com.planta.acopio.importacion.VistaPreviaImportacionResponse;
import com.planta.acopio.repository.HistorialZonaRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.service.ProveedorService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * Importacion de proveedores contra PostgreSQL real, de punta a punta: plantilla -> archivo -> vista previa -> confirmacion.
 * Sin @Transactional a proposito: cada paso confirma su propia transaccion, como en produccion (la atomicidad y la
 * concurrencia no se pueden probar dentro de una transaccion de prueba).
 */
@SpringBootTest
class ImportacionProveedoresIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(1000);

    @Autowired private ImportacionService importacionService;
    @Autowired private PlantillaExcel plantilla;
    @Autowired private RegistroImportaciones registro;
    @Autowired private ImportacionExcelRepository importacionRepository;
    @Autowired private ProveedorRepository proveedorRepository;
    @Autowired private HistorialZonaRepository historialRepository;
    @Autowired private ZonaRepository zonaRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private RolRepository rolRepository;
    @SpyBean private ProveedorService proveedorService;

    private int prueba;
    private String sufijo;
    private Zona zona;
    private byte[] plantillaBytes;

    @BeforeEach
    void preparar() {
        prueba = PRUEBAS.incrementAndGet();
        sufijo = String.valueOf(prueba);
        Mockito.reset(proveedorService);
        autenticar("admin@planta.com");
        zona = new Zona();
        zona.setNombre("Zona Import " + sufijo);
        zona = zonaRepository.save(zona);
        ArchivoExcel archivo = importacionService.plantilla("proveedores");
        plantillaBytes = ExcelDePrueba.bytes(archivo);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------ ayudas

    private static void autenticar(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private String dni(int i) {
        return String.format("0%07d", prueba * 100 + i);
    }

    private List<Object> fila(int i, String zonaNombre) {
        return List.of("Proveedor Import " + i + " " + sufijo, dni(i), "9876543" + String.format("%02d", i), zonaNombre == null ? "" : zonaNombre);
    }

    // Fase G (plantilla v2): las filas de estas pruebas siguen escritas como (nombre, dni, telefono, zona) de persona natural; se
    // ubican en las columnas de la plantilla actual (tipo, nombre, dni, ruc, razon social, telefono, zona) con tipo, RUC y razon vacios.
    private static List<List<Object>> v2(List<List<Object>> filas) {
        return filas.stream().map(f -> List.<Object>of("", f.get(0), f.get(1), "", "", f.get(2), f.get(3))).toList();
    }

    private MockMultipartFile archivo(List<List<Object>> filas) {
        return new MockMultipartFile("archivo", "proveedores.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ExcelDePrueba.conFilas(plantillaBytes, v2(filas)));
    }

    private VistaPreviaImportacionResponse previsualizar(List<List<Object>> filas) {
        return importacionService.previsualizar("proveedores", archivo(filas));
    }

    private Usuario crearAdmin(String email) {
        Rol rol = rolRepository.findByNombre("ADMIN").orElseThrow();
        Usuario u = new Usuario();
        u.setNombre("Admin " + email);
        u.setEmail(email);
        u.setPasswordHash("hash");
        u.setRol(rol);
        return usuarioRepository.save(u);
    }

    private long proveedoresConPrefijo() {
        return proveedorRepository.findAll().stream().filter(p -> p.getNombre().endsWith(sufijo)).count();
    }

    private Proveedor proveedorPorDni(int i) {
        return proveedorRepository.findByDni(dni(i)).orElseThrow();
    }

    // ----------------------------------------------------------------------------- vista previa sin escrituras

    @Test
    void laVistaPreviaNoCreaNingunDatoDeNegocio() {
        long proveedores = proveedorRepository.count();
        long historial = historialRepository.count();
        long importaciones = importacionRepository.count();

        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, zona.getNombre()), fila(2, null), fila(3, zona.getNombre())));

        assertThat(vista.validas()).isEqualTo(3);
        assertThat(vista.puedeConfirmar()).isTrue();
        assertThat(proveedorRepository.count()).isEqualTo(proveedores);
        assertThat(historialRepository.count()).isEqualTo(historial);
        // Lo unico que se guarda es la propia vista previa (la bitacora de la importacion).
        assertThat(importacionRepository.count()).isEqualTo(importaciones + 1);
        ImportacionExcel guardada = importacionRepository.findById(vista.id()).orElseThrow();
        assertThat(guardada.getEstado()).isEqualTo(EstadoImportacion.PREVISUALIZADA);
        assertThat(guardada.getCreadas()).isZero();
        assertThat(guardada.getHashArchivo()).hasSize(64);
        assertThat(guardada.getUsuario().getEmail()).isEqualTo("admin@planta.com");
        assertThat(guardada.getExpiraEn()).isAfter(LocalDateTime.now());
    }

    @Test
    void laVistaPreviaDetallaValidasInvalidasDuplicadasYOmitidas() {
        proveedorRepository.save(proveedorExistente(1, "Existente " + sufijo, true));
        List<List<Object>> filas = new ArrayList<>();
        filas.add(fila(2, zona.getNombre()));                                  // fila 2: valida
        filas.add(List.of("Sin DNI " + sufijo, "123", "", ""));                // fila 3: invalida
        filas.add(fila(1, null));                                              // fila 4: duplicada (ya existe)
        filas.add(List.of("", "", "", ""));                                    // fila 5: en blanco
        filas.add(fila(2, null));                                              // fila 6: repetida en el archivo
        filas.add(fila(3, "Zona Que No Existe"));                              // fila 7: invalida (zona)

        VistaPreviaImportacionResponse vista = previsualizar(filas);

        assertThat(vista.totalFilas()).isEqualTo(5);
        assertThat(vista.validas()).isEqualTo(1);
        assertThat(vista.invalidas()).isEqualTo(2);
        assertThat(vista.duplicadas()).isEqualTo(2);
        assertThat(vista.omitidas()).isEqualTo(1);
        assertThat(vista.filas()).extracting(f -> f.fila() + ":" + f.estado())
                .containsExactly("2:VALIDA", "3:INVALIDA", "4:DUPLICADA", "6:DUPLICADA", "7:INVALIDA");
        assertThat(vista.filas().get(3).mensajes().get(0)).contains("Repetida en el archivo").contains("fila 2");
        assertThat(vista.filas().get(2).mensajes().get(0)).contains("Ya existe un proveedor");
        assertThat(vista.columnas()).extracting(c -> c.clave()).containsExactly("tipo", "nombre", "dni", "ruc", "razonSocial", "telefono", "zona");
    }

    // -------------------------------------------------------------------------------- confirmar y crear

    @Test
    void confirmarCreaProveedoresConQrHistorialDeZonaTelefonoNormalizadoYDniConCeros() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                fila(1, zona.getNombre()),
                fila(2, null),
                java.util.Arrays.<Object>asList("Numero Excel " + sufijo, Double.valueOf(prueba * 100 + 3), 987654300.0, zona.getNombre())));

        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(3);
        assertThat(resultado.yaConfirmada()).isFalse();
        assertThat(proveedoresConPrefijo()).isEqualTo(3);

        Proveedor conZona = proveedorPorDni(1);
        assertThat(conZona.getCodigoQr()).isNotBlank();                       // QR generado por ProveedorService.crear
        assertThat(conZona.getTelefono()).isEqualTo("+51987654301");           // normalizado
        assertThat(conZona.getZonaActual().getId()).isEqualTo(zona.getId());
        assertThat(conZona.getActivo()).isTrue();
        assertThat(historialRepository.findByProveedorIdAndFinEnIsNull(conZona.getId())).isPresent()
                .get().extracting(h -> h.getZona().getId()).isEqualTo(zona.getId());

        Proveedor sinZona = proveedorPorDni(2);
        assertThat(sinZona.getZonaActual()).isNull();
        assertThat(historialRepository.findByProveedorIdOrderByDesdeDesc(sinZona.getId())).isEmpty();

        // El DNI que Excel guardo como numero (sin su cero inicial) queda completo.
        assertThat(proveedorRepository.findByDni(dni(3))).isPresent();
        assertThat(proveedorPorDni(3).getTelefono()).isEqualTo("+51987654300");

        // QR distintos entre si.
        assertThat(List.of(conZona.getCodigoQr(), sinZona.getCodigoQr(), proveedorPorDni(3).getCodigoQr())).doesNotHaveDuplicates();
        assertThat(importacionRepository.findById(vista.id()).orElseThrow().getEstado()).isEqualTo(EstadoImportacion.CONFIRMADA);
    }

    @Test
    void losProveedoresImportadosApareceEnLaSincronizacionMovil() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, zona.getNombre()), fila(2, null)));
        importacionService.confirmar(vista.id());

        // SyncService.cambios() entrega ProveedorService.listarPublico() a la app movil.
        List<ProveedorPublicoResponse> publico = proveedorService.listarPublico();
        List<UUID> ids = publico.stream().map(ProveedorPublicoResponse::id).toList();
        assertThat(ids).contains(proveedorPorDni(1).getId(), proveedorPorDni(2).getId());
        // Sin DNI ni telefono (RNF-12): el DTO movil no los trae.
        assertThat(ProveedorPublicoResponse.class.getRecordComponents()).extracting(c -> c.getName()).doesNotContain("dni", "telefono");
    }

    @Test
    void unDniQueYaExisteSeOmiteYElProveedorExistenteNoSeModifica() {
        Proveedor existente = proveedorRepository.save(proveedorExistente(1, "Nombre Original " + sufijo, false)); // inactivo
        existente.setTelefono("+51911111111");
        proveedorRepository.save(existente);

        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                List.of("Nombre Distinto " + sufijo, dni(1), "987654321", zona.getNombre()),
                fila(2, null)));
        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(1);
        assertThat(resultado.duplicadas()).isEqualTo(1);
        Proveedor despues = proveedorPorDni(1);
        assertThat(despues.getNombre()).isEqualTo("Nombre Original " + sufijo);
        assertThat(despues.getTelefono()).isEqualTo("+51911111111");
        assertThat(despues.getActivo()).isFalse();
        assertThat(despues.getZonaActual()).isNull();
        assertThat(historialRepository.findByProveedorIdOrderByDesdeDesc(despues.getId())).isEmpty();
        assertThat(resultado.filasNoCreadas()).hasSize(1);
        assertThat(resultado.filasNoCreadas().get(0).estado()).isEqualTo("DUPLICADA");
    }

    @Test
    void unDniRepetidoEnElArchivoSoloCreaLaPrimeraFila() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                List.of("Primero " + sufijo, dni(1), "", ""),
                List.of("Segundo " + sufijo, dni(1), "", "")));
        importacionService.confirmar(vista.id());

        assertThat(proveedoresConPrefijo()).isEqualTo(1);
        assertThat(proveedorPorDni(1).getNombre()).isEqualTo("Primero " + sufijo);
    }

    @Test
    void lasFilasInvalidasSeOmitenYElReporteIndicaNumeroDeFilaYMotivo() throws Exception {
        List<List<Object>> filas = new ArrayList<>();
        filas.add(fila(1, null));                                                     // fila 2: valida
        filas.add(List.of("DNI corto " + sufijo, "12345", "", ""));                   // fila 3: invalida
        filas.add(List.of("=HYPERLINK(\"http://malo.example\")", "abc", "", ""));     // fila 4: invalida, nombre con formula escrita como texto
        filas.add(List.of("Telefono malo " + sufijo, dni(5), "123", ""));             // fila 5: invalida
        filas.add(List.of(new ExcelDePrueba.Formula("\"x\"&\"y\""), dni(6), "", ""));  // fila 6: celda con formula real
        filas.add(fila(7, null));                                                     // fila 7: valida

        VistaPreviaImportacionResponse vista = previsualizar(filas);
        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(2);
        assertThat(resultado.invalidas()).isEqualTo(4);
        assertThat(proveedoresConPrefijo()).isEqualTo(2);
        assertThat(proveedorRepository.findByDni(dni(5))).isEmpty();

        ArchivoExcel reporte = importacionService.reporte(vista.id());
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(ExcelDePrueba.bytes(reporte)))) {
            Sheet hoja = libro.getSheetAt(0);
            Row encabezado = hoja.getRow(0);
            assertThat(encabezado.getCell(0).getStringCellValue()).isEqualTo("Fila");
            assertThat(encabezado.getCell(1).getStringCellValue()).isEqualTo("Resultado");
            assertThat(encabezado.getCell(2).getStringCellValue()).isEqualTo("Motivo");
            assertThat(encabezado.getCell(3).getStringCellValue()).isEqualTo("Tipo de persona");
            assertThat(encabezado.getCell(5).getStringCellValue()).isEqualTo("DNI");
            assertThat(encabezado.getCell(6).getStringCellValue()).isEqualTo("RUC");
            assertThat(hoja.getLastRowNum()).isEqualTo(4);
            assertThat(hoja.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(3.0);
            assertThat(hoja.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Inválida");
            assertThat(hoja.getRow(1).getCell(2).getStringCellValue()).contains("DNI").contains("8 dígitos");
            assertThat(hoja.getRow(1).getCell(5).getStringCellValue()).isEqualTo("12345");
            assertThat(hoja.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(4.0);
            // El reporte devuelve texto del archivo: lo que parezca formula se neutraliza para que no se ejecute al abrirlo.
            assertThat(hoja.getRow(2).getCell(4).getStringCellValue()).startsWith("'=HYPERLINK");
            assertThat(hoja.getRow(3).getCell(2).getStringCellValue()).contains("Teléfono").contains("celular peruano");
            assertThat(hoja.getRow(4).getCell(0).getNumericCellValue()).isEqualTo(6.0);
            assertThat(hoja.getRow(4).getCell(2).getStringCellValue()).contains("contiene una fórmula");
            assertThat(hoja.getRow(4).getCell(4).getStringCellValue()).isEqualTo("(fórmula)");
        }
    }

    @Test
    void elReporteDeUnaVistaPreviaSinConfirmarTambienSePuedeBajar() throws Exception {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, null), List.of("Malo " + sufijo, "1", "", "")));

        ArchivoExcel reporte = importacionService.reporte(vista.id());

        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(ExcelDePrueba.bytes(reporte)))) {
            assertThat(libro.getSheetAt(0).getLastRowNum()).isEqualTo(1);
        }
        assertThat(proveedoresConPrefijo()).isZero(); // bajar el reporte tampoco crea nada
    }

    // ----------------------------------------------------------------- reintentos, concurrencia y duenos

    @Test
    void confirmarDosVecesNoDuplicaYDevuelveElResultadoGuardado() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, null), fila(2, null)));

        ResultadoImportacionResponse primera = importacionService.confirmar(vista.id());
        ResultadoImportacionResponse repetida = importacionService.confirmar(vista.id());

        assertThat(primera.yaConfirmada()).isFalse();
        assertThat(repetida.yaConfirmada()).isTrue();
        assertThat(repetida.creadas()).isEqualTo(2);
        assertThat(proveedoresConPrefijo()).isEqualTo(2);
        Mockito.verify(proveedorService, Mockito.times(2)).crear(any());
    }

    @Test
    void confirmacionesSimultaneasDeLaMismaImportacionCreanCadaProveedorUnaSolaVez() throws Exception {
        List<List<Object>> filas = new ArrayList<>();
        for (int i = 1; i <= 20; i++) filas.add(fila(i, null));
        VistaPreviaImportacionResponse vista = previsualizar(filas);

        ExecutorService hilos = Executors.newFixedThreadPool(6);
        try {
            List<Callable<ResultadoImportacionResponse>> tareas = new ArrayList<>();
            for (int h = 0; h < 6; h++) {
                tareas.add(() -> {
                    autenticar("admin@planta.com");
                    try {
                        return importacionService.confirmar(vista.id());
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }
            List<ResultadoImportacionResponse> resultados = new ArrayList<>();
            for (Future<ResultadoImportacionResponse> f : hilos.invokeAll(tareas, 60, TimeUnit.SECONDS)) {
                resultados.add(f.get());
            }
            assertThat(resultados).hasSize(6).allSatisfy(r -> assertThat(r.creadas()).isEqualTo(20));
            assertThat(resultados.stream().filter(r -> !r.yaConfirmada())).hasSize(1); // una sola ejecucion real
        } finally {
            hilos.shutdownNow();
        }
        assertThat(proveedoresConPrefijo()).isEqualTo(20);
        Mockito.verify(proveedorService, Mockito.times(20)).crear(any());
    }

    @Test
    void dosAdministradoresConfirmandoElMismoArchivoALaVezNuncaDuplicanUnDni() throws Exception {
        crearAdmin("otro-admin-" + sufijo + "@planta.com");
        List<List<Object>> filas = new ArrayList<>();
        for (int i = 1; i <= 15; i++) filas.add(fila(i, null));
        VistaPreviaImportacionResponse deUno = previsualizar(filas);
        autenticar("otro-admin-" + sufijo + "@planta.com");
        VistaPreviaImportacionResponse deOtro = previsualizar(filas);

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> unoConfirma = () -> {
                autenticar("admin@planta.com");
                return intentarConfirmar(deUno.id());
            };
            Callable<Object> otroConfirma = () -> {
                autenticar("otro-admin-" + sufijo + "@planta.com");
                return intentarConfirmar(deOtro.id());
            };
            for (Future<Object> f : hilos.invokeAll(List.of(unoConfirma, otroConfirma), 60, TimeUnit.SECONDS)) {
                f.get();
            }
        } finally {
            hilos.shutdownNow();
        }

        // Pase lo que pase con cada confirmacion, no hay un solo DNI duplicado ni queda un registro a medias.
        assertThat(proveedoresConPrefijo()).isEqualTo(15);
        long dnisDistintos = proveedorRepository.findAll().stream().filter(p -> p.getNombre().endsWith(sufijo)).map(Proveedor::getDni).distinct().count();
        assertThat(dnisDistintos).isEqualTo(15);
    }

    /** Una confirmacion que pierde la carrera falla con un mensaje de negocio (409) y revierte TODO; nunca con un error interno. */
    private Object intentarConfirmar(UUID id) {
        try {
            return importacionService.confirmar(id);
        } catch (ConflictoNegocioException esperado) {
            assertThat(esperado.getMessage()).contains("No se creó ningún registro");
            return esperado;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void soloQuienSubioElArchivoPuedeConfirmarloDescartarloOBajarSuReporte() {
        crearAdmin("intruso-" + sufijo + "@planta.com");
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, null)));

        autenticar("intruso-" + sufijo + "@planta.com");
        assertThatThrownBy(() -> importacionService.confirmar(vista.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> importacionService.descartar(vista.id())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> importacionService.reporte(vista.id())).isInstanceOf(AccessDeniedException.class);

        autenticar("admin@planta.com");
        assertThat(proveedoresConPrefijo()).isZero();
        assertThat(importacionService.confirmar(vista.id()).creadas()).isEqualTo(1);
    }

    @Test
    void unaVistaPreviaVencidaNoSeConfirma() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, null)));
        ImportacionExcel guardada = importacionRepository.findById(vista.id()).orElseThrow();
        guardada.setExpiraEn(LocalDateTime.now().minusMinutes(1));
        importacionRepository.save(guardada);

        assertThatThrownBy(() -> importacionService.confirmar(vista.id()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("venció");
        assertThat(proveedoresConPrefijo()).isZero();
    }

    @Test
    void cancelarDescartaLaVistaPreviaYYaNoSePuedeConfirmar_peroUnaConfirmadaNoSeCancela() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, null)));
        importacionService.descartar(vista.id());

        assertThat(importacionRepository.findById(vista.id()).orElseThrow().getEstado()).isEqualTo(EstadoImportacion.DESCARTADA);
        assertThatThrownBy(() -> importacionService.confirmar(vista.id()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("cancelada");

        VistaPreviaImportacionResponse otra = previsualizar(List.of(fila(2, null)));
        importacionService.confirmar(otra.id());
        assertThatThrownBy(() -> importacionService.descartar(otra.id()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("ya fue confirmada");
        assertThat(proveedoresConPrefijo()).isEqualTo(1);
    }

    @Test
    void unaImportacionInexistenteDaNoEncontrado() {
        assertThatThrownBy(() -> importacionService.confirmar(UUID.randomUUID()))
                .isInstanceOf(com.planta.acopio.exception.RecursoNoEncontradoException.class);
    }

    // ----------------------------------------------------------------------- cambios entre vista previa y confirmar

    @Test
    void unDniRegistradoDespuesDeLaVistaPreviaSeOmiteAlConfirmarSinFallarLoDemas() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, null), fila(2, null), fila(3, null)));
        proveedorRepository.save(proveedorExistente(2, "Llego Antes " + sufijo, true));

        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(2);
        assertThat(resultado.omitidas()).isEqualTo(1);
        assertThat(resultado.filasNoCreadas()).hasSize(1);
        assertThat(resultado.filasNoCreadas().get(0).estado()).isEqualTo("OMITIDA");
        assertThat(resultado.filasNoCreadas().get(0).mensajes().get(0)).contains("después de la vista previa");
        assertThat(proveedorPorDni(2).getNombre()).isEqualTo("Llego Antes " + sufijo);
    }

    @Test
    void unaZonaDesactivadaDespuesDeLaVistaPreviaOmiteLaFilaEnVezDeCrearConZonaInactiva() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, zona.getNombre()), fila(2, null)));
        zona.setActiva(false);
        zonaRepository.save(zona);

        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(1);
        assertThat(resultado.omitidas()).isEqualTo(1);
        assertThat(resultado.filasNoCreadas().get(0).mensajes().get(0)).contains("ya no está activa");
        assertThat(proveedorRepository.findByDni(dni(1))).isEmpty();
    }

    // --------------------------------------------------------------------------- atomicidad ante fallos

    @Test
    void unErrorInesperadoAMitadRevierteTodoYNoDejaProveedoresParcialesNiExponeDetalles() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, zona.getNombre()), fila(2, null), fila(3, null), fila(4, null)));
        doCallRealMethod().doCallRealMethod()
                .doThrow(new IllegalStateException("fallo interno secreto: jdbc:postgresql://servidor/clave"))
                .when(proveedorService).crear(any());

        assertThatThrownBy(() -> importacionService.confirmar(vista.id()))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessageContaining("No se creó ningún registro")
                .hasMessageContaining("fila 4")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secreto").doesNotContain("jdbc"));

        // Las dos primeras filas ya se habian "creado" dentro de la transaccion: deben haberse revertido.
        assertThat(proveedoresConPrefijo()).isZero();
        assertThat(proveedorRepository.findByDni(dni(1))).isEmpty();
        assertThat(historialRepository.findAll().stream().filter(h -> h.getZona().getId().equals(zona.getId()))).isEmpty();
        ImportacionExcel guardada = importacionRepository.findById(vista.id()).orElseThrow();
        assertThat(guardada.getEstado()).isEqualTo(EstadoImportacion.PREVISUALIZADA);
        assertThat(guardada.getCreadas()).isZero();

        // Y se puede reintentar la MISMA vista previa cuando el problema desaparece.
        Mockito.reset(proveedorService);
        assertThat(importacionService.confirmar(vista.id()).creadas()).isEqualTo(4);
        assertThat(proveedoresConPrefijo()).isEqualTo(4);
    }

    @Test
    void unaViolacionDeUnicidadAMitadRevierteTodoConUnMensajeDeNegocio() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, null), fila(2, null), fila(3, null)));
        doCallRealMethod()
                .doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"proveedor_dni_key\""))
                .when(proveedorService).crear(any());

        assertThatThrownBy(() -> importacionService.confirmar(vista.id()))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessageContaining("No se creó ningún registro").hasMessageContaining("fila 3")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("proveedor_dni_key").doesNotContain("duplicate key"));

        assertThat(proveedoresConPrefijo()).isZero();
        assertThat(importacionRepository.findById(vista.id()).orElseThrow().getEstado()).isEqualTo(EstadoImportacion.PREVISUALIZADA);
    }

    @Test
    void unaReglaDeNegocioQueFallaAlCrearTambienRevierteTodo() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(fila(1, null), fila(2, null)));
        doCallRealMethod().doThrow(new ValidacionNegocioException("regla de negocio violada")).when(proveedorService).crear(any());

        assertThatThrownBy(() -> importacionService.confirmar(vista.id()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("fila 3").hasMessageContaining("regla de negocio violada");
        assertThat(proveedoresConPrefijo()).isZero();
    }

    // -------------------------------------------------------------------------- archivos hostiles o ajenos

    @Test
    void ningunArchivoInvalidoDejaRastroNiModificaDatos() {
        long proveedores = proveedorRepository.count();
        long importaciones = importacionRepository.count();
        byte[] valido = ExcelDePrueba.conFilas(plantillaBytes, v2(List.of(fila(1, null))));

        List<MockMultipartFile> hostiles = List.of(
                new MockMultipartFile("archivo", "datos.xlsm", "application/octet-stream", valido),
                new MockMultipartFile("archivo", "datos.csv", "text/csv", "Nombre;DNI\nRosa;12345678".getBytes()),
                new MockMultipartFile("archivo", "texto.xlsx", "application/octet-stream", "esto no es un zip".getBytes()),
                new MockMultipartFile("archivo", "bomba.xlsx", "application/octet-stream", ExcelDePrueba.zipBomba(30L * 1024 * 1024)),
                new MockMultipartFile("archivo", "macro.xlsx", "application/octet-stream", ExcelDePrueba.conEntradaExtra(valido, "xl/vbaProject.bin", new byte[]{1})),
                new MockMultipartFile("archivo", "vacio.xlsx", "application/octet-stream", new byte[0]),
                new MockMultipartFile("archivo", "sin-marca.xlsx", "application/octet-stream",
                        ExcelDePrueba.modificar(valido, l -> l.removeSheetAt(l.getSheetIndex("_plantilla")))),
                new MockMultipartFile("archivo", "version-vieja.xlsx", "application/octet-stream",
                        ExcelDePrueba.modificar(valido, l -> l.getSheet("_plantilla").getRow(2).getCell(0).setCellValue(0))),
                new MockMultipartFile("archivo", "encabezado-roto.xlsx", "application/octet-stream",
                        ExcelDePrueba.modificar(valido, l -> l.getSheet("Datos").getRow(0).getCell(1).setCellValue("Documento"))),
                new MockMultipartFile("archivo", "de-insumos.xlsx", "application/octet-stream",
                        ExcelDePrueba.bytes(importacionService.plantilla("insumos"))));

        for (MockMultipartFile hostil : hostiles) {
            assertThatThrownBy(() -> importacionService.previsualizar("proveedores", hostil))
                    .as(hostil.getOriginalFilename()).isInstanceOf(ValidacionNegocioException.class);
        }
        assertThat(proveedorRepository.count()).isEqualTo(proveedores);
        assertThat(importacionRepository.count()).isEqualTo(importaciones);
    }

    @Test
    void masFilasQueElMaximoSeRechazaCompleto() {
        List<List<Object>> demasiadas = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            demasiadas.add(List.of("P" + i, String.format("%08d", i), "", ""));
        }
        long importaciones = importacionRepository.count();

        assertThatThrownBy(() -> previsualizar(demasiadas))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("más de 2000 filas");

        assertThat(importacionRepository.count()).isEqualTo(importaciones);
    }

    @Test
    void ningunaFilaValidaNoSePuedeConfirmarPeroElReporteExplicaPorQue() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(List.of("Malo " + sufijo, "1", "", "")));

        assertThat(vista.validas()).isZero();
        assertThat(vista.puedeConfirmar()).isFalse();
        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());
        assertThat(resultado.creadas()).isZero();
        assertThat(resultado.invalidas()).isEqualTo(1);
    }

    @Test
    void lasImportacionesTipoDesconocidoDanNoEncontrado() {
        assertThatThrownBy(() -> importacionService.plantilla("ventas"))
                .isInstanceOf(com.planta.acopio.exception.RecursoNoEncontradoException.class).hasMessageContaining("proveedores");
        assertThat(registro.obtener("PROVEEDORES").codigo()).isEqualTo("proveedores");
    }

    @Test
    void unaImportacionGrandeSeProcesaCompleta() {
        List<List<Object>> filas = new ArrayList<>();
        for (int i = 1; i <= 99; i++) filas.add(fila(i, i % 2 == 0 ? zona.getNombre() : null));
        VistaPreviaImportacionResponse vista = previsualizar(filas);

        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(99);
        assertThat(proveedoresConPrefijo()).isEqualTo(99);
        assertThat(historialRepository.findAll().stream().filter(h -> h.getZona().getId().equals(zona.getId())).count()).isEqualTo(49);
    }

    // ---------------------------------------------------------------------------------------------- datos

    private Proveedor proveedorExistente(int i, String nombre, boolean activo) {
        Proveedor p = new Proveedor();
        p.setNombre(nombre);
        p.setDni(dni(i));
        p.setActivo(activo);
        p.setCodigoQr(UUID.randomUUID().toString());
        return p;
    }
}
