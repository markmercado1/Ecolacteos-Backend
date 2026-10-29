package com.planta.acopio.importacion;

import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.service.BusquedaTexto;
import com.planta.acopio.service.InsumoService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Importacion de insumos (catalogo inicial con su stock de partida). Cada insumo se crea con {@link InsumoService#crear}: el
 * stock informado es el SALDO_INICIAL del kardex del insumo, exactamente como al crearlo desde «Nuevo insumo». La importacion
 * NO puede cambiar el stock de un insumo existente (un nombre repetido se omite): el stock solo cambia despues por ingresos,
 * produccion o ajustes, que dejan su movimiento.
 */
@Component
public class ImportacionInsumos implements TipoImportacion<Set<String>> {

    public static final String CODIGO = "insumos";
    /** Hasta 10 enteros y 2 decimales (NUMERIC(12,2) en la base y @Digits en InsumoRequest). */
    private static final Pattern STOCK = Pattern.compile("\\d{1,10}(\\.\\d{1,2})?");

    private static final ColumnaImportacion NOMBRE = new ColumnaImportacion("nombre", "Nombre del insumo", true, false, 30,
            "Hasta 60 caracteres. No puede repetirse (se ignoran mayúsculas y acentos).", "Cuajo líquido");
    private static final ColumnaImportacion UNIDAD = new ColumnaImportacion("unidad", "Unidad de medida", true, false, 20,
            "Hasta 20 caracteres. La lista es una sugerencia: se acepta otra unidad.", "ml");
    private static final ColumnaImportacion STOCK_INICIAL = new ColumnaImportacion("stock", "Stock inicial", false, false, 16,
            "Número mayor o igual a 0, con hasta 2 decimales (usa coma o punto). Vacío = 0.", "250.50");

    private final InsumoService insumoService;
    private final InsumoRepository insumoRepository;

    public ImportacionInsumos(InsumoService insumoService, InsumoRepository insumoRepository) {
        this.insumoService = insumoService;
        this.insumoRepository = insumoRepository;
    }

    @Override public String codigo() { return CODIGO; }
    @Override public String titulo() { return "Insumos"; }
    @Override public int version() { return 1; }

    @Override
    public List<ColumnaImportacion> columnas() {
        return List.of(NOMBRE, UNIDAD, STOCK_INICIAL);
    }

    @Override
    public List<String> instrucciones() {
        return List.of(
                "INSUMOS",
                "• El stock inicial queda registrado como «Saldo inicial» en el kardex del insumo, igual que al crearlo desde el panel.",
                "• Esta importación no cambia el stock de insumos que ya existen: si el nombre ya está registrado (aunque el insumo esté desactivado), "
                        + "la fila se omite. Para reponer o corregir stock usa Inventario → Insumos.",
                "• Si no pones stock inicial, el insumo se crea con 0 y sin movimiento en el kardex."
        );
    }

    @Override
    public Map<String, List<String>> listasDesplegables() {
        return Map.of(UNIDAD.clave(), List.of("kg", "g", "L", "ml", "unidades", "sacos", "bolsas", "cajas", "frascos"));
    }

    @Override
    public Set<String> listasAbiertas() {
        return Set.of(UNIDAD.clave());
    }

    @Override
    public Set<String> preparar(List<FilaCruda> filas) {
        return nombresExistentes();
    }

    private Set<String> nombresExistentes() {
        Set<String> nombres = new HashSet<>();
        for (String nombre : insumoRepository.todosLosNombres()) {
            nombres.add(BusquedaTexto.clave(nombre));
        }
        return nombres;
    }

    @Override
    public ResultadoFila validar(FilaCruda fila, Set<String> existentes) {
        List<String> errores = new ArrayList<>();
        String nombre = ReglasCelda.textoObligatorio(fila, NOMBRE, 60, errores);
        String unidad = ReglasCelda.textoObligatorio(fila, UNIDAD, 20, errores);
        String stock = leerStock(fila, errores);
        if (!errores.isEmpty()) {
            return ResultadoFila.invalida(ReglasCelda.crudos(fila, columnas()), errores);
        }
        Map<String, String> valores = new LinkedHashMap<>();
        valores.put("nombre", nombre);
        valores.put("unidad", unidad);
        valores.put("stock", stock);
        if (existentes.contains(BusquedaTexto.clave(nombre))) {
            return ResultadoFila.duplicada(valores, "Ya existe un insumo llamado «" + nombre + "» (activo o desactivado). No se modifica su stock.");
        }
        return ResultadoFila.valida(valores);
    }

    private String leerStock(FilaCruda fila, List<String> errores) {
        String texto = ReglasCelda.texto(fila, STOCK_INICIAL, errores);
        if (texto == null || texto.isEmpty()) {
            return "0.00";
        }
        String compacto = texto.replace(" ", "");
        if (compacto.contains(",") && compacto.contains(".")) {
            errores.add("Stock inicial: «" + texto + "» tiene coma y punto; escribe solo un separador decimal (por ejemplo 1250.50).");
            return "0.00";
        }
        String normalizado = compacto.replace(',', '.');
        if (!STOCK.matcher(normalizado).matches()) {
            errores.add("Stock inicial: «" + texto + "» no es válido; debe ser un número mayor o igual a 0 con hasta 2 decimales.");
            return "0.00";
        }
        return new BigDecimal(normalizado).setScale(2).toPlainString();
    }

    @Override
    public String claveUnica(Map<String, String> valores) {
        return BusquedaTexto.clave(valores.get("nombre"));
    }

    @Override
    public Optional<String> conflictoAlConfirmar(Map<String, String> valores) {
        if (nombresExistentes().contains(BusquedaTexto.clave(valores.get("nombre")))) {
            return Optional.of("Ya existe un insumo llamado «" + valores.get("nombre") + "» (se registró después de la vista previa). No se modifica su stock.");
        }
        return Optional.empty();
    }

    @Override
    public void crear(Map<String, String> v) {
        insumoService.crear(new InsumoRequest(v.get("nombre"), v.get("unidad"), new BigDecimal(v.get("stock"))));
    }
}
