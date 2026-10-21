package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// mejora #3 (CORE_DESIGN.md §6): zonas_destino (TEXT libre) reemplazado por
// relacion real N:M con Zona via comunicado_zona.
@Entity
@Table(name = "comunicado")
public class Comunicado {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String mensaje;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @ManyToMany
    @JoinTable(
        name = "comunicado_zona",
        joinColumns = @JoinColumn(name = "comunicado_id"),
        inverseJoinColumns = @JoinColumn(name = "zona_id")
    )
    private Set<Zona> zonasDestino = new HashSet<>();

    public Comunicado() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getMensaje() { return mensaje; }
    public void setMensaje(String mensaje) { this.mensaje = mensaje; }
    public LocalDateTime getFecha() { return fecha; }
    public void setFecha(LocalDateTime fecha) { this.fecha = fecha; }
    public Set<Zona> getZonasDestino() { return zonasDestino; }
    public void setZonasDestino(Set<Zona> zonasDestino) { this.zonasDestino = zonasDestino; }
}
