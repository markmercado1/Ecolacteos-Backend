package com.planta.acopio.service;

import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.MovimientoInventarioLeche;
import com.planta.acopio.entity.RecepcionPlanta;
import com.planta.acopio.entity.TipoMovimientoInventarioLeche;
import com.planta.acopio.repository.MovimientoInventarioLecheRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

// Libro mayor de leche (V8__inventario_leche.sql), mismo criterio que InventarioService (queso, V7): el
// saldo nunca se escribe directamente por un endpoint, solo cambia como efecto de una recepcion valida (esta
// fase) o, mas adelante, de una produccion valida que la consuma. Por eso registrarEntradaRecepcion() NO
// declara @Transactional propio -- debe ejecutarse siempre dentro de la transaccion ya abierta por quien la
// invoca (ConciliacionService), para que "no puede existir una recepcion sin su entrada de leche" sea una
// garantia real del mismo commit/rollback, no una ilusion de dos transacciones separadas.
@Service
public class InventarioLecheService {

    private final MovimientoInventarioLecheRepository repository;

    public InventarioLecheService(MovimientoInventarioLecheRepository repository) {
        this.repository = repository;
    }

    // litrosPlanta es el campo autoritativo de leche efectivamente recibida (ver ConciliacionService) --
    // litrosCampo nunca alimenta este libro mayor, sea cual sea su origen (digitado a mano o SUM de jornada).
    public void registrarEntradaRecepcion(RecepcionPlanta recepcion) {
        MovimientoInventarioLeche movimiento = new MovimientoInventarioLeche();
        movimiento.setTipoMovimiento(TipoMovimientoInventarioLeche.ENTRADA_LECHE_RECEPCION);
        movimiento.setCantidad(recepcion.getLitrosPlanta());
        movimiento.setRecepcionPlanta(recepcion);
        repository.save(movimiento);
    }

    // Lectura simple, sin bloqueo: informativa (para mostrar "leche disponible" en pantalla). La validacion
    // autoritativa de saldo suficiente para producir usa su propio bloqueo pesimista, mas fino -- ver
    // LoteProduccionService.planificarConsumo() (bloquea las RecepcionPlanta candidatas, no esta suma global).
    public BigDecimal obtenerSaldoDisponibleLitros() {
        return repository.sumarLitrosDisponibles();
    }

    // Refactor de Produccion, fase 2: salida AGREGADA del total de leche consumido por un lote, en una sola
    // fila (uk_movimiento_inventario_leche_lote de V8 garantiza como maximo una por lote). El desglose de
    // DE QUE recepciones salio esa leche vive aparte, en LoteProduccionRecepcionConsumo -- este libro mayor
    // solo necesita el total para que el saldo (SUM) sea correcto, no el detalle FIFO.
    public void registrarSalidaProduccion(LoteProduccion lote, BigDecimal litrosUsados) {
        MovimientoInventarioLeche movimiento = new MovimientoInventarioLeche();
        movimiento.setTipoMovimiento(TipoMovimientoInventarioLeche.SALIDA_LECHE_PRODUCCION);
        movimiento.setCantidad(litrosUsados.negate());
        movimiento.setLoteProduccion(lote);
        repository.save(movimiento);
    }
}
