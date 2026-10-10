package com.smartcoin.entry.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.entry.domain.DeletionScope;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryDeletionPlanner;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Candidate;
import com.smartcoin.entry.domain.EntryDeletionPlanner.ItemFacts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.service.EntryService;
import com.smartcoin.entry.service.EntryService.Changes;
import com.smartcoin.entry.service.EntryService.DeletionPreview;
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

	// ---------------------------------------------------------------- HU-18: eliminar

	private ResultActions deleteEntry(String id, String query) throws Exception {
		return mvc.perform(delete("/api/entries/" + id + query).header("Authorization", bearer()));
	}

	@Test
	void deleteOfAnEntryWithoutAnItemRespondsNoContentAndPassesNoScope() throws Exception {
		deleteEntry("900", "")
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(entries).delete(USER_ID, 900L, null);
	}

	@ParameterizedTest
	@ValueSource(strings = { "ONLY_THIS", "THIS_AND_FUTURE" })
	void deleteWithAScopeRespondsNoContentAndPassesTheScopeAndTheUserOfTheToken(String scope) throws Exception {
		deleteEntry("512", "?scope=" + scope).andExpect(status().isNoContent());

		verify(entries).delete(USER_ID, 512L, DeletionScope.valueOf(scope));
	}

	@ParameterizedTest
	@ValueSource(strings = { "?scope=FOO", "?scope=only_this", "?scope=ALL" })
	void deleteWithAnInvalidScopeValueIsAValidationErrorAndDoesNotReachTheService(String query) throws Exception {
		deleteEntry("512", query)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(entries);
	}

	@Test
	void deleteWithANonNumericIdIsAValidationError() throws Exception {
		deleteEntry("abc", "?scope=ONLY_THIS")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(entries);
	}

	@Test
	void deleteOfAnEntryThatIsNotTheUsersIsNotFound() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."))
				.when(entries).delete(anyLong(), anyLong(), any());

		deleteEntry("512", "?scope=ONLY_THIS")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void deleteWithAMissingOrSuperfluousScopeIsAValidationErrorOnScope() throws Exception {
		org.mockito.Mockito.doThrow(BusinessException.invalidField("scope",
				"Indicá si querés eliminar solo este mes o este mes y los siguientes."))
				.when(entries).delete(anyLong(), anyLong(), any());

		deleteEntry("512", "")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("scope"))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
	}

	@Test
	void deleteOfAClosedPeriodIsAConflict() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PERIOD_CLOSED, "El período 2026-10 está cerrado."))
				.when(entries).delete(anyLong(), anyLong(), any());

		deleteEntry("512", "?scope=ONLY_THIS")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
				.andExpect(jsonPath("$.entries").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = { "ENTRY_NOT_PENDING", "ENTRY_HAS_MOVEMENTS" })
	void deleteRejectedBecauseOfOtherEntriesIsAConflictWithTheirIds(String code) throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.valueOf(code), "No se eliminó nada.",
				List.of(514L, 515L))).when(entries).delete(anyLong(), anyLong(), any());

		deleteEntry("512", "?scope=THIS_AND_FUTURE")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("No se eliminó nada."))
				.andExpect(jsonPath("$.entries[0]").value(514))
				.andExpect(jsonPath("$.entries[1]").value(515))
				.andExpect(jsonPath("$.entries.length()").value(2));
	}

	// ---------------------------------------------------------------- HU-18: vista previa

	private static DeletionPreview recurringPreview() {
		List<Candidate> candidates = List.of(new Candidate(512, YearMonth.of(2026, 12), false, false),
				new Candidate(513, YearMonth.of(2027, 1), true, true),
				new Candidate(514, YearMonth.of(2027, 2), false, true));
		ItemFacts item = new ItemFacts(null, YearMonth.of(2028, 10));
		return new DeletionPreview(512, true, EntryOrigin.RECURRING, YearMonth.of(2026, 12), null,
				EntryDeletionPlanner.forRecurring(DeletionScope.ONLY_THIS, 512, item, candidates),
				EntryDeletionPlanner.forRecurring(DeletionScope.THIS_AND_FUTURE, 512, item, candidates));
	}

	@Test
	void previewOfARecurringEntryAnswersBothScopesWithTheirBlockers() throws Exception {
		when(entries.deletionPreview(USER_ID, 512L)).thenReturn(recurringPreview());

		mvc.perform(get("/api/entries/512/deletion-preview").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryId").value(512))
				.andExpect(jsonPath("$.recurring").value(true))
				.andExpect(jsonPath("$.origin").value("RECURRING"))
				.andExpect(jsonPath("$.period").value("2026-12"))
				.andExpect(jsonPath("$.removal").doesNotExist())
				.andExpect(jsonPath("$.onlyThis.allowed").value(true))
				.andExpect(jsonPath("$.onlyThis.entryCount").value(1))
				.andExpect(jsonPath("$.onlyThis.fromPeriod").value("2026-12"))
				.andExpect(jsonPath("$.onlyThis.toPeriod").value("2026-12"))
				.andExpect(jsonPath("$.onlyThis.itemOutcome").value("KEEPS_ITEM"))
				.andExpect(jsonPath("$.onlyThis.blockers.length()").value(0))
				.andExpect(jsonPath("$.thisAndFuture.allowed").value(false))
				.andExpect(jsonPath("$.thisAndFuture.entryCount").value(3))
				.andExpect(jsonPath("$.thisAndFuture.fromPeriod").value("2026-12"))
				.andExpect(jsonPath("$.thisAndFuture.toPeriod").value("2027-02"))
				.andExpect(jsonPath("$.thisAndFuture.itemOutcome").value("REMOVES_ITEM"))
				.andExpect(jsonPath("$.thisAndFuture.blockers[0].entryId").value(513))
				.andExpect(jsonPath("$.thisAndFuture.blockers[0].period").value("2027-01"))
				.andExpect(jsonPath("$.thisAndFuture.blockers[0].reason").value("CONSOLIDATED"))
				.andExpect(jsonPath("$.thisAndFuture.blockers[1].reason").value("HAS_MOVEMENTS"));
	}

	@Test
	void previewOfAnEndingItemAnswersTheNewEndPeriod() throws Exception {
		List<Candidate> candidates = List.of(new Candidate(511, YearMonth.of(2026, 11), false, false),
				new Candidate(512, YearMonth.of(2026, 12), false, false));
		DeletionPreview preview = new DeletionPreview(512, true, EntryOrigin.RECURRING, YearMonth.of(2026, 12), null,
				EntryDeletionPlanner.forRecurring(DeletionScope.ONLY_THIS, 512, new ItemFacts(null, null), candidates),
				EntryDeletionPlanner.forRecurring(DeletionScope.THIS_AND_FUTURE, 512, new ItemFacts(null, null),
						candidates));
		when(entries.deletionPreview(USER_ID, 512L)).thenReturn(preview);

		mvc.perform(get("/api/entries/512/deletion-preview").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.thisAndFuture.itemOutcome").value("ENDS_ITEM"))
				.andExpect(jsonPath("$.thisAndFuture.newEndPeriod").value("2026-11"))
				.andExpect(jsonPath("$.onlyThis.newEndPeriod").doesNotExist());
	}

	@Test
	void previewOfAnEntryWithoutAnItemAnswersOnlyRemoval() throws Exception {
		DeletionPreview preview = new DeletionPreview(900, false, EntryOrigin.ONE_OFF, YearMonth.of(2026, 11),
				EntryDeletionPlanner.forEntryWithoutItem(new Candidate(900, YearMonth.of(2026, 11), false, false)),
				null, null);
		when(entries.deletionPreview(USER_ID, 900L)).thenReturn(preview);

		mvc.perform(get("/api/entries/900/deletion-preview").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recurring").value(false))
				.andExpect(jsonPath("$.removal.allowed").value(true))
				.andExpect(jsonPath("$.removal.entryCount").value(1))
				.andExpect(jsonPath("$.removal.itemOutcome").doesNotExist())
				.andExpect(jsonPath("$.onlyThis").doesNotExist())
				.andExpect(jsonPath("$.thisAndFuture").doesNotExist());
	}

	@Test
	void previewErrorsKeepTheCommonFormat() throws Exception {
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."))
				.when(entries).deletionPreview(anyLong(), anyLong());
		mvc.perform(get("/api/entries/512/deletion-preview").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.PERIOD_CLOSED, "El período 2026-10 está cerrado."))
				.when(entries).deletionPreview(anyLong(), anyLong());
		mvc.perform(get("/api/entries/512/deletion-preview").header("Authorization", bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
	}

	@Test
	void requiresAToken() throws Exception {
		mvc.perform(post("/api/periods/2026-11/entries").contentType(MediaType.APPLICATION_JSON)
				.content(VALID_CREATE)).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/entries/900").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/entries/900?scope=ONLY_THIS")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/entries/900/deletion-preview")).andExpect(status().isUnauthorized());
		verifyNoInteractions(entries);
	}
}
