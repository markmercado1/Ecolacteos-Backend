package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 4 (V23): historial append-only de una reserva (quien, cuando y por que). Nunca se edita ni se borra.
@Entity
@Table(name = "reserva_evento")
public class ReservaEvento {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "reserva_id")
    private Reserva reserva;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private TipoEventoReserva tipo;

    // null = el propio cliente (creacion publica).
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(length = 300)
    private String motivo;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn;

    public ReservaEvento() {}

    public ReservaEvento(Reserva reserva, TipoEventoReserva tipo, Usuario usuario, String motivo, LocalDateTime creadoEn) {
        this.reserva = reserva;
        this.tipo = tipo;
        this.usuario = usuario;
        this.motivo = motivo;
        this.creadoEn = creadoEn;
    }

    public UUID getId() { return id; }
    public Reserva getReserva() { return reserva; }
    public TipoEventoReserva getTipo() { return tipo; }
    public Usuario getUsuario() { return usuario; }
    public String getMotivo() { return motivo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
