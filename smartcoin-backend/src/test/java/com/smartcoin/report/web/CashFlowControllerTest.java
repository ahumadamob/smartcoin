package com.smartcoin.report.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.smartcoin.report.domain.CashFlowCalculator.CashFlowRowStatus;
import com.smartcoin.report.domain.CashFlowCalculator.CashFlowRowType;
import com.smartcoin.report.domain.CashFlowCalculator.Result;
import com.smartcoin.report.domain.CashFlowCalculator.Row;
import com.smartcoin.report.service.CashFlowService;
import com.smartcoin.report.service.CashFlowService.CashFlow;
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

/** HU-34: códigos HTTP, forma de la respuesta y formato de errores de {@code GET /api/cashflow}. */
@WebMvcTest(CashFlowController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + CashFlowControllerTest.SECRET)
class CashFlowControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CashFlowService cashFlow;

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
		return mvc.perform(get("/api/cashflow" + query).header("Authorization", bearer()));
	}

	private static CashFlow flow() {
		Row real = new Row(LocalDate.of(2026, 11, 5), CashFlowRowStatus.REAL, CashFlowRowType.MOVEMENT,
				"Sueldo · Banco", new BigDecimal("400000.00"), new BigDecimal("450000.00"), null, false);
		Row planned = new Row(LocalDate.of(2026, 11, 17), CashFlowRowStatus.PLANNED, CashFlowRowType.PENDING_ENTRY,
				"Luz · Banco", new BigDecimal("-500000.00"), new BigDecimal("-50000.00"), LocalDate.of(2026, 11, 10),
				true);
		return new CashFlow(Currency.ARS, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
				new Result(new BigDecimal("50000.00"), true, List.of(real, planned), new BigDecimal("-50000.00")));
	}

	@Test
	void respondsWithTheFlowItsRowsAndTheirAccumulatedBalance() throws Exception {
		when(cashFlow.cashFlow(eq(USER_ID), eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 30)),
				eq(Currency.ARS))).thenReturn(flow());

		request("?from=2026-11-01&to=2026-11-30&currency=ARS")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("ARS"))
				.andExpect(jsonPath("$.from").value("2026-11-01"))
				.andExpect(jsonPath("$.to").value("2026-11-30"))
				.andExpect(jsonPath("$.openingBalance").value(50000.00))
				.andExpect(jsonPath("$.openingBalanceDate").value("2026-10-31"))
				.andExpect(jsonPath("$.openingBalanceProjected").value(true))
				.andExpect(jsonPath("$.closingBalance").value(-50000.00))
				.andExpect(jsonPath("$.rows.length()").value(2))
				.andExpect(jsonPath("$.rows[0].status").value("REAL"))
				.andExpect(jsonPath("$.rows[0].type").value("MOVEMENT"))
				.andExpect(jsonPath("$.rows[0].amount").value(400000.00))
				.andExpect(jsonPath("$.rows[0].balance").value(450000.00))
				.andExpect(jsonPath("$.rows[0].dueDate").doesNotExist())
				.andExpect(jsonPath("$.rows[0].overdue").value(false))
				.andExpect(jsonPath("$.rows[1].status").value("PLANNED"))
				.andExpect(jsonPath("$.rows[1].type").value("PENDING_ENTRY"))
				.andExpect(jsonPath("$.rows[1].balance").value(-50000.00))
				.andExpect(jsonPath("$.rows[1].dueDate").value("2026-11-10"))
				.andExpect(jsonPath("$.rows[1].overdue").value(true));
	}

	@Test
	void withoutDatesItAsksTheServiceForTheDefaultRange() throws Exception {
		when(cashFlow.cashFlow(USER_ID, null, null, Currency.USD)).thenReturn(flow());

		request("?currency=USD").andExpect(status().isOk());

		verify(cashFlow).cashFlow(USER_ID, null, null, Currency.USD);
	}

	@Test
	void theUserComesFromTheTokenNeverFromTheQuery() throws Exception {
		when(cashFlow.cashFlow(eq(USER_ID), any(), any(), any())).thenReturn(flow());

		request("?currency=ARS&userId=99").andExpect(status().isOk());

		verify(cashFlow).cashFlow(USER_ID, null, null, Currency.ARS);
	}

	@Test
	void withoutTokenItIs401() throws Exception {
		mvc.perform(get("/api/cashflow?currency=ARS")).andExpect(status().isUnauthorized());

		verifyNoInteractions(cashFlow);
	}

	@Test
	void aMissingCurrencyIsAValidationError() throws Exception {
		request("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(cashFlow);
	}

	@ParameterizedTest
	@ValueSource(strings = { "EUR", "ars", "", "1" })
	void anInvalidCurrencyIsAValidationError(String currency) throws Exception {
		request("?currency=" + currency).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(cashFlow);
	}

	@ParameterizedTest
	@ValueSource(strings = { "from=2026-13-01", "from=01/11/2026", "to=ayer", "from=2026-11-31" })
	void aMalformedDateIsAValidationError(String dateParam) throws Exception {
		request("?currency=ARS&" + dateParam).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(cashFlow);
	}

	@Test
	void anInvertedRangeIsAValidationErrorOnFrom() throws Exception {
		when(cashFlow.cashFlow(eq(USER_ID), any(), any(), any())).thenThrow(
				BusinessException.invalidField("from", "La fecha desde no puede ser posterior a la fecha hasta."));

		request("?currency=ARS&from=2026-11-10&to=2026-11-01")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("from"));
	}
}
