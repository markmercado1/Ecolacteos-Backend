package com.planta.acopio.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Base de datos PostgreSQL para pruebas de integracion, para las pruebas de Excel.
 *
 * - Por defecto levanta un contenedor (Testcontainers), igual que las demas pruebas de integracion.
 * - Si existe la variable de entorno ECO_TEST_PG_ADMIN_URL (p. ej. jdbc:postgresql://127.0.0.1:55432/postgres, con
 *   ECO_TEST_PG_USER y ECO_TEST_PG_PASSWORD opcionales), usa ESE servidor en vez de Docker: crea una base nueva por
 *   clase de prueba, de modo que el esquema (Flyway) y los datos no se mezclan entre clases. Sirve en equipos sin
 *   Docker, con un PostgreSQL temporal.
 */
final class PostgresDePrueba {

    private static final String ADMIN_URL = System.getenv("ECO_TEST_PG_ADMIN_URL");
    private static final String USUARIO = System.getenv().getOrDefault("ECO_TEST_PG_USER", "postgres");
    private static final String CLAVE = System.getenv().getOrDefault("ECO_TEST_PG_PASSWORD", "");

    private PostgresDePrueba() {
    }

    /** Mantiene el ciclo de vida Testcontainers y permite el mismo PostgreSQL temporal que las pruebas nuevas. */
    static PostgreSQLContainer<?> contenedor() {
        if (ADMIN_URL == null || ADMIN_URL.isBlank()) return new PostgreSQLContainer<>("postgres:15-alpine");
        return new Temporal();
    }

    private static final class Temporal extends PostgreSQLContainer<Temporal> {
        private String[] conexion;
        private Temporal() { super("postgres:15-alpine"); }
        @Override public void start() { if (conexion == null) conexion = baseNueva(); }
        @Override public void stop() { /* El ejecutor elimina el servidor temporal completo al terminar. */ }
        @Override public String getJdbcUrl() { start(); return conexion[0]; }
        @Override public String getUsername() { start(); return conexion[1]; }
        @Override public String getPassword() { start(); return conexion[2]; }
    }

    /** Contenedor compartido por todas las clases que lo piden; solo se crea si hace falta. */
    private static final class Contenedor {
        static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("acopio_lechero_test")
                .withUsername("acopio")
                .withPassword("acopio");

        static {
            POSTGRES.start();
        }
    }

    /** [url, usuario, clave] de una base NUEVA y vacia (sin esquema), para pruebas de migraciones que aplican Flyway a mano. */
    static String[] baseNueva() {
        if (ADMIN_URL != null && !ADMIN_URL.isBlank()) {
            String base = crearBase();
            return new String[]{ADMIN_URL.replaceFirst("/[^/?]*(\\?.*)?$", "/" + base), USUARIO, CLAVE};
        }
        return new String[]{Contenedor.POSTGRES.getJdbcUrl(), Contenedor.POSTGRES.getUsername(), Contenedor.POSTGRES.getPassword()};
    }

    private static String crearBase() {
        String base = "eco_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USUARIO, CLAVE); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + base);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear la base de pruebas en " + ADMIN_URL, e);
        }
        return base;
    }

    static void registrar(DynamicPropertyRegistry registry) {
        if (ADMIN_URL != null && !ADMIN_URL.isBlank()) {
            String base = crearBase();
            String url = ADMIN_URL.replaceFirst("/[^/?]*(\\?.*)?$", "/" + base);
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> USUARIO);
            registry.add("spring.datasource.password", () -> CLAVE);
        } else {
            registry.add("spring.datasource.url", Contenedor.POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", Contenedor.POSTGRES::getUsername);
            registry.add("spring.datasource.password", Contenedor.POSTGRES::getPassword);
        }
    }
}
