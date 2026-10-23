package com.planta.acopio.importacion;

import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.service.BusquedaTexto;
import com.planta.acopio.service.ProveedorService;
import com.planta.acopio.service.TelefonoPeru;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Importacion de proveedores (padron inicial). Cada proveedor se crea con {@link ProveedorService#crear}, el mismo camino que
 * la pantalla «Nuevo proveedor»: asi conserva el codigo QR, el historial de zona y su aparicion en la sincronizacion movil.
 *
 * Reglas propias de la importacion (la pantalla manual hoy solo exige que no esten vacios): el DNI debe tener 8 digitos y el
 * telefono, de informarse, ser un celular peruano valido (se guarda normalizado como +519XXXXXXXX). La zona, de informarse,
 * debe ser una zona activa existente. Solo crea: un DNI que ya existe se omite.
 */
@Component
public class ImportacionProveedores implements TipoImportacion<ImportacionProveedores.Contexto> {

    public static final String CODIGO = "proveedores";
    private static final Pattern OCHO_DIGITOS = Pattern.compile("\\d{8}");
    private static final Pattern SOLO_DIGITOS = Pattern.compile("\\d+");

    private static final ColumnaImportacion NOMBRE = new ColumnaImportacion("nombre", "Nombre completo", true, false, 34,
            "Nombres y apellidos, hasta 150 caracteres.", "Rosa Quispe Huamán");
    private static final ColumnaImportacion DNI = new ColumnaImportacion("dni", "DNI", true, true, 14,
            "8 dígitos (con los ceros del inicio). La plantilla lo guarda como texto.", "04567890");
    private static final ColumnaImportacion TELEFONO = new ColumnaImportacion("telefono", "Teléfono", false, true, 18,
            "Celular peruano: 9 dígitos que empiezan con 9, con o sin +51. Se guarda como +519XXXXXXXX. Opcional.", "987 654 321");
    private static final ColumnaImportacion ZONA = new ColumnaImportacion("zona", "Zona", false, false, 24,
            "Una de las zonas existentes (lista desplegable). Vacío = sin zona.", "Zona Norte");

    /** Zona activa tal como se busca por nombre. */
    public record ZonaConocida(UUID id, String nombre) {
    }

    /** Lo que se consulta UNA vez por archivo: zonas activas (por nombre plegado) y de los DNI del archivo, cuales ya existen. */
    public record Contexto(Map<String, ZonaConocida> zonasPorNombre, Set<String> dniExistentes) {
    }

    private final ProveedorService proveedorService;
    private final ProveedorRepository proveedorRepository;
    private final ZonaRepository zonaRepository;

    public ImportacionProveedores(ProveedorService proveedorService, ProveedorRepository proveedorRepository, ZonaRepository zonaRepository) {
        this.proveedorService = proveedorService;
        this.proveedorRepository = proveedorRepository;
        this.zonaRepository = zonaRepository;
    }

    @Override public String codigo() { return CODIGO; }
    @Override public String titulo() { return "Proveedores"; }
    @Override public int version() { return 1; }

    @Override
    public List<ColumnaImportacion> columnas() {
        return List.of(NOMBRE, DNI, TELEFONO, ZONA);
    }

    @Override
    public List<String> instrucciones() {
        return List.of(
                "PROVEEDORES",
                "• DNI y teléfono: la plantilla los guarda como texto para no perder los ceros del inicio. Si Excel igual los convierte en número, "
                        + "el DNI se completa con ceros a la izquierda hasta 8 dígitos.",
                "• Zona: elige una de la lista. Si el proveedor aún no tiene zona, déjala vacía.",
                "• Cada proveedor creado recibe su código QR y su historial de zona, y aparece en la app móvil en la próxima sincronización.",
                "• Un DNI que ya existe (aunque ese proveedor esté inactivo) o que se repite dentro del archivo se omite: no se modifica a nadie."
        );
    }

    @Override
    public Map<String, List<String>> listasDesplegables() {
        List<String> zonas = zonaRepository.findByActivaTrue().stream().map(Zona::getNombre)
                .sorted(Comparator.comparing(BusquedaTexto::plegar)).toList();
        return Map.of(ZONA.clave(), zonas);
    }

    @Override
    public Contexto preparar(List<FilaCruda> filas) {
        Map<String, ZonaConocida> zonas = new HashMap<>();
        for (Zona z : zonaRepository.findByActivaTrue()) {
            zonas.putIfAbsent(BusquedaTexto.plegar(ReglasCelda.limpiar(z.getNombre())), new ZonaConocida(z.getId(), z.getNombre()));
        }
        Set<String> candidatos = new HashSet<>();
        for (FilaCruda fila : filas) {
            normalizarDni(fila.celda(DNI.clave()), new ArrayList<>()).ifPresent(candidatos::add);
        }
        Set<String> existentes = candidatos.isEmpty() ? Set.of() : new HashSet<>(proveedorRepository.dnisExistentes(candidatos));
        return new Contexto(zonas, existentes);
    }

    @Override
    public ResultadoFila validar(FilaCruda fila, Contexto contexto) {
        List<String> errores = new ArrayList<>();
        String nombre = ReglasCelda.textoObligatorio(fila, NOMBRE, 150, errores);
        String dni = leerDni(fila, errores);
        String telefono = leerTelefono(fila, errores);
        ZonaConocida zona = leerZona(fila, contexto, errores);
        if (!errores.isEmpty()) {
            return ResultadoFila.invalida(ReglasCelda.crudos(fila, columnas()), errores);
        }
        Map<String, String> valores = new LinkedHashMap<>();
        valores.put("nombre", nombre);
        valores.put("dni", dni);
        valores.put("telefono", telefono);
        valores.put("zona", zona == null ? "" : zona.nombre());
        valores.put("zonaId", zona == null ? "" : zona.id().toString());
        if (contexto.dniExistentes().contains(dni)) {
            return ResultadoFila.duplicada(valores, "Ya existe un proveedor con el DNI " + dni + " (activo o inactivo). No se modifica.");
        }
        return ResultadoFila.valida(valores);
    }

    @Override
    public String claveUnica(Map<String, String> valores) {
        return valores.get("dni");
    }

    @Override
    public Optional<String> conflictoAlConfirmar(Map<String, String> valores) {
        if (proveedorRepository.findByDni(valores.get("dni")).isPresent()) {
            return Optional.of("Ya existe un proveedor con el DNI " + valores.get("dni") + " (se registró después de la vista previa). No se modifica.");
        }
        String zonaId = valores.get("zonaId");
        if (zonaId != null && !zonaId.isBlank()
                && zonaRepository.findById(UUID.fromString(zonaId)).filter(z -> Boolean.TRUE.equals(z.getActiva())).isEmpty()) {
            return Optional.of("La zona «" + valores.get("zona") + "» ya no está activa. Corrige la zona y vuelve a importar esta fila.");
        }
        return Optional.empty();
    }

    @Override
    public void crear(Map<String, String> v) {
        String telefono = v.get("telefono");
        String zonaId = v.get("zonaId");
        proveedorService.crear(new ProveedorRequest(
                v.get("nombre"),
                v.get("dni"),
                telefono == null || telefono.isBlank() ? null : telefono,
                zonaId == null || zonaId.isBlank() ? null : UUID.fromString(zonaId)));
    }

    // ------------------------------------------------------------------------------------------ lectura

    private String leerDni(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.texto(fila, DNI, errores);
        if (texto == null) {
            return null;
        }
        if (texto.isEmpty()) {
            errores.add("DNI: es obligatorio.");
            return null;
        }
        return normalizarDni(fila.celda(DNI.clave()), errores).orElse(null);
    }

    /**
     * El DNI de una celda: texto de 8 digitos (se admiten espacios entre ellos) o, si Excel lo guardo como NUMERO, el entero
     * completado con ceros a la izquierda hasta 8 digitos. Cualquier otra cosa agrega un error y no devuelve nada.
     */
    static Optional<String> normalizarDni(CeldaLeida celda, List<String> errores) {
        if (celda.problematica() || celda.vacia()) {
            return Optional.empty();
        }
        String limpio = ReglasCelda.limpiar(celda.texto()).replace(" ", "");
        if (celda.numerica()) {
            if (!SOLO_DIGITOS.matcher(limpio).matches() || limpio.length() > 8) {
                errores.add("DNI: «" + celda.texto() + "» no es un DNI válido (8 dígitos).");
                return Optional.empty();
            }
            return Optional.of("0".repeat(8 - limpio.length()) + limpio);
        }
        if (!OCHO_DIGITOS.matcher(limpio).matches()) {
            errores.add("DNI: «" + celda.texto() + "» no es un DNI válido: debe tener exactamente 8 dígitos"
                    + (SOLO_DIGITOS.matcher(limpio).matches() ? " (tiene " + limpio.length() + ")." : " y solo números."));
            return Optional.empty();
        }
        return Optional.of(limpio);
    }

    private String leerTelefono(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.texto(fila, TELEFONO, errores);
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        Optional<String> normalizado = TelefonoPeru.normalizar(texto);
        if (normalizado.isEmpty()) {
            errores.add("Teléfono: «" + texto + "» no es un celular peruano válido (9 dígitos que empiezan con 9, con o sin +51).");
            return "";
        }
        return normalizado.get();
    }

    private ZonaConocida leerZona(FilaCruda fila, Contexto contexto, List<String> errores) {
        String texto = ReglasCelda.texto(fila, ZONA, errores);
        if (texto == null || texto.isEmpty()) {
            return null;
        }
        ZonaConocida zona = contexto.zonasPorNombre().get(BusquedaTexto.plegar(texto));
        if (zona == null) {
            errores.add("Zona: «" + texto + "» no existe o está desactivada. Elige una zona de la lista o déjala vacía.");
        }
        return zona;
    }
}
