package com.smartcoin.user.web;

import java.time.YearMonth;

import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.shared.security.SecurityConfig;
import com.smartcoin.user.repository.UserRepository;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.service.UserService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class })
@TestPropertySource(properties = {
		"app.security.jwt-secret=0123456789abcdef0123456789abcdef",
		"app.security.admin-key=" + AdminUserControllerTest.ADMIN_KEY })
class AdminUserControllerTest {

	static final String ADMIN_KEY = "clave-de-administracion-de-prueba";
	static final String URL = "/api/admin/users";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	UserService users;

	// SecurityConfig lo necesita para el filtro de usuario actual (RN-50).
	@MockitoBean
	UserRepository userRepository;

	private ResultActions create(String adminKey, String body) throws Exception {
		var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
		if (adminKey != null) {
			request.header("X-Admin-Key", adminKey);
		}
		return mvc.perform(request);
	}

	private static String body(String email, String password, String startPeriod) {
		String period = startPeriod == null ? "" : ", \"startPeriod\": \"" + startPeriod + "\"";
		return "{\"email\": \"" + email + "\", \"password\": \"" + password + "\"" + period + "}";
	}

	private static User user(long id, String email, YearMonth start) {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", id);
		user.setEmail(email);
		user.setPasswordHash("$2a$10$hash-que-no-debe-salir");
		user.setStartPeriod(start);
		return user;
	}

