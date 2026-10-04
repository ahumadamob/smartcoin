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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
}
