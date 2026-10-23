package com.planta.acopio.service;

import com.planta.acopio.entity.CorreccionRegistro;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.repository.CorreccionRegistroRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Fase B: litros EFECTIVOS de una entrega = los de la ultima correccion valida o, si no tiene, los originales. Se DERIVAN: el
// registro original nunca se sobrescribe (RNF-03/05) y la cantidad solo cambia con una correccion justificada y registrada.
// Una correccion es valida si tiene motivo (las anteriores a la Fase B podian no tenerlo: se conservan y se ven, pero no
// se aplican al pago hasta que otra correccion justificada las reemplace).
@Service
public class LitrosEfectivos {

    public record Efectivo(BigDecimal litros, CorreccionRegistro correccion) {}

    private final CorreccionRegistroRepository correccionRepository;

    public LitrosEfectivos(CorreccionRegistroRepository correccionRepository) {
        this.correccionRepository = correccionRepository;
    }

    public static boolean esValida(CorreccionRegistro correccion) {
        return correccion.getMotivo() != null && !correccion.getMotivo().isBlank();
    }

    // Varias entregas con UNA sola consulta (el pago de una semana liquida muchas).
    public Map<UUID, Efectivo> deRegistros(Collection<RegistroAcopio> registros) {
        Map<UUID, CorreccionRegistro> ultimaValida = new HashMap<>();
        if (!registros.isEmpty()) {
            List<CorreccionRegistro> correcciones = correccionRepository.findByRegistroAcopioIdInOrderByCreadoEnAscIdAsc(
                    registros.stream().map(RegistroAcopio::getId).toList());
            for (CorreccionRegistro c : correcciones) { // cronologico: la ultima valida pisa a las anteriores
                if (esValida(c)) {
                    ultimaValida.put(c.getRegistroAcopio().getId(), c);
                }
            }
        }
        Map<UUID, Efectivo> resultado = new HashMap<>();
        for (RegistroAcopio r : registros) {
            CorreccionRegistro c = ultimaValida.get(r.getId());
            resultado.put(r.getId(), c != null ? new Efectivo(c.getLitrosCorregido(), c) : new Efectivo(r.getLitros(), null));
        }
        return resultado;
    }

    public Efectivo deRegistro(RegistroAcopio registro) {
        return deRegistros(List.of(registro)).get(registro.getId());
    }
}
