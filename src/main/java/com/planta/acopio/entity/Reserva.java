package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 4 (V23). Una reserva compromete unidades (sin reducir el stock fisico, sin venta ni kardex). Las fechas y horas
// se guardan en hora de Lima. Las lineas viven en ReservaDetalle y el historial en ReservaEvento (append-only).
@Entity
@Table(name = "reserva")
public class Reserva {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 12, updatable = false)
    private String codigo;

    @Column(name = "uuid_cliente", nullable = false, unique = true, length = 36, updatable = false)
    private String uuidCliente;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private EstadoReserva estado = EstadoReserva.PENDIENTE;

    @Column(name = "nombre_cliente", nullable = false, length = 80)
    private String nombreCliente;

    @Column(nullable = false, length = 13)
    private String telefono;

    @Column(length = 120)
    private String correo;

    @Column(name = "nota_cliente", length = 300)
    private String notaCliente;

    @Column(name = "fecha_retiro", nullable = false)
    private LocalDate fechaRetiro;

    @Column(name = "fecha_maxima_retiro", nullable = false, updatable = false)
    private LocalDate fechaMaximaRetiro;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn;

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;

    @Column(precision = 10, scale = 2)
    private BigDecimal total;

    @Column(name = "requiere_aceptacion", nullable = false)
    private boolean requiereAceptacion;

    @Column(name = "total_aceptado", precision = 10, scale = 2)
    private BigDecimal totalAceptado;

    @Column(name = "aceptado_en")
    private LocalDateTime aceptadoEn;

    @Column(name = "aceptado_por")
    private UUID aceptadoPor;

    @Column(name = "aceptado_medio", length = 20)
    private String aceptadoMedio;

    @Column(name = "aceptado_nota", length = 300)
    private String aceptadoNota;

    @Column(name = "venta_id")
    private UUID ventaId;

    public Reserva() {}

    /**
     * Estado vigente AHORA. PENDIENTE pasa a VENCIDA al llegar su expira_en; CONFIRMADA, al terminar el dia de su
     * fecha de retiro. Es un calculo (sin proceso programado): una reserva vencida no compromete stock aunque nadie
     * la haya consultado, porque las consultas de compromiso aplican el mismo criterio.
     */
    public EstadoReserva estadoEfectivo(LocalDateTime ahora, LocalDate hoy) {
        if (estado == EstadoReserva.PENDIENTE && !ahora.isBefore(expiraEn)) return EstadoReserva.VENCIDA;
        if (estado == EstadoReserva.CONFIRMADA && fechaRetiro.isBefore(hoy)) return EstadoReserva.VENCIDA;
        return estado;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public EstadoReserva getEstado() { return estado; }
    public void setEstado(EstadoReserva estado) { this.estado = estado; }
    public String getNombreCliente() { return nombreCliente; }
    public void setNombreCliente(String nombreCliente) { this.nombreCliente = nombreCliente; }
    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
    public String getCorreo() { return correo; }
    public void setCorreo(String correo) { this.correo = correo; }
    public String getNotaCliente() { return notaCliente; }
    public void setNotaCliente(String notaCliente) { this.notaCliente = notaCliente; }
    public LocalDate getFechaRetiro() { return fechaRetiro; }
    public void setFechaRetiro(LocalDate fechaRetiro) { this.fechaRetiro = fechaRetiro; }
    public LocalDate getFechaMaximaRetiro() { return fechaMaximaRetiro; }
    public void setFechaMaximaRetiro(LocalDate fechaMaximaRetiro) { this.fechaMaximaRetiro = fechaMaximaRetiro; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
    public LocalDateTime getExpiraEn() { return expiraEn; }
    public void setExpiraEn(LocalDateTime expiraEn) { this.expiraEn = expiraEn; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public boolean isRequiereAceptacion() { return requiereAceptacion; }
    public void setRequiereAceptacion(boolean requiereAceptacion) { this.requiereAceptacion = requiereAceptacion; }
    public BigDecimal getTotalAceptado() { return totalAceptado; }
    public void setTotalAceptado(BigDecimal totalAceptado) { this.totalAceptado = totalAceptado; }
    public LocalDateTime getAceptadoEn() { return aceptadoEn; }
    public void setAceptadoEn(LocalDateTime aceptadoEn) { this.aceptadoEn = aceptadoEn; }
    public UUID getAceptadoPor() { return aceptadoPor; }
    public void setAceptadoPor(UUID aceptadoPor) { this.aceptadoPor = aceptadoPor; }
    public String getAceptadoMedio() { return aceptadoMedio; }
    public void setAceptadoMedio(String aceptadoMedio) { this.aceptadoMedio = aceptadoMedio; }
    public String getAceptadoNota() { return aceptadoNota; }
    public void setAceptadoNota(String aceptadoNota) { this.aceptadoNota = aceptadoNota; }
    public UUID getVentaId() { return ventaId; }
    public void setVentaId(UUID ventaId) { this.ventaId = ventaId; }
}
