package com.smartcoin.entry.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.service.EntryService;
import com.smartcoin.entry.service.EntryService.Changes;
import com.smartcoin.entry.service.EntryService.NewOneOff;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.shared.config.WebConfig;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.shared.error.GlobalExceptionHandler;
import com.smartcoin.shared.security.CurrentUser;
import com.smartcoin.shared.security.SecurityConfig;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

/**
 * HU-16: códigos HTTP, forma de la respuesta y formato de errores de {@code POST /api/periods/{period}/entries} y
 * {@code PATCH /api/entries/{id}}. Servicio simulado. Se importa {@link WebConfig} porque ahí está el convertidor de
 * períodos de la ruta.
 */
@WebMvcTest(EntryController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class, WebConfig.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + EntryControllerTest.SECRET)
class EntryControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	@Autowired
	MockMvc mvc;

	@MockitoBean
	EntryService entries;

	@MockitoBean
	UserRepository users;

	@BeforeEach
	void existingEnabledUser() {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		user.setEnabled(true);
		user.setCredentialsVersion(1);
		user.setStartPeriod(YearMonth.of(2026, 8));
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
	}

	private static String bearer() {
		var key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var claims = JwtClaimsSet.builder().subject(String.valueOf(USER_ID)).claim("cv", 1)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		return "Bearer " + new NimbusJwtEncoder(new ImmutableSecret<>(key))
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	private ResultActions create(String period, String json) throws Exception {
		return mvc.perform(post("/api/periods/" + period + "/entries").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions update(String id, String json) throws Exception {
		return mvc.perform(patch("/api/entries/" + id).header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static final String VALID_CREATE = """
			{"name":"Service del auto","kind":"EXPENSE","accountId":12,"categoryId":3,
			 "dueDate":"2026-11-18","budgetedAmount":85000.50}""";

	private static EntryRow row() {
		BigDecimal budgeted = new BigDecimal("85000.50");
		return new EntryRow(900L, null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, "Service del auto", 3L, "Hogar", 12L,
				"Banco Nación", Currency.ARS, LocalDate.of(2026, 11, 18), null, null, budgeted,
				EntryAmounts.of(budgeted, StoredEntryStatus.PENDING, null, null), false, false);
	}

	@Test
	void createRespondsCreatedWithTheEntryAndItsDerivedValues() throws Exception {
		when(entries.createOneOff(eq(USER_ID), eq(NOVEMBER), any(NewOneOff.class))).thenReturn(row());

		String body = create("2026-11", VALID_CREATE)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(900))
				.andExpect(jsonPath("$.budgetItemId").doesNotExist())
				.andExpect(jsonPath("$.origin").value("ONE_OFF"))
				.andExpect(jsonPath("$.kind").value("EXPENSE"))
				.andExpect(jsonPath("$.name").value("Service del auto"))
				.andExpect(jsonPath("$.categoryId").value(3))
				.andExpect(jsonPath("$.categoryName").value("Hogar"))
				.andExpect(jsonPath("$.accountName").value("Banco Nación"))
				.andExpect(jsonPath("$.currency").value("ARS"))
				.andExpect(jsonPath("$.dueDate").value("2026-11-18"))
				.andExpect(jsonPath("$.budgetedAmount").value(85000.50))
				.andExpect(jsonPath("$.actualAmount").value(0.00))
				.andExpect(jsonPath("$.pendingAmount").value(85000.50))
				.andExpect(jsonPath("$.forecastAmount").value(85000.50))
				.andExpect(jsonPath("$.status").value("ESTIMATED"))
				.andExpect(jsonPath("$.manual").value(false))
				.andExpect(jsonPath("$.overdue").value(false))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		// Los montos viajan como número con 2 decimales (T-12), también el que vale 0.
		assertThat(body).contains("\"actualAmount\":0.00").contains("\"budgetedAmount\":85000.50");
	}

	@Test
	void createPassesTheBodyAndTheUserOfTheTokenToTheService() throws Exception {
		when(entries.createOneOff(anyLong(), any(), any())).thenReturn(row());

		// Un `userId` en el cuerpo no tiene dónde ir: se ignora y manda el del token (RN-01).
		create("2026-11", """
				{"userId":99,"name":"Service del auto","kind":"EXPENSE","accountId":12,
				 "dueDate":"2026-11-18","budgetedAmount":85000.50}""").andExpect(status().isCreated());

		ArgumentCaptor<NewOneOff> values = ArgumentCaptor.forClass(NewOneOff.class);
		verify(entries).createOneOff(eq(USER_ID), eq(NOVEMBER), values.capture());
		assertThat(values.getValue()).isEqualTo(new NewOneOff("Service del auto", EntryKind.EXPENSE, 12L, null,
				LocalDate.of(2026, 11, 18), new BigDecimal("85000.50")));
	}

	@Test
	void createWithoutRequiredFieldsIsAValidationErrorWithTheErrorsPerField() throws Exception {
		create("2026-11", "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='kind')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='accountId')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='dueDate')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='budgetedAmount')]").exists());
		verifyNoInteractions(entries);
	}

	static Stream<String> malformedCreates() {
		String valid = VALID_CREATE;
		return Stream.of(
				valid.replace("\"Service del auto\"", "\"   \""),
				valid.replace("\"Service del auto\"", "\"" + "x".repeat(101) + "\""),
				valid.replace("85000.50", "-0.01"),
				valid.replace("85000.50", "10.005"),
				valid.replace("85000.50", "\"abc\""),
				valid.replace("2026-11-18", "2026-02-30"),
				valid.replace("2026-11-18", "18/11/2026"),
				valid.replace("EXPENSE", "TRANSFER"));
	}

	@ParameterizedTest
	@MethodSource("malformedCreates")
	void createWithAMalformedFieldIsAValidationError(String json) throws Exception {
		create("2026-11", json)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(entries);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-13", "2026-00", "2026-1", "202611", "noviembre", "actual" })
	void createWithAPeriodOfInvalidFormatIsAValidationError(String period) throws Exception {
		create(period, VALID_CREATE)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(entries);
	}

	@Test
	void createInAPeriodOutOfRangeIsNotFound() throws Exception {
		when(entries.createOneOff(eq(USER_ID), eq(YearMonth.of(2030, 1)), any()))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "El período 2030-01 no existe."));

		create("2030-01", VALID_CREATE)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void createInAClosedPeriodIsAConflict() throws Exception {
		when(entries.createOneOff(anyLong(), any(), any())).thenThrow(
				new BusinessException(ErrorCode.PERIOD_CLOSED, "El período 2026-11 está cerrado y no admite cambios."));

		create("2026-11", VALID_CREATE)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.title").value("Período cerrado"))
				.andExpect(jsonPath("$.detail").value("El período 2026-11 está cerrado y no admite cambios."));
	}

	@Test
	void createWithAReferenceThatIsNotTheUsersIsAValidationErrorInItsField() throws Exception {
		when(entries.createOneOff(anyLong(), any(), any()))
				.thenThrow(BusinessException.invalidField("accountId", "La cuenta no existe."));

		create("2026-11", VALID_CREATE)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("accountId"))
				.andExpect(jsonPath("$.errors[0].message").value("La cuenta no existe."));
	}

	@Test
	void createWithADueDateOutOfRangeIsAValidationErrorOnDueDate() throws Exception {
		when(entries.createOneOff(anyLong(), any(), any())).thenThrow(BusinessException.invalidField("dueDate",
				"El vencimiento debe estar entre el 01/10/2026 y el 30/11/2026."));

		create("2026-11", VALID_CREATE)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("dueDate"));
	}

	@Test
	void updateRespondsTheEntryWithItsDerivedValues() throws Exception {
		when(entries.update(eq(USER_ID), eq(900L), any(Changes.class))).thenReturn(row());

		update("900", "{\"name\":\"Service del auto\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(900))
				.andExpect(jsonPath("$.origin").value("ONE_OFF"))
				.andExpect(jsonPath("$.manual").value(false))
				.andExpect(jsonPath("$.pendingAmount").value(85000.50));
	}

	@Test
	void updatePassesOnlyWhatWasSent() throws Exception {
		when(entries.update(anyLong(), anyLong(), any())).thenReturn(row());

		update("900", "{\"name\":\"Otro\",\"dueDate\":\"2026-11-20\"}").andExpect(status().isOk());
		update("900", "{}").andExpect(status().isOk());

		ArgumentCaptor<Changes> changes = ArgumentCaptor.forClass(Changes.class);
		verify(entries, times(2)).update(eq(USER_ID), eq(900L), changes.capture());
		assertThat(changes.getAllValues().get(0)).isEqualTo(
				new Changes("Otro", null, null, null, false, LocalDate.of(2026, 11, 20), null));
		assertThat(changes.getAllValues().get(1)).isEqualTo(new Changes(null, null, null, null, false, null, null));
	}

	@Test
	void updateOfTheBudgetedAmountAloneIsPassedAsTheOnlyChangeAndAnswersTheEditedMark() throws Exception {
		when(entries.update(anyLong(), anyLong(), any())).thenReturn(row());

		update("900", "{\"budgetedAmount\":240000.00}").andExpect(status().isOk());
		update("900", "{\"budgetedAmount\":0}").andExpect(status().isOk());

		ArgumentCaptor<Changes> changes = ArgumentCaptor.forClass(Changes.class);
		verify(entries, times(2)).update(eq(USER_ID), eq(900L), changes.capture());
		assertThat(changes.getAllValues().get(0)).isEqualTo(
				new Changes(null, null, null, null, false, null, new java.math.BigDecimal("240000.00")));
		assertThat(changes.getAllValues().get(1).budgetedAmount()).isEqualByComparingTo("0");
	}

	@Test
	void updateDistinguishesNotSendingTheCategoryFromClearingIt() throws Exception {
		when(entries.update(anyLong(), anyLong(), any())).thenReturn(row());

		update("900", "{\"clearCategory\":true}").andExpect(status().isOk());
		// Un `categoryId: null` explícito es «no cambia», igual que omitirlo: vaciar se pide con `clearCategory`.
		update("900", "{\"categoryId\":null}").andExpect(status().isOk());
		update("900", "{\"categoryId\":6}").andExpect(status().isOk());

		ArgumentCaptor<Changes> changes = ArgumentCaptor.forClass(Changes.class);
		verify(entries, times(3)).update(eq(USER_ID), eq(900L), changes.capture());
		assertThat(changes.getAllValues().get(0).clearCategory()).isTrue();
		assertThat(changes.getAllValues().get(0).categoryId()).isNull();
		assertThat(changes.getAllValues().get(1).clearCategory()).isFalse();
		assertThat(changes.getAllValues().get(1).categoryId()).isNull();
		assertThat(changes.getAllValues().get(2).clearCategory()).isFalse();
		assertThat(changes.getAllValues().get(2).categoryId()).isEqualTo(6L);
	}

	static Stream<String> malformedUpdates() {
		return Stream.of(
				"{\"name\":\"   \"}",
				"{\"name\":\"" + "x".repeat(101) + "\"}",
				"{\"budgetedAmount\":-1}",
				"{\"budgetedAmount\":1.234}",
				"{\"dueDate\":\"mañana\"}",
				"{\"kind\":\"TRANSFER\"}",
				"{\"accountId\":\"doce\"}");
	}

	@ParameterizedTest
	@MethodSource("malformedUpdates")
	void updateWithAMalformedFieldIsAValidationError(String json) throws Exception {
		update("900", json)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(entries);
	}

	@Test
	void updateWithANonNumericIdIsAValidationError() throws Exception {
		update("abc", "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(entries);
	}

	@Test
	void updateOfAnEntryThatIsNotTheUsersIsNotFound() throws Exception {
		when(entries.update(anyLong(), anyLong(), any()))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));

		update("900", "{\"name\":\"X\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "PERIOD_CLOSED", "ENTRY_NOT_PENDING", "FIELD_NOT_EDITABLE", "CURRENCY_MISMATCH" })
	void updateStateErrorsAreConflictsWithTheirCode(String code) throws Exception {
		when(entries.update(anyLong(), anyLong(), any()))
				.thenThrow(new BusinessException(ErrorCode.valueOf(code), "Detalle de " + code + "."));

		update("900", "{\"name\":\"X\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.detail").value("Detalle de " + code + "."));
	}

	@Test
	void updateWithAReferenceOrDateThatIsInvalidIsAValidationErrorInItsField() throws Exception {
		when(entries.update(anyLong(), anyLong(), any()))
				.thenThrow(BusinessException.invalidField("categoryId", "La categoría no existe."));

		update("900", "{\"categoryId\":77}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("categoryId"));
	}

	@Test
	void requiresAToken() throws Exception {
		mvc.perform(post("/api/periods/2026-11/entries").contentType(MediaType.APPLICATION_JSON)
				.content(VALID_CREATE)).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/entries/900").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(entries);
	}
}
