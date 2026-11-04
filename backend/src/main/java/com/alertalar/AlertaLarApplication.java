package com.alertalar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Ponto de entrada do AlertaLar - sistema de consulta e análise de riscos de
 * enchentes e alagamentos.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AlertaLarApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertaLarApplication.class, args);
    }
}
