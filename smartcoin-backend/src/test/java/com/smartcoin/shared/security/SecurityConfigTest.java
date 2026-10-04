package com.smartcoin.shared.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Sin controladores: la cadena de seguridad se prueba sola, y un 404 indica que pasó la autenticación.
@WebMvcTest(excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = RestController.class))
@Import({ SecurityConfig.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + SecurityConfigTest.SECRET)
class SecurityConfigTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final int VERSION = 3;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	UserRepository users;

	@BeforeEach
	void existingEnabledUser() {
		when(users.findById(USER_ID)).thenReturn(Optional.of(user(true, VERSION)));
	}

	@Test
	void requestWithoutTokenIsUnauthorizedProblem() throws Exception {
		mvc.perform(get("/api/accounts"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void invalidTokenIsUnauthorizedProblem() throws Exception {
		mvc.perform(get("/api/accounts").header("Authorization", "Bearer no.es.un.jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void tokenSignedWithAnotherSecretIsUnauthorized() throws Exception {
		mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + token("otro-secreto-de-32-caracteres-xx")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void validTokenPassesAuthentication() throws Exception {
		// No hay controladores todavía: pasar la autenticación se nota en que la respuesta es 404 y no 401.
		mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + token(SECRET)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void swaggerAndApiDocsDoNotRequireAuthentication() throws Exception {
		// springdoc no está en este slice: 404 (y no 401) prueba que la seguridad deja pasar la ruta.
		mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
		mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
	}

	@Test
	void expiredTokenIsUnauthorized() throws Exception {
		Instant now = Instant.now();
		String expired = token(SECRET, String.valueOf(USER_ID), VERSION, now.minusSeconds(7200), now.minusSeconds(3600));

		mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void tokenWithOldCredentialsVersionIsUnauthorized() throws Exception {
		// La contraseña se cambió o restableció después de emitir el token (RN-50).
		String old = token(SECRET, String.valueOf(USER_ID), VERSION - 1);

		mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + old))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void tokenWithoutCredentialsVersionIsUnauthorized() throws Exception {
		String noVersion = token(SECRET, String.valueOf(USER_ID), null);

		mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + noVersion))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void tokenOfDisabledUserIsUnauthorized() throws Exception {
		when(users.findById(USER_ID)).thenReturn(Optional.of(user(false, VERSION)));

		mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + token(SECRET)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void tokenOfMissingUserIsUnauthorized() throws Exception {
		when(users.findById(USER_ID)).thenReturn(Optional.empty());

		mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + token(SECRET)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void tokenWhoseSubjectIsNotAnIdIsUnauthorized() throws Exception {
		String token = token(SECRET, "no-es-un-id", VERSION);

		mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	private static User user(boolean enabled, int credentialsVersion) {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		user.setEnabled(enabled);
		user.setCredentialsVersion(credentialsVersion);
		return user;
	}

	private static String token(String secret) {
		return token(secret, String.valueOf(USER_ID), VERSION);
	}

	private static String token(String secret, String subject, Integer credentialsVersion) {
		return token(secret, subject, credentialsVersion, Instant.now(), Instant.now().plusSeconds(60));
	}

	private static String token(String secret, String subject, Integer credentialsVersion, Instant issuedAt,
			Instant expiresAt) {
		var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		var claims = JwtClaimsSet.builder().subject(subject).issuedAt(issuedAt).expiresAt(expiresAt);
		if (credentialsVersion != null) {
			claims.claim("cv", credentialsVersion);
		}
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
				.getTokenValue();
	}
}
