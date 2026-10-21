package com.planta.acopio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // habilita los jobs del paquete scheduler/ (score, forecasting, prediccion)
public class AcopioApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcopioApplication.class, args);
    }
}
