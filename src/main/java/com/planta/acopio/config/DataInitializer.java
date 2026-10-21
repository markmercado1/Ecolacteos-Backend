package com.planta.acopio.config;

import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.RolRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner crearAdminInicial(
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            PasswordEncoder passwordEncoder
    ) {
        return args -> {

            String email = "admin@planta.com";
            String password = "Admin1234";

            // Solo evita crear ESTE usuario nuevamente.
            // Los demás usuarios existentes no se modifican.
            if (usuarioRepository.existsByEmail(email)) {
                return;
            }

            Rol rolAdmin = rolRepository.findByNombre("ADMIN")
                    .orElseThrow(() -> new IllegalStateException("No existe el rol ADMIN"));

            Usuario admin = new Usuario();
            admin.setNombre("Administrador");
            admin.setEmail(email);
            admin.setPasswordHash(passwordEncoder.encode(password));
            admin.setRol(rolAdmin);
            admin.setActivo(true);

            usuarioRepository.save(admin);

            System.out.println("Nuevo ADMIN creado: " + email);
        };
    }
}