	@Test
	void createsTheUserAndNeverReturnsThePassword() throws Exception {
		when(users.create("Persona@Ejemplo.com", "clave-larga-123", YearMonth.of(2026, 8)))
				.thenReturn(user(3L, "persona@ejemplo.com", YearMonth.of(2026, 8)));

		create(ADMIN_KEY, body("Persona@Ejemplo.com", "clave-larga-123", "2026-08"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(3))
				.andExpect(jsonPath("$.email").value("persona@ejemplo.com"))
				.andExpect(jsonPath("$.startPeriod").value("2026-08"))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void startPeriodIsOptional() throws Exception {
		when(users.create(eq("persona@ejemplo.com"), eq("clave-larga-123"), eq(null)))
				.thenReturn(user(3L, "persona@ejemplo.com", YearMonth.of(2026, 10)));

		create(ADMIN_KEY, body("persona@ejemplo.com", "clave-larga-123", null))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.startPeriod").value("2026-10"));
	}

	@Test
	void startPeriodAfterTheCurrentOneIsValidationError() throws Exception {
		when(users.create(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR,
				"El período inicial no puede ser posterior al período actual (2026-10)."));

		create(ADMIN_KEY, body("persona@ejemplo.com", "clave-larga-123", "2026-11"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void invalidEmailIsValidationErrorWithTheField() throws Exception {
		create(ADMIN_KEY, body("no-es-un-email", "clave-larga-123", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("email"));

		verifyNoInteractions(users);
	}

	@Test
	void shortPasswordIsValidationErrorWithTheField() throws Exception {
		create(ADMIN_KEY, body("persona@ejemplo.com", "corta", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("password"))
				.andExpect(jsonPath("$.errors[0].message").value("La contraseña debe tener al menos 10 caracteres."));

		verifyNoInteractions(users);
	}

	@Test
	void passwordOverBcryptLimitIsValidationError() throws Exception {
		create(ADMIN_KEY, body("persona@ejemplo.com", "a".repeat(73), null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("password"));

		verifyNoInteractions(users);
	}

	@Test
	void missingFieldsAreValidationError() throws Exception {
		create(ADMIN_KEY, "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.length()").value(2));
	}

	@Test
	void malformedStartPeriodIsValidationError() throws Exception {
		create(ADMIN_KEY, body("persona@ejemplo.com", "clave-larga-123", "octubre"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(users);
	}

	@Test
	void withoutHeaderIsUnauthorizedAndNothingIsCreated() throws Exception {
		create(null, body("persona@ejemplo.com", "clave-larga-123", null))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(users);
	}

	@Test
	void wrongKeyIsUnauthorizedAndNothingIsCreated() throws Exception {
		create("clave-incorrecta", body("persona@ejemplo.com", "clave-larga-123", null))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(users);
	}

	@Test
	void emptyKeyHeaderIsUnauthorized() throws Exception {
		create("", body("persona@ejemplo.com", "clave-larga-123", null))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(users);
	}

	@Test
	void wrongKeyWinsOverAnInvalidBody() throws Exception {
		create("clave-incorrecta", "{}")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verify(users, never()).create(any(), any(), any());
	}

	@Test
	void jwtDoesNotReplaceTheAdminKey() throws Exception {
		mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer cualquier.token.jwt")
				.content(body("persona@ejemplo.com", "clave-larga-123", null)))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(users);
	}

	@Test
	void existingEmailIsConflict() throws Exception {
		when(users.create(any(), any(), any())).thenThrow(
				new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Ya existe un usuario con ese email."));

		create(ADMIN_KEY, body("persona@ejemplo.com", "clave-larga-123", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
	}

	// --- Restablecer contraseña ---

	static final String RESET_URL = "/api/admin/users/password-reset";

	private ResultActions reset(String adminKey, String body) throws Exception {
		var request = post(RESET_URL).contentType(MediaType.APPLICATION_JSON).content(body);
		if (adminKey != null) {
			request.header("X-Admin-Key", adminKey);
		}
		return mvc.perform(request);
	}

	private static String resetBody(String email, String password) {
		return "{\"email\": \"" + email + "\", \"temporaryPassword\": \"" + password + "\"}";
	}

	@Test
	void resetRespondsNoContentWithoutBody() throws Exception {
		String response = reset(ADMIN_KEY, resetBody("Persona@Ejemplo.com", "temporal-123456"))
				.andExpect(status().isNoContent())
				.andReturn().getResponse().getContentAsString();

		assertThat(response).isEmpty();
		verify(users).resetPassword("Persona@Ejemplo.com", "temporal-123456");
	}

	@Test
	void resetWithInvalidEmailIsValidationError() throws Exception {
		reset(ADMIN_KEY, resetBody("no-es-un-email", "temporal-123456"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("email"));

		verifyNoInteractions(users);
	}

	@Test
	void resetWithShortPasswordIsValidationErrorAndDoesNotEchoIt() throws Exception {
		String response = reset(ADMIN_KEY, resetBody("persona@ejemplo.com", "corta"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("temporaryPassword"))
				.andReturn().getResponse().getContentAsString();

		assertThat(response).doesNotContain("corta\"");
		verifyNoInteractions(users);
	}

	@Test
	void resetWithPasswordOverBcryptLimitIsValidationError() throws Exception {
		reset(ADMIN_KEY, resetBody("persona@ejemplo.com", "a".repeat(73)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("temporaryPassword"));

		verifyNoInteractions(users);
	}

	@Test
	void resetWithMissingFieldsIsValidationError() throws Exception {
		reset(ADMIN_KEY, "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(2));

		verifyNoInteractions(users);
	}

	@Test
	void resetWithoutHeaderIsUnauthorized() throws Exception {
		reset(null, resetBody("persona@ejemplo.com", "temporal-123456"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(users);
	}

	@Test
	void resetWithWrongKeyIsUnauthorized() throws Exception {
		reset("clave-incorrecta", resetBody("persona@ejemplo.com", "temporal-123456"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(users);
	}

	@Test
	void resetWrongKeyWinsOverAnInvalidBody() throws Exception {
		reset("clave-incorrecta", "{}")
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(users);
	}

	@Test
	void resetOfUnknownEmailIsNotFound() throws Exception {
		doThrow(new BusinessException(ErrorCode.NOT_FOUND, "No existe un usuario con ese email."))
				.when(users).resetPassword(any(), any());

		String response = reset(ADMIN_KEY, resetBody("nadie@ejemplo.com", "temporal-123456"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andReturn().getResponse().getContentAsString();

		assertThat(response).doesNotContain("temporal-123456");
	}
}
