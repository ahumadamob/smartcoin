package com.smartcoin.report.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.period.domain.MonthTotals.CurrencyTotals;
import com.smartcoin.period.domain.MonthTotals.SideTotals;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.report.service.BudgetGridService;
import com.smartcoin.report.service.BudgetGridService.Cell;
import com.smartcoin.report.service.BudgetGridService.Grid;
import com.smartcoin.report.service.BudgetGridService.Month;
import com.smartcoin.report.service.BudgetGridService.MonthTotal;
import com.smartcoin.report.service.BudgetGridService.Row;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.error.BusinessException;
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

/** HU-36: códigos HTTP, forma de la respuesta y formato de errores de {@code GET /api/budget-grid}. */
@WebMvcTest(BudgetGridController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + BudgetGridControllerTest.SECRET)
class BudgetGridControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	BudgetGridService grid;

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

	private ResultActions request(String query) throws Exception {
		return mvc.perform(get("/api/budget-grid" + query).header("Authorization", bearer()));
	}

	private static Grid grid() {
		Cell edited = new Cell(55L, 1, Currency.ARS, new BigDecimal("240000.00"), EntryStatus.ESTIMATED, true, false);
		Cell done = new Cell(56L, 1, Currency.ARS, new BigDecimal("180000.00"), EntryStatus.CONSOLIDATED, false, true);
		Cell several = new Cell(null, 2, Currency.USD, new BigDecimal("30.00"), null, false, false);
		SideTotals income = new SideTotals(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
				BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
		SideTotals expense = new SideTotals(2, new BigDecimal("420000.00"), new BigDecimal("0.00"),
				new BigDecimal("420000.00"), new BigDecimal("420000.00"));
		return new Grid(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 10),
				YearMonth.of(2026, 8), YearMonth.of(2028, 10),
				List.of(new Month(YearMonth.of(2026, 10), PeriodStatus.CLOSED),
						new Month(YearMonth.of(2026, 11), PeriodStatus.OPEN)),
				List.of(new Row(9L, null, "Resumen Visa", EntryKind.EXPENSE, Currency.ARS, java.util.Arrays.asList(edited, done)),
						new Row(null, EntryOrigin.ONE_OFF, null, EntryKind.EXPENSE, Currency.USD,
								java.util.Arrays.asList(null, several))),
				List.of(new MonthTotal(YearMonth.of(2026, 10),
						List.of(new CurrencyTotals(Currency.ARS, income, expense, new BigDecimal("-420000.00")))),
						new MonthTotal(YearMonth.of(2026, 11), List.of())));
	}

	@Test
	void respondsWithTheMonthsTheRowsTheirCellsAndTheTotals() throws Exception {
		when(grid.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2026, 11))).thenReturn(grid());

		request("?from=2026-10&to=2026-11")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.from").value("2026-10"))
				.andExpect(jsonPath("$.to").value("2026-11"))
				.andExpect(jsonPath("$.currentPeriod").value("2026-10"))
				.andExpect(jsonPath("$.startPeriod").value("2026-08"))
				.andExpect(jsonPath("$.horizon").value("2028-10"))
				.andExpect(jsonPath("$.months[0].period").value("2026-10"))
				.andExpect(jsonPath("$.months[0].status").value("CLOSED"))
				.andExpect(jsonPath("$.months[1].status").value("OPEN"))
				.andExpect(jsonPath("$.rows.length()").value(2))
				.andExpect(jsonPath("$.rows[0].budgetItemId").value(9))
				.andExpect(jsonPath("$.rows[0].name").value("Resumen Visa"))
				.andExpect(jsonPath("$.rows[0].origin").doesNotExist())
				.andExpect(jsonPath("$.rows[0].kind").value("EXPENSE"))
				.andExpect(jsonPath("$.rows[0].currency").value("ARS"))
				.andExpect(jsonPath("$.rows[0].cells[0].entryId").value(55))
				.andExpect(jsonPath("$.rows[0].cells[0].forecast").value(240000.00))
				.andExpect(jsonPath("$.rows[0].cells[0].status").value("ESTIMATED"))
				.andExpect(jsonPath("$.rows[0].cells[0].manual").value(true))
				.andExpect(jsonPath("$.rows[0].cells[0].consolidated").value(false))
				.andExpect(jsonPath("$.rows[0].cells[1].status").value("CONSOLIDATED"))
				.andExpect(jsonPath("$.rows[0].cells[1].consolidated").value(true))
				.andExpect(jsonPath("$.rows[1].budgetItemId").doesNotExist())
				.andExpect(jsonPath("$.rows[1].origin").value("ONE_OFF"))
				.andExpect(jsonPath("$.rows[1].cells[0]").doesNotExist())
				.andExpect(jsonPath("$.rows[1].cells[1].entryCount").value(2))
				.andExpect(jsonPath("$.rows[1].cells[1].entryId").doesNotExist())
				.andExpect(jsonPath("$.totals[0].period").value("2026-10"))
				.andExpect(jsonPath("$.totals[0].currencies[0].currency").value("ARS"))
				.andExpect(jsonPath("$.totals[0].currencies[0].incomeForecast").value(0.00))
				.andExpect(jsonPath("$.totals[0].currencies[0].expenseForecast").value(420000.00))
				.andExpect(jsonPath("$.totals[0].currencies[0].result").value(-420000.00))
				.andExpect(jsonPath("$.totals[1].currencies.length()").value(0));
	}

	@Test
	void withoutParametersItAsksTheServiceForTheDefaultRange() throws Exception {
		when(grid.grid(USER_ID, null, null)).thenReturn(grid());

		request("").andExpect(status().isOk());

		verify(grid).grid(USER_ID, null, null);
	}

	@Test
	void theUserComesFromTheTokenNeverFromTheQuery() throws Exception {
		when(grid.grid(USER_ID, null, null)).thenReturn(grid());

		request("?userId=99").andExpect(status().isOk());

		verify(grid).grid(USER_ID, null, null);
	}

	@Test
	void withoutTokenItIs401() throws Exception {
		mvc.perform(get("/api/budget-grid")).andExpect(status().isUnauthorized());

		verifyNoInteractions(grid);
	}

	@ParameterizedTest
	@ValueSource(strings = { "from=2026-13", "from=11/2026", "to=ayer", "to=2026-10-01", "from=202610" })
	void aMalformedMonthIsAValidationError(String query) throws Exception {
		request("?" + query).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(grid);
	}

	@Test
	void anInvalidRangeIsAValidationErrorOnTheOffendingField() throws Exception {
		when(grid.grid(USER_ID, YearMonth.of(2027, 5), YearMonth.of(2027, 1))).thenThrow(
				BusinessException.invalidField("from", "El mes desde no puede ser posterior al mes hasta."));
		when(grid.grid(USER_ID, YearMonth.of(2026, 8), YearMonth.of(2028, 10))).thenThrow(
				BusinessException.invalidField("to", "El rango no puede tener más de 24 meses."));

		request("?from=2027-05&to=2027-01")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("from"));
		request("?from=2026-08&to=2028-10")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("to"));
	}
}
