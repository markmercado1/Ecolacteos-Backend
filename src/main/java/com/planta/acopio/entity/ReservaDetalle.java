package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Fase 4 (V23): una linea de reserva. precioUnitario null = "Precio a confirmar".
@Entity
@Table(name = "reserva_detalle")
public class ReservaDetalle {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "reserva_id")
    private Reserva reserva;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @Column(nullable = false)
    private Integer cantidad;

    @Column(name = "precio_unitario", precision = 8, scale = 2)
    private BigDecimal precioUnitario;

    public ReservaDetalle() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Reserva getReserva() { return reserva; }
    public void setReserva(Reserva reserva) { this.reserva = reserva; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(BigDecimal precioUnitario) { this.precioUnitario = precioUnitario; }
}
