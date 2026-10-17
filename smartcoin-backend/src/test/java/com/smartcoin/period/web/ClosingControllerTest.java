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
import java.util.Map;
import java.util.Optional;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.period.domain.ClosingBlockReason;
import com.smartcoin.period.domain.ClosingPlanner;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.service.ClosingService;
import com.smartcoin.period.service.ClosingService.Blocker;
import com.smartcoin.period.service.ClosingService.Preview;
import com.smartcoin.period.service.ClosingService.RealBalance;
import com.smartcoin.period.service.ClosingService.Resolution;
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
 * HU-30 a HU-32: forma de la respuesta de POST /api/periods/{period}/closing-preview, con y sin resoluciones ni saldos
 * reales, el 400 por formato, el 404 fuera de rango (D-42, D-43, D-44). Servicio simulado. Se importa
 * {@link WebConfig} por el convertidor de períodos.
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

	private ResultActions preview(String period, String body) throws Exception {
		return mvc.perform(post("/api/periods/" + period + "/closing-preview").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	static final EntryLine POWER = new EntryLine(512L, EntryOrigin.RECURRING, "Luz", EntryKind.EXPENSE, 3L,
			"Servicios", 12L, "Banco Nación", Currency.ARS, LocalDate.of(2026, 10, 18), amount("45000.00"),
			EntryAmounts.of(amount("45000.00"), StoredEntryStatus.PENDING, null, amount("20000.00")));
	static final EntryLine GAS = new EntryLine(513L, EntryOrigin.ONE_OFF, "Gas", EntryKind.EXPENSE, null, null, 12L,
			"Banco Nación", Currency.ARS, LocalDate.of(2026, 10, 20), amount("8000.00"),
			EntryAmounts.of(amount("8000.00"), StoredEntryStatus.PENDING, null, null));

	/** Octubre con el anterior abierto, sin terminar, la luz con un pago parcial y dos cuentas. */
	private static Preview october() {
		EntryLine power = POWER;
		AccountLine bank = new AccountLine(12L, "Banco Nación", Currency.ARS, LocalDate.of(2026, 8, 1),
				amount("-1500.50"));
		AccountLine dollars = new AccountLine(13L, "Dólares", Currency.USD, LocalDate.of(2026, 9, 1),
				amount("0.00"));
		var plan = ClosingPlanner.plan(new ClosingPlanner.Input(OCTOBER, PeriodStatus.OPEN, PeriodStatus.OPEN, false,
				LocalDate.of(2026, 10, 16), List.of(power), List.of(bank, dollars), Map.of(), Map.of()));
		return new Preview(plan, PeriodStatus.OPEN, YearMonth.of(2026, 8), List.of(
				new Blocker(ClosingBlockReason.PREVIOUS_PERIOD_OPEN, "El período anterior, 2026-09, sigue abierto."),
				new Blocker(ClosingBlockReason.PERIOD_NOT_FINISHED, "El período 2026-10 termina el 31/10/2026.")));
	}

	@Test
	void respondsThePreconditionsThePendingEntriesAndTheAccounts() throws Exception {
		when(closing.preview(USER_ID, OCTOBER, List.of(), List.of())).thenReturn(october());

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
				.andExpect(jsonPath("$.pendingEntries[0].resolution").isEmpty())
				.andExpect(jsonPath("$.pendingEntries[0].consolidatedAmount").isEmpty())
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry").isEmpty())
				.andExpect(jsonPath("$.newEntries.length()").value(0))
				.andExpect(jsonPath("$.unresolvedEntryIds[0]").value(512))
				.andExpect(jsonPath("$.invalidResolutions.length()").value(0))
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
				true, LocalDate.of(2026, 10, 16), List.of(), List.of(), Map.of(), Map.of()));
		when(closing.preview(USER_ID, YearMonth.of(2026, 8), List.of(), List.of())).thenReturn(new Preview(plan, PeriodStatus.CLOSED,
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

	/** Octubre ya terminado, con la luz parcial y el gas sin movimientos, y las resoluciones recibidas. */
	private static Preview resolved(Map<Long, ClosingResolution> resolutions) {
		var plan = ClosingPlanner.plan(new ClosingPlanner.Input(OCTOBER, PeriodStatus.OPEN, null, true,
				LocalDate.of(2026, 11, 2), List.of(POWER, GAS), List.of(), resolutions, Map.of()));
		return new Preview(plan, PeriodStatus.OPEN, OCTOBER, List.of());
	}

	/** HU-31: con resoluciones, el efecto de cada pendiente y la partida de saldo postergado que se crearía. */
	@Test
	void withResolutionsRespondsTheEffectOfEach() throws Exception {
		List<Resolution> received = List.of(new Resolution(512L, ClosingResolution.CARRY_OVER),
				new Resolution(513L, ClosingResolution.CLOSE_AS_IS));
		when(closing.preview(USER_ID, OCTOBER, received, List.of())).thenReturn(resolved(
				Map.of(512L, ClosingResolution.CARRY_OVER, 513L, ClosingResolution.CLOSE_AS_IS)));

		String body = preview("2026-10", "{\"resolutions\":[{\"entryId\":512,\"resolution\":\"CARRY_OVER\"},"
				+ "{\"entryId\":513,\"resolution\":\"CLOSE_AS_IS\"}]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pendingEntries[0].resolution").value("CARRY_OVER"))
				.andExpect(jsonPath("$.pendingEntries[0].consolidatedAmount").value(20000.00))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.period").value("2026-11"))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.origin").value("CARRIED_OVER"))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.name").value("Saldo pendiente: Luz"))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.kind").value("EXPENSE"))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.categoryId").value(3))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.categoryName").value("Servicios"))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.accountId").value(12))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.accountName").value("Banco Nación"))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.currency").value("ARS"))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.dueDate").value("2026-11-18"))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.budgetedAmount").value(25000.00))
				.andExpect(jsonPath("$.pendingEntries[0].carriedOverEntry.sourceEntryId").value(512))
				.andExpect(jsonPath("$.pendingEntries[1].resolution").value("CLOSE_AS_IS"))
				.andExpect(jsonPath("$.pendingEntries[1].consolidatedAmount").value(0.00))
				.andExpect(jsonPath("$.pendingEntries[1].carriedOverEntry").isEmpty())
				.andExpect(jsonPath("$.newEntries.length()").value(1))
				.andExpect(jsonPath("$.newEntries[0].sourceEntryId").value(512))
				.andExpect(jsonPath("$.unresolvedEntryIds.length()").value(0))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("\"consolidatedAmount\":0.00").contains("\"budgetedAmount\":25000.00");
	}

	@Test
	void withSomeResolutionsTheRestAreUnresolved() throws Exception {
		List<Resolution> received = List.of(new Resolution(513L, ClosingResolution.CARRY_OVER));
		when(closing.preview(USER_ID, OCTOBER, received, List.of())).thenReturn(resolved(
				Map.of(513L, ClosingResolution.CARRY_OVER)));

		preview("2026-10", "{\"resolutions\":[{\"entryId\":513,\"resolution\":\"CARRY_OVER\"}]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pendingEntries[0].resolution").isEmpty())
				.andExpect(jsonPath("$.pendingEntries[1].carriedOverEntry.budgetedAmount").value(8000.00))
				.andExpect(jsonPath("$.unresolvedEntryIds.length()").value(1))
				.andExpect(jsonPath("$.unresolvedEntryIds[0]").value(512));
	}

	/** D-43: una resolución para una partida ajena, de otro mes o consolidada no es un error de la simulación. */
	@Test
	void resolutionsThatDoNotApplyAreReportedWithTheirReason() throws Exception {
		List<Resolution> received = List.of(new Resolution(999L, ClosingResolution.CARRY_OVER));
		when(closing.preview(USER_ID, OCTOBER, received, List.of())).thenReturn(resolved(
				Map.of(999L, ClosingResolution.CARRY_OVER)));

		preview("2026-10", "{\"resolutions\":[{\"entryId\":999,\"resolution\":\"CARRY_OVER\"}]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.invalidResolutions.length()").value(1))
				.andExpect(jsonPath("$.invalidResolutions[0].entryId").value(999))
				.andExpect(jsonPath("$.invalidResolutions[0].reason").value("NOT_IN_PERIOD"))
				.andExpect(jsonPath("$.unresolvedEntryIds.length()").value(2));
	}

	/** Sin cuerpo, con un cuerpo vacío o sin listas: ni resoluciones ni saldos, el diagnóstico de HU-30. */
	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"resolutions\":null}", "{\"resolutions\":[]}", "{\"realBalances\":null}",
			"{\"realBalances\":[]}" })
	void anEmptyBodyHasNoResolutionsNorBalances(String body) throws Exception {
		when(closing.preview(USER_ID, OCTOBER, List.of(), List.of())).thenReturn(october());

		preview("2026-10", body).andExpect(status().isOk());

		verify(closing).preview(USER_ID, OCTOBER, List.of(), List.of());
	}

	/** Octubre, el período inicial, ya terminado: el Banco en pesos y los Dólares con su saldo calculado. */
	private static Preview reconciled(Map<Long, ClosingResolution> resolutions, Map<Long, BigDecimal> realBalances) {
		AccountLine bank = new AccountLine(12L, "Banco", Currency.ARS, LocalDate.of(2026, 8, 1), amount("512300.00"));
		AccountLine dollars = new AccountLine(13L, "Dólares", Currency.USD, LocalDate.of(2026, 9, 1),
				amount("1000.00"));
		var plan = ClosingPlanner.plan(new ClosingPlanner.Input(OCTOBER, PeriodStatus.OPEN, null, true,
				LocalDate.of(2026, 11, 2), List.of(POWER, GAS), List.of(bank, dollars), resolutions, realBalances));
		return new Preview(plan, PeriodStatus.OPEN, OCTOBER, List.of());
	}

	/**
	 * HU-32: con saldos reales, el real y la diferencia de cada cuenta y la partida de diferencia que se crearía. El
	 * ejemplo del banco de RN-40 y una cuenta en dólares con diferencia positiva.
	 */
	@Test
	void withRealBalancesRespondsTheDifferenceOfEachAccount() throws Exception {
		List<RealBalance> received = List.of(new RealBalance(12L, amount("509800.00")),
				new RealBalance(13L, amount("1200.00")));
		when(closing.preview(USER_ID, OCTOBER, List.of(), received)).thenReturn(reconciled(Map.of(),
				Map.of(12L, amount("509800.00"), 13L, amount("1200.00"))));

		String body = preview("2026-10", "{\"realBalances\":[{\"accountId\":12,\"amount\":509800.00},"
				+ "{\"accountId\":13,\"amount\":1200.00}]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts[0].computedBalance").value(512300.00))
				.andExpect(jsonPath("$.accounts[0].realBalance").value(509800.00))
				.andExpect(jsonPath("$.accounts[0].difference").value(-2500.00))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.period").value("2026-11"))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.origin").value("CLOSING_DIFFERENCE"))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.name")
						.value("Diferencia de cierre: Banco (oct 2026)"))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.kind").value("EXPENSE"))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.categoryId").isEmpty())
				.andExpect(jsonPath("$.accounts[0].differenceEntry.accountId").value(12))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.currency").value("ARS"))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.dueDate").value("2026-11-01"))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.budgetedAmount").value(2500.00))
				.andExpect(jsonPath("$.accounts[0].differenceEntry.sourceEntryId").isEmpty())
				.andExpect(jsonPath("$.accounts[0].differenceEntry.sourceAccountId").value(12))
				.andExpect(jsonPath("$.accounts[1].difference").value(200.00))
				.andExpect(jsonPath("$.accounts[1].differenceEntry.kind").value("INCOME"))
				.andExpect(jsonPath("$.accounts[1].differenceEntry.currency").value("USD"))
				.andExpect(jsonPath("$.accounts[1].differenceEntry.budgetedAmount").value(200.00))
				.andExpect(jsonPath("$.newEntries.length()").value(2))
				.andExpect(jsonPath("$.missingRealBalanceAccountIds.length()").value(0))
				.andExpect(jsonPath("$.invalidBalances.length()").value(0))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("\"difference\":-2500.00").contains("\"realBalance\":509800.00");
	}

	/** Con la diferencia en 0 no se crea nada: la respuesta dice 0.00 y no trae partida. */
	@Test
	void aMatchingBalanceHasNoDifferenceEntry() throws Exception {
		List<RealBalance> received = List.of(new RealBalance(12L, amount("512300.00")));
		when(closing.preview(USER_ID, OCTOBER, List.of(), received)).thenReturn(reconciled(Map.of(),
				Map.of(12L, amount("512300.00"))));

		String body = preview("2026-10", "{\"realBalances\":[{\"accountId\":12,\"amount\":512300.00}]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts[0].difference").value(0.00))
				.andExpect(jsonPath("$.accounts[0].differenceEntry").isEmpty())
				.andExpect(jsonPath("$.newEntries.length()").value(0))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("\"difference\":0.00");
	}

	/** Una cuenta sin saldo real no tiene real, diferencia ni partida, y se informa como dato que falta. */
	@Test
	void anAccountWithoutRealBalanceIsReportedAsMissing() throws Exception {
		List<RealBalance> received = List.of(new RealBalance(13L, amount("900.00")));
		when(closing.preview(USER_ID, OCTOBER, List.of(), received)).thenReturn(reconciled(Map.of(),
				Map.of(13L, amount("900.00"))));

		preview("2026-10", "{\"realBalances\":[{\"accountId\":13,\"amount\":900.00}]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts[0].realBalance").isEmpty())
				.andExpect(jsonPath("$.accounts[0].difference").isEmpty())
				.andExpect(jsonPath("$.accounts[0].differenceEntry").isEmpty())
				.andExpect(jsonPath("$.accounts[1].difference").value(-100.00))
				.andExpect(jsonPath("$.missingRealBalanceAccountIds.length()").value(1))
				.andExpect(jsonPath("$.missingRealBalanceAccountIds[0]").value(12));
	}

	/** D-44: un saldo para una cuenta que no se concilia no es un error de la simulación. */
	@Test
	void balancesThatDoNotApplyAreReportedWithTheirReason() throws Exception {
		List<RealBalance> received = List.of(new RealBalance(999L, amount("10.00")));
		when(closing.preview(USER_ID, OCTOBER, List.of(), received)).thenReturn(reconciled(Map.of(),
				Map.of(999L, amount("10.00"))));

		preview("2026-10", "{\"realBalances\":[{\"accountId\":999,\"amount\":10.00}]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.invalidBalances.length()").value(1))
				.andExpect(jsonPath("$.invalidBalances[0].accountId").value(999))
				.andExpect(jsonPath("$.invalidBalances[0].reason").value("NOT_RECONCILABLE"))
				.andExpect(jsonPath("$.missingRealBalanceAccountIds.length()").value(2))
				.andExpect(jsonPath("$.newEntries.length()").value(0));
	}

	/** Las resoluciones de HU-31 y los saldos de HU-32 conviven: primero el saldo postergado, después la diferencia. */
	@Test
	void resolutionsAndBalancesComeInTheSameRequest() throws Exception {
		List<Resolution> resolutions = List.of(new Resolution(512L, ClosingResolution.CARRY_OVER));
		List<RealBalance> balances = List.of(new RealBalance(12L, amount("509800.00")));
		when(closing.preview(USER_ID, OCTOBER, resolutions, balances)).thenReturn(reconciled(
				Map.of(512L, ClosingResolution.CARRY_OVER), Map.of(12L, amount("509800.00"))));

		preview("2026-10", "{\"resolutions\":[{\"entryId\":512,\"resolution\":\"CARRY_OVER\"}],"
				+ "\"realBalances\":[{\"accountId\":12,\"amount\":509800.00}]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.newEntries.length()").value(2))
				.andExpect(jsonPath("$.newEntries[0].origin").value("CARRIED_OVER"))
				.andExpect(jsonPath("$.newEntries[0].sourceEntryId").value(512))
				.andExpect(jsonPath("$.newEntries[0].sourceAccountId").isEmpty())
				.andExpect(jsonPath("$.newEntries[1].origin").value("CLOSING_DIFFERENCE"))
				.andExpect(jsonPath("$.newEntries[1].sourceAccountId").value(12))
				.andExpect(jsonPath("$.pendingEntries[0].resolution").value("CARRY_OVER"))
				.andExpect(jsonPath("$.unresolvedEntryIds[0]").value(513));
	}

	/** El saldo real puede ser 0 o negativo (RN-03): llega al servicio tal como se envió. */
	@Test
	void aZeroOrNegativeRealBalanceIsAccepted() throws Exception {
		List<RealBalance> received = List.of(new RealBalance(12L, new BigDecimal("-1500.50")),
				new RealBalance(13L, new BigDecimal("0")));
		when(closing.preview(USER_ID, OCTOBER, List.of(), received)).thenReturn(october());

		preview("2026-10", "{\"realBalances\":[{\"accountId\":12,\"amount\":-1500.50},"
				+ "{\"accountId\":13,\"amount\":0}]}")
				.andExpect(status().isOk());

		verify(closing).preview(USER_ID, OCTOBER, List.of(), received);
	}

	/** Formato de los saldos: sin cuenta, sin monto, con más de 2 decimales, fuera de rango o un elemento nulo. */
	@ParameterizedTest
	@ValueSource(strings = {
			"{\"realBalances\":[{\"amount\":100.00}]}",
			"{\"realBalances\":[{\"accountId\":12}]}",
			"{\"realBalances\":[{\"accountId\":12,\"amount\":null}]}",
			"{\"realBalances\":[{\"accountId\":12,\"amount\":100.005}]}",
			"{\"realBalances\":[{\"accountId\":12,\"amount\":123456789012345678.00}]}",
			"{\"realBalances\":[{\"accountId\":12,\"amount\":\"mucho\"}]}",
			"{\"realBalances\":[null]}",
			"{\"realBalances\":{\"12\":100.00}}",
	})
	void aMalformedRealBalanceIsAValidationError(String body) throws Exception {
		preview("2026-10", body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(closing);
	}

	@Test
	void aRepeatedAccountIsAValidationErrorInRealBalances() throws Exception {
		List<RealBalance> received = List.of(new RealBalance(12L, amount("100.00")),
				new RealBalance(12L, amount("200.00")));
		when(closing.preview(USER_ID, OCTOBER, List.of(), received)).thenThrow(
				BusinessException.invalidField("realBalances", "La cuenta 12 tiene más de un saldo real."));

		preview("2026-10", "{\"realBalances\":[{\"accountId\":12,\"amount\":100.00},"
				+ "{\"accountId\":12,\"amount\":200.00}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("realBalances"))
				.andExpect(jsonPath("$.errors[0].message").value("La cuenta 12 tiene más de un saldo real."));
	}

	/** Formato del cuerpo: sin partida, sin resolución, un valor inválido o un elemento nulo. */
	@ParameterizedTest
	@ValueSource(strings = {
			"{\"resolutions\":[{\"resolution\":\"CARRY_OVER\"}]}",
			"{\"resolutions\":[{\"entryId\":512}]}",
			"{\"resolutions\":[{\"entryId\":512,\"resolution\":\"POSTPONE\"}]}",
			"{\"resolutions\":[null]}",
			"{\"resolutions\":{\"512\":\"CARRY_OVER\"}}",
	})
	void aMalformedResolutionIsAValidationError(String body) throws Exception {
		preview("2026-10", body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(closing);
	}

	@Test
	void aRepeatedEntryIsAValidationErrorInResolutions() throws Exception {
		List<Resolution> received = List.of(new Resolution(512L, ClosingResolution.CARRY_OVER),
				new Resolution(512L, ClosingResolution.CLOSE_AS_IS));
		when(closing.preview(USER_ID, OCTOBER, received, List.of())).thenThrow(
				BusinessException.invalidField("resolutions", "La partida 512 tiene más de una resolución."));

		preview("2026-10", "{\"resolutions\":[{\"entryId\":512,\"resolution\":\"CARRY_OVER\"},"
				+ "{\"entryId\":512,\"resolution\":\"CLOSE_AS_IS\"}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("resolutions"))
				.andExpect(jsonPath("$.errors[0].message").value("La partida 512 tiene más de una resolución."));
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
		when(closing.preview(USER_ID, YearMonth.of(2030, 1), List.of(), List.of()))
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
