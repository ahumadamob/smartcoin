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
import com.smartcoin.report.domain.ProjectionCalculator.AccountBalance;
import com.smartcoin.report.domain.ProjectionCalculator.AccountStart;
import com.smartcoin.report.domain.ProjectionCalculator.CurrencySubtotal;
import com.smartcoin.report.domain.ProjectionCalculator.Month;
import com.smartcoin.report.domain.ProjectionCalculator.Result;
import com.smartcoin.report.service.ProjectionService;
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

/** HU-35: códigos HTTP, forma de la respuesta y formato de errores de {@code GET /api/projection}. */
@WebMvcTest(ProjectionController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + ProjectionControllerTest.SECRET)
class ProjectionControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ProjectionService projection;

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
		return mvc.perform(get("/api/projection" + query).header("Authorization", bearer()));
	}

	private static Result result() {
		Month october = new Month(YearMonth.of(2026, 10),
				List.of(new AccountBalance(1, new BigDecimal("340000.00")), new AccountBalance(3, new BigDecimal("-5.00"))),
				List.of(new CurrencySubtotal(Currency.ARS, new BigDecimal("340000.00")),
						new CurrencySubtotal(Currency.USD, new BigDecimal("-5.00"))));
		return new Result(YearMonth.of(2026, 10), YearMonth.of(2026, 10),
				List.of(new AccountStart(1, "Banco", Currency.ARS, new BigDecimal("100000.00")),
						new AccountStart(3, "Caja USD", Currency.USD, new BigDecimal("500.00"))),
				List.of(october));
	}

	@Test
	void respondsWithTheAccountsAndTheMonthsWithTheirSubtotals() throws Exception {
		when(projection.projection(USER_ID, YearMonth.of(2026, 10))).thenReturn(result());

		request("?until=2026-10")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.from").value("2026-10"))
				.andExpect(jsonPath("$.until").value("2026-10"))
				.andExpect(jsonPath("$.accounts.length()").value(2))
				.andExpect(jsonPath("$.accounts[0].name").value("Banco"))
				.andExpect(jsonPath("$.accounts[1].currency").value("USD"))
				.andExpect(jsonPath("$.months[0].month").value("2026-10"))
				.andExpect(jsonPath("$.months[0].balances[0].accountId").value(1))
				.andExpect(jsonPath("$.months[0].balances[0].balance").value(340000.00))
				.andExpect(jsonPath("$.months[0].balances[1].balance").value(-5.00))
				.andExpect(jsonPath("$.months[0].subtotals[0].currency").value("ARS"))
				.andExpect(jsonPath("$.months[0].subtotals[1].balance").value(-5.00));
	}

	@Test
	void withoutUntilItAsksTheServiceForTheHorizon() throws Exception {
		when(projection.projection(USER_ID, null)).thenReturn(result());

		request("").andExpect(status().isOk());

		verify(projection).projection(USER_ID, null);
	}

	@Test
	void theUserComesFromTheTokenNeverFromTheQuery() throws Exception {
		when(projection.projection(USER_ID, null)).thenReturn(result());

		request("?userId=99").andExpect(status().isOk());

		verify(projection).projection(USER_ID, null);
	}

	@Test
	void withoutTokenItIs401() throws Exception {
		mvc.perform(get("/api/projection")).andExpect(status().isUnauthorized());

		verifyNoInteractions(projection);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-13", "11/2026", "ayer", "2026-10-01", "202610" })
	void aMalformedUntilIsAValidationError(String until) throws Exception {
		request("?until=" + until).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(projection);
	}

	@Test
	void anUntilOutOfRangeIsAValidationErrorOnUntil() throws Exception {
		when(projection.projection(USER_ID, YearMonth.of(2030, 1))).thenThrow(
				BusinessException.invalidField("until", "El mes hasta no puede ser posterior al horizonte (2028-10)."));

		request("?until=2030-01")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("until"));
	}
}
