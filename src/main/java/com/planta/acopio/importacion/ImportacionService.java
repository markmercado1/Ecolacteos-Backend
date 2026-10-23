package com.planta.acopio.importacion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.excel.ArchivoExcel;
import com.planta.acopio.excel.ColumnaExcel;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.LectorExcelSeguro;
import com.planta.acopio.excel.LimitesExcel;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Importacion de datos maestros desde Excel, en dos pasos.
 *
 * 1. {@link #previsualizar}: abre el archivo con defensas ({@link LectorExcelSeguro}), comprueba la plantilla, valida cada fila y
 *    guarda el resultado en importacion_excel. NO crea ningun dato de negocio: un archivo invalido o malicioso no modifica nada.
 * 2. {@link #confirmar}: crea EXACTAMENTE las filas validas que se vieron, llamando a los servicios de negocio de cada tipo, en UNA
 *    transaccion. Si algo falla de forma inesperada no queda ningun registro parcial (todo o nada); las filas invalidas o
 *    duplicadas ya se habian omitido y quedan en el reporte.
 *
 * Reintentos y concurrencia: la importacion se bloquea (FOR UPDATE) al confirmar. Una segunda confirmacion simultanea o repetida
 * espera, ve CONFIRMADA y devuelve el resultado guardado sin crear nada otra vez. Solo quien subio el archivo puede confirmarlo,
 * descartarlo o bajar su reporte.
 */
@Service
public class ImportacionService {

    private static final Logger log = LoggerFactory.getLogger(ImportacionService.class);
    private static final TypeReference<List<FilaImportacion>> TIPO_FILAS = new TypeReference<>() {
    };

    private final RegistroImportaciones registro;
    private final LectorExcelSeguro lector;
    private final LectorPlantilla lectorPlantilla;
    private final PlantillaExcel plantilla;
    private final ExportacionExcelService exportador;
    private final ImportacionExcelRepository repository;
    private final UsuarioRepository usuarioRepository;
    private final LimitesExcel limites;
    private final EntityManager entityManager;
    private final ObjectMapper json;

    public ImportacionService(RegistroImportaciones registro, LectorExcelSeguro lector, LectorPlantilla lectorPlantilla,
                              PlantillaExcel plantilla, ExportacionExcelService exportador, ImportacionExcelRepository repository,
                              UsuarioRepository usuarioRepository, LimitesExcel limites, EntityManager entityManager, ObjectMapper json) {
        this.registro = registro;
        this.lector = lector;
        this.lectorPlantilla = lectorPlantilla;
        this.plantilla = plantilla;
        this.exportador = exportador;
        this.repository = repository;
        this.usuarioRepository = usuarioRepository;
        this.limites = limites;
        this.entityManager = entityManager;
        this.json = json;
    }

    public ArchivoExcel plantilla(String codigo) {
        return plantilla.generar(registro.obtener(codigo));
    }

    // ------------------------------------------------------------------------------------ vista previa

    @Transactional
    public VistaPreviaImportacionResponse previsualizar(String codigo, MultipartFile archivo) {
        return previsualizar(registro.obtener(codigo), archivo);
    }

    private <C> VistaPreviaImportacionResponse previsualizar(TipoImportacion<C> tipo, MultipartFile archivo) {
        Usuario usuario = usuarioActual();
        repository.borrarNoConfirmadasVencidas(LocalDateTime.now().minusDays(1));

        try (LectorExcelSeguro.LibroSeguro seguro = lector.abrir(archivo)) {
            LectorPlantilla.Lectura lectura = lectorPlantilla.leer(seguro.libro(), tipo);
            C contexto = tipo.preparar(lectura.filas());

            List<FilaImportacion> filas = new ArrayList<>();
            Map<String, Integer> primeraFilaPorClave = new HashMap<>();
            for (FilaCruda cruda : lectura.filas()) {
                ResultadoFila resultado = tipo.validar(cruda, contexto);
                EstadoFila estado = resultado.estado();
                List<String> mensajes = resultado.mensajes();
                if (estado == EstadoFila.VALIDA) {
                    String clave = tipo.claveUnica(resultado.valores());
                    Integer anterior = clave == null ? null : primeraFilaPorClave.putIfAbsent(clave, cruda.numero());
                    if (anterior != null) {
                        estado = EstadoFila.DUPLICADA;
                        mensajes = List.of("Repetida en el archivo: ya aparece en la fila " + anterior + ". Se importa solo la primera.");
                    }
                }
                filas.add(new FilaImportacion(cruda.numero(), estado, resultado.valores(), mensajes));
            }

            ImportacionExcel importacion = new ImportacionExcel();
            importacion.setTipo(tipo.codigo());
            importacion.setVersion(tipo.version());
            importacion.setUsuario(usuario);
            importacion.setNombreArchivo(seguro.nombre());
            importacion.setHashArchivo(seguro.sha256());
            importacion.setTotalFilas(filas.size());
            importacion.setValidas(contar(filas, EstadoFila.VALIDA));
            importacion.setInvalidas(contar(filas, EstadoFila.INVALIDA));
            importacion.setDuplicadas(contar(filas, EstadoFila.DUPLICADA));
            importacion.setOmitidas(lectura.filasEnBlanco());
            importacion.setFilasJson(aJson(filas));
            importacion.setExpiraEn(LocalDateTime.now().plusMinutes(limites.importacionVigenciaMinutos()));
            repository.save(importacion);

            log.info("Importación {} previsualizada por {}: {} filas ({} válidas, {} inválidas, {} duplicadas)",
                    tipo.codigo(), usuario.getEmail(), filas.size(), importacion.getValidas(), importacion.getInvalidas(), importacion.getDuplicadas());
            return vistaPrevia(importacion, tipo, filas);
        }
    }

    // --------------------------------------------------------------------------------------- confirmar

    @Transactional
    public ResultadoImportacionResponse confirmar(UUID id) {
        ImportacionExcel importacion = repository.bloquear(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe esa importación. Vuelve a cargar el archivo."));
        verificarDueno(importacion);
        TipoImportacion<?> tipo = registro.obtener(importacion.getTipo());

        if (importacion.getEstado() == EstadoImportacion.CONFIRMADA) {
            return resultado(importacion, tipo, leerFilas(importacion), true);
        }
        if (importacion.getEstado() == EstadoImportacion.DESCARTADA) {
            throw new ConflictoNegocioException("Esta importación fue cancelada. Vuelve a cargar el archivo.");
        }
        if (importacion.getExpiraEn().isBefore(LocalDateTime.now())) {
            throw new ConflictoNegocioException("La vista previa venció. Vuelve a cargar el archivo para revisarlo otra vez.");
        }
        if (importacion.getVersion() != tipo.version()) {
            throw new ConflictoNegocioException("La plantilla cambió de versión después de esta vista previa. Vuelve a cargar el archivo.");
        }

        List<FilaImportacion> procesadas = new ArrayList<>();
        int creadas = 0;
        int omitidasAlConfirmar = 0;
        for (FilaImportacion fila : leerFilas(importacion)) {
            if (fila.estado() != EstadoFila.VALIDA) {
                procesadas.add(fila);
                continue;
            }
            var conflicto = tipo.conflictoAlConfirmar(fila.valores());
            if (conflicto.isPresent()) {
                procesadas.add(fila.conEstado(EstadoFila.OMITIDA, conflicto.get()));
                omitidasAlConfirmar++;
                continue;
            }
            crearFila(tipo, fila, id);
            procesadas.add(fila.conEstado(EstadoFila.CREADA, null));
            creadas++;
        }

        importacion.setEstado(EstadoImportacion.CONFIRMADA);
        importacion.setCreadas(creadas);
        importacion.setValidas(0);
        importacion.setOmitidas(importacion.getOmitidas() + omitidasAlConfirmar);
        importacion.setFilasJson(aJson(procesadas));
        importacion.setConfirmadoEn(LocalDateTime.now());
        repository.save(importacion);

        log.info("Importación {} {} confirmada por {}: {} creadas, {} omitidas al confirmar",
                tipo.codigo(), id, importacion.getUsuario().getEmail(), creadas, omitidasAlConfirmar);
        return resultado(importacion, tipo, procesadas, false);
    }

    /** Crea una fila con el servicio de negocio. Cualquier fallo revierte TODA la importacion (la transaccion es una sola). */
    private void crearFila(TipoImportacion<?> tipo, FilaImportacion fila, UUID id) {
        try {
            tipo.crear(fila.valores());
            // Fuerza los INSERT ahora para que una violacion de unicidad se atribuya a ESTA fila y no a la confirmacion final.
            entityManager.flush();
        } catch (ValidacionNegocioException | ConflictoNegocioException | RecursoNoEncontradoException | RecursoInactivoException e) {
            throw new ConflictoNegocioException("No se creó ningún registro. La fila " + fila.fila() + " no se pudo guardar: " + e.getMessage());
        } catch (DataIntegrityViolationException e) {
            log.warn("Importación {}: conflicto de integridad en la fila {}: {}", id, fila.fila(), e.getMostSpecificCause().getMessage());
            throw new ConflictoNegocioException("No se creó ningún registro. Mientras se importaba, alguien más registró el dato de la fila "
                    + fila.fila() + ". Vuelve a cargar el archivo para revisarlo otra vez.");
        } catch (RuntimeException e) {
            log.error("Importación {}: fallo inesperado en la fila {}", id, fila.fila(), e);
            throw new ConflictoNegocioException("No se creó ningún registro por un error inesperado en la fila " + fila.fila()
                    + ". Inténtalo de nuevo; si se repite, avisa al administrador del sistema.");
        }
    }

    // ------------------------------------------------------------------------------ descartar y reporte

    @Transactional
    public void descartar(UUID id) {
        ImportacionExcel importacion = repository.bloquear(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe esa importación."));
        verificarDueno(importacion);
        if (importacion.getEstado() == EstadoImportacion.CONFIRMADA) {
            throw new ConflictoNegocioException("Esta importación ya fue confirmada y no se puede cancelar.");
        }
        importacion.setEstado(EstadoImportacion.DESCARTADA);
    }

    /** Reporte descargable de lo que NO se importo: numero de fila, resultado, motivo y los valores que traia. */
    @Transactional(readOnly = true)
    public ArchivoExcel reporte(UUID id) {
        ImportacionExcel importacion = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe esa importación."));
        verificarDueno(importacion);
        TipoImportacion<?> tipo = registro.obtener(importacion.getTipo());
        List<FilaImportacion> noImportadas = leerFilas(importacion).stream()
                .filter(f -> f.estado() == EstadoFila.INVALIDA || f.estado() == EstadoFila.DUPLICADA || f.estado() == EstadoFila.OMITIDA)
                .toList();

        List<ColumnaExcel<FilaImportacion>> columnas = new ArrayList<>();
        columnas.add(ColumnaExcel.entero("Fila", FilaImportacion::fila));
        columnas.add(ColumnaExcel.texto("Resultado", 14, f -> etiqueta(f.estado())));
        columnas.add(ColumnaExcel.texto("Motivo", 70, f -> String.join(" ", f.mensajes())));
        for (ColumnaImportacion c : tipo.columnas()) {
            columnas.add(ColumnaExcel.texto(c.encabezado(), Math.max(14, c.ancho()), f -> f.valores().get(c.clave())));
        }
        return exportador.exportarLista("reporte-" + tipo.codigo(), "Reporte", columnas, noImportadas, null);
    }

    // ------------------------------------------------------------------------------------------ apoyo

    private VistaPreviaImportacionResponse vistaPrevia(ImportacionExcel i, TipoImportacion<?> tipo, List<FilaImportacion> filas) {
        return new VistaPreviaImportacionResponse(i.getId(), tipo.codigo(), tipo.titulo(), tipo.version(), i.getNombreArchivo(),
                i.getTotalFilas(), i.getValidas(), i.getInvalidas(), i.getDuplicadas(), i.getOmitidas(), i.getExpiraEn(),
                i.getValidas() > 0, columnasDe(tipo), filas.stream().map(FilaImportacionResponse::de).toList());
    }

    private ResultadoImportacionResponse resultado(ImportacionExcel i, TipoImportacion<?> tipo, List<FilaImportacion> filas, boolean repetida) {
        List<FilaImportacionResponse> noCreadas = filas.stream()
                .filter(f -> f.estado() != EstadoFila.CREADA && f.estado() != EstadoFila.VALIDA)
                .map(FilaImportacionResponse::de)
                .toList();
        return new ResultadoImportacionResponse(i.getId(), tipo.codigo(), tipo.titulo(), i.getCreadas(), i.getInvalidas(), i.getDuplicadas(),
                i.getOmitidas(), repetida, i.getConfirmadoEn(), columnasDe(tipo), noCreadas);
    }

    private static List<ColumnaImportacionResponse> columnasDe(TipoImportacion<?> tipo) {
        return tipo.columnas().stream().map(c -> new ColumnaImportacionResponse(c.clave(), c.encabezado())).toList();
    }

    private static int contar(List<FilaImportacion> filas, EstadoFila estado) {
        return (int) filas.stream().filter(f -> f.estado() == estado).count();
    }

    private static String etiqueta(EstadoFila estado) {
        return switch (estado) {
            case INVALIDA -> "Inválida";
            case DUPLICADA -> "Duplicada";
            case OMITIDA -> "Omitida";
            case VALIDA -> "Válida";
            case CREADA -> "Creada";
        };
    }

    private String aJson(List<FilaImportacion> filas) {
        try {
            return json.writeValueAsString(filas);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar la vista previa", e);
        }
    }

    private List<FilaImportacion> leerFilas(ImportacionExcel importacion) {
        try {
            return json.readValue(importacion.getFilasJson(), TIPO_FILAS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("La vista previa guardada está dañada", e);
        }
    }

    private Usuario usuarioActual() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        String email = autenticacion != null ? autenticacion.getName() : null;
        return usuarioRepository.findByEmail(email == null ? "" : email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado"));
    }

    private void verificarDueno(ImportacionExcel importacion) {
        Usuario actual = usuarioActual();
        if (!importacion.getUsuario().getId().equals(actual.getId())) {
            throw new AccessDeniedException("Esta importación la inició otra persona.");
        }
    }
}
