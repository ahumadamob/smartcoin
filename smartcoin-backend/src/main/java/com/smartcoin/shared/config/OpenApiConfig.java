package com.smartcoin.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI smartcoinOpenApi() {
		return new OpenAPI().info(new Info()
				.title("Smartcoin API")
				.description("API REST de presupuesto personal con proyección a 24 meses.")
				.version("0.0.1"));
	}
}
