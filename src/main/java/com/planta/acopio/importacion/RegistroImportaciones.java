package com.planta.acopio.importacion;

import com.planta.acopio.exception.RecursoNoEncontradoException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Los tipos de importacion disponibles. Agregar uno nuevo (precios, productos...) es crear un bean TipoImportacion. */
@Component
public class RegistroImportaciones {

    private final Map<String, TipoImportacion<?>> porCodigo = new LinkedHashMap<>();

    public RegistroImportaciones(List<TipoImportacion<?>> tipos) {
        for (TipoImportacion<?> tipo : tipos) {
            if (porCodigo.putIfAbsent(tipo.codigo(), tipo) != null) {
                throw new IllegalStateException("Tipo de importación repetido: " + tipo.codigo());
            }
        }
    }

    public TipoImportacion<?> obtener(String codigo) {
        TipoImportacion<?> tipo = codigo == null ? null : porCodigo.get(codigo.trim().toLowerCase());
        if (tipo == null) {
            throw new RecursoNoEncontradoException("No existe una importación de tipo '" + codigo + "'. Disponibles: " + String.join(", ", porCodigo.keySet()) + ".");
        }
        return tipo;
    }
}
