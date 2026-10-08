package com.smartcoin.period.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.category.domain.Category;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.MonthTotals.CurrencyTotals;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.period.service.PeriodViewService.View;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-15, RN-44, D-29 y RN-01: sin base de datos, con repositorios simulados y el reloj en el 8 de octubre de 2026.
 * El usuario tiene los períodos 2026-08 a 2028-10.
 */
class PeriodViewServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
	static final long NOVEMBER_ID = 100;

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	PeriodViewService service;

	Account pesos = account(42, "Banco", Currency.ARS);
	Account dollars = account(43, "Caja de ahorro en dólares", Currency.USD);
	Category home = category(5, "Hogar");
	Category services = category(6, "Servicios");
	List<BudgetEntry> novemberEntries = new ArrayList<>();
	List<EntryTotal> novemberMovements = new ArrayList<>();
	long nextId = 1;

	@BeforeEach
	void setUp() {
		// 12:00 en Mendoza (UTC−3) del 8 de octubre de 2026.
		service = new PeriodViewService(periods, entries, movements,
				Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZONE));

		List<YearMonth> existing = new ArrayList<>();
		for (YearMonth month = YearMonth.of(2026, 8); !month.isAfter(YearMonth.of(2028, 10)); month = month
				.plusMonths(1)) {
			existing.add(month);
		}
		when(periods.findPeriodMonthsByUserId(USER_ID)).thenReturn(existing);
		givenPeriod(NOVEMBER, NOVEMBER_ID, PeriodStatus.OPEN);
		when(entries.findByUserIdAndPeriodIdWithDetails(USER_ID, NOVEMBER_ID)).thenReturn(novemberEntries);
		when(movements.sumByEntryOfPeriod(USER_ID, NOVEMBER_ID)).thenReturn(novemberMovements);
	}

	void givenPeriod(YearMonth month, long id, PeriodStatus status) {
		BudgetPeriod period = BudgetPeriod.open(USER_ID, month);
		ReflectionTestUtils.setField(period, "id", id);
		period.setStatus(status);
		when(periods.findByUserIdAndPeriodMonth(USER_ID, month)).thenReturn(Optional.of(period));
	}

	static Account account(long id, String name, Currency currency) {
		Account a = new Account();
		ReflectionTestUtils.setField(a, "id", id);
		a.setName(name);
		a.setCurrency(currency);
		return a;
	}

	static Category category(long id, String name) {
		Category c = new Category();
		ReflectionTestUtils.setField(c, "id", id);
		c.setName(name);
		return c;
	}

	BudgetItem item(String name, Category category) {
		BudgetItem i = new BudgetItem();
		ReflectionTestUtils.setField(i, "id", 1000 + nextId++);
		i.setUserId(USER_ID);
		i.setName(name);
		i.setCategory(category);
		return i;
	}

	/** Partida recurrente pendiente de noviembre: como las guarda la generación, sin nombre ni categoría propios. */
	BudgetEntry recurring(BudgetItem item, EntryKind kind, Account account, String dueDate, String budgeted) {
		BudgetEntry e = entry(EntryOrigin.RECURRING, kind, account, dueDate, budgeted);
		e.setBudgetItem(item);
		return e;
	}

	BudgetEntry oneOff(String name, Category category, EntryKind kind, Account account, String dueDate,
			String budgeted) {
		BudgetEntry e = entry(EntryOrigin.ONE_OFF, kind, account, dueDate, budgeted);
		e.setName(name);
		e.setCategory(category);
		return e;
	}

	BudgetEntry entry(EntryOrigin origin, EntryKind kind, Account account, String dueDate, String budgeted) {
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", nextId++);
		e.setUserId(USER_ID);
		e.setOrigin(origin);
		e.setKind(kind);
		e.setAccount(account);
		e.setDueDate(LocalDate.parse(dueDate));
		e.setBudgetedAmount(new BigDecimal(budgeted));
		e.setStatus(StoredEntryStatus.PENDING);
		novemberEntries.add(e);
		return e;
	}

	void paid(BudgetEntry entry, String total) {
		novemberMovements.add(new EntryTotal(entry.getId(), new BigDecimal(total)));
	}

	void consolidated(BudgetEntry entry, String total) {
		paid(entry, total);
		entry.setStatus(StoredEntryStatus.CONSOLIDATED);
		entry.setConsolidatedAmount(new BigDecimal(total));
		entry.setConsolidatedAt(Instant.parse("2026-10-01T12:00:00Z"));
	}

	static List<String> names(List<EntryRow> rows) {
		return rows.stream().map(EntryRow::name).toList();
	}

	static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	@Test
	void theExampleOfTheStoryWithItsDerivedValuesAndTotals() {
		consolidated(recurring(item("Sueldo A", null), EntryKind.INCOME, pesos, "2026-10-25", "1200000.00"),
				"1200000.00");
		recurring(item("Sueldo B", null), EntryKind.INCOME, pesos, "2026-10-30", "650000.00");
		consolidated(recurring(item("Alquiler", home), EntryKind.EXPENSE, pesos, "2026-11-05", "450000.00"),
				"450000.00");
		paid(recurring(item("Luz", services), EntryKind.EXPENSE, pesos, "2026-11-18", "45000.00"), "20000.00");
		recurring(item("Resumen Visa", null), EntryKind.EXPENSE, pesos, "2026-11-10", "240000.00");

		View view = service.view(USER_ID, NOVEMBER);

		assertThat(view.period()).isEqualTo(NOVEMBER);
		assertThat(view.status()).isEqualTo(PeriodStatus.OPEN);
		assertThat(names(view.incomes())).containsExactly("Sueldo A", "Sueldo B");
		assertThat(names(view.expenses())).containsExactly("Alquiler", "Resumen Visa", "Luz");
		assertThat(view.incomes()).extracting(r -> r.amounts().status())
				.containsExactly(EntryStatus.CONSOLIDATED, EntryStatus.ESTIMATED);
		EntryRow light = view.expenses().get(2);
		assertThat(light.budgetedAmount()).isEqualTo(amount("45000.00"));
		assertThat(light.amounts().actual()).isEqualTo(amount("20000.00"));
		assertThat(light.amounts().pending()).isEqualTo(amount("25000.00"));
		assertThat(light.amounts().forecast()).isEqualTo(amount("45000.00"));
		assertThat(light.amounts().status()).isEqualTo(EntryStatus.PARTIAL);
		assertThat(light.currency()).isEqualTo(Currency.ARS);
		assertThat(light.accountName()).isEqualTo("Banco");

		assertThat(view.totals()).hasSize(1);
		CurrencyTotals ars = view.totals().getFirst();
		assertThat(ars.currency()).isEqualTo(Currency.ARS);
		assertThat(ars.income().budgeted()).isEqualTo(amount("1850000.00"));
		assertThat(ars.income().actual()).isEqualTo(amount("1200000.00"));
		assertThat(ars.income().pending()).isEqualTo(amount("650000.00"));
		assertThat(ars.income().forecast()).isEqualTo(amount("1850000.00"));
		assertThat(ars.expense().budgeted()).isEqualTo(amount("735000.00"));
		assertThat(ars.expense().actual()).isEqualTo(amount("470000.00"));
		assertThat(ars.expense().pending()).isEqualTo(amount("265000.00"));
		assertThat(ars.expense().forecast()).isEqualTo(amount("735000.00"));
		assertThat(ars.result()).isEqualTo(amount("1115000.00"));
	}

	@Test
	void ordersByDueDateThenNameIgnoringCaseThenCreation() {
		BudgetEntry secondTwin = recurring(item("Seguro", null), EntryKind.EXPENSE, pesos, "2026-11-10", "1.00");
		BudgetEntry firstTwin = recurring(item("Seguro", null), EntryKind.EXPENSE, pesos, "2026-11-10", "2.00");
		// El que se creó primero tiene el id menor, aunque la consulta lo devuelva después.
		ReflectionTestUtils.setField(firstTwin, "id", 1L);
		ReflectionTestUtils.setField(secondTwin, "id", 2L);
		recurring(item("alquiler", null), EntryKind.EXPENSE, pesos, "2026-11-10", "3.00");
		recurring(item("Zapatos", null), EntryKind.EXPENSE, pesos, "2026-11-10", "4.00");
		// Con desfase −1 vence el mes anterior al período (S-20): va primero.
		recurring(item("Tarjeta", null), EntryKind.EXPENSE, pesos, "2026-10-31", "5.00");
		oneOff("Regalo", null, EntryKind.EXPENSE, dollars, "2026-11-02", "6.00");

		View view = service.view(USER_ID, NOVEMBER);

		assertThat(names(view.expenses()))
				.containsExactly("Tarjeta", "Regalo", "alquiler", "Seguro", "Seguro", "Zapatos");
		assertThat(view.expenses()).extracting(EntryRow::budgetedAmount).containsExactly(amount("5.00"),
				amount("6.00"), amount("3.00"), amount("2.00"), amount("1.00"), amount("4.00"));
		assertThat(view.incomes()).isEmpty();
	}

	@Test
	void aRecurringEntryShowsTheNameCategoryAndInstallmentsOfItsItem() {
		BudgetItem fridge = item("Heladera", home);
		fridge.setInstallmentsTotal(12);
		fridge.setFirstInstallmentNumber(4);
		BudgetEntry entry = recurring(fridge, EntryKind.EXPENSE, pesos, "2026-11-15", "85000.00");
		entry.setInstallmentNumber(5);
		entry.setManual(true);
		// Aunque la fila tuviera datos propios, en una recurrente mandan los del Concepto (RN-15).
		entry.setName("Nombre viejo");
		entry.setCategory(services);

		EntryRow row = service.view(USER_ID, NOVEMBER).expenses().getFirst();

		assertThat(row.id()).isEqualTo(entry.getId());
		assertThat(row.budgetItemId()).isEqualTo(fridge.getId());
		assertThat(row.origin()).isEqualTo(EntryOrigin.RECURRING);
		assertThat(row.name()).isEqualTo("Heladera");
		assertThat(row.categoryId()).isEqualTo(5L);
		assertThat(row.categoryName()).isEqualTo("Hogar");
		assertThat(row.installmentNumber()).isEqualTo(5);
		assertThat(row.installmentsTotal()).isEqualTo(12);
		assertThat(row.manual()).isTrue();
	}

	@Test
	void aRecurringEntryOfAnItemWithoutCategoryOrInstallmentsHasNone() {
		recurring(item("Sueldo", null), EntryKind.INCOME, pesos, "2026-11-01", "10.00");

		EntryRow row = service.view(USER_ID, NOVEMBER).incomes().getFirst();

		assertThat(row.categoryId()).isNull();
		assertThat(row.categoryName()).isNull();
		assertThat(row.installmentNumber()).isNull();
		assertThat(row.installmentsTotal()).isNull();
		assertThat(row.manual()).isFalse();
	}

	@Test
	void anEntryWithoutItemShowsItsOwnNameAndCategory() {
		oneOff("Regalo de casamiento", home, EntryKind.EXPENSE, dollars, "2026-11-20", "300.00");
		BudgetEntry carried = entry(EntryOrigin.CARRIED_OVER, EntryKind.EXPENSE, pesos, "2026-11-18", "25000.00");
		carried.setName("Saldo pendiente: Luz");

		List<EntryRow> rows = service.view(USER_ID, NOVEMBER).expenses();

		assertThat(rows).extracting(EntryRow::name, EntryRow::categoryName, EntryRow::origin, EntryRow::budgetItemId,
				EntryRow::currency).containsExactly(
						org.assertj.core.groups.Tuple.tuple("Saldo pendiente: Luz", null, EntryOrigin.CARRIED_OVER,
								null, Currency.ARS),
						org.assertj.core.groups.Tuple.tuple("Regalo de casamiento", "Hogar", EntryOrigin.ONE_OFF,
								null, Currency.USD));
	}

	@Test
	void overdueDependsOnTodayFromTheClock() {
		recurring(item("Ayer", null), EntryKind.EXPENSE, pesos, "2026-10-07", "1.00");
		recurring(item("Hoy", null), EntryKind.EXPENSE, pesos, "2026-10-08", "1.00");
		recurring(item("Mañana", null), EntryKind.EXPENSE, pesos, "2026-10-09", "1.00");
		consolidated(recurring(item("Pagada", null), EntryKind.EXPENSE, pesos, "2026-10-01", "1.00"), "1.00");

		assertThat(service.view(USER_ID, NOVEMBER).expenses()).extracting(EntryRow::name, EntryRow::overdue)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("Pagada", false),
						org.assertj.core.groups.Tuple.tuple("Ayer", true),
						org.assertj.core.groups.Tuple.tuple("Hoy", false),
						org.assertj.core.groups.Tuple.tuple("Mañana", false));
	}

	@Test
	void totalsAreSeparatedByCurrency() {
		recurring(item("Sueldo", null), EntryKind.INCOME, pesos, "2026-11-01", "650000.00");
		recurring(item("Alquiler cobrado", null), EntryKind.INCOME, dollars, "2026-11-05", "1000.00");
		recurring(item("Luz", null), EntryKind.EXPENSE, pesos, "2026-11-18", "45000.00");

		List<CurrencyTotals> totals = service.view(USER_ID, NOVEMBER).totals();

		assertThat(totals).extracting(CurrencyTotals::currency, CurrencyTotals::result, t -> t.expense().entryCount())
				.containsExactly(org.assertj.core.groups.Tuple.tuple(Currency.ARS, amount("605000.00"), 1),
						org.assertj.core.groups.Tuple.tuple(Currency.USD, amount("1000.00"), 0));
	}

	@Test
	void anEmptyPeriodHasNoEntriesNorTotals() {
		View view = service.view(USER_ID, NOVEMBER);

		assertThat(view.incomes()).isEmpty();
		assertThat(view.expenses()).isEmpty();
		assertThat(view.totals()).isEmpty();
	}

	@Test
	void informsTheRangeFromTheExistingPeriodsAndTheCurrentPeriodFromTheClock() {
		View view = service.view(USER_ID, NOVEMBER);

		assertThat(view.startPeriod()).isEqualTo(YearMonth.of(2026, 8));
		assertThat(view.currentPeriod()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(view.horizon()).isEqualTo(YearMonth.of(2028, 10));
	}

	@Test
	void currentIsThePeriodOfTodayInTheZoneOfTheClock() {
		// 01:00 UTC del 1 de noviembre todavía es 31 de octubre en Mendoza.
		service = new PeriodViewService(periods, entries, movements,
				Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZONE));
		givenPeriod(YearMonth.of(2026, 10), 99, PeriodStatus.OPEN);

		View view = service.current(USER_ID);

		assertThat(view.period()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(view.currentPeriod()).isEqualTo(YearMonth.of(2026, 10));
		verify(entries).findByUserIdAndPeriodIdWithDetails(USER_ID, 99L);
	}

	@Test
	void aClosedPeriodIsShownTheSameWithItsStatus() {
		givenPeriod(YearMonth.of(2026, 8), 98, PeriodStatus.CLOSED);

		assertThat(service.view(USER_ID, YearMonth.of(2026, 8)).status()).isEqualTo(PeriodStatus.CLOSED);
	}

	@Test
	void aPeriodBeforeTheStartOrAfterTheHorizonIsNotFoundAndNothingElseIsRead() {
		for (YearMonth outside : List.of(YearMonth.of(2026, 7), YearMonth.of(2028, 11), YearMonth.of(1999, 1))) {
			when(periods.findByUserIdAndPeriodMonth(USER_ID, outside)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.view(USER_ID, outside))
					.isInstanceOfSatisfying(BusinessException.class,
							e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND))
					.hasMessageContaining(outside.toString());
		}
		verifyNoInteractions(entries, movements);
	}

	@Test
	void everyQueryCarriesTheCurrentUser() {
		recurring(item("Sueldo", null), EntryKind.INCOME, pesos, "2026-11-01", "10.00");

		service.view(USER_ID, NOVEMBER);

		verify(periods).findByUserIdAndPeriodMonth(USER_ID, NOVEMBER);
		verify(periods).findPeriodMonthsByUserId(USER_ID);
		verify(entries).findByUserIdAndPeriodIdWithDetails(USER_ID, NOVEMBER_ID);
		verify(movements).sumByEntryOfPeriod(USER_ID, NOVEMBER_ID);
		verify(periods, never()).findByUserIdAndPeriodMonth(OTHER_USER_ID, NOVEMBER);
	}

	@Test
	void theDataOfAnotherUserNeitherAppearsNorAdds() {
		// El otro usuario tiene su propio noviembre, con su id de período, sus partidas y sus movimientos.
		long otherNovemberId = 200;
		BudgetPeriod otherNovember = BudgetPeriod.open(OTHER_USER_ID, NOVEMBER);
		ReflectionTestUtils.setField(otherNovember, "id", otherNovemberId);
		when(periods.findByUserIdAndPeriodMonth(OTHER_USER_ID, NOVEMBER)).thenReturn(Optional.of(otherNovember));
		BudgetEntry foreign = new BudgetEntry();
		ReflectionTestUtils.setField(foreign, "id", 900L);
		foreign.setUserId(OTHER_USER_ID);
		when(entries.findByUserIdAndPeriodIdWithDetails(OTHER_USER_ID, otherNovemberId)).thenReturn(List.of(foreign));
		when(movements.sumByEntryOfPeriod(OTHER_USER_ID, otherNovemberId))
				.thenReturn(List.of(new EntryTotal(900L, amount("999999.00"))));
		recurring(item("Sueldo", null), EntryKind.INCOME, pesos, "2026-11-01", "10.00");

		View view = service.view(USER_ID, NOVEMBER);

		assertThat(names(view.incomes())).containsExactly("Sueldo");
		assertThat(view.totals().getFirst().income().budgeted()).isEqualTo(amount("10.00"));
		assertThat(view.totals().getFirst().income().actual()).isEqualTo(amount("0.00"));
		verify(entries, never()).findByUserIdAndPeriodIdWithDetails(OTHER_USER_ID, otherNovemberId);
		verify(movements, never()).sumByEntryOfPeriod(anyLong(), org.mockito.ArgumentMatchers.eq(otherNovemberId));
	}

	@Test
	void aUserWithoutThatPeriodGetsNotFoundEvenIfAnotherUserHasIt() {
		when(periods.findByUserIdAndPeriodMonth(OTHER_USER_ID, NOVEMBER)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.view(OTHER_USER_ID, NOVEMBER))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
	}
}
