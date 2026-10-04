package com.smartcoin.shared.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Corta el arranque, antes de crear nada, si falta alguna variable de entorno obligatoria.
 * Sin esto, Spring deja el texto {@code ${DB_URL}} como valor y el error llega mucho más tarde y confuso.
 */
public class RequiredConfigurationCheck implements EnvironmentPostProcessor {

	/** Propiedad de Spring → variable de entorno que la alimenta. */
	static final Map<String, String> REQUIRED = new LinkedHashMap<>();

	static {
		REQUIRED.put("spring.datasource.url", "DB_URL");
		REQUIRED.put("spring.datasource.username", "DB_USER");
		REQUIRED.put("spring.datasource.password", "DB_PASSWORD");
		REQUIRED.put("app.security.jwt-secret", "APP_JWT_SECRET");
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		// Los tests de capa web no tienen base ni variables: lo desactivan en src/test/resources/application.properties.
		if (!environment.getProperty("app.required-config-check", Boolean.class, true)) {
			return;
		}
		List<String> missing = new ArrayList<>();
		REQUIRED.forEach((property, variable) -> {
			try {
				environment.getProperty(property);
			}
			catch (IllegalArgumentException e) {
				missing.add(variable);
			}
		});
		if (!missing.isEmpty()) {
			throw new MissingConfigurationException(missing);
		}
	}
}
