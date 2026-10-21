package com.planta.acopio.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

// Bloque 10 (confirmado): proteccion basica contra intentos masivos de login,
// EN MEMORIA -- sin agregar Bucket4j/Redis/infraestructura nueva (el tamano
// actual del proyecto, una sola instancia, no lo justifica). LIMITACION
// DOCUMENTADA: si el backend llega a correr en mas de una instancia, este
// contador deja de ser efectivo (es por-instancia, no un estado compartido
// global) -- eso requeriria revisarse aparte si el despliegue cambia.
@Component
public class LoginAttemptService {

    private final int maxIntentos;
    private final long ventanaBloqueoMinutos;
    private final ConcurrentHashMap<String, Intentos> intentosPorEmail = new ConcurrentHashMap<>();

    public LoginAttemptService(
            @Value("${app.security.login.max-intentos:5}") int maxIntentos,
            @Value("${app.security.login.ventana-bloqueo-minutos:15}") long ventanaBloqueoMinutos) {
        this.maxIntentos = maxIntentos;
        this.ventanaBloqueoMinutos = ventanaBloqueoMinutos;
    }

    public void registrarFallo(String email) {
        String clave = email.toLowerCase();
        intentosPorEmail.compute(clave, (k, actual) -> {
            if (actual == null || haExpirado(actual)) {
                return new Intentos(1, Instant.now());
            }
            return new Intentos(actual.cantidad() + 1, actual.desde());
        });
    }

    // Login exitoso resetea el contador -- un usuario que se equivoco un par
    // de veces y despues entro bien no debe quedar "a mitad de camino" hacia
    // el bloqueo la proxima vez que se equivoque.
    public void registrarExito(String email) {
        intentosPorEmail.remove(email.toLowerCase());
    }

    public boolean estaBloqueado(String email) {
        Intentos actual = intentosPorEmail.get(email.toLowerCase());
        return actual != null && !haExpirado(actual) && actual.cantidad() >= maxIntentos;
    }

    public long minutosRestantesDeBloqueo(String email) {
        Intentos actual = intentosPorEmail.get(email.toLowerCase());
        if (actual == null) {
            return 0;
        }
        long transcurridos = Duration.between(actual.desde(), Instant.now()).toMinutes();
        return Math.max(0, ventanaBloqueoMinutos - transcurridos);
    }

    private boolean haExpirado(Intentos intentos) {
        return Instant.now().isAfter(intentos.desde().plusSeconds(ventanaBloqueoMinutos * 60));
    }

    private record Intentos(int cantidad, Instant desde) {}
}
