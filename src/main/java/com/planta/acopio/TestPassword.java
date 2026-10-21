package com.planta.acopio;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class TestPassword {

    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String hash = "$2a$10$8Fy1iivCS3uo21AkFdJvruhTzYb1No57nQO6WSTXpwPCQuBo5AVz6";

        System.out.println(
                "RESULTADO: " + encoder.matches("Calidad123!", hash)
        );
    }
}