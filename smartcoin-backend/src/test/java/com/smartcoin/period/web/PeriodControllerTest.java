package com.smartcoin.period.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.period.domain.MonthTotals;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.service.PeriodViewService;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.period.service.PeriodViewService.View;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
 * HU-15: forma de la respuesta de GET /api/periods/{period} y /api/periods/current, el 400 por formato y el 404 fuera
 * de rango. Servicio simulado. Se importa {@link WebConfig} porque ahí está el convertidor de períodos de la ruta.
 */
@WebMvcTest(PeriodController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class, WebConfig.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + PeriodControllerTest.SECRET)
class PeriodControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	@Autowired
	MockMvc mvc;

	@MockitoBean
	PeriodViewService view;

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

	private ResultActions period(String period) throws Exception {
		return mvc.perform(get("/api/periods/" + period).header("Authorization", bearer()));
	}

	private static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	/** Noviembre de 2026 con un sueldo y la cuota 5 de 12 de la Heladera, editada, vencida y con un pago parcial. */
	private static View november(PeriodStatus status) {
		EntryRow salary = new EntryRow(501L, 31L, EntryOrigin.RECURRING, EntryKind.INCOME, "Sueldo", null, null, 12L,
				"Banco Nación", Currency.ARS, LocalDate.of(2026, 10, 25), null, null, amount("650000.00"),
				EntryAmounts.of(amount("650000.00"), StoredEntryStatus.PENDING, null, null), false, false);
		EntryRow fridge = new EntryRow(502L, 32L, EntryOrigin.RECURRING, EntryKind.EXPENSE, "Heladera", 3L, "Hogar",
				12L, "Banco Nación", Currency.ARS, LocalDate.of(2026, 11, 15), 5, 12, amount("85000.00"),
				EntryAmounts.of(amount("85000.00"), StoredEntryStatus.PENDING, null, amount("20000.50")), true, true);
		List<MonthTotals.CurrencyTotals> totals = MonthTotals.calculate(List.of(
				new MonthTotals.Line(EntryKind.INCOME, Currency.ARS, salary.budgetedAmount(),
						salary.amounts().actual(), salary.amounts().pending(), salary.amounts().forecast()),
				new MonthTotals.Line(EntryKind.EXPENSE, Currency.ARS, fridge.budgetedAmount(),
						fridge.amounts().actual(), fridge.amounts().pending(), fridge.amounts().forecast())));
		return new View(NOVEMBER, status, YearMonth.of(2026, 8), YearMonth.of(2026, 10), YearMonth.of(2028, 10),
				List.of(salary), List.of(fridge), totals);
	}

	@Test
	void respondsThePeriodItsRangeItsEntriesAndItsTotals() throws Exception {
		when(view.view(USER_ID, NOVEMBER)).thenReturn(november(PeriodStatus.OPEN));

		String body = period("2026-11")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.period").value("2026-11"))
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.startPeriod").value("2026-08"))
				.andExpect(jsonPath("$.currentPeriod").value("2026-10"))
				.andExpect(jsonPath("$.horizon").value("2028-10"))
				.andExpect(jsonPath("$.incomes.length()").value(1))
				.andExpect(jsonPath("$.incomes[0].id").value(501))
				.andExpect(jsonPath("$.incomes[0].name").value("Sueldo"))
				.andExpect(jsonPath("$.incomes[0].kind").value("INCOME"))
				.andExpect(jsonPath("$.incomes[0].categoryId").doesNotExist())
				.andExpect(jsonPath("$.incomes[0].categoryName").doesNotExist())
				.andExpect(jsonPath("$.incomes[0].installmentNumber").doesNotExist())
				.andExpect(jsonPath("$.incomes[0].installmentsTotal").doesNotExist())
				.andExpect(jsonPath("$.incomes[0].dueDate").value("2026-10-25"))
				.andExpect(jsonPath("$.incomes[0].status").value("ESTIMATED"))
				.andExpect(jsonPath("$.incomes[0].manual").value(false))
				.andExpect(jsonPath("$.incomes[0].overdue").value(false))
				.andExpect(jsonPath("$.expenses.length()").value(1))
				.andExpect(jsonPath("$.expenses[0].id").value(502))
				.andExpect(jsonPath("$.expenses[0].budgetItemId").value(32))
				.andExpect(jsonPath("$.expenses[0].origin").value("RECURRING"))
				.andExpect(jsonPath("$.expenses[0].kind").value("EXPENSE"))
				.andExpect(jsonPath("$.expenses[0].name").value("Heladera"))
				.andExpect(jsonPath("$.expenses[0].categoryId").value(3))
				.andExpect(jsonPath("$.expenses[0].categoryName").value("Hogar"))
				.andExpect(jsonPath("$.expenses[0].accountId").value(12))
				.andExpect(jsonPath("$.expenses[0].accountName").value("Banco Nación"))
				.andExpect(jsonPath("$.expenses[0].currency").value("ARS"))
				.andExpect(jsonPath("$.expenses[0].dueDate").value("2026-11-15"))
				.andExpect(jsonPath("$.expenses[0].installmentNumber").value(5))
				.andExpect(jsonPath("$.expenses[0].installmentsTotal").value(12))
				.andExpect(jsonPath("$.expenses[0].budgetedAmount").value(85000.00))
				.andExpect(jsonPath("$.expenses[0].actualAmount").value(20000.50))
				.andExpect(jsonPath("$.expenses[0].pendingAmount").value(64999.50))
				.andExpect(jsonPath("$.expenses[0].forecastAmount").value(85000.00))
				.andExpect(jsonPath("$.expenses[0].status").value("PARTIAL"))
				.andExpect(jsonPath("$.expenses[0].manual").value(true))
				.andExpect(jsonPath("$.expenses[0].overdue").value(true))
				.andExpect(jsonPath("$.totals.length()").value(1))
				.andExpect(jsonPath("$.totals[0].currency").value("ARS"))
				.andExpect(jsonPath("$.totals[0].income.entryCount").value(1))
				.andExpect(jsonPath("$.totals[0].income.budgetedAmount").value(650000.00))
				.andExpect(jsonPath("$.totals[0].income.actualAmount").value(0.00))
				.andExpect(jsonPath("$.totals[0].income.pendingAmount").value(650000.00))
				.andExpect(jsonPath("$.totals[0].income.forecastAmount").value(650000.00))
				.andExpect(jsonPath("$.totals[0].expense.entryCount").value(1))
				.andExpect(jsonPath("$.totals[0].expense.budgetedAmount").value(85000.00))
				.andExpect(jsonPath("$.totals[0].expense.actualAmount").value(20000.50))
				.andExpect(jsonPath("$.totals[0].expense.pendingAmount").value(64999.50))
				.andExpect(jsonPath("$.totals[0].expense.forecastAmount").value(85000.00))
				.andExpect(jsonPath("$.totals[0].result").value(565000.00))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		// Los montos viajan como número con 2 decimales (T-12), también los que valen 0.
		assertThat(body).contains("\"result\":565000.00").contains("\"actualAmount\":0.00")
				.contains("\"pendingAmount\":64999.50");
	}

	@Test
	void aClosedAndEmptyPeriodHasEmptyListsAndItsStatus() throws Exception {
		when(view.view(USER_ID, YearMonth.of(2026, 8))).thenReturn(new View(YearMonth.of(2026, 8),
				PeriodStatus.CLOSED, YearMonth.of(2026, 8), YearMonth.of(2026, 10), YearMonth.of(2028, 10), List.of(),
				List.of(), List.of()));

		period("2026-08")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLOSED"))
				.andExpect(jsonPath("$.incomes.length()").value(0))
				.andExpect(jsonPath("$.expenses.length()").value(0))
				.andExpect(jsonPath("$.totals.length()").value(0));
	}

	@Test
	void currentRespondsTheViewOfTheCurrentPeriodOfTheUserFromTheToken() throws Exception {
		when(view.current(USER_ID)).thenReturn(november(PeriodStatus.OPEN));

		period("current").andExpect(status().isOk()).andExpect(jsonPath("$.period").value("2026-11"));

		verify(view).current(USER_ID);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-13", "2026-00", "2026-1", "202611", "2026-11-01", "noviembre", "actual", "11-2026" })
	void aPeriodWithAnInvalidFormatIsAValidationError(String period) throws Exception {
		period(period)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(view);
	}

	@Test
	void aPeriodOutOfRangeIsNotFound() throws Exception {
		when(view.view(USER_ID, YearMonth.of(2030, 1)))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "El período 2030-01 no existe."));

		period("2030-01")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("El período 2030-01 no existe."));
	}

	@Test
	void requiresAToken() throws Exception {
		mvc.perform(get("/api/periods/2026-11")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/periods/current")).andExpect(status().isUnauthorized());
		verifyNoInteractions(view);
	}
}
