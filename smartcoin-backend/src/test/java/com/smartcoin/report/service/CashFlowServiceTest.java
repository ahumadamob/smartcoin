package com.smartcoin.report.service;

import static com.smartcoin.shared.domain.Currency.ARS;
import static com.smartcoin.shared.domain.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.service.AccountService;
import com.smartcoin.account.service.AccountService.AccountBalance;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.domain.Movement;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.report.domain.CashFlowCalculator.CashFlowRowStatus;
import com.smartcoin.report.domain.CashFlowCalculator.CashFlowRowType;
import com.smartcoin.report.domain.CashFlowCalculator.Row;
import com.smartcoin.report.service.CashFlowService.CashFlow;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.transfer.domain.Transfer;
import com.smartcoin.transfer.repository.TransferRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-34, RN-45, RN-01 y D-46: sin base de datos, con repositorios simulados y el reloj en el 17 de octubre de 2026.
 * Período inicial agosto de 2026; horizonte octubre de 2028.
 */
class CashFlowServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

	AccountService accounts = mock(AccountService.class);
	MovementRepository movements = mock(MovementRepository.class);
	TransferRepository transfers = mock(TransferRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	CashFlowService service;

	Account bank = account(1, "Banco", ARS, "2026-08-01", "60000.00");
	Account wallet = account(2, "Billetera", ARS, "2026-10-12", "20000.00");
	Account dollars = account(3, "Caja USD", USD, "2026-08-01", "100.00");

	@BeforeEach
	void setUp() {
		service = new CashFlowService(accounts, movements, transfers, entries, periods,
				Clock.fixed(Instant.parse("2026-10-17T15:00:00Z"), ZONE));
		when(periods.findPeriodMonthsByUserId(USER_ID))
				.thenReturn(List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2028, 10)));
		when(accounts.balancesAt(anyLong(), any(LocalDate.class))).thenReturn(List.of());
		when(movements.findForCashFlow(anyLong(), any(), any(), any())).thenReturn(List.of());
		when(movements.sumByPendingEntriesDueUntil(anyLong(), any(), any(), any())).thenReturn(List.of());
		when(transfers.findCrossCurrencyForCashFlow(anyLong(), any(), any())).thenReturn(List.of());
		when(entries.findPendingForCashFlow(anyLong(), any(), any(), any())).thenReturn(List.of());
	}

	static Account account(long id, String name, Currency currency, String openingDate, String initialBalance) {
		Account a = new Account();
		ReflectionTestUtils.setField(a, "id", id);
		a.setName(name);
		a.setCurrency(currency);
		a.setOpeningDate(LocalDate.parse(openingDate));
		a.setInitialBalance(new BigDecimal(initialBalance));
		return a;
	}

	static BudgetEntry entry(long id, EntryKind kind, String name, Account account, String dueDate, String budgeted) {
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", id);
		e.setKind(kind);
		e.setName(name);
		e.setAccount(account);
		e.setDueDate(LocalDate.parse(dueDate));
		e.setBudgetedAmount(new BigDecimal(budgeted));
		e.setStatus(StoredEntryStatus.PENDING);
		return e;
	}

	static Movement movement(long id, BudgetEntry entry, Account account, String date, String amount) {
		Movement m = new Movement();
		ReflectionTestUtils.setField(m, "id", id);
		m.setEntry(entry);
		m.setAccount(account);
		m.setMovementDate(LocalDate.parse(date));
		m.setAmount(new BigDecimal(amount));
		return m;
	}

	static List<String> balances(CashFlow flow) {
		return flow.result().rows().stream().map(r -> r.balance().toPlainString()).toList();
	}

	@Test
	void withoutDatesItIsTheCurrentMonthOfTheClock() {
		CashFlow flow = service.cashFlow(USER_ID, null, null, ARS);

		assertThat(flow.from()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(flow.to()).isEqualTo(LocalDate.of(2026, 10, 31));
		assertThat(flow.currency()).isEqualTo(ARS);
		assertThat(flow.result().rows()).isEmpty();
		assertThat(flow.result().openingBalance()).isEqualByComparingTo("0.00");
		// Los saldos se piden al día anterior al rango.
		verify(accounts).balancesAt(USER_ID, LocalDate.of(2026, 9, 30));
	}

	@Test
	void onlyFromCompletesToWithTheEndOfItsMonth() {
		CashFlow flow = service.cashFlow(USER_ID, LocalDate.of(2026, 11, 10), null, ARS);

		assertThat(flow.to()).isEqualTo(LocalDate.of(2026, 11, 30));
	}

	@Test
	void theOpeningBalanceSumsTheAccountsOfTheCurrencyOpenedBeforeTheRangeAndOtherAccountsBecomeRows() {
		LocalDate dayBefore = LocalDate.of(2026, 10, 9);
		when(accounts.balancesAt(USER_ID, dayBefore)).thenReturn(List.of(
				new AccountBalance(bank, new BigDecimal("80000.00")),
				// Billetera abre el 12/10: su "saldo" a esa fecha es el inicial y no debe entrar al arranque.
				new AccountBalance(wallet, new BigDecimal("20000.00")),
				new AccountBalance(dollars, new BigDecimal("500.00"))));

		CashFlow flow = service.cashFlow(USER_ID, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 31), ARS);

		assertThat(flow.result().openingBalance()).isEqualByComparingTo("80000.00");
		assertThat(flow.result().rows()).hasSize(1);
		Row opening = flow.result().rows().get(0);
		assertThat(opening.type()).isEqualTo(CashFlowRowType.ACCOUNT_OPENING);
		assertThat(opening.date()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(opening.balance()).isEqualByComparingTo("100000.00");
	}

	@Test
	void theUsdFlowOnlyUsesTheAccountsInDollars() {
		when(accounts.balancesAt(USER_ID, LocalDate.of(2026, 9, 30))).thenReturn(List.of(
				new AccountBalance(bank, new BigDecimal("80000.00")),
				new AccountBalance(dollars, new BigDecimal("500.00"))));

		CashFlow flow = service.cashFlow(USER_ID, null, null, USD);

		assertThat(flow.result().openingBalance()).isEqualByComparingTo("500.00");
		verify(movements).findForCashFlow(USER_ID, USD, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
	}

	@Test
	void movementsAreSignedByTheEntryKindIncludingAnEarlyCollectionOfNextMonthsEntry() {
		BudgetItem salary = new BudgetItem();
		salary.setName("Sueldo");
		BudgetEntry december = entry(10, EntryKind.INCOME, null, bank, "2026-12-01", "400000.00");
		december.setBudgetItem(salary);
		BudgetEntry rent = entry(11, EntryKind.EXPENSE, "Alquiler", bank, "2026-10-05", "150000.00");
		when(movements.findForCashFlow(USER_ID, ARS, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
				.thenReturn(List.of(movement(1, december, bank, "2026-11-25", "400000.00"),
						movement(2, rent, bank, "2026-11-03", "150000.00")));

		CashFlow flow = service.cashFlow(USER_ID, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), ARS);

		assertThat(flow.result().rows()).extracting(Row::description).containsExactly("Alquiler · Banco",
				"Sueldo · Banco");
		assertThat(flow.result().rows()).extracting(Row::amount).containsExactly(new BigDecimal("-150000.00"),
				new BigDecimal("400000.00"));
		assertThat(flow.result().rows()).extracting(Row::status).containsOnly(CashFlowRowStatus.REAL);
	}

	@Test
	void aPartialEntryListsOnlyWhatIsStillPendingAndAPaidInFullOneIsNotListed() {
		BudgetEntry partial = entry(20, EntryKind.EXPENSE, "Luz", bank, "2026-10-25", "100000.00");
		BudgetEntry paid = entry(21, EntryKind.EXPENSE, "Gas", bank, "2026-10-26", "30000.00");
		BudgetEntry untouched = entry(22, EntryKind.INCOME, "Freelance", bank, "2026-10-27", "50000.00");
		when(entries.findPendingForCashFlow(USER_ID, StoredEntryStatus.PENDING, ARS, LocalDate.of(2026, 10, 31)))
				.thenReturn(List.of(partial, paid, untouched));
		when(movements.sumByPendingEntriesDueUntil(USER_ID, StoredEntryStatus.PENDING, ARS, LocalDate.of(2026, 10, 31)))
				.thenReturn(List.of(new EntryTotal(20L, new BigDecimal("30000.00")),
						new EntryTotal(21L, new BigDecimal("30000.00"))));

		CashFlow flow = service.cashFlow(USER_ID, null, null, ARS);

		assertThat(flow.result().rows()).extracting(Row::description).containsExactly("Luz · Banco",
				"Freelance · Banco");
		assertThat(flow.result().rows()).extracting(Row::amount).containsExactly(new BigDecimal("-70000.00"),
				new BigDecimal("50000.00"));
		assertThat(flow.result().rows()).extracting(Row::status).containsOnly(CashFlowRowStatus.PLANNED);
	}

	@Test
	void aRangeAfterTodayOpensWithTheProjectedBalance() {
		when(accounts.balancesAt(USER_ID, LocalDate.of(2026, 10, 31)))
				.thenReturn(List.of(new AccountBalance(bank, new BigDecimal("100000.00"))));
		when(entries.findPendingForCashFlow(USER_ID, StoredEntryStatus.PENDING, ARS, LocalDate.of(2026, 11, 30)))
				.thenReturn(List.of(entry(1, EntryKind.EXPENSE, "Luz", bank, "2026-10-10", "20000.00"),
						entry(2, EntryKind.EXPENSE, "Tarjeta", bank, "2026-10-20", "40000.00"),
						entry(3, EntryKind.INCOME, "Sueldo", bank, "2026-10-30", "300000.00"),
						entry(4, EntryKind.EXPENSE, "Alquiler", bank, "2026-11-05", "150000.00")));

		CashFlow flow = service.cashFlow(USER_ID, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), ARS);

		assertThat(flow.result().openingBalanceProjected()).isTrue();
		assertThat(flow.result().openingBalance()).isEqualByComparingTo("340000.00");
		assertThat(balances(flow)).containsExactly("190000.00");
	}

	@Test
	void crossCurrencyTransfersCarryTheSignOfTheRequestedCurrency() {
		Transfer buy = new Transfer();
		ReflectionTestUtils.setField(buy, "id", 5L);
		buy.setTransferDate(LocalDate.of(2026, 10, 6));
		buy.setSourceAccount(bank);
		buy.setTargetAccount(dollars);
		buy.setSourceAmount(new BigDecimal("1250000.00"));
		buy.setTargetAmount(new BigDecimal("1000.00"));
		when(transfers.findCrossCurrencyForCashFlow(USER_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
				.thenReturn(List.of(buy));

		CashFlow ars = service.cashFlow(USER_ID, null, null, ARS);
		CashFlow usd = service.cashFlow(USER_ID, null, null, USD);

		assertThat(ars.result().rows()).extracting(Row::amount).containsExactly(new BigDecimal("-1250000.00"));
		assertThat(usd.result().rows()).extracting(Row::amount).containsExactly(new BigDecimal("1000.00"));
	}

	@Test
	void everyQueryIsFilteredByTheCurrentUserAndRunsOncePerType() {
		service.cashFlow(USER_ID, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), ARS);

		verify(periods).findPeriodMonthsByUserId(USER_ID);
		verify(accounts, times(1)).balancesAt(eq(USER_ID), any(LocalDate.class));
		verify(movements, times(1)).findForCashFlow(eq(USER_ID), eq(ARS), any(), any());
		verify(movements, times(1)).sumByPendingEntriesDueUntil(eq(USER_ID), any(), eq(ARS), any());
		verify(transfers, times(1)).findCrossCurrencyForCashFlow(eq(USER_ID), any(), any());
		verify(entries, times(1)).findPendingForCashFlow(eq(USER_ID), any(), eq(ARS), any());
		verifyNoMoreInteractions(accounts, movements, transfers, entries, periods);
	}

	@Test
	void anotherUsersDataNeverAppears() {
		// El otro usuario tiene datos; el servicio consulta con el id del usuario actual y los repositorios solo
		// devuelven lo suyo.
		BudgetEntry othersEntry = entry(99, EntryKind.EXPENSE, "Ajena", bank, "2026-10-25", "1000.00");
		when(periods.findPeriodMonthsByUserId(OTHER_USER_ID)).thenReturn(List.of(YearMonth.of(2026, 8)));
		when(entries.findPendingForCashFlow(eq(OTHER_USER_ID), any(), any(), any())).thenReturn(List.of(othersEntry));

		CashFlow mine = service.cashFlow(USER_ID, null, null, ARS);

		assertThat(mine.result().rows()).isEmpty();
		verify(entries, times(0)).findPendingForCashFlow(eq(OTHER_USER_ID), any(), any(), any());
	}

	@Test
	void aRangeBeforeTheStartPeriodAfterTheHorizonOrTooLongIsAValidationError() {
		assertValidation(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), "from");
		assertValidation(LocalDate.of(2028, 10, 1), LocalDate.of(2028, 11, 1), "to");
		assertValidation(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 2), "to");
		assertValidation(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 9), "from");
		verify(accounts, times(0)).balancesAt(anyLong(), any(LocalDate.class));
	}

	private void assertValidation(LocalDate from, LocalDate to, String field) {
		assertThatThrownBy(() -> service.cashFlow(USER_ID, from, to, ARS))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo(field);
				});
	}
}
