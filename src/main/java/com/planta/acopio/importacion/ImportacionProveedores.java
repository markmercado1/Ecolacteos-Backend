package com.planta.acopio.importacion;

import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.entity.TipoPersona;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.ValidacionNegocioException;
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
 * Reglas propias de la importacion: el telefono, de informarse, debe ser un celular peruano valido (se guarda normalizado
 * como +519XXXXXXXX) y la zona, de informarse, una zona activa existente. Solo crea: un DNI o RUC que ya existe se omite.
 *
 * Fase G (version 2 de la plantilla): tipo de persona, RUC y razon social. Los documentos se validan con las MISMAS reglas que
 * la pantalla (ProveedorService.validarDocumentos): persona natural = nombre y DNI de 8 digitos, RUC opcional (10 + DNI +
 * verificador); empresa = RUC 20... y razon social, sin DNI. Una plantilla de la version 1 se rechaza con un aviso claro.
 */
@Component
public class ImportacionProveedores implements TipoImportacion<ImportacionProveedores.Contexto> {

    public static final String CODIGO = "proveedores";
    private static final Pattern OCHO_DIGITOS = Pattern.compile("\\d{8}");
    private static final Pattern ONCE_DIGITOS = Pattern.compile("\\d{11}");
    static final String TIPO_NATURAL = "Persona natural";
    static final String TIPO_EMPRESA = "Empresa";
    private static final Pattern SOLO_DIGITOS = Pattern.compile("\\d+");

    private static final ColumnaImportacion TIPO = new ColumnaImportacion("tipo", "Tipo de persona", false, false, 18,
            "«Persona natural» o «Empresa» (lista desplegable). Vacío = Persona natural.", TIPO_NATURAL);
    private static final ColumnaImportacion NOMBRE = new ColumnaImportacion("nombre", "Nombre completo", false, false, 34,
            "Nombres y apellidos, hasta 150 caracteres. Obligatorio para una persona natural; para una empresa es opcional "
                    + "(nombre comercial o de contacto; si falta se usa la razón social).", "Rosa Quispe Huamán");
    private static final ColumnaImportacion DNI = new ColumnaImportacion("dni", "DNI", false, true, 14,
            "8 dígitos (con los ceros del inicio). Obligatorio para una persona natural; una empresa no lleva DNI.", "04567890");
    private static final ColumnaImportacion RUC = new ColumnaImportacion("ruc", "RUC", false, true, 14,
            "11 dígitos. Opcional para una persona natural (10 + su DNI + verificador); obligatorio para una empresa (empieza con 20).",
            "10045678901");
    private static final ColumnaImportacion RAZON_SOCIAL = new ColumnaImportacion("razonSocial", "Razón social", false, false, 34,
            "Solo para una empresa (obligatoria), hasta 150 caracteres.", "Lácteos del Valle S.A.C.");
    private static final ColumnaImportacion TELEFONO = new ColumnaImportacion("telefono", "Teléfono", false, true, 18,
            "Celular peruano: 9 dígitos que empiezan con 9, con o sin +51. Se guarda como +519XXXXXXXX. Opcional.", "987 654 321");
    private static final ColumnaImportacion ZONA = new ColumnaImportacion("zona", "Zona", false, false, 24,
            "Una de las zonas existentes (lista desplegable). Vacío = sin zona.", "Zona Norte");

    /** Zona activa tal como se busca por nombre. */
    public record ZonaConocida(UUID id, String nombre) {
    }

    /** Lo que se consulta UNA vez por archivo: zonas activas (por nombre plegado) y de los DNI y RUC del archivo, cuales ya existen. */
    public record Contexto(Map<String, ZonaConocida> zonasPorNombre, Set<String> dniExistentes, Set<String> rucExistentes) {
        public Contexto(Map<String, ZonaConocida> zonasPorNombre, Set<String> dniExistentes) {
            this(zonasPorNombre, dniExistentes, Set.of());
        }
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
    @Override public int version() { return 2; }

    @Override
    public List<ColumnaImportacion> columnas() {
        return List.of(TIPO, NOMBRE, DNI, RUC, RAZON_SOCIAL, TELEFONO, ZONA);
    }

