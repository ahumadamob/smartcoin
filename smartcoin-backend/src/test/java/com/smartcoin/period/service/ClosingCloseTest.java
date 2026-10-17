package com.smartcoin.period.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.service.AccountService;
import com.smartcoin.account.service.AccountService.AccountBalance;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.category.domain.Category;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.AccountClosing;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.AccountClosingRepository;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.ClosingService.ConsolidatedEntry;
import com.smartcoin.period.service.ClosingService.CreatedEntry;
import com.smartcoin.period.service.ClosingService.RealBalance;
import com.smartcoin.period.service.ClosingService.Resolution;
import com.smartcoin.period.service.ClosingService.Result;
import com.smartcoin.period.service.ClosingService.SavedClosing;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-33, RN-38 a RN-40, RN-43 y RN-01: el cierre efectivo con repositorios simulados. El usuario tiene los períodos
 * 2026-08 a 2028-10, con agosto y septiembre cerrados; se cierra octubre con el reloj, salvo donde se indica, el
 * 02/11/2026. Octubre tiene los ejemplos de RN-40: la luz recurrente (45.000,00, vence el 18/10, 20.000,00 pagados) y
 * el Banco (saldo calculado 512.300,00); además un seguro puntual sin movimientos, un alquiler ya consolidado y una
 * cuenta en dólares que coincide.
 */
class ClosingCloseTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final YearMonth START = YearMonth.of(2026, 8);
	static final YearMonth OCTOBER = YearMonth.of(2026, 10);
	static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
	static final Instant NOW = Instant.parse("2026-11-02T15:00:00Z");

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	AccountClosingRepository closings = mock(AccountClosingRepository.class);
	AccountService accounts = mock(AccountService.class);
	List<BudgetPeriod> existing = new ArrayList<>();
	long nextId = 1;

	Account bank = ClosingServiceTest.account(42, "Banco", Currency.ARS, "2026-08-01");
	Account dollars = ClosingServiceTest.account(43, "Dólares", Currency.USD, "2026-08-01");
	Category services = new Category();
	BudgetItem powerItem = new BudgetItem();
	BudgetEntry power;
	BudgetEntry insurance;
	BudgetEntry rent;
	BudgetEntry novemberPower;

	@BeforeEach
	void setUp() {
		for (YearMonth month = START; !month.isAfter(YearMonth.of(2028, 10)); month = month.plusMonths(1)) {
			BudgetPeriod period = BudgetPeriod.open(USER_ID, month);
			ReflectionTestUtils.setField(period, "id", 100L + existing.size());
			existing.add(period);
			when(periods.findByUserIdAndPeriodMonth(USER_ID, month)).thenReturn(Optional.of(period));
			when(periods.lockByUserIdAndPeriodMonth(USER_ID, month)).thenReturn(Optional.of(period));
		}
		close(START, START.plusMonths(1));
		when(periods.findPeriodMonthsByUserId(USER_ID))
				.thenReturn(existing.stream().map(BudgetPeriod::getPeriodMonth).toList());
		when(periods.findFirstByUserIdAndStatusOrderByPeriodMonthDesc(USER_ID, PeriodStatus.CLOSED))
				.thenAnswer(call -> existing.stream().filter(p -> p.getStatus() == PeriodStatus.CLOSED)
						.reduce((first, second) -> second));
		when(accounts.balancesAt(eq(USER_ID), any(LocalDate.class))).thenReturn(List.of(
				new AccountBalance(bank, new BigDecimal("512300.00")),
				new AccountBalance(dollars, new BigDecimal("500.00"))));
		when(entries.saveAll(anyList())).thenAnswer(call -> {
			List<BudgetEntry> saved = call.getArgument(0);
			saved.stream().filter(e -> e.getId() == null)
					.forEach(e -> ReflectionTestUtils.setField(e, "id", 1000L + nextId++));
			return saved;
		});
		when(closings.saveAll(anyList())).thenAnswer(call -> {
			List<AccountClosing> saved = call.getArgument(0);
			saved.forEach(c -> ReflectionTestUtils.setField(c, "id", 500L + nextId++));
			return saved;
		});

		ReflectionTestUtils.setField(services, "id", 300L);
		services.setName("Servicios");
		ReflectionTestUtils.setField(powerItem, "id", 900L);
		powerItem.setName("Luz");
		powerItem.setCategory(services);
		powerItem.setEstimationRule(EstimationRule.AVERAGE_LAST_3);
		powerItem.setCurrentAmount(new BigDecimal("45000.00"));
		power = entry(OCTOBER, null, EntryKind.EXPENSE, "45000.00");
		power.setOrigin(EntryOrigin.RECURRING);
		power.setBudgetItem(powerItem);
		power.setManual(true);
		insurance = entry(OCTOBER, "Seguro", EntryKind.EXPENSE, "12000.00");
		insurance.setDueDate(OCTOBER.atDay(10));
		rent = entry(OCTOBER, "Alquiler", EntryKind.EXPENSE, "450000.00");
		rent.setDueDate(OCTOBER.atDay(5));
		rent.setStatus(StoredEntryStatus.CONSOLIDATED);
		rent.setConsolidatedAmount(new BigDecimal("450000.00"));
		rent.setConsolidatedAt(Instant.parse("2026-10-06T12:00:00Z"));
		novemberPower = entry(NOVEMBER, null, EntryKind.EXPENSE, "45000.00");
		novemberPower.setOrigin(EntryOrigin.RECURRING);
		novemberPower.setBudgetItem(powerItem);
		long octoberId = period(OCTOBER).getId();
		when(entries.findByUserIdAndPeriodIdWithDetails(USER_ID, octoberId)).thenReturn(List.of(power, insurance, rent));
		when(movements.sumByEntryOfPeriod(USER_ID, octoberId)).thenReturn(List.of(
				new EntryTotal(power.getId(), new BigDecimal("20000.00")),
				new EntryTotal(rent.getId(), new BigDecimal("450000.00"))));
	}

	private BudgetEntry entry(YearMonth month, String name, EntryKind kind, String budgeted) {
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", nextId++);
		e.setUserId(USER_ID);
		e.setPeriod(period(month));
		e.setOrigin(EntryOrigin.ONE_OFF);
		e.setName(name);
		e.setKind(kind);
		e.setAccount(bank);
		e.setDueDate(month.atDay(18));
		e.setBudgetedAmount(new BigDecimal(budgeted));
		e.setStatus(StoredEntryStatus.PENDING);
		return e;
	}

	private BudgetPeriod period(YearMonth month) {
		return existing.stream().filter(p -> p.getPeriodMonth().equals(month)).findFirst().orElseThrow();
	}

	private void close(YearMonth... months) {
		for (YearMonth month : months) {
			period(month).setStatus(PeriodStatus.CLOSED);
			period(month).setClosedAt(Instant.parse("2026-10-01T12:00:00Z"));
		}
	}

	private ClosingService serviceAt(Instant now) {
		Clock clock = Clock.fixed(now, ZONE);
		return new ClosingService(periods, entries, closings, new PeriodViewService(periods, entries, movements, clock),
				accounts, clock);
	}

	private Result close(List<Resolution> resolutions, List<RealBalance> realBalances) {
		return serviceAt(NOW).close(USER_ID, OCTOBER, resolutions, realBalances);
	}

	private List<Resolution> allResolved() {
		return List.of(new Resolution(power.getId(), ClosingResolution.CARRY_OVER),
				new Resolution(insurance.getId(), ClosingResolution.CLOSE_AS_IS));
	}

	private static List<RealBalance> allBalances() {
		return List.of(real(42, "509800.00"), real(43, "500.00"));
	}

	private static RealBalance real(long accountId, String amount) {
		return new RealBalance(accountId, new BigDecimal(amount));
	}

	// El cierre completo (criterio 1, RN-40), con los ejemplos de la luz y del banco.

	@Test
	void theCompleteClosingAppliesEveryEffectOfRn40() {
		Result result = close(allResolved(), allBalances());

		// 1 y 2: las pendientes, consolidadas con su real y su resolución.
		assertThat(power.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
		assertThat(power.getConsolidatedAmount()).isEqualTo(new BigDecimal("20000.00"));
		assertThat(power.getClosingResolution()).isEqualTo(ClosingResolution.CARRY_OVER);
		assertThat(power.getConsolidatedAt()).isEqualTo(NOW);
		assertThat(insurance.getStatus()).isEqualTo(StoredEntryStatus.CONSOLIDATED);
		assertThat(insurance.getConsolidatedAmount()).isEqualTo(new BigDecimal("0.00"));
		assertThat(insurance.getClosingResolution()).isEqualTo(ClosingResolution.CLOSE_AS_IS);
		assertThat(result.consolidatedEntries()).containsExactly(
				new ConsolidatedEntry(insurance.getId(), "Seguro", ClosingResolution.CLOSE_AS_IS,
						new BigDecimal("0.00")),
				new ConsolidatedEntry(power.getId(), "Luz", ClosingResolution.CARRY_OVER,
						new BigDecimal("20000.00")));

		// 1: «Saldo pendiente: Luz» por 25.000,00 con vencimiento el 18/11, en noviembre.
		ArgumentCaptor<List<BudgetEntry>> saved = captor();
		verify(entries, org.mockito.Mockito.times(3)).saveAll(saved.capture());
		assertThat(saved.getAllValues().get(0)).containsExactlyInAnyOrder(power, insurance);
		BudgetEntry carried = saved.getAllValues().get(1).getFirst();
		assertThat(saved.getAllValues().get(1)).hasSize(1);
		assertThat(carried.getPeriod()).isSameAs(period(NOVEMBER));
		assertThat(carried.getOrigin()).isEqualTo(EntryOrigin.CARRIED_OVER);
		assertThat(carried.getName()).isEqualTo("Saldo pendiente: Luz");
		assertThat(carried.getKind()).isEqualTo(EntryKind.EXPENSE);
		assertThat(carried.getCategory()).isSameAs(services);
		assertThat(carried.getAccount()).isSameAs(bank);
		assertThat(carried.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 18));
		assertThat(carried.getBudgetedAmount()).isEqualTo(new BigDecimal("25000.00"));
		assertThat(carried.getSourceEntry()).isSameAs(power);
		assertThat(carried.getSourceClosing()).isNull();
		assertThat(carried.getBudgetItem()).isNull();
		assertThat(carried.isManual()).isFalse();
		assertThat(carried.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(carried.getUserId()).isEqualTo(USER_ID);

		// 3: un cierre de cuenta por cada cuenta, también el de diferencia 0.
		ArgumentCaptor<List<AccountClosing>> savedClosings = captor();
		verify(closings).saveAll(savedClosings.capture());
		List<AccountClosing> accountClosings = savedClosings.getValue();
		assertThat(accountClosings).hasSize(2);
		AccountClosing bankClosing = accountClosings.get(0);
		assertThat(bankClosing.getAccount()).isSameAs(bank);
		assertThat(bankClosing.getPeriod()).isSameAs(period(OCTOBER));
		assertThat(bankClosing.getUserId()).isEqualTo(USER_ID);
		assertThat(bankClosing.getComputedBalance()).isEqualTo(new BigDecimal("512300.00"));
		assertThat(bankClosing.getRealBalance()).isEqualTo(new BigDecimal("509800.00"));
		assertThat(bankClosing.getDifference()).isEqualTo(new BigDecimal("-2500.00"));
		AccountClosing dollarsClosing = accountClosings.get(1);
		assertThat(dollarsClosing.getAccount()).isSameAs(dollars);
		assertThat(dollarsClosing.getDifference()).isEqualTo(new BigDecimal("0.00"));

		// 3: el gasto «Diferencia de cierre: Banco (oct 2026)» por 2.500,00, el 01/11, referido a su cierre.
		BudgetEntry difference = saved.getAllValues().get(2).getFirst();
		assertThat(saved.getAllValues().get(2)).hasSize(1);
		assertThat(difference.getPeriod()).isSameAs(period(NOVEMBER));
		assertThat(difference.getOrigin()).isEqualTo(EntryOrigin.CLOSING_DIFFERENCE);
		assertThat(difference.getName()).isEqualTo("Diferencia de cierre: Banco (oct 2026)");
		assertThat(difference.getKind()).isEqualTo(EntryKind.EXPENSE);
		assertThat(difference.getCategory()).isNull();
		assertThat(difference.getAccount()).isSameAs(bank);
		assertThat(difference.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(difference.getBudgetedAmount()).isEqualTo(new BigDecimal("2500.00"));
		assertThat(difference.getSourceClosing()).isSameAs(bankClosing);
		assertThat(difference.getSourceEntry()).isNull();
		assertThat(difference.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(difference.isManual()).isFalse();

		// 4: el período, cerrado con su fecha de cierre, la misma de las consolidaciones.
		assertThat(period(OCTOBER).getStatus()).isEqualTo(PeriodStatus.CLOSED);
		assertThat(period(OCTOBER).getClosedAt()).isEqualTo(NOW);
		verify(periods).save(period(OCTOBER));

		// Lo que informa, con los ids asignados.
		assertThat(result.period()).isEqualTo(OCTOBER);
		assertThat(result.nextPeriod()).isEqualTo(NOVEMBER);
		assertThat(result.closedAt()).isEqualTo(NOW);
		assertThat(result.createdEntries()).extracting(CreatedEntry::id)
				.containsExactly(carried.getId(), difference.getId());
		assertThat(result.createdEntries()).extracting(c -> c.entry().name())
				.containsExactly("Saldo pendiente: Luz", "Diferencia de cierre: Banco (oct 2026)");
		assertThat(result.accountClosings()).extracting(SavedClosing::id)
				.containsExactly(bankClosing.getId(), dollarsClosing.getId());
		assertThat(result.accountClosings()).extracting(SavedClosing::differenceEntryId)
				.containsExactly(difference.getId(), null);
		assertThat(result.accountClosings().getFirst().account().name()).isEqualTo("Banco");
	}

	/** RN-40 en su orden (D-45): el cierre de cuenta existe antes que la partida de diferencia que lo referencia. */
	@Test
	void everythingIsSavedInTheOrderOfRn40AfterTakingTheLock() {
		close(allResolved(), allBalances());

		InOrder order = inOrder(periods, entries, closings, accounts);
		order.verify(periods).lockByUserIdAndPeriodMonth(USER_ID, OCTOBER);
		order.verify(periods).findPeriodMonthsByUserId(USER_ID);
		order.verify(entries).saveAll(List.of(insurance, power));
		order.verify(entries).saveAll(argThatHasOrigin(EntryOrigin.CARRIED_OVER));
		order.verify(closings).saveAll(anyList());
		order.verify(entries).saveAll(argThatHasOrigin(EntryOrigin.CLOSING_DIFFERENCE));
		order.verify(periods).save(period(OCTOBER));
		order.verifyNoMoreInteractions();
	}

	private static List<BudgetEntry> argThatHasOrigin(EntryOrigin origin) {
		return org.mockito.ArgumentMatchers.argThat(list -> list != null && !list.isEmpty()
				&& list.stream().allMatch(e -> e.getOrigin() == origin));
	}

	/** RN-40 y S-04: ni el Concepto, ni su monto vigente, ni el presupuestado o la marca de editada cambian. */
	@Test
	void neitherTheConceptNorOtherMonthsChange() {
		close(allResolved(), allBalances());

		assertThat(powerItem.getCurrentAmount()).isEqualTo(new BigDecimal("45000.00"));
		assertThat(power.getBudgetedAmount()).isEqualTo(new BigDecimal("45000.00"));
		assertThat(power.isManual()).isTrue();
		assertThat(novemberPower.getBudgetedAmount()).isEqualTo(new BigDecimal("45000.00"));
		assertThat(novemberPower.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(rent.getConsolidatedAt()).isEqualTo(Instant.parse("2026-10-06T12:00:00Z"));
		assertThat(rent.getClosingResolution()).isNull();
		verify(entries, never()).findByUserIdAndBudgetItemId(anyLong(), anyLong());
		assertThat(existing).filteredOn(p -> !p.getPeriodMonth().isBefore(NOVEMBER))
				.allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(PeriodStatus.OPEN));
	}

	/** Postergar con pendiente 0 consolida y no crea nada; un mes sin nada que resolver ni conciliar se cierra igual. */
	@Test
	void withNothingToCarryOverOrReconcileOnlyThePeriodCloses() {
		when(entries.findByUserIdAndPeriodIdWithDetails(eq(USER_ID), anyLong())).thenReturn(List.of(rent));
		when(accounts.balancesAt(eq(USER_ID), any(LocalDate.class))).thenReturn(List.of());

		Result result = close(List.of(), List.of());

		assertThat(result.consolidatedEntries()).isEmpty();
		assertThat(result.createdEntries()).isEmpty();
		assertThat(result.accountClosings()).isEmpty();
		assertThat(period(OCTOBER).getStatus()).isEqualTo(PeriodStatus.CLOSED);
	}

	@Test
	void carryingOverAFullyPaidEntryCreatesNothing() {
		when(movements.sumByEntryOfPeriod(USER_ID, period(OCTOBER).getId())).thenReturn(List.of(
				new EntryTotal(power.getId(), new BigDecimal("47000.00")),
				new EntryTotal(rent.getId(), new BigDecimal("450000.00"))));

		Result result = close(List.of(new Resolution(power.getId(), ClosingResolution.CARRY_OVER),
				new Resolution(insurance.getId(), ClosingResolution.CARRY_OVER)), allBalances());

		assertThat(power.getConsolidatedAmount()).isEqualTo(new BigDecimal("47000.00"));
		assertThat(result.createdEntries()).extracting(c -> c.entry().name())
				.containsExactly("Saldo pendiente: Seguro", "Diferencia de cierre: Banco (oct 2026)");
	}

	// Errores (criterio 4, RN-38, RN-39): ninguno guarda nada.

	private void assertFailsWithoutSaving(Runnable call, ErrorCode code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.code()).isEqualTo(code));
		assertNothingSaved();
	}

	private void assertNothingSaved() {
		verify(entries, never()).saveAll(any());
		verify(entries, never()).save(any());
		verify(periods, never()).save(any());
		verifyNoInteractions(closings);
		assertThat(power.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(insurance.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(power.getClosingResolution()).isNull();
		assertThat(period(OCTOBER).getStatus()).isEqualTo(PeriodStatus.OPEN);
		assertThat(period(OCTOBER).getClosedAt()).isNull();
	}

	@Test
	void aClosedPeriodCannotBeClosedAgain() {
		close(OCTOBER);
		assertThatThrownBy(() -> close(allResolved(), allBalances()))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_CLOSED);
					assertThat(e.getMessage()).isEqualTo("El período 2026-10 ya está cerrado.");
				});
		verify(entries, never()).saveAll(any());
		verify(periods, never()).save(any());
		verifyNoInteractions(closings);
		verify(entries, never()).findByUserIdAndPeriodIdWithDetails(anyLong(), anyLong());
	}

	@Test
	void withThePreviousPeriodOpen() {
		period(START.plusMonths(1)).setStatus(PeriodStatus.OPEN);
		period(START.plusMonths(1)).setClosedAt(null);

		assertThatThrownBy(() -> close(allResolved(), allBalances()))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.PREVIOUS_PERIOD_OPEN);
					assertThat(e.getMessage()).contains("2026-09").contains("el primero abierto es 2026-09");
				});
		assertNothingSaved();
	}

	/** Un día antes del último: no terminó. El último día, a las 00:00 de Mendoza, ya se puede. */
	@Test
	void beforeItsLastDayThePeriodIsNotFinished() {
		assertThatThrownBy(() -> serviceAt(Instant.parse("2026-10-31T02:59:59Z"))
				.close(USER_ID, OCTOBER, allResolved(), allBalances()))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.PERIOD_NOT_FINISHED);
					assertThat(e.getMessage()).contains("31/10/2026");
				});
		assertNothingSaved();

		serviceAt(Instant.parse("2026-10-31T03:00:00Z")).close(USER_ID, OCTOBER, allResolved(), allBalances());
		assertThat(period(OCTOBER).getStatus()).isEqualTo(PeriodStatus.CLOSED);
	}

	/** Con las dos precondiciones fallando, responde la primera (RN-43). */
	@Test
	void withSeveralBlockersTheFirstOneAnswers() {
		period(START.plusMonths(1)).setStatus(PeriodStatus.OPEN);

		assertFailsWithoutSaving(() -> serviceAt(Instant.parse("2026-10-20T15:00:00Z"))
				.close(USER_ID, OCTOBER, allResolved(), allBalances()), ErrorCode.PREVIOUS_PERIOD_OPEN);
	}

	@Test
	void withAPendingEntryUnresolved() {
		assertThatThrownBy(() -> close(List.of(new Resolution(power.getId(), ClosingResolution.CARRY_OVER)),
				allBalances()))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.UNRESOLVED_PENDING_ENTRIES);
					assertThat(e.entries()).containsExactly(insurance.getId());
					assertThat(e.getMessage()).startsWith("Falta resolver 1 partida pendiente");
				});
		assertNothingSaved();
	}

	@Test
	void withNoResolutionsAllPendingEntriesAreReported() {
		assertThatThrownBy(() -> close(List.of(), allBalances()))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.UNRESOLVED_PENDING_ENTRIES);
					assertThat(e.entries()).containsExactly(insurance.getId(), power.getId());
					assertThat(e.getMessage()).startsWith("Faltan resolver 2 partidas pendientes");
				});
		assertNothingSaved();
	}

	@Test
	void withARealBalanceMissing() {
		assertThatThrownBy(() -> close(allResolved(), List.of(real(42, "509800.00"))))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.MISSING_REAL_BALANCE);
					assertThat(e.accounts()).containsExactly(43L);
					assertThat(e.entries()).isEmpty();
					assertThat(e.getMessage()).isEqualTo("Falta el saldo real de 1 cuenta: cargá el saldo que figura "
							+ "en el banco o la billetera al 31/10/2026.");
				});
		assertNothingSaved();
	}

	/** Una resolución para una consolidada o para una partida de otro mes, de otro usuario o inexistente (RN-39). */
	@Test
	void aResolutionThatDoesNotCorrespondIsAValidationError() {
		List<Resolution> resolutions = new ArrayList<>(allResolved());
		resolutions.add(new Resolution(rent.getId(), ClosingResolution.CLOSE_AS_IS));
		resolutions.add(new Resolution(novemberPower.getId(), ClosingResolution.CARRY_OVER));

		assertThatThrownBy(() -> close(resolutions, allBalances()))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("resolutions");
					assertThat(e.getMessage()).contains("la partida " + rent.getId() + " ya está consolidada")
							.contains("la partida " + novemberPower.getId() + " no es del período");
				});
		assertNothingSaved();
	}

	@Test
	void aRealBalanceForAnAccountThatIsNotReconciledIsAValidationError() {
		List<RealBalance> balances = new ArrayList<>(allBalances());
		balances.add(real(99, "10.00"));

		assertThatThrownBy(() -> close(allResolved(), balances))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("realBalances");
					assertThat(e.getMessage()).contains("99");
				});
		assertNothingSaved();
	}

	/** D-45: lo que vino mal en el pedido se informa antes que lo que falta. */
	@Test
	void invalidDataIsReportedBeforeMissingData() {
		assertFailsWithoutSaving(() -> close(List.of(new Resolution(rent.getId(), ClosingResolution.CLOSE_AS_IS)),
				List.of()), ErrorCode.VALIDATION_ERROR);
		assertFailsWithoutSaving(() -> close(List.of(), List.of(real(99, "1.00"))), ErrorCode.VALIDATION_ERROR);
		assertFailsWithoutSaving(() -> close(allResolved(), List.of()), ErrorCode.MISSING_REAL_BALANCE);
	}

	/** Una partida o una cuenta repetida es un pedido mal formado: 400 antes de leer nada, ni siquiera el bloqueo. */
	@Test
	void repeatedResolutionsOrBalancesFailBeforeReadingAnything() {
		List<Resolution> repeated = List.of(new Resolution(power.getId(), ClosingResolution.CARRY_OVER),
				new Resolution(power.getId(), ClosingResolution.CLOSE_AS_IS));

		assertThatThrownBy(() -> close(repeated, allBalances()))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.field()).isEqualTo("resolutions"));
		assertThatThrownBy(() -> close(allResolved(), List.of(real(42, "1.00"), real(42, "2.00"))))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.field()).isEqualTo("realBalances"));
		verify(periods, never()).lockByUserIdAndPeriodMonth(anyLong(), any());
		verifyNoInteractions(entries, accounts, closings);
	}

	/** Si el período siguiente no existiera o estuviera cerrado, es un error de programación y no cambia nada. */
	@Test
	void withoutAnOpenNextPeriodNothingChanges() {
		close(NOVEMBER);

		assertThatThrownBy(() -> close(allResolved(), allBalances())).isInstanceOf(IllegalStateException.class);
		assertNothingSaved();
	}

	// Aislamiento (RN-01).

	@Test
	void anotherUsersPeriodIsNotFound() {
		when(periods.lockByUserIdAndPeriodMonth(eq(OTHER_USER_ID), any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> serviceAt(NOW).close(OTHER_USER_ID, OCTOBER, allResolved(), allBalances()))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
		assertNothingSaved();
		verifyNoInteractions(accounts);
	}

	@Test
	void aPeriodOutOfRangeIsNotFound() {
		assertFailsWithoutSaving(() -> serviceAt(NOW).close(USER_ID, YearMonth.of(2026, 7), List.of(), List.of()),
				ErrorCode.NOT_FOUND);
	}

	/** Toda consulta va con el usuario actual. */
	@Test
	void everyQueryUsesTheCurrentUser() {
		close(allResolved(), allBalances());

		long octoberId = period(OCTOBER).getId();
		verify(periods).lockByUserIdAndPeriodMonth(USER_ID, OCTOBER);
		verify(periods).findByUserIdAndPeriodMonth(USER_ID, OCTOBER.minusMonths(1));
		verify(periods).findByUserIdAndPeriodMonth(USER_ID, NOVEMBER);
		verify(entries).findByUserIdAndPeriodIdWithDetails(USER_ID, octoberId);
		verify(movements).sumByEntryOfPeriod(USER_ID, octoberId);
		verify(accounts).balancesAt(USER_ID, LocalDate.of(2026, 10, 31));
		verify(periods, never()).findByUserIdAndPeriodMonth(eq(OTHER_USER_ID), any());
		verify(accounts, never()).balancesAt(eq(OTHER_USER_ID), any());
	}

	@SuppressWarnings("unchecked")
	private static <T> ArgumentCaptor<List<T>> captor() {
		return ArgumentCaptor.forClass((Class<List<T>>) (Class<?>) List.class);
	}
}
