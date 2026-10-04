package com.smartcoin.shared.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import com.smartcoin.shared.error.GlobalExceptionHandler;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Sin controladores: la cadena de seguridad se prueba sola, y un 404 indica que pasó la autenticación.
@WebMvcTest(excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = RestController.class))
@Import({ SecurityConfig.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + SecurityConfigTest.SECRET)
class SecurityConfigTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";

	@Autowired
	MockMvc mvc;

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

	private static String token(String secret) {
		var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		var claims = JwtClaimsSet.builder().subject("1")
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}
}
