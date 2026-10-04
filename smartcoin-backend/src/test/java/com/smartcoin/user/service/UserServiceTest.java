package com.smartcoin.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

import com.smartcoin.budgetitem.service.HorizonService;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import java.util.Optional;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	@Mock
	UserRepository users;

	@Mock
	HorizonService horizon;

	PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

	UserService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneId.of("America/Argentina/Mendoza"));
		service = new UserService(users, passwordEncoder, horizon, clock);
	}

	/** Simula el guardado: la base asigna el id. */
	private void saveAssigningId(long id) {
		when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			ReflectionTestUtils.setField(user, "id", id);
			return user;
		});
	}

	@Test
	void createsEnabledUserWithMandatoryPasswordChange() {
		saveAssigningId(5L);

		User created = service.create("persona@ejemplo.com", "clave-larga-123", YearMonth.of(2026, 8));

		assertThat(created.getId()).isEqualTo(5L);
		assertThat(created.isEnabled()).isTrue();
		assertThat(created.isMustChangePassword()).isTrue();
		assertThat(created.getCredentialsVersion()).isZero();
		assertThat(created.getStartPeriod()).isEqualTo(YearMonth.of(2026, 8));
	}

	@Test
	void storesTheEmailInLowercase() {
		saveAssigningId(1L);

		User created = service.create("Persona@Ejemplo.COM", "clave-larga-123", null);

		assertThat(created.getEmail()).isEqualTo("persona@ejemplo.com");
		verify(users).existsByEmail("persona@ejemplo.com");
	}

	@Test
	void storesABcryptHashAndNeverThePlainPassword() {
		saveAssigningId(1L);

		User created = service.create("persona@ejemplo.com", "clave-larga-123", null);

		assertThat(created.getPasswordHash()).startsWith("$2").isNotEqualTo("clave-larga-123");
		assertThat(passwordEncoder.matches("clave-larga-123", created.getPasswordHash())).isTrue();
	}

	@Test
	void withoutStartPeriodUsesTheCurrentOneInTheBusinessZone() {
		saveAssigningId(1L);

		User created = service.create("persona@ejemplo.com", "clave-larga-123", null);

		assertThat(created.getStartPeriod()).isEqualTo(YearMonth.of(2026, 10));
	}

	@Test
	void startPeriodEqualToTheCurrentOneIsAccepted() {
		saveAssigningId(1L);

		User created = service.create("persona@ejemplo.com", "clave-larga-123", YearMonth.of(2026, 10));

		assertThat(created.getStartPeriod()).isEqualTo(YearMonth.of(2026, 10));
	}

	@Test
	void startPeriodAfterTheCurrentOneIsRejectedAndNothingIsSaved() {
		assertThatThrownBy(() -> service.create("persona@ejemplo.com", "clave-larga-123", YearMonth.of(2026, 11)))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(users, never()).saveAndFlush(any());
		verifyNoInteractions(horizon);
	}

	@Test
	void ensuresTheHorizonOfTheNewUser() {
		saveAssigningId(5L);

		User created = service.create("persona@ejemplo.com", "clave-larga-123", null);

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(horizon).ensureHorizon(captor.capture());
		assertThat(captor.getValue()).isSameAs(created);
	}

	@Test
	void existingEmailIsRejectedAndNothingIsCreated() {
		when(users.existsByEmail("persona@ejemplo.com")).thenReturn(true);

		assertThatThrownBy(() -> service.create("Persona@Ejemplo.com", "clave-larga-123", null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));

		verify(users, never()).saveAndFlush(any());
		verifyNoInteractions(horizon);
	}

	@Test
	void duplicateDetectedByTheDatabaseIsAlsoEmailAlreadyExists() {
		when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_app_user_email"));

		assertThatThrownBy(() -> service.create("persona@ejemplo.com", "clave-larga-123", null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));

		verifyNoInteractions(horizon);
	}

	// --- Restablecer contraseña (RN-48, RN-50) ---

	private User existingUser(int credentialsVersion) {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", 7L);
		user.setEmail("persona@ejemplo.com");
		user.setPasswordHash(passwordEncoder.encode("clave-vieja-123"));
		user.setMustChangePassword(false);
		user.setCredentialsVersion(credentialsVersion);
		user.setEnabled(true);
		return user;
	}

	@Test
	void resetStoresANewBcryptHashOfTheTemporaryPassword() {
		User user = existingUser(0);
		String oldHash = user.getPasswordHash();
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(user));

		service.resetPassword("persona@ejemplo.com", "temporal-123456");

		assertThat(user.getPasswordHash()).isNotEqualTo(oldHash).startsWith("$2").isNotEqualTo("temporal-123456");
		assertThat(passwordEncoder.matches("temporal-123456", user.getPasswordHash())).isTrue();
		assertThat(passwordEncoder.matches("clave-vieja-123", user.getPasswordHash())).isFalse();
		verify(users).save(user);
	}

	@Test
	void resetForcesPasswordChange() {
		User user = existingUser(0);
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(user));

		service.resetPassword("persona@ejemplo.com", "temporal-123456");

		assertThat(user.isMustChangePassword()).isTrue();
	}

	@Test
	void resetIncrementsTheCredentialsVersionByOne() {
		User fresh = existingUser(0);
		User used = existingUser(4);
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(fresh), Optional.of(used));

		service.resetPassword("persona@ejemplo.com", "temporal-123456");
		service.resetPassword("persona@ejemplo.com", "temporal-123456");

		assertThat(fresh.getCredentialsVersion()).isEqualTo(1);
		assertThat(used.getCredentialsVersion()).isEqualTo(5);
	}

	@Test
	void resetLooksUpTheEmailIgnoringCaseAndKeepsTheUserEnabled() {
		User user = existingUser(0);
		user.setEnabled(false);
		when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(user));

		service.resetPassword("Persona@Ejemplo.COM", "temporal-123456");

		verify(users).findByEmail("persona@ejemplo.com");
		assertThat(user.isEnabled()).isFalse();
	}

	@Test
	void resetOfUnknownEmailIsNotFoundAndSavesNothing() {
		when(users.findByEmail("nadie@ejemplo.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.resetPassword("nadie@ejemplo.com", "temporal-123456"))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
					assertThat(e.getMessage()).doesNotContain("temporal-123456");
				});

		verify(users, never()).save(any());
	}

	@Test
	void resetNeverLogsThePassword() {
		Logger logger = (Logger) LoggerFactory.getLogger(UserService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			when(users.findByEmail("persona@ejemplo.com")).thenReturn(Optional.of(existingUser(0)));
			service.resetPassword("persona@ejemplo.com", "temporal-123456");
		}
		finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).isNotEmpty();
		assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains("temporal-123456"));
	}
}
