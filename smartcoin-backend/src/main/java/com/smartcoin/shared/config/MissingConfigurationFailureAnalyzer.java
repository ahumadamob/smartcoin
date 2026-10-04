package com.smartcoin.shared.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

class MissingConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<MissingConfigurationException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, MissingConfigurationException cause) {
		return new FailureAnalysis(
				"Faltan variables de entorno obligatorias: " + String.join(", ", cause.variables()) + ".",
				"Definilas en el entorno o en smartcoin-backend/config/application-local.yml y arrancar con el perfil local "
						+ "(SPRING_PROFILES_ACTIVE=local). Ver smartcoin-backend/CLAUDE.md, sección Configuración.",
				cause);
	}
}
