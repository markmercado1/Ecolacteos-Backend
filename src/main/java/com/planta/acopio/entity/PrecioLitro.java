package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "precio_litro")
public class PrecioLitro {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal precio;

    @Column(name = "vigente_desde", nullable = false)
    private LocalDate vigenteDesde;

    public PrecioLitro() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public BigDecimal getPrecio() { return precio; }
    public void setPrecio(BigDecimal precio) { this.precio = precio; }
    public LocalDate getVigenteDesde() { return vigenteDesde; }
    public void setVigenteDesde(LocalDate vigenteDesde) { this.vigenteDesde = vigenteDesde; }
}
