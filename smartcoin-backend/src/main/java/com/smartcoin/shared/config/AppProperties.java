package com.smartcoin.shared.config;

import java.time.Duration;
import java.time.ZoneId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(
		@NotNull ZoneId zone,
		@NotNull @Valid Budget budget,
		@NotNull @Valid Security security) {

	public record Budget(
			@Min(1) int horizonMonths,
			@Min(0) int earlyDays) {
	}

	public record Security(
			@NotBlank @Size(min = 32, message = "APP_JWT_SECRET debe tener 32 caracteres o más") String jwtSecret,
			@NotNull Duration jwtExpiration,
			String adminKey,
			@Min(1) int passwordMinLength) {
	}
}
