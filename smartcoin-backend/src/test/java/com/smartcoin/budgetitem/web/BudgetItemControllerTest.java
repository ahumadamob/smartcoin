package com.smartcoin.budgetitem.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.account.domain.Account;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.BudgetItemValues;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.service.BudgetItemService;
import com.smartcoin.budgetitem.service.BudgetItemService.Created;
import com.smartcoin.category.domain.Category;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.period.domain.BudgetPeriod;
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
import org.junit.jupiter.params.provider.CsvSource;
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

/** HU-10: códigos HTTP, formato de errores y forma de la respuesta de POST /api/budget-items. Servicio simulado. */
@WebMvcTest(BudgetItemController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + BudgetItemControllerTest.SECRET)
class BudgetItemControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	BudgetItemService items;

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

	private ResultActions create(String body) throws Exception {
		return mvc.perform(post("/api/budget-items").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** Cuerpo válido, con un campo reemplazado o quitado ({@code value} nulo lo quita). */
	private static String body(String field, String value) {
		var fields = new java.util.LinkedHashMap<String, String>();
		fields.put("name", "\"Sueldo A\"");
		fields.put("kind", "\"INCOME\"");
		fields.put("defaultAccountId", "12");
		fields.put("categoryId", "3");
		fields.put("periodicity", "\"MONTHLY\"");
		fields.put("dueDay", "25");
		fields.put("dueMonthOffset", "-1");
		fields.put("startPeriod", "\"2026-11\"");
		fields.put("endPeriod", "\"2027-01\"");
		fields.put("estimationRule", "\"LAST_VALUE\"");
		fields.put("currentAmount", "1200000.50");
		if (field != null) {
			if (value == null) {
				fields.remove(field);
			}
			else {
				fields.put(field, value);
			}
		}
		return fields.entrySet().stream().map(e -> "\"" + e.getKey() + "\": " + e.getValue())
				.collect(java.util.stream.Collectors.joining(", ", "{", "}"));
	}

	private static final String VALID_BODY = body(null, null);

	/** "Sueldo A": 3 partidas de 2026-11 a 2027-01 que vencen el 25 del mes anterior. */
	private static Created created() {
		Account account = new Account();
		ReflectionTestUtils.setField(account, "id", 12L);
		account.setCurrency(Currency.ARS);
		Category category = new Category();
		ReflectionTestUtils.setField(category, "id", 3L);
		BudgetItem item = new BudgetItem();
		ReflectionTestUtils.setField(item, "id", 31L);
		item.setUserId(USER_ID);
		item.setName("Sueldo A");
		item.setKind(EntryKind.INCOME);
		item.setDefaultAccount(account);
		item.setCategory(category);
		item.setPeriodicity(Periodicity.MONTHLY);
		item.setDueDay(25);
		item.setDueMonthOffset(-1);
		item.setStartPeriod(YearMonth.of(2026, 11));
		item.setEndPeriod(YearMonth.of(2027, 1));
		item.setEstimationRule(EstimationRule.LAST_VALUE);
		item.setCurrentAmount(new BigDecimal("1200000.50"));
		return new Created(item, List.of(entry(YearMonth.of(2026, 11)), entry(YearMonth.of(2026, 12)),
				entry(YearMonth.of(2027, 1))));
	}

	/** "Heladera": 12 cuotas, primera 4, de 2026-10 a 2027-06; la respuesta resume las dos primeras partidas. */
	private static Created createdInInstallments() {
		Created plain = created();
		BudgetItem item = plain.item();
		item.setName("Heladera");
		item.setStartPeriod(YearMonth.of(2026, 10));
		item.setEndPeriod(YearMonth.of(2027, 6));
		item.setInstallmentsTotal(12);
		item.setFirstInstallmentNumber(4);
		BudgetEntry first = entry(YearMonth.of(2026, 10));
		first.setInstallmentNumber(4);
		BudgetEntry second = entry(YearMonth.of(2026, 11));
		second.setInstallmentNumber(5);
		return new Created(item, List.of(first, second));
	}

	private static BudgetEntry entry(YearMonth period) {
		BudgetEntry entry = new BudgetEntry();
		entry.setPeriod(BudgetPeriod.open(USER_ID, period));
		entry.setDueDate(period.minusMonths(1).atDay(25));
		return entry;
	}

	@Test
	void createRespondsCreatedWithTheItemItsCurrencyAndTheGenerationSummary() throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(created());

		create(VALID_BODY)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/budget-items/31"))
				.andExpect(jsonPath("$.id").value(31))
				.andExpect(jsonPath("$.name").value("Sueldo A"))
				.andExpect(jsonPath("$.kind").value("INCOME"))
				.andExpect(jsonPath("$.defaultAccountId").value(12))
				.andExpect(jsonPath("$.currency").value("ARS"))
				.andExpect(jsonPath("$.categoryId").value(3))
				.andExpect(jsonPath("$.periodicity").value("MONTHLY"))
				.andExpect(jsonPath("$.dueDay").value(25))
				.andExpect(jsonPath("$.dueMonthOffset").value(-1))
				.andExpect(jsonPath("$.startPeriod").value("2026-11"))
				.andExpect(jsonPath("$.endPeriod").value("2027-01"))
				.andExpect(jsonPath("$.estimationRule").value("LAST_VALUE"))
				.andExpect(jsonPath("$.currentAmount").value(1200000.50))
				.andExpect(jsonPath("$.generation.entryCount").value(3))
				.andExpect(jsonPath("$.generation.firstPeriod").value("2026-11"))
				.andExpect(jsonPath("$.generation.lastPeriod").value("2027-01"))
				.andExpect(jsonPath("$.generation.firstDueDate").value("2026-10-25"))
				.andExpect(jsonPath("$.userId").doesNotExist());
	}

	@Test
	void theBodyReachesTheServiceWithTheUserOfTheTokenNotOneFromTheBody() throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(created());

		create(body("userId", "999")).andExpect(status().isCreated());

		ArgumentCaptor<BudgetItemValues> values = ArgumentCaptor.forClass(BudgetItemValues.class);
		verify(items).create(eq(USER_ID), values.capture());
		assertThat(values.getValue()).isEqualTo(new BudgetItemValues("Sueldo A", EntryKind.INCOME, 12L, 3L,
				Periodicity.MONTHLY, 25, -1, YearMonth.of(2026, 11), YearMonth.of(2027, 1), EstimationRule.LAST_VALUE,
				new BigDecimal("1200000.50")));
	}

	@Test
	void categoryAndEndPeriodAreOptional() throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(created());

		for (String body : List.of(body("categoryId", null), body("categoryId", "null"), body("endPeriod", null),
				body("endPeriod", "null"))) {
			create(body).andExpect(status().isCreated());
		}
	}

	@Test
	void installmentDataReachesTheServiceAndTheEndPeriodIsSentAsNullWhenItIsLeftOut() throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(created());

		create(body("endPeriod", null).replace("}", ", \"installmentsTotal\": 12, \"firstInstallmentNumber\": 4}"))
				.andExpect(status().isCreated());

		ArgumentCaptor<BudgetItemValues> values = ArgumentCaptor.forClass(BudgetItemValues.class);
		verify(items).create(eq(USER_ID), values.capture());
		assertThat(values.getValue().installmentsTotal()).isEqualTo(12);
		assertThat(values.getValue().firstInstallmentNumber()).isEqualTo(4);
		assertThat(values.getValue().endPeriod()).isNull();
	}

	@Test
	void installmentDataIsOptionalAndNullIsTheSameAsMissing() throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(created());

		for (String body : List.of(VALID_BODY, body("installmentsTotal", "null"), body("firstInstallmentNumber", "null"))) {
			create(body).andExpect(status().isCreated());
		}
	}

	@Test
	void theResponseOfAnInstallmentPlanCarriesItsInstallmentData() throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(createdInInstallments());

		create(body("installmentsTotal", "12"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.installmentsTotal").value(12))
				.andExpect(jsonPath("$.firstInstallmentNumber").value(4))
				.andExpect(jsonPath("$.endPeriod").value("2027-06"))
				.andExpect(jsonPath("$.generation.entryCount").value(2))
				.andExpect(jsonPath("$.generation.firstInstallment").value(4))
				.andExpect(jsonPath("$.generation.lastInstallment").value(5));
	}

	@Test
	void theResponseOfAPlainConceptHasNullInstallmentData() throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(created());

		create(VALID_BODY)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.installmentsTotal").value((Object) null))
				.andExpect(jsonPath("$.firstInstallmentNumber").value((Object) null))
				.andExpect(jsonPath("$.generation.firstInstallment").value((Object) null))
				.andExpect(jsonPath("$.generation.lastInstallment").value((Object) null));
	}

	@ParameterizedTest(name = "{0} = {1}")
	@CsvSource({
			"installmentsTotal, 0",
			"installmentsTotal, -1",
			"installmentsTotal, 361",
			"installmentsTotal, 32767",
			"firstInstallmentNumber, 0",
			"firstInstallmentNumber, -2",
	})
	void installmentNumbersOutOfRangeRespondValidationErrorWithAnErrorForThatField(String field, String value)
			throws Exception {
		create(body(field, value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
		verifyNoInteractions(items);
	}

	@ParameterizedTest(name = "{0} = {1}")
	@CsvSource({ "installmentsTotal, \"doce\"", "firstInstallmentNumber, \"cuatro\"" })
	void installmentNumbersThatCannotBeReadRespondValidationError(String field, String value) throws Exception {
		create(body(field, value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(items);
	}

	@ParameterizedTest
	@CsvSource({ "installmentsTotal, 1", "installmentsTotal, 360", "firstInstallmentNumber, 1" })
	void installmentNumbersOnTheLimitsAreAccepted(String field, String value) throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(created());

		create(body(field, value)).andExpect(status().isCreated());
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"installmentsTotal | El total de cuotas es obligatorio si se informa la primera cuota.",
			"firstInstallmentNumber | La primera cuota no puede ser mayor que el total de cuotas.",
			"endPeriod | En un Concepto en cuotas el período de fin se calcula: no se informa.",
	})
	void theInstallmentErrorsOfTheServiceRespondValidationErrorWithTheErrorOfTheirField(String field, String message)
			throws Exception {
		when(items.create(eq(USER_ID), any())).thenThrow(BusinessException.invalidField(field, message));

		create(VALID_BODY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value(message))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").value(message));
	}

	@ParameterizedTest(name = "{0} = {1}")
	@CsvSource(nullValues = "(falta)", delimiter = '|', value = {
			"name | (falta)",
			"name | \"   \"",
			"kind | (falta)",
			"defaultAccountId | (falta)",
			"periodicity | (falta)",
			"dueDay | (falta)",
			"dueDay | 0",
			"dueDay | 32",
			"dueMonthOffset | (falta)",
			"dueMonthOffset | 1",
			"dueMonthOffset | -2",
			"startPeriod | (falta)",
			"estimationRule | (falta)",
			"currentAmount | (falta)",
			"currentAmount | -0.01",
			"currentAmount | 10.005",
	})
	void missingOrOutOfRangeFieldsRespondValidationErrorWithAnErrorForThatField(String field, String value)
			throws Exception {
		create(body(field, value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
		verifyNoInteractions(items);
	}

	@Test
	void aNameLongerThan100CharactersIsAValidationError() throws Exception {
		create(body("name", "\"" + "x".repeat(101) + "\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("name"));
		verifyNoInteractions(items);
	}

	@ParameterizedTest(name = "{0} = {1}")
	@CsvSource(delimiter = '|', value = {
			"startPeriod | \"2026-13\"",
			"startPeriod | \"octubre\"",
			"endPeriod | \"2026-11-01\"",
			"kind | \"TRANSFER\"",
			"periodicity | \"WEEKLY\"",
			"estimationRule | \"INFLATION\"",
			"dueDay | \"veinte\"",
			"currentAmount | \"mil\"",
	})
	void valuesThatCannotBeReadRespondValidationError(String field, String value) throws Exception {
		create(body(field, value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(items);
	}

	@ParameterizedTest
	@CsvSource({ "dueDay, 1", "dueDay, 31", "dueMonthOffset, 0", "currentAmount, 0", "currentAmount, 0.01" })
	void valuesOnTheLimitAreAccepted(String field, String value) throws Exception {
		when(items.create(eq(USER_ID), any())).thenReturn(created());

		create(body(field, value)).andExpect(status().isCreated());
	}

	@Test
	void anEndBeforeTheStartRespondsValidationErrorWithTheErrorOfTheEndPeriod() throws Exception {
		when(items.create(eq(USER_ID), any())).thenThrow(BusinessException.invalidField("endPeriod",
				"El período de fin no puede ser anterior al período de inicio."));

		create(body("endPeriod", "\"2026-10\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.detail").value("El período de fin no puede ser anterior al período de inicio."))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value("endPeriod"))
				.andExpect(jsonPath("$.errors[0].message")
						.value("El período de fin no puede ser anterior al período de inicio."));
	}

	@Test
	void anAccountOrCategoryOfAnotherUserRespondsValidationErrorOfItsField() throws Exception {
		when(items.create(eq(USER_ID), any()))
				.thenThrow(BusinessException.invalidField("defaultAccountId", "La cuenta por defecto no existe."));
		create(VALID_BODY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("defaultAccountId"))
				.andExpect(jsonPath("$.errors[0].message").value("La cuenta por defecto no existe."));
	}

	@Test
	void aStartPeriodOutOfRangeRespondsConflictWithTheErrorFormat() throws Exception {
		when(items.create(eq(USER_ID), any())).thenThrow(new BusinessException(ErrorCode.PERIOD_NOT_AVAILABLE,
				"El período de inicio debe estar entre 2026-08 (el primer período abierto) y 2028-10 (el horizonte)."));

		create(VALID_BODY)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Período no disponible"))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.code").value("PERIOD_NOT_AVAILABLE"))
				.andExpect(jsonPath("$.detail").value(
						"El período de inicio debe estar entre 2026-08 (el primer período abierto) y 2028-10 (el horizonte)."))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@Test
	void withoutTokenIsUnauthorized() throws Exception {
		mvc.perform(post("/api/budget-items").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		verifyNoInteractions(items);
	}
}
