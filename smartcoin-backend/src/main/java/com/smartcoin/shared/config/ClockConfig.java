package com.smartcoin.shared.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class ClockConfig {

	/** Reloj de la aplicación con la zona de negocio: "hoy" siempre sale de acá. */
	@Bean
	Clock clock(AppProperties properties) {
		return Clock.system(properties.zone());
	}
}
