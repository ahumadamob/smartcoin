package com.smartcoin.report.service;

import static com.smartcoin.shared.domain.Currency.ARS;
import static com.smartcoin.shared.domain.Currency.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.report.domain.ProjectionCalculator.Result;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** HU-35, RN-46, RN-01: repositorios simulados y el reloj en el 17/10/2026; horizonte octubre de 2028. */
class ProjectionServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

	AccountService accounts = mock(AccountService.class);
	MovementRepository movements = mock(MovementRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	ProjectionService service;

	Account bank = CashFlowServiceTest.account(1, "Banco", ARS, "2026-08-01", "60000.00");
	Account dollars = CashFlowServiceTest.account(3, "Caja USD", USD, "2026-08-01", "100.00");

	@BeforeEach
	void setUp() {
		service = new ProjectionService(accounts, movements, entries, periods,
				Clock.fixed(Instant.parse("2026-10-17T15:00:00Z"), ZONE));
		when(periods.findPeriodMonthsByUserId(USER_ID))
				.thenReturn(List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2028, 10)));
		when(accounts.balancesAt(anyLong(), any(LocalDate.class))).thenReturn(List.of());
		when(movements.sumByPendingEntriesUntil(anyLong(), any(), any())).thenReturn(List.of());
		when(entries.findPendingUntil(anyLong(), any(), any())).thenReturn(List.of());
	}

	private static String balance(Result result, int monthIndex, int accountIndex) {
		return result.months().get(monthIndex).balances().get(accountIndex).balance().toPlainString();
	}

	@Test
	void withoutUntilItGoesFromTheCurrentMonthToTheHorizon() {
		when(accounts.balancesAt(USER_ID, TODAY)).thenReturn(List.of(new AccountBalance(bank, new BigDecimal("100000"))));

		Result result = service.projection(USER_ID, null);

		assertThat(result.from()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(result.until()).isEqualTo(YearMonth.of(2028, 10));
		assertThat(result.months()).hasSize(25);
		assertThat(balance(result, 0, 0)).isEqualTo("100000.00");
		// Los saldos son los de hoy y los pendientes se piden hasta el último día proyectado.
		verify(accounts).balancesAt(USER_ID, TODAY);
		verify(entries).findPendingUntil(USER_ID, StoredEntryStatus.PENDING, LocalDate.of(2028, 10, 31));
		verify(movements).sumByPendingEntriesUntil(USER_ID, StoredEntryStatus.PENDING, LocalDate.of(2028, 10, 31));
	}

	@Test
	void anExplicitUntilLimitsTheMonthsAndTheQuery() {
		Result result = service.projection(USER_ID, YearMonth.of(2026, 12));

		assertThat(result.months()).hasSize(3);
		verify(entries).findPendingUntil(USER_ID, StoredEntryStatus.PENDING, LocalDate.of(2026, 12, 31));
	}

	@Test
	void untilTheCurrentMonthAndTheHorizonAreAccepted() {
		assertThat(service.projection(USER_ID, YearMonth.of(2026, 10)).months()).hasSize(1);
		assertThat(service.projection(USER_ID, YearMonth.of(2028, 10)).months()).hasSize(25);
	}

	@Test
	void anUntilBeyondTheHorizonIsAValidationErrorOnUntil() {
		assertThatThrownBy(() -> service.projection(USER_ID, YearMonth.of(2028, 11)))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("until");
				});
	}

	@Test
	void anUntilBeforeTheCurrentMonthIsAValidationErrorOnUntil() {
		assertThatThrownBy(() -> service.projection(USER_ID, YearMonth.of(2026, 9)))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("until");
				});
	}

	@Test
	void aUserWithoutPeriodsIsNotFound() {
		assertThatThrownBy(() -> service.projection(OTHER_USER_ID, null))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void aPartialEntryProjectsOnlyWhatIsMissingAndAnotherCurrencyStaysApart() {
		BudgetEntry card = CashFlowServiceTest.entry(10, EntryKind.EXPENSE, "Tarjeta", bank, "2026-10-20", "40000.00");
		BudgetEntry salary = CashFlowServiceTest.entry(11, EntryKind.INCOME, "Sueldo", bank, "2026-10-30", "300000.00");
		BudgetEntry usdRent = CashFlowServiceTest.entry(12, EntryKind.EXPENSE, "Alquiler", dollars, "2026-11-05", "50.00");
		when(accounts.balancesAt(USER_ID, TODAY)).thenReturn(List.of(
				new AccountBalance(bank, new BigDecimal("100000.00")),
				new AccountBalance(dollars, new BigDecimal("500.00"))));
		when(entries.findPendingUntil(USER_ID, StoredEntryStatus.PENDING, LocalDate.of(2026, 11, 30)))
				.thenReturn(List.of(card, salary, usdRent));
		// La Tarjeta tiene 15.000 pagados: queda un pendiente de 25.000.
		when(movements.sumByPendingEntriesUntil(USER_ID, StoredEntryStatus.PENDING, LocalDate.of(2026, 11, 30)))
				.thenReturn(List.of(new EntryTotal(10L, new BigDecimal("15000.00"))));

		Result result = service.projection(USER_ID, YearMonth.of(2026, 11));

		assertThat(balance(result, 0, 0)).isEqualTo("375000.00");
		assertThat(balance(result, 1, 1)).isEqualTo("450.00");
		assertThat(result.months().get(0).subtotals()).extracting(s -> s.currency()).containsExactly(ARS, USD);
		assertThat(result.months().get(0).subtotals().get(1).balance().toPlainString()).isEqualTo("500.00");
	}

	@Test
	void everyQueryCarriesTheUserAndTheNumberOfQueriesDoesNotGrowWithTheData() {
		BudgetEntry a = CashFlowServiceTest.entry(10, EntryKind.EXPENSE, "A", bank, "2026-10-20", "1.00");
		BudgetEntry b = CashFlowServiceTest.entry(11, EntryKind.EXPENSE, "B", bank, "2026-11-20", "1.00");
		BudgetEntry c = CashFlowServiceTest.entry(12, EntryKind.EXPENSE, "C", bank, "2026-12-20", "1.00");
		when(entries.findPendingUntil(anyLong(), any(), any())).thenReturn(List.of(a, b, c));

		service.projection(USER_ID, null);

		verify(periods).findPeriodMonthsByUserId(USER_ID);
		verify(accounts).balancesAt(USER_ID, TODAY);
		verify(entries).findPendingUntil(USER_ID, StoredEntryStatus.PENDING, LocalDate.of(2028, 10, 31));
		verify(movements).sumByPendingEntriesUntil(USER_ID, StoredEntryStatus.PENDING, LocalDate.of(2028, 10, 31));
		verifyNoMoreInteractions(accounts, entries, movements, periods);
	}

	@Test
	void anotherUsersDataNeverAppears() {
		when(accounts.balancesAt(OTHER_USER_ID, TODAY)).thenReturn(List.of(new AccountBalance(bank, new BigDecimal("999.00"))));
		when(periods.findPeriodMonthsByUserId(OTHER_USER_ID)).thenReturn(List.of(YearMonth.of(2026, 8), YearMonth.of(2028, 10)));

		Result mine = service.projection(USER_ID, YearMonth.of(2026, 10));

		assertThat(mine.accounts()).isEmpty();
		verify(accounts).balancesAt(USER_ID, TODAY);
	}
}
