package com.smartcoin.shared.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.user.domain.User;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

	final SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	final AppProperties properties = new AppProperties(ZoneId.of("America/Argentina/Mendoza"),
			new AppProperties.Budget(24, 10),
			new AppProperties.Security(SECRET, Duration.ofHours(8), "", 10));
	final TokenService service = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), properties,
			Clock.fixed(NOW, ZoneId.of("America/Argentina/Mendoza")));

	@Test
	void issuesHs256TokenWithSubjectVersionAndEightHourExpiry() {
		IssuedToken issued = service.issue(user(42, 5));

		Jwt jwt = decoder().decode(issued.value());
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(((Number) jwt.getClaim("cv")).intValue()).isEqualTo(5);
		assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
		assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
		assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
	}

	@Test
	void toStringDoesNotExposeTheToken() {
		IssuedToken issued = service.issue(user(1, 0));

		assertThat(issued.toString()).doesNotContain(issued.value());
	}

	private static User user(long id, int credentialsVersion) {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", id);
		user.setCredentialsVersion(credentialsVersion);
		return user;
	}

	/** Decodifica con el reloj fijo con el que se emitió el token: la prueba no depende del día en que se corre. */
	private NimbusJwtDecoder decoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator();
		timestamps.setClock(Clock.fixed(NOW, ZoneId.of("America/Argentina/Mendoza")));
		decoder.setJwtValidator(timestamps);
		return decoder;
	}
}
