package com.planta.acopio.config;

import com.planta.acopio.entity.Rol;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Bloque 9 (confirmado): el bootstrap del ADMIN inicial SOLO ocurre si ambas
// variables de entorno estan explicitamente configuradas -- nunca con
// valores por defecto, nunca de forma incondicional.
class DataInitializerTest {

    private final DataInitializer dataInitializer = new DataInitializer();
    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private final RolRepository rolRepository = mock(RolRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    @Test
    void noCreaNingunAdminSiNoSeConfiguraNingunaVariable() throws Exception {
        CommandLineRunner runner = dataInitializer.crearAdminInicial(
                usuarioRepository, rolRepository, passwordEncoder, "", "");

        runner.run();

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void noCreaAdminSiSoloSeConfiguroElEmail() throws Exception {
        CommandLineRunner runner = dataInitializer.crearAdminInicial(
                usuarioRepository, rolRepository, passwordEncoder, "admin@ecolacteos.pe", "");

        runner.run();

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void noCreaAdminSiLaPasswordEsMuyCorta() throws Exception {
        CommandLineRunner runner = dataInitializer.crearAdminInicial(
                usuarioRepository, rolRepository, passwordEncoder, "admin@ecolacteos.pe", "corta");

        runner.run();

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void noCreaUnSegundoAdminSiElEmailYaExiste() throws Exception {
        when(usuarioRepository.existsByEmail("admin@ecolacteos.pe")).thenReturn(true);

        CommandLineRunner runner = dataInitializer.crearAdminInicial(
                usuarioRepository, rolRepository, passwordEncoder, "admin@ecolacteos.pe", "Password123");

        runner.run();

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void creaElAdminCuandoAmbasVariablesEstanConfiguradasCorrectamente() throws Exception {
        when(usuarioRepository.existsByEmail("admin@ecolacteos.pe")).thenReturn(false);
        Rol rolAdmin = new Rol();
        rolAdmin.setNombre("ADMIN");
        when(rolRepository.findByNombre("ADMIN")).thenReturn(Optional.of(rolAdmin));
        when(passwordEncoder.encode("Password123")).thenReturn("hash-simulado");

        CommandLineRunner runner = dataInitializer.crearAdminInicial(
                usuarioRepository, rolRepository, passwordEncoder, "admin@ecolacteos.pe", "Password123");

        runner.run();

        verify(usuarioRepository).save(any());
    }
}
