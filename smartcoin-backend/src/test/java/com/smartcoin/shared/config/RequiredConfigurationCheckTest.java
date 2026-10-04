package com.smartcoin.shared.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredConfigurationCheckTest {

	private final RequiredConfigurationCheck check = new RequiredConfigurationCheck();

	/** Entorno como el de application.yml: las propiedades apuntan a variables que pueden no existir. */
	private StandardEnvironment environment(Map<String, Object> variables) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
		environment.getPropertySources().addLast(new MapPropertySource("application", Map.of(
				"spring.datasource.url", "${DB_URL}",
				"spring.datasource.username", "${DB_USER}",
				"spring.datasource.password", "${DB_PASSWORD}",
				"app.security.jwt-secret", "${APP_JWT_SECRET}")));
		return environment;
	}

	@Test
	void passesWhenAllVariablesAreDefined() {
		var env = environment(Map.of("DB_URL", "jdbc:mysql://localhost/smartcoin", "DB_USER", "smartcoin",
				"DB_PASSWORD", "", "APP_JWT_SECRET", "0123456789abcdef0123456789abcdef"));
		assertThatCode(() -> check.postProcessEnvironment(env, new SpringApplication())).doesNotThrowAnyException();
	}

	@Test
	void reportsEveryMissingVariableByName() {
		var env = environment(Map.of("DB_USER", "smartcoin"));
		assertThatThrownBy(() -> check.postProcessEnvironment(env, new SpringApplication()))
				.isInstanceOfSatisfying(MissingConfigurationException.class,
						e -> assertThat(e.variables()).containsExactly("DB_URL", "DB_PASSWORD", "APP_JWT_SECRET"));
	}

	@Test
	void analyzerExplainsWhatIsMissing() {
		var cause = new MissingConfigurationException(java.util.List.of("APP_JWT_SECRET"));
		FailureAnalysis analysis = new MissingConfigurationFailureAnalyzer().analyze(cause);
		assertThat(analysis.getDescription()).contains("APP_JWT_SECRET");
		assertThat(analysis.getAction()).contains("application-local.yml");
	}
}
