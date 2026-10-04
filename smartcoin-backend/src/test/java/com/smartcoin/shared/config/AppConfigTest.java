package com.smartcoin.shared.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

	private static final String SECRET = "0123456789abcdef0123456789abcdef";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(EnableProperties.class, ClockConfig.class)
			.withPropertyValues(
					"app.zone=America/Argentina/Mendoza",
					"app.budget.horizon-months=24",
					"app.budget.early-days=10",
					"app.security.jwt-expiration=8h",
					"app.security.password-min-length=10");

	@Configuration
	@EnableConfigurationProperties(AppProperties.class)
	static class EnableProperties {
	}

	@Test
	void bindsProperties() {
		runner.withPropertyValues("app.security.jwt-secret=" + SECRET).run(context -> {
			assertThat(context).hasNotFailed();
			AppProperties properties = context.getBean(AppProperties.class);
			assertThat(properties.zone()).isEqualTo(ZoneId.of("America/Argentina/Mendoza"));
			assertThat(properties.budget().horizonMonths()).isEqualTo(24);
			assertThat(properties.budget().earlyDays()).isEqualTo(10);
			assertThat(properties.security().jwtExpiration()).isEqualTo(Duration.ofHours(8));
			assertThat(properties.security().adminKey()).isNull();
		});
	}

	@Test
	void clockUsesConfiguredZone() {
		runner.withPropertyValues("app.security.jwt-secret=" + SECRET).run(context -> {
			Clock clock = context.getBean(Clock.class);
			assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/Argentina/Mendoza"));
			// 2026-10-05T01:30Z son las 22:30 del 4 de octubre en Mendoza (UTC-3).
			Clock fixed = Clock.fixed(Instant.parse("2026-10-05T01:30:00Z"), clock.getZone());
			assertThat(LocalDate.now(fixed)).isEqualTo(LocalDate.of(2026, 10, 4));
		});
	}

	@Test
	void failsWithoutJwtSecret() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("jwtSecret");
		});
	}

	@Test
	void failsWithShortJwtSecret() {
		runner.withPropertyValues("app.security.jwt-secret=corto").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("APP_JWT_SECRET");
		});
	}
}
