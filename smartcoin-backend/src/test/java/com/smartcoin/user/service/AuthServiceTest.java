package com.smartcoin.user.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.budgetitem.service.HorizonService;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.shared.security.TokenService;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
	static final String PASSWORD = "contraseña-correcta";

	@Mock
	UserRepository users;

	@Mock
	HorizonService horizon;

	final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
	final SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

	AuthService service;

	@BeforeEach
	void setUp() {
		var properties = new AppProperties(ZoneId.of("America/Argentina/Mendoza"), new AppProperties.Budget(24, 10),
				new AppProperties.Security(SECRET, Duration.ofHours(8), "", 10));
		var tokens = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), properties,
				Clock.fixed(NOW, ZoneId.of("America/Argentina/Mendoza")));
		service = new AuthService(users, passwordEncoder, horizon, tokens);
	}

	@Test
	void validCredentialsIssueEightHourTokenWithIdAndVersion() {
		User user = user(42, true, 3, false);
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(user));

		AuthService.Login login = service.login("persona@ejemplo.com", PASSWORD);

		Jwt jwt = decoder()
				.decode(login.token().value());
		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(((Number) jwt.getClaim("cv")).intValue()).isEqualTo(3);
		assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
		assertThat(login.token().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
		assertThat(login.mustChangePassword()).isFalse();
	}

	@Test
	void emailIsMatchedIgnoringCase() {
		User user = user(1, true, 0, false);
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(user));

		service.login("Persona@EJEMPLO.com", PASSWORD);

		verify(users).findByEmail("persona@ejemplo.com");
	}

	@Test
	void reportsPendingPasswordChange() {
		when(users.findByEmail("nuevo@ejemplo.com")).thenReturn(Optional.of(user(5, true, 0, true)));

		assertThat(service.login("nuevo@ejemplo.com", PASSWORD).mustChangePassword()).isTrue();
	}

	@Test
	void ensuresHorizonOnSuccessfulLogin() {
		User user = user(1, true, 0, false);
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(user));

		service.login("persona@ejemplo.com", PASSWORD);

		verify(horizon).ensureHorizon(user);
	}

	@Test
	void unknownUserWrongPasswordAndDisabledUserFailWithTheSameMessage() {
		when(users.findByEmail("nadie@ejemplo.com")).thenReturn(Optional.empty());
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(user(1, true, 0, false)));
		when(users.findByEmail("baja@ejemplo.com")).thenReturn(Optional.of(user(2, false, 0, false)));

		BusinessException unknown = failure("nadie@ejemplo.com", PASSWORD);
		BusinessException wrongPassword = failure("persona@ejemplo.com", "otra-contraseña");
		BusinessException disabled = failure("baja@ejemplo.com", PASSWORD);

		for (BusinessException e : new BusinessException[] { unknown, wrongPassword, disabled }) {
			assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHORIZED);
			assertThat(e.getMessage()).isEqualTo("Email o contraseña incorrectos.");
		}
	}

	@Test
	void failedLoginDoesNotEnsureHorizon() {
		when(users.findByEmail("baja@ejemplo.com")).thenReturn(Optional.of(user(2, false, 0, false)));
		when(users.findByEmail("nadie@ejemplo.com")).thenReturn(Optional.empty());

		failure("baja@ejemplo.com", PASSWORD);
		failure("nadie@ejemplo.com", PASSWORD);

		verifyNoInteractions(horizon);
	}

	@Test
	void passwordLongerThanBcryptLimitIsInvalidCredentials() {
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(user(1, true, 0, false)));

		BusinessException e = failure("persona@ejemplo.com", PASSWORD + "x".repeat(80));

		assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHORIZED);
		verify(horizon, never()).ensureHorizon(any());
	}

	@Test
	void meReturnsTheUser() {
		User user = user(9, true, 0, true);
		when(users.findById(9L)).thenReturn(Optional.of(user));

		assertThat(service.me(9)).isSameAs(user);
	}

	@Test
	void meWithMissingUserIsUnauthorized() {
		when(users.findById(9L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.me(9))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
	}

	@Test
	void changePasswordStoresNewHashClearsFlagAndIncrementsVersionByOne() {
		User user = user(42, true, 3, true);
		when(users.findById(42L)).thenReturn(Optional.of(user));

		service.changePassword(42, PASSWORD, "una-contraseña-nueva");

		assertThat(passwordEncoder.matches("una-contraseña-nueva", user.getPasswordHash())).isTrue();
		assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isFalse();
		assertThat(user.getPasswordHash()).doesNotContain("una-contraseña-nueva");
		assertThat(user.isMustChangePassword()).isFalse();
		assertThat(user.getCredentialsVersion()).isEqualTo(4);
	}

	@Test
	void changePasswordIssuesNewTokenWithTheIncrementedVersion() {
		when(users.findById(42L)).thenReturn(Optional.of(user(42, true, 3, true)));

		AuthService.Login login = service.changePassword(42, PASSWORD, "una-contraseña-nueva");

		Jwt jwt = decoder()
				.decode(login.token().value());
		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(((Number) jwt.getClaim("cv")).intValue()).isEqualTo(4);
		assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
		assertThat(login.token().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
		assertThat(login.mustChangePassword()).isFalse();
	}

	@Test
	void changePasswordWithWrongCurrentPasswordChangesNothing() {
		User user = user(42, true, 3, true);
		String hash = user.getPasswordHash();
		when(users.findById(42L)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> service.changePassword(42, "no-es-la-actual", "una-contraseña-nueva"))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD));

		assertThat(user.getPasswordHash()).isEqualTo(hash);
		assertThat(user.isMustChangePassword()).isTrue();
		assertThat(user.getCredentialsVersion()).isEqualTo(3);
	}

	@Test
	void changePasswordWithCurrentPasswordLongerThanBcryptLimitIsInvalidCurrentPassword() {
		when(users.findById(42L)).thenReturn(Optional.of(user(42, true, 3, true)));

		assertThatThrownBy(() -> service.changePassword(42, PASSWORD + "x".repeat(80), "una-contraseña-nueva"))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD));
	}

	@Test
	void changePasswordToTheSameOneIsValidationErrorAndChangesNothing() {
		User user = user(42, true, 3, true);
		String hash = user.getPasswordHash();
		when(users.findById(42L)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> service.changePassword(42, PASSWORD, PASSWORD))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.getMessage()).isEqualTo("La contraseña nueva debe ser distinta de la actual.");
				});

		assertThat(user.getPasswordHash()).isEqualTo(hash);
		assertThat(user.isMustChangePassword()).isTrue();
		assertThat(user.getCredentialsVersion()).isEqualTo(3);
	}

	@Test
	void changePasswordKeepsTheFlagClearedWhenItWasNotPending() {
		User user = user(42, true, 0, false);
		when(users.findById(42L)).thenReturn(Optional.of(user));

		service.changePassword(42, PASSWORD, "una-contraseña-nueva");

		assertThat(user.isMustChangePassword()).isFalse();
		assertThat(user.getCredentialsVersion()).isEqualTo(1);
	}

	@Test
	void changePasswordWithMissingUserIsUnauthorized() {
		when(users.findById(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.changePassword(42, PASSWORD, "una-contraseña-nueva"))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHORIZED));
	}

	private BusinessException failure(String email, String password) {
		try {
			service.login(email, password);
		}
		catch (BusinessException e) {
			return e;
		}
		throw new AssertionError("El login debía fallar");
	}

	private User user(long id, boolean enabled, int credentialsVersion, boolean mustChangePassword) {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", id);
		user.setEmail("persona@ejemplo.com");
		user.setPasswordHash(passwordEncoder.encode(PASSWORD));
		user.setEnabled(enabled);
		user.setCredentialsVersion(credentialsVersion);
		user.setMustChangePassword(mustChangePassword);
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
