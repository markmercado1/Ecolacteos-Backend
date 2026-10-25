package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase H (V33): constancia de una fusion MANUAL de dos fichas de cliente (nunca automatica).
@Entity
@Table(name = "cliente_fusion")
public class ClienteFusion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "origen_id", nullable = false)
    private UUID origenId;

    @Column(name = "destino_id", nullable = false)
    private UUID destinoId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String motivo;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "ventas_movidas", nullable = false)
    private int ventasMovidas;

    @Column(name = "reservas_movidas", nullable = false)
    private int reservasMovidas;

    @Column(name = "telefonos_agregados", nullable = false)
    private int telefonosAgregados;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public ClienteFusion() {}

    public ClienteFusion(UUID origenId, UUID destinoId, String motivo, UUID usuarioId, int ventas, int reservas, int telefonos) {
        this.origenId = origenId;
        this.destinoId = destinoId;
        this.motivo = motivo;
        this.usuarioId = usuarioId;
        this.ventasMovidas = ventas;
        this.reservasMovidas = reservas;
        this.telefonosAgregados = telefonos;
    }

    public UUID getId() { return id; }
    public UUID getOrigenId() { return origenId; }
    public UUID getDestinoId() { return destinoId; }
    public String getMotivo() { return motivo; }
    public UUID getUsuarioId() { return usuarioId; }
    public int getVentasMovidas() { return ventasMovidas; }
    public int getReservasMovidas() { return reservasMovidas; }
    public int getTelefonosAgregados() { return telefonosAgregados; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
