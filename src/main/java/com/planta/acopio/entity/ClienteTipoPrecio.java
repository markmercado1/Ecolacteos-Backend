package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

// Habilitacion de un cliente para un tipo de precio con regla CLIENTE_HABILITADO (V39): quien, cuando y por que. Unica fuente de verdad.
@Entity
@Table(name = "cliente_tipo_precio")
@IdClass(ClienteTipoPrecio.Clave.class)
public class ClienteTipoPrecio {

    @Id
    @Column(name = "cliente_id")
    private UUID clienteId;

    @Id
    @Column(name = "tipo_codigo", length = 15)
    private String tipoCodigo;

    @Column(name = "habilitado_por")
    private UUID habilitadoPor;

    @Column(name = "habilitado_en", nullable = false)
    private LocalDateTime habilitadoEn = LocalDateTime.now();

    @Column(nullable = false, length = 300)
    private String motivo;

    public ClienteTipoPrecio() {}

    public ClienteTipoPrecio(UUID clienteId, String tipoCodigo, UUID habilitadoPor, LocalDateTime habilitadoEn, String motivo) {
        this.clienteId = clienteId;
        this.tipoCodigo = tipoCodigo;
        this.habilitadoPor = habilitadoPor;
        this.habilitadoEn = habilitadoEn;
        this.motivo = motivo;
    }

    public UUID getClienteId() { return clienteId; }
    public String getTipoCodigo() { return tipoCodigo; }
    public UUID getHabilitadoPor() { return habilitadoPor; }
    public LocalDateTime getHabilitadoEn() { return habilitadoEn; }
    public String getMotivo() { return motivo; }

    public static class Clave implements Serializable {
        private UUID clienteId;
        private String tipoCodigo;
        public Clave() {}
        public Clave(UUID clienteId, String tipoCodigo) { this.clienteId = clienteId; this.tipoCodigo = tipoCodigo; }
        @Override public boolean equals(Object o) {
            return o instanceof Clave c && Objects.equals(clienteId, c.clienteId) && Objects.equals(tipoCodigo, c.tipoCodigo);
        }
        @Override public int hashCode() { return Objects.hash(clienteId, tipoCodigo); }
    }
}
