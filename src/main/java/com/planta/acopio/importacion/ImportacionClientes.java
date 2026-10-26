package com.planta.acopio.importacion;

import com.planta.acopio.dto.ClienteDtos.AutorizacionComercial;
import com.planta.acopio.dto.ClienteDtos.AutorizacionRegistro;
import com.planta.acopio.dto.ClienteDtos.ClienteRequest;
import com.planta.acopio.entity.TipoPersona;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.service.BusquedaTexto;
import com.planta.acopio.service.ClienteService;
import com.planta.acopio.service.PrivacidadService;
import com.planta.acopio.service.TelefonoPeru;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Refactor clientes: importacion del padron de clientes. Cada ficha se crea con {@link ClienteService#registrar}, el mismo camino que
 * el registro del panel: mismas reglas de documentos (Fase G), mismo control de identidad (DNI/RUC de otra ficha o de un proveedor =
 * duplicado; nada se fusiona) y la MISMA constancia de autorizaciones (append-only, con la version vigente del aviso y quien importo).
 *
 * Reglas propias de la importacion:
 *  * Telefono obligatorio (celular peruano), como en el registro.
 *  * «Cómo autorizó el registro» es obligatorio: sin la autorizacion de la persona no se crea su ficha. Quien importa declara, fila
 *    por fila, como la obtuvo (la prueba es del responsable: DS 016-2024-JUS art. 9).
 *  * Promociones: solo si la fila trae los medios autorizados; vacio = no autorizo (nunca se asume).
 *  * Si el telefono ya figura en otra ficha (cliente o proveedor), la fila se omite como posible duplicado salvo que «Mismo teléfono,
 *    otra persona» diga «Sí» (lo mismo que confirma el trabajador en el panel).
 *  * Tipo de cliente «Mayorista» exige motivo; canal habitual «Mayorista» exige tipo mayorista. Solo ADMIN importa (endpoint ADMIN).
 */
@Component
public class ImportacionClientes implements TipoImportacion<Void> {

    public static final String CODIGO = "clientes";
    static final String REGULAR = "Regular";
    static final String MAYORISTA = "Mayorista";
    static final String PUBLICO = "Público";
    private static final Map<String, String> FORMAS = new LinkedHashMap<>();
    private static final Map<String, String> MEDIOS = Map.of("whatsapp", "WHATSAPP", "sms", "SMS", "llamada", "LLAMADA", "correo", "CORREO");
    private static final Map<String, String> ETIQUETAS_MEDIO = Map.of("WHATSAPP", "WhatsApp", "SMS", "SMS", "LLAMADA", "Llamada", "CORREO", "Correo");

    static {
        FORMAS.put("De palabra, en persona", "VERBAL_PRESENCIAL");
        FORMAS.put("Por escrito", "ESCRITO");
        FORMAS.put("Por teléfono", "TELEFONICA");
        FORMAS.put("Por un medio digital", "DIGITAL");
    }

    private static final ColumnaImportacion TIPO = new ColumnaImportacion("tipo", "Tipo de persona", false, false, 18,
            "«Persona natural» o «Empresa» (lista desplegable). Vacío = Persona natural.", ImportacionProveedores.TIPO_NATURAL);
    private static final ColumnaImportacion NOMBRE = new ColumnaImportacion("nombre", "Nombre", false, false, 32,
            "Hasta 150 caracteres. Obligatorio para una persona natural; para una empresa es opcional (si falta se usa la razón social).",
            "Rosa Quispe Huamán");
    private static final ColumnaImportacion TELEFONO = new ColumnaImportacion("telefono", "Teléfono", true, true, 16,
            "Celular peruano: 9 dígitos que empiezan con 9, con o sin +51.", "987 654 321");
    private static final ColumnaImportacion CORREO = new ColumnaImportacion("correo", "Correo", false, false, 28,
            "Opcional.", "rosa@ejemplo.pe");
    private static final ColumnaImportacion DNI = new ColumnaImportacion("dni", "DNI", false, true, 12,
            "Opcional para una persona natural (8 dígitos); una empresa no lleva DNI.", "04567890");
    private static final ColumnaImportacion RUC = new ColumnaImportacion("ruc", "RUC", false, true, 14,
            "Opcional para una persona natural (10 + su DNI + verificador); obligatorio para una empresa (empieza con 20).", "10045678901");
    private static final ColumnaImportacion RAZON_SOCIAL = new ColumnaImportacion("razonSocial", "Razón social", false, false, 32,
            "Solo para una empresa (obligatoria).", "Bodega El Sol S.A.C.");
    private static final ColumnaImportacion TIPO_CLIENTE = new ColumnaImportacion("tipoCliente", "Tipo de cliente", false, false, 14,
            "«Regular» o «Mayorista» (lista desplegable). Vacío = Regular. Mayorista habilita el precio MAYORISTA y exige motivo.", REGULAR);
    private static final ColumnaImportacion MOTIVO = new ColumnaImportacion("motivoMayorista", "Motivo mayorista", false, false, 30,
            "Obligatorio si el tipo es Mayorista (hasta 300 caracteres).", "Compra por mayor para su bodega");
    private static final ColumnaImportacion CANAL = new ColumnaImportacion("canal", "Canal habitual", false, false, 14,
            "«Público» o «Mayorista» (este solo para mayoristas). Es el canal que se propone al venderle. Vacío = Público.", PUBLICO);
    private static final ColumnaImportacion FORMA = new ColumnaImportacion("forma", "Cómo autorizó el registro", true, false, 24,
            "Cómo dio la persona su autorización para guardar su ficha (lista desplegable). Sin autorización, no la incluyas.",
            "De palabra, en persona");
    private static final ColumnaImportacion PROMOCIONES = new ColumnaImportacion("promociones", "Promociones (medios)", false, false, 24,
            "Solo si la persona AUTORIZÓ promociones: WhatsApp, SMS, Llamada y/o Correo, separados por comas. Vacío = no autorizó.",
            "WhatsApp, Correo");
    private static final ColumnaImportacion OTRA_PERSONA = new ColumnaImportacion("otraPersona", "Mismo teléfono, otra persona", false, false, 16,
            "«Sí» solo si confirmaste que es otra persona aunque su teléfono ya figure en otra ficha. Vacío = No.", "No");

    private final ClienteService clienteService;
    private final PrivacidadService privacidad;

    public ImportacionClientes(ClienteService clienteService, PrivacidadService privacidad) {
        this.clienteService = clienteService;
        this.privacidad = privacidad;
    }

    @Override public String codigo() { return CODIGO; }
    @Override public String titulo() { return "Clientes"; }
    @Override public int version() { return 1; }

    @Override
    public List<ColumnaImportacion> columnas() {
        return List.of(TIPO, NOMBRE, TELEFONO, CORREO, DNI, RUC, RAZON_SOCIAL, TIPO_CLIENTE, MOTIVO, CANAL, FORMA, PROMOCIONES, OTRA_PERSONA);
    }

    @Override
    public List<String> instrucciones() {
        return List.of(
                "CLIENTES",
                "• Incluye SOLO a personas que autorizaron que guardemos su ficha, e indica cómo lo autorizaron. Registrarse es voluntario.",
                "• Promociones: escribe los medios solo si la persona las autorizó expresamente; si no, deja la celda vacía.",
                "• Se registra la constancia de cada autorización con la versión vigente del aviso de privacidad y tu usuario.",
                "• Persona natural: nombre obligatorio; DNI y RUC opcionales. Empresa: RUC (empieza con 20) y razón social obligatorios, sin DNI.",
                "• Un DNI o RUC que ya es de otra ficha o de un proveedor, o que se repite en el archivo, se omite: no se modifica ni se fusiona a nadie.",
                "• Si el teléfono ya figura en otra ficha, la fila se omite como posible duplicado salvo que confirmes «Sí» en «Mismo teléfono, otra persona».",
                "• Tipo de cliente y canal habitual son cosas distintas: el tipo habilita el precio mayorista; el canal es el que se propone al venderle."
        );
    }

    @Override
    public Map<String, List<String>> listasDesplegables() {
        return Map.of(
                TIPO.clave(), List.of(ImportacionProveedores.TIPO_NATURAL, ImportacionProveedores.TIPO_EMPRESA),
                TIPO_CLIENTE.clave(), List.of(REGULAR, MAYORISTA),
                CANAL.clave(), List.of(PUBLICO, MAYORISTA),
                FORMA.clave(), List.copyOf(FORMAS.keySet()),
                OTRA_PERSONA.clave(), List.of("No", "Sí"));
    }

    @Override
    public Void preparar(List<FilaCruda> filas) {
        return null;
    }

    @Override
    public ResultadoFila validar(FilaCruda fila, Void contexto) {
        List<String> errores = new ArrayList<>();
        TipoPersona tipo = leerTipo(fila, errores);
        String nombre = opcional(fila, NOMBRE, 150, errores);
        String telefono = leerTelefono(fila, errores);
        String correo = opcional(fila, CORREO, 120, errores);
        String dni = leerDocumento(fila, DNI, errores);
        String ruc = leerDocumento(fila, RUC, errores);
        String razonSocial = opcional(fila, RAZON_SOCIAL, 150, errores);
        Boolean mayorista = leerOpcion(fila, TIPO_CLIENTE, Map.of(REGULAR, false, MAYORISTA, true), false, errores);
        String motivo = opcional(fila, MOTIVO, 300, errores);
        String canal = leerOpcion(fila, CANAL, Map.of(PUBLICO, "PUBLICO", MAYORISTA, "MAYORISTA"), "PUBLICO", errores);
        String forma = leerForma(fila, errores);
        List<String> medios = leerMedios(fila, errores);
        Boolean otraPersona = leerOpcion(fila, OTRA_PERSONA, Map.of("No", false, "Sí", true), false, errores);

        ClienteService.Documentos doc = null;
        if (errores.isEmpty()) {
            try {
                doc = ClienteService.documentos(tipo, nombre, dni, ruc, razonSocial);
                if (mayorista && motivo == null) throw new ValidacionNegocioException(MOTIVO.encabezado() + ": es obligatorio para un mayorista.");
                ClienteService.canalHabitual(canal, mayorista);
            } catch (ValidacionNegocioException e) {
                errores.add(e.getMessage());
            }
        }
        if (!errores.isEmpty()) {
            return ResultadoFila.invalida(ReglasCelda.crudos(fila, columnas()), errores);
        }
        Map<String, String> v = new LinkedHashMap<>();
        v.put("tipo", doc.tipo() == TipoPersona.EMPRESA ? ImportacionProveedores.TIPO_EMPRESA : ImportacionProveedores.TIPO_NATURAL);
        v.put("nombre", doc.nombre());
        v.put("telefono", telefono);
        v.put("correo", correo == null ? "" : correo);
        v.put("dni", doc.dni() == null ? "" : doc.dni());
        v.put("ruc", doc.ruc() == null ? "" : doc.ruc());
        v.put("razonSocial", doc.razonSocial() == null ? "" : doc.razonSocial());
        v.put("tipoCliente", mayorista ? MAYORISTA : REGULAR);
        v.put("motivoMayorista", mayorista ? motivo : "");
        v.put("canal", "MAYORISTA".equals(canal) ? MAYORISTA : PUBLICO);
        v.put("forma", etiquetaForma(forma));
        v.put("promociones", String.join(", ", medios));
        v.put("otraPersona", otraPersona ? "Sí" : "No");
        v.put("tipoPersona", doc.tipo().name());
        v.put("canalCodigo", canal);
        v.put("formaCodigo", forma);
        Optional<String> conflicto = conflicto(v);
        return conflicto.isPresent() ? ResultadoFila.duplicada(v, conflicto.get()) : ResultadoFila.valida(v);
    }

    // DNI o RUC identifican; sin documento, el telefono (salvo que se confirme que es otra persona).
    @Override
    public String claveUnica(Map<String, String> v) {
        if (!v.get("dni").isBlank()) return v.get("dni");
        if (!v.get("ruc").isBlank()) return "RUC " + v.get("ruc");
        return "Sí".equals(v.get("otraPersona")) ? null : "Teléfono " + v.get("telefono");
    }

    @Override
    public Optional<String> conflictoAlConfirmar(Map<String, String> v) {
        return conflicto(v).map(m -> m + " (se registró después de la vista previa).");
    }

    /** Mismas reglas de identidad que el registro: documentos de otra ficha o de un proveedor; teléfono ya usado sin confirmar. */
    private Optional<String> conflicto(Map<String, String> v) {
        ClienteService.Documentos doc = new ClienteService.Documentos(TipoPersona.valueOf(v.get("tipoPersona")), v.get("nombre"),
                vacioANull(v.get("dni")), vacioANull(v.get("ruc")), vacioANull(v.get("razonSocial")));
        Optional<String> documentos = clienteService.conflictoDeDocumentos(doc);
        if (documentos.isPresent()) return documentos;
        if (!"Sí".equals(v.get("otraPersona"))) {
            List<String> mismos = clienteService.fichasConTelefono(v.get("telefono"));
            if (!mismos.isEmpty()) {
                return Optional.of("Ese teléfono ya figura en " + String.join(", ", mismos) + ": posible duplicado, no se fusiona. Si confirmaste "
                        + "que es otra persona, escribe «Sí» en «" + OTRA_PERSONA.encabezado() + "».");
            }
        }
        return Optional.empty();
    }

    @Override
    public void crear(Map<String, String> v) {
        String forma = v.get("formaCodigo");
        String version = privacidad.version();
        List<String> medios = v.get("promociones").isBlank() ? List.of()
                : Arrays.stream(v.get("promociones").split(",")).map(String::trim).map(m -> MEDIOS.get(BusquedaTexto.plegar(m))).toList();
        clienteService.registrar(new ClienteRequest(
                v.get("tipoPersona"), v.get("nombre"), v.get("telefono"), vacioANull(v.get("correo")), vacioANull(v.get("dni")),
                vacioANull(v.get("ruc")), vacioANull(v.get("razonSocial")),
                new AutorizacionRegistro(true, forma),
                medios.isEmpty() ? null : new AutorizacionComercial(true, medios, forma),
                version, "Sí".equals(v.get("otraPersona")),
                MAYORISTA.equals(v.get("tipoCliente")), vacioANull(v.get("motivoMayorista")), v.get("canalCodigo")), true);
    }

    // ------------------------------------------------------------------------------------------ lectura

    private static String vacioANull(String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    private static String etiquetaForma(String codigo) {
        return FORMAS.entrySet().stream().filter(e -> e.getValue().equals(codigo)).map(Map.Entry::getKey).findFirst().orElse(codigo);
    }

    private static TipoPersona leerTipo(FilaCruda fila, List<String> errores) {
        return leerOpcion(fila, TIPO, Map.of(ImportacionProveedores.TIPO_NATURAL, TipoPersona.NATURAL, "Natural", TipoPersona.NATURAL,
                ImportacionProveedores.TIPO_EMPRESA, TipoPersona.EMPRESA), TipoPersona.NATURAL, errores);
    }

    /** Valor de una lista cerrada (sin distinguir mayusculas ni acentos); vacio = el valor por defecto. */
    private static <T> T leerOpcion(FilaCruda fila, ColumnaImportacion columna, Map<String, T> opciones, T porDefecto, List<String> errores) {
        String texto = ReglasCelda.texto(fila, columna, errores);
        if (texto == null) return porDefecto;
        if (texto.isEmpty()) return porDefecto;
        String plegado = BusquedaTexto.plegar(texto);
        for (Map.Entry<String, T> e : opciones.entrySet()) {
            if (BusquedaTexto.plegar(e.getKey()).equals(plegado)) return e.getValue();
        }
        errores.add(columna.encabezado() + ": «" + texto + "» no es válido. Elige un valor de la lista.");
        return porDefecto;
    }

    private static String leerForma(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.textoObligatorio(fila, FORMA, 60, errores);
        if (texto == null) return null;
        String plegado = BusquedaTexto.plegar(texto);
        for (Map.Entry<String, String> e : FORMAS.entrySet()) {
            if (BusquedaTexto.plegar(e.getKey()).equals(plegado) || e.getValue().equalsIgnoreCase(texto)) return e.getValue();
        }
        errores.add(FORMA.encabezado() + ": «" + texto + "» no es válido. Elige un valor de la lista.");
        return null;
    }

    private static List<String> leerMedios(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.texto(fila, PROMOCIONES, errores);
        if (texto == null || texto.isEmpty()) return List.of();
        List<String> medios = new ArrayList<>();
        for (String parte : texto.split("[,;/]")) {
            String p = BusquedaTexto.plegar(parte.trim());
            if (p.isEmpty()) continue;
            String medio = MEDIOS.get(p);
            if (medio == null) {
                errores.add(PROMOCIONES.encabezado() + ": «" + parte.trim() + "» no es un medio válido (WhatsApp, SMS, Llamada o Correo).");
            } else if (!medios.contains(medio)) {
                medios.add(medio);
            }
        }
        return medios.stream().sorted().map(ETIQUETAS_MEDIO::get).toList();
    }

    private static String opcional(FilaCruda fila, ColumnaImportacion columna, int maximo, List<String> errores) {
        String texto = ReglasCelda.texto(fila, columna, errores);
        if (texto == null || texto.isEmpty()) return null;
        if (texto.length() > maximo) {
            errores.add(columna.encabezado() + ": tiene " + texto.length() + " caracteres y el máximo es " + maximo + ".");
            return null;
        }
        return texto;
    }

    private static String leerTelefono(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.textoObligatorio(fila, TELEFONO, 30, errores);
        if (texto == null) return "";
        Optional<String> normalizado = TelefonoPeru.normalizar(texto);
        if (normalizado.isEmpty()) {
            errores.add("Teléfono: «" + texto + "» no es un celular peruano válido (9 dígitos que empiezan con 9, con o sin +51).");
            return "";
        }
        return normalizado.get();
    }

    /** DNI o RUC con la misma lectura que la importacion de proveedores (ceros a la izquierda si Excel lo guardo como numero). */
    private static String leerDocumento(FilaCruda fila, ColumnaImportacion columna, List<String> errores) {
        String texto = ReglasCelda.texto(fila, columna, errores);
        if (texto == null || texto.isEmpty()) return null;
        CeldaLeida celda = fila.celda(columna.clave());
        return (columna == DNI ? ImportacionProveedores.normalizarDni(celda, errores) : ImportacionProveedores.normalizarRuc(celda, errores))
                .orElse(null);
    }
}
