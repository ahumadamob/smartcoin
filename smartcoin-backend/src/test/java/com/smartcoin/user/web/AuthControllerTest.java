package com.smartcoin.user.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.shared.security.CurrentUser;
import com.smartcoin.shared.security.IssuedToken;
import com.smartcoin.shared.security.SecurityConfig;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;
import com.smartcoin.user.service.AuthService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + AuthControllerTest.SECRET)
class AuthControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final int VERSION = 3;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AuthService auth;

	@MockitoBean
	UserRepository users;

	@BeforeEach
	void existingEnabledUser() {
		when(users.findById(USER_ID)).thenReturn(Optional.of(user(true, VERSION, false)));
	}

	private ResultActions login(String body) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String credentials(String email, String password) {
		return "{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}";
	}

	@Test
	void validLoginRespondsWithTokenExpiryAndPendingChangeFlag() throws Exception {
		Instant expiresAt = Instant.parse("2026-10-05T20:00:00Z");
		when(auth.login("persona@ejemplo.com", "secreta-123")).thenReturn(
				new AuthService.Login(new IssuedToken("el.token.jwt", expiresAt), true));

		login(credentials("persona@ejemplo.com", "secreta-123"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").value("el.token.jwt"))
				.andExpect(jsonPath("$.expiresAt").value("2026-10-05T20:00:00Z"))
				.andExpect(jsonPath("$.mustChangePassword").value(true));
	}

	@Test
	void everyFailedLoginHasTheSameUnauthorizedBody() throws Exception {
		// El servicio lanza lo mismo en los tres casos; acá se verifica que el cuerpo sale idéntico.
		when(auth.login("nadie@ejemplo.com", "secreta-123"))
				.thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "Email o contraseña incorrectos."));
		when(auth.login("persona@ejemplo.com", "incorrecta"))
				.thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "Email o contraseña incorrectos."));
		when(auth.login("baja@ejemplo.com", "secreta-123"))
				.thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "Email o contraseña incorrectos."));

		String unknown = login(credentials("nadie@ejemplo.com", "secreta-123"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.detail").value("Email o contraseña incorrectos."))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		String wrongPassword = login(credentials("persona@ejemplo.com", "incorrecta"))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		String disabled = login(credentials("baja@ejemplo.com", "secreta-123"))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(wrongPassword).isEqualTo(unknown);
		assertThat(disabled).isEqualTo(unknown);
	}

	@Test
	void loginWithoutRequiredFieldsIsValidationError() throws Exception {
		login("{\"email\": \"\", \"password\": \"\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void loginIgnoresATokenWithOldCredentialsVersion() throws Exception {
		// El login se autentica con email y contraseña: un token revocado en el pedido no debe impedirlo.
		Instant expiresAt = Instant.parse("2026-10-05T20:00:00Z");
		when(auth.login("persona@ejemplo.com", "secreta-123")).thenReturn(
				new AuthService.Login(new IssuedToken("nuevo.token.jwt", expiresAt), false));
		String oldVersion = token(SECRET, VERSION - 1);

		mvc.perform(post("/api/auth/login").header("Authorization", "Bearer " + oldVersion)
						.contentType(MediaType.APPLICATION_JSON).content(credentials("persona@ejemplo.com", "secreta-123")))
				.andExpect(status().isOk());
	}

	@Test
	void meReturnsTheCurrentUserIncludingPendingChange() throws Exception {
		User user = user(true, VERSION, true);
		when(auth.me(USER_ID)).thenReturn(user);

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(SECRET, VERSION)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(USER_ID))
				.andExpect(jsonPath("$.email").value("persona@ejemplo.com"))
				.andExpect(jsonPath("$.mustChangePassword").value(true))
				.andExpect(jsonPath("$.startPeriod").value("2026-08"))
				.andExpect(content().string(not(containsString("hash"))));
	}

	@Test
	void meWithoutTokenIsUnauthorized() throws Exception {
		mvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void meWithBadSignatureIsUnauthorized() throws Exception {
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token("otro-secreto-de-32-caracteres-xx", VERSION)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void meWithExpiredTokenIsUnauthorized() throws Exception {
		Instant now = Instant.now();
		String expired = token(SECRET, VERSION, now.minusSeconds(7200), now.minusSeconds(3600));

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void meWithOldCredentialsVersionIsUnauthorized() throws Exception {
		// Cierra el criterio de HU-02: tras restablecer la contraseña, los tokens anteriores dejan de funcionar.
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(SECRET, VERSION - 1)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	private ResultActions changePassword(String token, String body) throws Exception {
		return mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String passwords(String current, String next) {
		return "{\"currentPassword\": \"" + current + "\", \"newPassword\": \"" + next + "\"}";
	}

	@Test
	void changePasswordWithPendingChangeRespondsWithNewTokenShapedLikeLogin() throws Exception {
		when(users.findById(USER_ID)).thenReturn(Optional.of(user(true, VERSION, true)));
		Instant expiresAt = Instant.parse("2026-10-05T20:00:00Z");
		when(auth.changePassword(USER_ID, "contraseña-inicial", "una-contraseña-nueva")).thenReturn(
				new AuthService.Login(new IssuedToken("nuevo.token.jwt", expiresAt), false));

		changePassword(token(SECRET, VERSION), passwords("contraseña-inicial", "una-contraseña-nueva"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").value("nuevo.token.jwt"))
				.andExpect(jsonPath("$.expiresAt").value("2026-10-05T20:00:00Z"))
				.andExpect(jsonPath("$.mustChangePassword").value(false));
	}

	@Test
	void changePasswordTakesTheUserFromTheTokenNotFromTheBody() throws Exception {
		when(auth.changePassword(USER_ID, "contraseña-inicial", "una-contraseña-nueva")).thenReturn(
				new AuthService.Login(new IssuedToken("nuevo.token.jwt", Instant.parse("2026-10-05T20:00:00Z")), false));

		changePassword(token(SECRET, VERSION),
				"{\"userId\": 99, \"currentPassword\": \"contraseña-inicial\", \"newPassword\": \"una-contraseña-nueva\"}")
				.andExpect(status().isOk());
		verify(auth).changePassword(USER_ID, "contraseña-inicial", "una-contraseña-nueva");
	}

	@Test
	void changePasswordWithWrongCurrentPasswordIsBadRequest() throws Exception {
		when(auth.changePassword(USER_ID, "incorrecta-123", "una-contraseña-nueva")).thenThrow(
				new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD, "La contraseña actual es incorrecta."));

		changePassword(token(SECRET, VERSION), passwords("incorrecta-123", "una-contraseña-nueva"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"))
				.andExpect(jsonPath("$.detail").value("La contraseña actual es incorrecta."));
	}

	@Test
	void changePasswordToTheSameOneIsValidationError() throws Exception {
		when(auth.changePassword(USER_ID, "contraseña-inicial", "contraseña-inicial")).thenThrow(
				new BusinessException(ErrorCode.VALIDATION_ERROR, "La contraseña nueva debe ser distinta de la actual."));

		changePassword(token(SECRET, VERSION), passwords("contraseña-inicial", "contraseña-inicial"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.detail").value("La contraseña nueva debe ser distinta de la actual."));
	}

	@Test
	void changePasswordWithShortNewPasswordIsValidationErrorOnThatField() throws Exception {
		changePassword(token(SECRET, VERSION), passwords("contraseña-inicial", "corta-123"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("newPassword"))
				.andExpect(jsonPath("$.errors[0].message").value("La contraseña debe tener al menos 10 caracteres."));
		verifyNoInteractions(auth);
	}

	@Test
	void changePasswordWithNewPasswordOverBcryptLimitIsValidationError() throws Exception {
		changePassword(token(SECRET, VERSION), passwords("contraseña-inicial", "x".repeat(73)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(auth);
	}

	@Test
	void changePasswordWithoutFieldsIsValidationError() throws Exception {
		changePassword(token(SECRET, VERSION), "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(auth);
	}

	@Test
	void changePasswordWithoutTokenIsUnauthorized() throws Exception {
		mvc.perform(post("/api/auth/change-password").contentType(MediaType.APPLICATION_JSON)
						.content(passwords("contraseña-inicial", "una-contraseña-nueva")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void previousTokenIsUnauthorizedAfterThePasswordChange() throws Exception {
		// Tras el cambio el usuario tiene la versión siguiente: el token anterior deja de servir en cualquier endpoint.
		String previous = token(SECRET, VERSION);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user(true, VERSION + 1, false)));
		when(auth.me(USER_ID)).thenReturn(user(true, VERSION + 1, false));

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + previous))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		changePassword(previous, passwords("una-contraseña-nueva", "otra-contraseña-nueva"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(SECRET, VERSION + 1)))
				.andExpect(status().isOk());
	}

	@Test
	void mePassesWithPendingChange() throws Exception {
		when(users.findById(USER_ID)).thenReturn(Optional.of(user(true, VERSION, true)));
		when(auth.me(USER_ID)).thenReturn(user(true, VERSION, true));

		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(SECRET, VERSION)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mustChangePassword").value(true));
	}

	private static User user(boolean enabled, int credentialsVersion, boolean mustChangePassword) {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		user.setEmail("persona@ejemplo.com");
		user.setPasswordHash("$2a$10$hash-que-no-debe-salir");
		user.setEnabled(enabled);
		user.setCredentialsVersion(credentialsVersion);
		user.setMustChangePassword(mustChangePassword);
		user.setStartPeriod(YearMonth.of(2026, 8));
		return user;
	}

	private static String token(String secret, int credentialsVersion) {
		return token(secret, credentialsVersion, Instant.now(), Instant.now().plusSeconds(60));
	}

	private static String token(String secret, int credentialsVersion, Instant issuedAt, Instant expiresAt) {
		var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		var claims = JwtClaimsSet.builder().subject(String.valueOf(USER_ID)).claim("cv", credentialsVersion)
				.issuedAt(issuedAt).expiresAt(expiresAt).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}
}
