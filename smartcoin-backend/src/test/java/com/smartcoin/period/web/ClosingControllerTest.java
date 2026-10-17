package com.smartcoin.period.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.period.domain.ClosingBlockReason;
import com.smartcoin.period.domain.ClosingPlanner;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.service.ClosingService;
import com.smartcoin.period.service.ClosingService.Blocker;
import com.smartcoin.period.service.ClosingService.Preview;
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
 * HU-30: forma de la respuesta de POST /api/periods/{period}/closing-preview, el 400 por formato, el 404 fuera de
 * rango y el cuerpo ignorado (D-42). Servicio simulado. Se importa {@link WebConfig} por el convertidor de períodos.
 */
@WebMvcTest(ClosingController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class, WebConfig.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + ClosingControllerTest.SECRET)
class ClosingControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final YearMonth OCTOBER = YearMonth.of(2026, 10);

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ClosingService closing;

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

	private ResultActions preview(String period) throws Exception {
		return mvc.perform(post("/api/periods/" + period + "/closing-preview").header("Authorization", bearer()));
	}

	private static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	/** Octubre con el anterior abierto, sin terminar, la luz con un pago parcial y dos cuentas. */
	private static Preview october() {
		EntryLine power = new EntryLine(512L, "Luz", EntryKind.EXPENSE, 12L, "Banco Nación", Currency.ARS,
				LocalDate.of(2026, 10, 18), amount("45000.00"),
				EntryAmounts.of(amount("45000.00"), StoredEntryStatus.PENDING, null, amount("20000.00")));
		AccountLine bank = new AccountLine(12L, "Banco Nación", Currency.ARS, LocalDate.of(2026, 8, 1),
				amount("-1500.50"));
		AccountLine dollars = new AccountLine(13L, "Dólares", Currency.USD, LocalDate.of(2026, 9, 1),
				amount("0.00"));
		var plan = ClosingPlanner.plan(new ClosingPlanner.Input(OCTOBER, PeriodStatus.OPEN, PeriodStatus.OPEN, false,
				LocalDate.of(2026, 10, 16), List.of(power), List.of(bank, dollars)));
		return new Preview(plan, PeriodStatus.OPEN, YearMonth.of(2026, 8), List.of(
				new Blocker(ClosingBlockReason.PREVIOUS_PERIOD_OPEN, "El período anterior, 2026-09, sigue abierto."),
				new Blocker(ClosingBlockReason.PERIOD_NOT_FINISHED, "El período 2026-10 termina el 31/10/2026.")));
	}

	@Test
	void respondsThePreconditionsThePendingEntriesAndTheAccounts() throws Exception {
		when(closing.preview(USER_ID, OCTOBER)).thenReturn(october());

		String body = preview("2026-10")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.period").value("2026-10"))
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.lastDay").value("2026-10-31"))
				.andExpect(jsonPath("$.firstOpenPeriod").value("2026-08"))
				.andExpect(jsonPath("$.preconditionsMet").value(false))
				.andExpect(jsonPath("$.blockers.length()").value(2))
				.andExpect(jsonPath("$.blockers[0].code").value("PREVIOUS_PERIOD_OPEN"))
				.andExpect(jsonPath("$.blockers[0].detail").value("El período anterior, 2026-09, sigue abierto."))
				.andExpect(jsonPath("$.blockers[1].code").value("PERIOD_NOT_FINISHED"))
				.andExpect(jsonPath("$.pendingEntries.length()").value(1))
				.andExpect(jsonPath("$.pendingEntries[0].id").value(512))
				.andExpect(jsonPath("$.pendingEntries[0].name").value("Luz"))
				.andExpect(jsonPath("$.pendingEntries[0].kind").value("EXPENSE"))
				.andExpect(jsonPath("$.pendingEntries[0].accountId").value(12))
				.andExpect(jsonPath("$.pendingEntries[0].accountName").value("Banco Nación"))
				.andExpect(jsonPath("$.pendingEntries[0].currency").value("ARS"))
				.andExpect(jsonPath("$.pendingEntries[0].dueDate").value("2026-10-18"))
				.andExpect(jsonPath("$.pendingEntries[0].budgetedAmount").value(45000.00))
				.andExpect(jsonPath("$.pendingEntries[0].actualAmount").value(20000.00))
				.andExpect(jsonPath("$.pendingEntries[0].pendingAmount").value(25000.00))
				.andExpect(jsonPath("$.pendingEntries[0].status").value("PARTIAL"))
				.andExpect(jsonPath("$.accounts.length()").value(2))
				.andExpect(jsonPath("$.accounts[0].id").value(12))
				.andExpect(jsonPath("$.accounts[0].name").value("Banco Nación"))
				.andExpect(jsonPath("$.accounts[0].currency").value("ARS"))
				.andExpect(jsonPath("$.accounts[0].openingDate").value("2026-08-01"))
				.andExpect(jsonPath("$.accounts[0].computedBalance").value(-1500.50))
				.andExpect(jsonPath("$.accounts[1].currency").value("USD"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		// Montos como número con 2 decimales (T-12), también el 0 y los negativos.
		assertThat(body).contains("\"computedBalance\":-1500.50").contains("\"computedBalance\":0.00")
				.contains("\"pendingAmount\":25000.00");
	}

	@Test
	void aClosedPeriodRespondsOkWithPeriodClosedAndEmptyLists() throws Exception {
		var plan = ClosingPlanner.plan(new ClosingPlanner.Input(YearMonth.of(2026, 8), PeriodStatus.CLOSED, null,
				true, LocalDate.of(2026, 10, 16), List.of(), List.of()));
		when(closing.preview(USER_ID, YearMonth.of(2026, 8))).thenReturn(new Preview(plan, PeriodStatus.CLOSED,
				YearMonth.of(2026, 9),
				List.of(new Blocker(ClosingBlockReason.PERIOD_CLOSED, "El período 2026-08 ya está cerrado."))));

		preview("2026-08")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLOSED"))
				.andExpect(jsonPath("$.preconditionsMet").value(false))
				.andExpect(jsonPath("$.blockers[0].code").value("PERIOD_CLOSED"))
				.andExpect(jsonPath("$.pendingEntries.length()").value(0))
				.andExpect(jsonPath("$.accounts.length()").value(0));
	}

	/** D-42: la simulación de HU-30 no lleva cuerpo; si llega uno, con resoluciones o saldos, se ignora. */
	@Test
	void aBodyIsIgnored() throws Exception {
		when(closing.preview(USER_ID, OCTOBER)).thenReturn(october());

		mvc.perform(post("/api/periods/2026-10/closing-preview").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"resolutions\":[{\"entryId\":512,\"resolution\":\"CARRY_OVER\"}],"
						+ "\"realBalances\":[{\"accountId\":12,\"realBalance\":100}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pendingEntries.length()").value(1));

		verify(closing).preview(USER_ID, OCTOBER);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-13", "2026-00", "2026-1", "202610", "2026-10-01", "octubre", "current" })
	void aPeriodWithAnInvalidFormatIsAValidationError(String period) throws Exception {
		preview(period)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(closing);
	}

	@Test
	void aPeriodOutOfRangeIsNotFound() throws Exception {
		when(closing.preview(USER_ID, YearMonth.of(2030, 1)))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "El período 2030-01 no existe."));

		preview("2030-01")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void onlyPostIsAllowed() throws Exception {
		mvc.perform(get("/api/periods/2026-10/closing-preview").header("Authorization", bearer()))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
		verifyNoInteractions(closing);
	}

	@Test
	void requiresAToken() throws Exception {
		mvc.perform(post("/api/periods/2026-10/closing-preview")).andExpect(status().isUnauthorized());
		verifyNoInteractions(closing);
	}
}