    @Override
    public List<String> instrucciones() {
        return List.of(
                "PROVEEDORES",
                "• Persona natural: nombre y DNI obligatorios; RUC opcional (si lo tiene, es 10 + su DNI + un dígito verificador). "
                        + "No lleva razón social.",
                "• Empresa: RUC (empieza con 20) y razón social obligatorios; no lleva DNI. El nombre es opcional.",
                "• DNI, RUC y teléfono: la plantilla los guarda como texto para no perder los ceros del inicio. Si Excel igual convierte "
                        + "el DNI en número, se completa con ceros a la izquierda hasta 8 dígitos.",
                "• Zona: elige una de la lista. Si el proveedor aún no tiene zona, déjala vacía.",
                "• Cada proveedor creado recibe su código QR y su historial de zona, y aparece en la app móvil en la próxima sincronización.",
                "• Un DNI o RUC que ya existe (aunque ese proveedor esté inactivo) o que se repite dentro del archivo se omite: no se modifica a nadie."
        );
    }

    @Override
    public Map<String, List<String>> listasDesplegables() {
        List<String> zonas = zonaRepository.findByActivaTrue().stream().map(Zona::getNombre)
                .sorted(Comparator.comparing(BusquedaTexto::plegar)).toList();
        return Map.of(ZONA.clave(), zonas, TIPO.clave(), List.of(TIPO_NATURAL, TIPO_EMPRESA));
    }

    @Override
    public Contexto preparar(List<FilaCruda> filas) {
        Map<String, ZonaConocida> zonas = new HashMap<>();
        for (Zona z : zonaRepository.findByActivaTrue()) {
            zonas.putIfAbsent(BusquedaTexto.plegar(ReglasCelda.limpiar(z.getNombre())), new ZonaConocida(z.getId(), z.getNombre()));
        }
        Set<String> candidatos = new HashSet<>();
        Set<String> rucs = new HashSet<>();
        for (FilaCruda fila : filas) {
            normalizarDni(fila.celda(DNI.clave()), new ArrayList<>()).ifPresent(candidatos::add);
            normalizarRuc(fila.celda(RUC.clave()), new ArrayList<>()).ifPresent(rucs::add);
        }
        Set<String> existentes = candidatos.isEmpty() ? Set.of() : new HashSet<>(proveedorRepository.dnisExistentes(candidatos));
        Set<String> rucExistentes = rucs.isEmpty() ? Set.of() : new HashSet<>(proveedorRepository.rucsExistentes(rucs));
        return new Contexto(zonas, existentes, rucExistentes);
    }

    @Override
    public ResultadoFila validar(FilaCruda fila, Contexto contexto) {
        List<String> errores = new ArrayList<>();
        TipoPersona tipo = leerTipo(fila, errores);
        String nombre = leerTextoOpcional(fila, NOMBRE, 150, errores);
        String dni = leerDni(fila, errores);
        String ruc = leerRuc(fila, errores);
        String razonSocial = leerTextoOpcional(fila, RAZON_SOCIAL, 150, errores);
        String telefono = leerTelefono(fila, errores);
        ZonaConocida zona = leerZona(fila, contexto, errores);
        // Lo que falta segun el tipo se informa junto con los demas errores de la fila (el resto de las reglas, despues).
        if (tipo == TipoPersona.NATURAL && nombre == null && !errores.stream().anyMatch(m -> m.startsWith(NOMBRE.encabezado()))) {
            errores.add(NOMBRE.encabezado() + ": es obligatorio para una persona natural.");
        }
        if (tipo == TipoPersona.NATURAL && dni == null && fila.celda(DNI.clave()).vacia()) {
            errores.add("DNI: es obligatorio para una persona natural.");
        }
        ProveedorService.Documentos doc = null;
        if (errores.isEmpty() && tipo != null) {
            try {
                doc = ProveedorService.validarDocumentos(tipo, nombre, dni, ruc, razonSocial, null);
            } catch (ValidacionNegocioException e) {
                errores.add(e.getMessage());
            }
        }
        if (!errores.isEmpty()) {
            return ResultadoFila.invalida(ReglasCelda.crudos(fila, columnas()), errores);
        }
        Map<String, String> valores = new LinkedHashMap<>();
        valores.put("tipo", doc.tipo() == TipoPersona.EMPRESA ? TIPO_EMPRESA : TIPO_NATURAL);
        valores.put("nombre", doc.nombre());
        valores.put("dni", doc.dni() == null ? "" : doc.dni());
        valores.put("ruc", doc.ruc() == null ? "" : doc.ruc());
        valores.put("razonSocial", doc.razonSocial() == null ? "" : doc.razonSocial());
        valores.put("telefono", telefono);
        valores.put("zona", zona == null ? "" : zona.nombre());
        valores.put("zonaId", zona == null ? "" : zona.id().toString());
        valores.put("tipoPersona", doc.tipo().name());
        if (doc.dni() != null && contexto.dniExistentes().contains(doc.dni())) {
            return ResultadoFila.duplicada(valores, "Ya existe un proveedor con el DNI " + doc.dni() + " (activo o inactivo). No se modifica.");
        }
        if (doc.ruc() != null && contexto.rucExistentes().contains(doc.ruc())) {
            return ResultadoFila.duplicada(valores, "Ya existe un proveedor con el RUC " + doc.ruc() + " (activo o inactivo). No se modifica.");
        }
        return ResultadoFila.valida(valores);
    }

