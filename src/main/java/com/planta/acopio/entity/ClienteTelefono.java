package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase H (V33): telefono normalizado (+519XXXXXXXX) de una ficha. No es unico entre fichas: solo sirve para sugerir.
@Entity
@Table(name = "cliente_telefono")
public class ClienteTelefono {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(nullable = false, length = 13)
    private String telefono;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public ClienteTelefono() {}

    public ClienteTelefono(UUID clienteId, String telefono) {
        this.clienteId = clienteId;
        this.telefono = telefono;
    }

    public UUID getId() { return id; }
    public UUID getClienteId() { return clienteId; }
    public void setClienteId(UUID clienteId) { this.clienteId = clienteId; }
    public String getTelefono() { return telefono; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
