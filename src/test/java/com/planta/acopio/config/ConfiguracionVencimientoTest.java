package com.planta.acopio.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 1: la activacion del control de vencimiento debe quedar DESHABILITADA por defecto en todo entorno real
 * hasta que la Fase 2 este implementada y validada. Si alguien cambia el valor por defecto de application.yml,
 * esta prueba falla.
 */
class ConfiguracionVencimientoTest {

    @Test
    void laActivacionDelControlDeVencimientoEstaDeshabilitadaPorDefecto() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties propiedades = yaml.getObject();

        assertThat(propiedades).isNotNull();
        assertThat(propiedades.getProperty("app.vencimiento.activacion-habilitada"))
                .isEqualTo("${VENCIMIENTO_ACTIVACION_HABILITADA:false}");
    }
}
