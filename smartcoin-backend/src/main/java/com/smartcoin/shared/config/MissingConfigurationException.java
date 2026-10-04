package com.smartcoin.shared.config;

import java.util.List;

public class MissingConfigurationException extends RuntimeException {

	private final List<String> variables;

	public MissingConfigurationException(List<String> variables) {
		super("Faltan variables de entorno obligatorias: " + String.join(", ", variables));
		this.variables = List.copyOf(variables);
	}

	public List<String> variables() {
		return variables;
	}
}