    // Un DNI identifica a la persona (y su RUC 10 lo contiene); una empresa, su RUC.
    @Override
    public String claveUnica(Map<String, String> valores) {
        String dni = valores.get("dni");
        return dni != null && !dni.isBlank() ? dni : "RUC " + valores.get("ruc");
    }

    @Override
    public Optional<String> conflictoAlConfirmar(Map<String, String> valores) {
        String dni = valores.get("dni");
        if (dni != null && !dni.isBlank() && proveedorRepository.findByDni(dni).isPresent()) {
            return Optional.of("Ya existe un proveedor con el DNI " + dni + " (se registró después de la vista previa). No se modifica.");
        }
        String ruc = valores.get("ruc");
        if (ruc != null && !ruc.isBlank() && proveedorRepository.findByRuc(ruc).isPresent()) {
            return Optional.of("Ya existe un proveedor con el RUC " + ruc + " (se registró después de la vista previa). No se modifica.");
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
                vacioANull(v.get("dni")),
                telefono == null || telefono.isBlank() ? null : telefono,
                zonaId == null || zonaId.isBlank() ? null : UUID.fromString(zonaId),
                v.getOrDefault("tipoPersona", TipoPersona.NATURAL.name()),
                vacioANull(v.get("ruc")),
                vacioANull(v.get("razonSocial"))));
    }

    private static String vacioANull(String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    // ------------------------------------------------------------------------------------------ lectura

    private String leerDni(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.texto(fila, DNI, errores);
        if (texto == null) {
            return null;
        }
        if (texto.isEmpty()) {
            return null;
        }
        return normalizarDni(fila.celda(DNI.clave()), errores).orElse(null);
    }

    private TipoPersona leerTipo(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.texto(fila, TIPO, errores);
        if (texto == null) {
            return null;
        }
        String plegado = BusquedaTexto.plegar(texto);
        if (plegado.isEmpty() || plegado.equals(BusquedaTexto.plegar(TIPO_NATURAL)) || plegado.equals("natural")) {
            return TipoPersona.NATURAL;
        }
        if (plegado.equals(BusquedaTexto.plegar(TIPO_EMPRESA))) {
            return TipoPersona.EMPRESA;
        }
        errores.add("Tipo de persona: «" + texto + "» no es válido. Elige «" + TIPO_NATURAL + "» o «" + TIPO_EMPRESA + "».");
        return null;
    }

    private static String leerTextoOpcional(FilaCruda fila, ColumnaImportacion columna, int maximo, List<String> errores) {
        String texto = ReglasCelda.texto(fila, columna, errores);
        if (texto == null || texto.isEmpty()) {
            return null;
        }
        if (texto.length() > maximo) {
            errores.add(columna.encabezado() + ": tiene " + texto.length() + " caracteres y el máximo es " + maximo + ".");
            return null;
        }
        return texto;
    }

    private String leerRuc(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.texto(fila, RUC, errores);
        if (texto == null || texto.isEmpty()) {
            return null;
        }
        return normalizarRuc(fila.celda(RUC.clave()), errores).orElse(null);
    }

    /** El RUC de una celda: 11 digitos (se admiten espacios), como texto o como numero. Otra cosa agrega un error. */
    static Optional<String> normalizarRuc(CeldaLeida celda, List<String> errores) {
        if (celda.problematica() || celda.vacia()) {
            return Optional.empty();
        }
        String limpio = ReglasCelda.limpiar(celda.texto()).replace(" ", "");
        if (!ONCE_DIGITOS.matcher(limpio).matches()) {
            errores.add("RUC: «" + celda.texto() + "» no es un RUC válido: debe tener exactamente 11 dígitos"
                    + (SOLO_DIGITOS.matcher(limpio).matches() ? " (tiene " + limpio.length() + ")." : " y solo números."));
            return Optional.empty();
        }
        return Optional.of(limpio);
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
