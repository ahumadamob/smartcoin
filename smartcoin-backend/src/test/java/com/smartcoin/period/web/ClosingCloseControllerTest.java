package com.smartcoin.period.web;

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
import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.NewEntry;
import com.smartcoin.period.domain.ClosingPlanner.Reconciliation;
import com.smartcoin.period.service.ClosingService;
import com.smartcoin.period.service.ClosingService.ConsolidatedEntry;
import com.smartcoin.period.service.ClosingService.CreatedEntry;
import com.smartcoin.period.service.ClosingService.RealBalance;
import com.smartcoin.period.service.ClosingService.Resolution;
import com.smartcoin.period.service.ClosingService.Result;
import com.smartcoin.period.service.ClosingService.SavedClosing;
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
 * HU-33: POST /api/periods/{period}/close. Forma de la respuesta con los ejemplos de RN-40, códigos HTTP y formato de
 * los errores (D-45). Servicio simulado. Se importa {@link WebConfig} por el convertidor de períodos.
 */
@WebMvcTest(ClosingController.class)
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, CurrentUser.class, WebConfig.class })
@TestPropertySource(properties = "app.security.jwt-secret=" + ClosingCloseControllerTest.SECRET)
class ClosingCloseControllerTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";
	static final long USER_ID = 7;
	static final YearMonth OCTOBER = YearMonth.of(2026, 10);
	static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

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

	private ResultActions close(String period, String body) throws Exception {
		return mvc.perform(post("/api/periods/" + period + "/close").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions close(String period) throws Exception {
		return mvc.perform(post("/api/periods/" + period + "/close").header("Authorization", bearer()));
	}

	private static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	static final String BODY = """
			{"resolutions":[{"entryId":512,"resolution":"CARRY_OVER"},{"entryId":513,"resolution":"CLOSE_AS_IS"}],
			 "realBalances":[{"accountId":12,"amount":509800},{"accountId":13,"amount":-0.5}]}""";

	static final List<Resolution> RESOLUTIONS = List.of(new Resolution(512, ClosingResolution.CARRY_OVER),
			new Resolution(513, ClosingResolution.CLOSE_AS_IS));

	static final List<RealBalance> BALANCES = List.of(new RealBalance(12, amount("509800")),
			new RealBalance(13, amount("-0.5")));

	/** El cierre de octubre con los ejemplos de la luz y del banco, y una cuenta en dólares que coincide. */
	private static Result october() {
		NewEntry carried = new NewEntry(NOVEMBER, EntryOrigin.CARRIED_OVER, "Saldo pendiente: Luz", EntryKind.EXPENSE,
				3L, "Servicios", 12L, "Banco Nación", Currency.ARS, LocalDate.of(2026, 11, 18), amount("25000.00"),
				512L, null);
		NewEntry difference = new NewEntry(NOVEMBER, EntryOrigin.CLOSING_DIFFERENCE,
				"Diferencia de cierre: Banco Nación (oct 2026)", EntryKind.EXPENSE, null, null, 12L, "Banco Nación",
				Currency.ARS, LocalDate.of(2026, 11, 1), amount("2500.00"), null, 12L);
		AccountLine bank = new AccountLine(12L, "Banco Nación", Currency.ARS, LocalDate.of(2026, 8, 1),
				amount("512300.00"));
		AccountLine dollars = new AccountLine(13L, "Dólares", Currency.USD, LocalDate.of(2026, 8, 1),
				amount("-0.50"));
		return new Result(OCTOBER, Instant.parse("2026-11-02T15:00:00Z"), NOVEMBER,
				List.of(new ConsolidatedEntry(512, "Luz", ClosingResolution.CARRY_OVER, amount("20000.00")),
						new ConsolidatedEntry(513, "Gas", ClosingResolution.CLOSE_AS_IS, amount("0.00"))),
				List.of(new CreatedEntry(940, carried), new CreatedEntry(941, difference)),
				List.of(new SavedClosing(31, bank, new Reconciliation(12L, amount("512300.00"), amount("509800.00"),
						amount("-2500.00"), difference), 941L),
						new SavedClosing(32, dollars, new Reconciliation(13L, amount("-0.50"), amount("-0.50"),
								amount("0.00"), null), null)));
	}

	@Test
	void respondsWithWhatTheClosingApplied() throws Exception {
		when(closing.close(USER_ID, OCTOBER, RESOLUTIONS, BALANCES)).thenReturn(october());

		close("2026-10", BODY)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.period").value("2026-10"))
				.andExpect(jsonPath("$.status").value("CLOSED"))
				.andExpect(jsonPath("$.closedAt").value("2026-11-02T15:00:00Z"))
				.andExpect(jsonPath("$.nextPeriod").value("2026-11"))
				.andExpect(jsonPath("$.consolidatedEntries.length()").value(2))
				.andExpect(jsonPath("$.consolidatedEntries[0].id").value(512))
				.andExpect(jsonPath("$.consolidatedEntries[0].name").value("Luz"))
				.andExpect(jsonPath("$.consolidatedEntries[0].resolution").value("CARRY_OVER"))
				.andExpect(jsonPath("$.consolidatedEntries[0].consolidatedAmount").value(20000.00))
				.andExpect(jsonPath("$.consolidatedEntries[1].resolution").value("CLOSE_AS_IS"))
				.andExpect(jsonPath("$.consolidatedEntries[1].consolidatedAmount").value(0.00))
				.andExpect(jsonPath("$.createdEntries.length()").value(2))
				.andExpect(jsonPath("$.createdEntries[0].id").value(940))
				.andExpect(jsonPath("$.createdEntries[0].entry.period").value("2026-11"))
				.andExpect(jsonPath("$.createdEntries[0].entry.origin").value("CARRIED_OVER"))
				.andExpect(jsonPath("$.createdEntries[0].entry.name").value("Saldo pendiente: Luz"))
				.andExpect(jsonPath("$.createdEntries[0].entry.dueDate").value("2026-11-18"))
				.andExpect(jsonPath("$.createdEntries[0].entry.budgetedAmount").value(25000.00))
				.andExpect(jsonPath("$.createdEntries[0].entry.sourceEntryId").value(512))
				.andExpect(jsonPath("$.createdEntries[1].id").value(941))
				.andExpect(jsonPath("$.createdEntries[1].entry.origin").value("CLOSING_DIFFERENCE"))
				.andExpect(jsonPath("$.createdEntries[1].entry.name")
						.value("Diferencia de cierre: Banco Nación (oct 2026)"))
				.andExpect(jsonPath("$.createdEntries[1].entry.dueDate").value("2026-11-01"))
				.andExpect(jsonPath("$.createdEntries[1].entry.sourceAccountId").value(12))
				.andExpect(jsonPath("$.accountClosings.length()").value(2))
				.andExpect(jsonPath("$.accountClosings[0].id").value(31))
				.andExpect(jsonPath("$.accountClosings[0].accountId").value(12))
				.andExpect(jsonPath("$.accountClosings[0].accountName").value("Banco Nación"))
				.andExpect(jsonPath("$.accountClosings[0].currency").value("ARS"))
				.andExpect(jsonPath("$.accountClosings[0].computedBalance").value(512300.00))
				.andExpect(jsonPath("$.accountClosings[0].realBalance").value(509800.00))
				.andExpect(jsonPath("$.accountClosings[0].difference").value(-2500.00))
				.andExpect(jsonPath("$.accountClosings[0].differenceEntryId").value(941))
				.andExpect(jsonPath("$.accountClosings[1].difference").value(0.00))
				.andExpect(jsonPath("$.accountClosings[1].differenceEntryId").doesNotExist());
	}

	/** Los montos viajan con 2 decimales. */
	@Test
	void amountsHaveTwoDecimals() throws Exception {
		when(closing.close(USER_ID, OCTOBER, RESOLUTIONS, BALANCES)).thenReturn(october());

		String json = close("2026-10", BODY).andReturn().getResponse().getContentAsString();

		org.assertj.core.api.Assertions.assertThat(json).contains("\"consolidatedAmount\":0.00")
				.contains("\"difference\":-2500.00").contains("\"computedBalance\":-0.50");
	}

	/** Sin cuerpo, un mes sin pendientes ni cuentas. */
	@Test
	void withoutABodyTheServiceGetsNoResolutionsNorBalances() throws Exception {
		when(closing.close(USER_ID, OCTOBER, List.of(), List.of()))
				.thenReturn(new Result(OCTOBER, Instant.parse("2026-11-02T15:00:00Z"), NOVEMBER, List.of(),
						List.of(), List.of()));

		close("2026-10")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.consolidatedEntries").isEmpty())
				.andExpect(jsonPath("$.createdEntries").isEmpty())
				.andExpect(jsonPath("$.accountClosings").isEmpty());
		verify(closing).close(USER_ID, OCTOBER, List.of(), List.of());
	}

	// Errores (D-45).

	@ParameterizedTest
	@ValueSource(strings = { "PERIOD_CLOSED", "PREVIOUS_PERIOD_OPEN", "PERIOD_NOT_FINISHED" })
	void aFailedPreconditionIsAConflict(String code) throws Exception {
		when(closing.close(USER_ID, OCTOBER, RESOLUTIONS, BALANCES))
				.thenThrow(new BusinessException(ErrorCode.valueOf(code), "No se puede cerrar."));

		close("2026-10", BODY)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("No se puede cerrar."))
				.andExpect(jsonPath("$.entries").doesNotExist())
				.andExpect(jsonPath("$.accounts").doesNotExist());
	}

	@Test
	void unresolvedPendingEntriesComeWithTheirIds() throws Exception {
		when(closing.close(USER_ID, OCTOBER, RESOLUTIONS, BALANCES))
				.thenThrow(new BusinessException(ErrorCode.UNRESOLVED_PENDING_ENTRIES,
						"Faltan resolver 2 partidas pendientes.", List.of(514L, 515L)));

		close("2026-10", BODY)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("UNRESOLVED_PENDING_ENTRIES"))
				.andExpect(jsonPath("$.title").value("Hay partidas pendientes sin resolver"))
				.andExpect(jsonPath("$.entries[0]").value(514))
				.andExpect(jsonPath("$.entries[1]").value(515))
				.andExpect(jsonPath("$.accounts").doesNotExist());
	}

	@Test
	void missingRealBalancesComeWithTheAccountIds() throws Exception {
		when(closing.close(USER_ID, OCTOBER, RESOLUTIONS, BALANCES))
				.thenThrow(BusinessException.withAccounts(ErrorCode.MISSING_REAL_BALANCE,
						"Falta el saldo real de 1 cuenta.", List.of(14L)));

		close("2026-10", BODY)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MISSING_REAL_BALANCE"))
				.andExpect(jsonPath("$.accounts[0]").value(14))
				.andExpect(jsonPath("$.accounts.length()").value(1))
				.andExpect(jsonPath("$.entries").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = { "resolutions", "realBalances" })
	void dataThatDoesNotCorrespondIsAValidationErrorInItsField(String field) throws Exception {
		when(closing.close(USER_ID, OCTOBER, RESOLUTIONS, BALANCES))
				.thenThrow(BusinessException.invalidField(field, "No corresponde."));

		close("2026-10", BODY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").value("No corresponde."));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"resolutions\":[{\"resolution\":\"CARRY_OVER\"}]}",
			"{\"resolutions\":[{\"entryId\":512}]}",
			"{\"resolutions\":[{\"entryId\":512,\"resolution\":\"POSTPONE\"}]}",
			"{\"resolutions\":[null]}",
			"{\"realBalances\":[{\"amount\":10}]}",
			"{\"realBalances\":[{\"accountId\":12}]}",
			"{\"realBalances\":[{\"accountId\":12,\"amount\":10.001}]}",
			"{\"realBalances\":[{\"accountId\":12,\"amount\":123456789012345678}]}",
			"{\"resolutions\":" })
	void aMalformedBodyIsAValidationErrorAndClosesNothing(String body) throws Exception {
		close("2026-10", body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(closing);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-13", "2026-1", "octubre" })
	void aPeriodWithAnInvalidFormatIsAValidationError(String period) throws Exception {
		close(period)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(closing);
	}

	@Test
	void aPeriodOutOfRangeIsNotFound() throws Exception {
		when(closing.close(USER_ID, YearMonth.of(2030, 1), List.of(), List.of()))
				.thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "El período 2030-01 no existe."));

		close("2030-01")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void anUnexpectedErrorIsAGenericInternalError() throws Exception {
		when(closing.close(USER_ID, OCTOBER, List.of(), List.of()))
				.thenThrow(new IllegalStateException("Falta el período abierto siguiente a 2026-10"));

		close("2026-10")
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.detail").value("Ocurrió un error inesperado."));
	}

	@Test
	void onlyPostIsAllowed() throws Exception {
		mvc.perform(get("/api/periods/2026-10/close").header("Authorization", bearer()))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
		verifyNoInteractions(closing);
	}

	@Test
	void requiresAToken() throws Exception {
		mvc.perform(post("/api/periods/2026-10/close")).andExpect(status().isUnauthorized());
		verifyNoInteractions(closing);
	}
}
