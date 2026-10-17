package com.smartcoin.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.entry.domain.EntryTotal;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService;
import com.smartcoin.report.service.BudgetGridService.Cell;
import com.smartcoin.report.service.BudgetGridService.Grid;
import com.smartcoin.report.service.BudgetGridService.Row;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-36, RN-17, RN-44, D-48 y RN-01: sin base de datos, con repositorios simulados y el reloj en el 8 de octubre de
 * 2026. El usuario tiene los períodos 2026-08 a 2028-10; 2026-08 y 2026-09 están cerrados.
 */
class BudgetGridServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final YearMonth START = YearMonth.of(2026, 8);
	static final YearMonth HORIZON = YearMonth.of(2028, 10);

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	BudgetGridService service;
	PeriodViewService monthView;

	Account pesos = account(42, Currency.ARS);
	Account dollars = account(43, Currency.USD);
	List<BudgetEntry> stored = new ArrayList<>();
	List<EntryTotal> storedMovements = new ArrayList<>();
	List<BudgetPeriod> storedPeriods = new ArrayList<>();
	long nextId = 1;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(Instant.parse("2026-10-08T15:00:00Z"), ZONE);
		service = new BudgetGridService(periods, entries, movements, clock);
		monthView = new PeriodViewService(periods, entries, movements, clock);

		List<YearMonth> existing = new ArrayList<>();
		for (YearMonth m = START; !m.isAfter(HORIZON); m = m.plusMonths(1)) {
			existing.add(m);
			BudgetPeriod p = BudgetPeriod.open(USER_ID, m);
			ReflectionTestUtils.setField(p, "id", 100L + existing.size());
			if (m.isBefore(YearMonth.of(2026, 10))) {
				p.setStatus(PeriodStatus.CLOSED);
			}
			storedPeriods.add(p);
			when(periods.findByUserIdAndPeriodMonth(USER_ID, m)).thenReturn(Optional.of(p));
			when(entries.findByUserIdAndPeriodIdWithDetails(USER_ID, p.getId()))
					.thenAnswer(i -> stored.stream().filter(e -> e.getPeriod() == p).toList());
			when(movements.sumByEntryOfPeriod(USER_ID, p.getId())).thenAnswer(i -> storedMovements.stream()
					.filter(t -> stored.stream().anyMatch(e -> e.getId().equals(t.entryId()) && e.getPeriod() == p))
					.toList());
		}
		when(periods.findPeriodMonthsByUserId(USER_ID)).thenReturn(existing);
		when(periods.findByUserIdAndPeriodMonthIn(anyLong(), any())).thenAnswer(i -> {
			@SuppressWarnings("unchecked")
			java.util.Collection<YearMonth> wanted = i.getArgument(1);
			return storedPeriods.stream().filter(p -> wanted.contains(p.getPeriodMonth())).toList();
		});
		when(entries.findByUserIdAndPeriodRangeWithDetails(anyLong(), any(), any())).thenAnswer(i -> {
			YearMonth from = i.getArgument(1);
			YearMonth to = i.getArgument(2);
			return stored.stream().filter(e -> !e.getPeriod().getPeriodMonth().isBefore(from)
					&& !e.getPeriod().getPeriodMonth().isAfter(to)).toList();
		});
		when(movements.sumByEntryOfPeriodRange(anyLong(), any(), any())).thenAnswer(i -> {
			YearMonth from = i.getArgument(1);
			YearMonth to = i.getArgument(2);
			return storedMovements.stream().filter(t -> stored.stream().anyMatch(e -> e.getId().equals(t.entryId())
					&& !e.getPeriod().getPeriodMonth().isBefore(from) && !e.getPeriod().getPeriodMonth().isAfter(to)))
					.toList();
		});
	}

	static Account account(long id, Currency currency) {
		Account a = new Account();
		ReflectionTestUtils.setField(a, "id", id);
		a.setName("Cuenta " + id);
		a.setCurrency(currency);
		return a;
	}

	BudgetItem item(String name, EntryKind kind, Account account) {
		BudgetItem i = new BudgetItem();
		ReflectionTestUtils.setField(i, "id", 1000 + nextId++);
		i.setUserId(USER_ID);
		i.setName(name);
		i.setKind(kind);
		i.setDefaultAccount(account);
		return i;
	}

	BudgetPeriod period(String month) {
		return storedPeriods.stream().filter(p -> p.getPeriodMonth().equals(YearMonth.parse(month))).findFirst()
				.orElseThrow();
	}

	BudgetEntry entry(BudgetItem item, EntryOrigin origin, EntryKind kind, Account account, String month,
			String budgeted) {
		BudgetEntry e = new BudgetEntry();
		ReflectionTestUtils.setField(e, "id", nextId++);
		e.setUserId(USER_ID);
		e.setPeriod(period(month));
		e.setOrigin(origin);
		e.setKind(kind);
		e.setAccount(account);
		e.setDueDate(YearMonth.parse(month).atDay(10));
		e.setBudgetedAmount(new BigDecimal(budgeted));
		e.setStatus(StoredEntryStatus.PENDING);
		if (item != null) {
			e.setBudgetItem(item);
		}
		else {
			e.setName("Puntual " + e.getId());
		}
		stored.add(e);
		return e;
	}

	BudgetEntry recurring(BudgetItem item, String month, String budgeted) {
		return entry(item, EntryOrigin.RECURRING, item.getKind(), item.getDefaultAccount(), month, budgeted);
	}

	void paid(BudgetEntry entry, String total) {
		storedMovements.add(new EntryTotal(entry.getId(), new BigDecimal(total)));
	}

	void consolidated(BudgetEntry entry, String total) {
		paid(entry, total);
		entry.setStatus(StoredEntryStatus.CONSOLIDATED);
		entry.setConsolidatedAmount(new BigDecimal(total));
		entry.setConsolidatedAt(Instant.parse("2026-10-01T12:00:00Z"));
	}

	static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	Row row(Grid grid, String name) {
		return grid.rows().stream().filter(r -> name.equals(r.name())).findFirst().orElseThrow();
	}

	@Test
	void withoutParametersItIsTwelveMonthsFromTheCurrentOneAndMarksTheClosedOnes() {
		Grid grid = service.grid(USER_ID, null, null);

		assertThat(grid.from()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(grid.to()).isEqualTo(YearMonth.of(2027, 9));
		assertThat(grid.months()).hasSize(12);
		assertThat(grid.months()).allMatch(m -> m.status() == PeriodStatus.OPEN);
		assertThat(grid.currentPeriod()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(grid.startPeriod()).isEqualTo(START);
		assertThat(grid.horizon()).isEqualTo(HORIZON);
		assertThat(grid.rows()).isEmpty();
		assertThat(grid.totals()).hasSize(12).allMatch(t -> t.totals().isEmpty());
		verify(entries).findByUserIdAndPeriodRangeWithDetails(USER_ID, YearMonth.of(2026, 10),
				YearMonth.of(2027, 9));
	}

	@Test
	void aClosedMonthIsMarked() {
		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 8), YearMonth.of(2026, 11));

		assertThat(grid.months().stream().map(m -> m.status()).toList()).containsExactly(PeriodStatus.CLOSED,
				PeriodStatus.CLOSED, PeriodStatus.OPEN, PeriodStatus.OPEN);
	}

	@Test
	void aMonthlyConceptHasACellInEveryMonth() {
		BudgetItem sueldo = item("Sueldo", EntryKind.INCOME, pesos);
		for (int m = 10; m <= 12; m++) {
			recurring(sueldo, "2026-" + m, "1000000.00");
		}
		recurring(sueldo, "2027-01", "1100000.00");

		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 2));

		Row row = row(grid, "Sueldo");
		assertThat(row.budgetItemId()).isEqualTo(sueldo.getId());
		assertThat(row.kind()).isEqualTo(EntryKind.INCOME);
		assertThat(row.currency()).isEqualTo(Currency.ARS);
		assertThat(row.cells()).hasSize(5);
		assertThat(row.cells().subList(0, 4)).allMatch(c -> c != null && c.entryCount() == 1);
		assertThat(row.cells().get(3).forecast()).isEqualByComparingTo("1100000.00");
		assertThat(row.cells().get(4)).isNull();
	}

	@Test
	void aBimonthlyConceptHasEmptyCellsEveryOtherMonth() {
		BudgetItem agua = item("Agua", EntryKind.EXPENSE, pesos);
		for (String month : List.of("2026-10", "2026-12", "2027-02")) {
			recurring(agua, month, "30000.00");
		}

		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 2));

		List<Cell> cells = row(grid, "Agua").cells();
		assertThat(cells.stream().map(c -> c != null).toList()).containsExactly(true, false, true, false, true);
	}

	@Test
	void anInstallmentPlanThatEndsInsideTheRangeStopsThere() {
		BudgetItem heladera = item("Heladera en cuotas", EntryKind.EXPENSE, pesos);
		for (String month : List.of("2026-10", "2026-11", "2026-12")) {
			recurring(heladera, month, "80000.00");
		}

		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 3));

		assertThat(row(grid, "Heladera en cuotas").cells().stream().map(c -> c != null).toList())
				.containsExactly(true, true, true, false, false, false);
	}

	@Test
	void anEditedAConsolidatedAndAPartialEntryCarryTheirMarks() {
		BudgetItem luz = item("Luz", EntryKind.EXPENSE, pesos);
		BudgetEntry edited = recurring(luz, "2026-10", "50000.00");
		edited.setManual(true);
		BudgetEntry done = recurring(luz, "2026-11", "45000.00");
		consolidated(done, "47000.00");
		BudgetEntry partial = recurring(luz, "2026-12", "45000.00");
		paid(partial, "20000.00");
		recurring(luz, "2027-01", "45000.00");

		List<Cell> cells = row(service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 1)), "Luz").cells();

		assertThat(cells.get(0)).extracting(Cell::manual, Cell::consolidated, Cell::status)
				.containsExactly(true, false, EntryStatus.ESTIMATED);
		assertThat(cells.get(0).forecast()).isEqualByComparingTo("50000.00");
		assertThat(cells.get(1)).extracting(Cell::manual, Cell::consolidated, Cell::status)
				.containsExactly(false, true, EntryStatus.CONSOLIDATED);
		assertThat(cells.get(1).forecast()).isEqualByComparingTo("47000.00");
		assertThat(cells.get(2)).extracting(Cell::manual, Cell::consolidated, Cell::status)
				.containsExactly(false, false, EntryStatus.PARTIAL);
		assertThat(cells.get(2).forecast()).isEqualByComparingTo("45000.00");
		assertThat(cells.get(2).entryId()).isEqualTo(stored.get(2).getId());
	}

	@Test
	void entriesWithoutAConceptAreGroupedByOriginKindAndCurrency() {
		entry(null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, pesos, "2026-10", "10000.00");
		entry(null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, pesos, "2026-10", "5000.50");
		entry(null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, pesos, "2026-12", "7000.00");
		entry(null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, dollars, "2026-10", "100.00");
		entry(null, EntryOrigin.ONE_OFF, EntryKind.INCOME, pesos, "2026-11", "300000.00");
		entry(null, EntryOrigin.CARRIED_OVER, EntryKind.EXPENSE, pesos, "2026-11", "2000.00");
		BudgetEntry difference = entry(null, EntryOrigin.CLOSING_DIFFERENCE, EntryKind.INCOME, pesos, "2026-11",
				"150.00");
		difference.setManual(true);

		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2026, 12));

		assertThat(grid.rows()).extracting(Row::kind, Row::origin, Row::currency).containsExactly(
				org.assertj.core.groups.Tuple.tuple(EntryKind.INCOME, EntryOrigin.ONE_OFF, Currency.ARS),
				org.assertj.core.groups.Tuple.tuple(EntryKind.INCOME, EntryOrigin.CLOSING_DIFFERENCE, Currency.ARS),
				org.assertj.core.groups.Tuple.tuple(EntryKind.EXPENSE, EntryOrigin.ONE_OFF, Currency.ARS),
				org.assertj.core.groups.Tuple.tuple(EntryKind.EXPENSE, EntryOrigin.ONE_OFF, Currency.USD),
				org.assertj.core.groups.Tuple.tuple(EntryKind.EXPENSE, EntryOrigin.CARRIED_OVER, Currency.ARS));
		assertThat(grid.rows()).allMatch(r -> r.budgetItemId() == null && r.name() == null);
		Row expensesInPesos = grid.rows().get(2);
		assertThat(expensesInPesos.cells().get(0)).extracting(Cell::entryCount, Cell::entryId, Cell::status)
				.containsExactly(2, null, null);
		assertThat(expensesInPesos.cells().get(0).forecast()).isEqualByComparingTo("15000.50");
		assertThat(expensesInPesos.cells().get(1)).isNull();
		assertThat(expensesInPesos.cells().get(2).entryCount()).isEqualTo(1);
		assertThat(expensesInPesos.cells().get(2).entryId()).isNotNull();
		assertThat(grid.rows().get(1).cells().get(1).manual()).isTrue();
	}

	@Test
	void incomesComeBeforeExpensesAndConceptsBeforeUngroupedEntriesWithinASection() {
		recurring(item("zeta", EntryKind.EXPENSE, pesos), "2026-10", "1.00");
		recurring(item("Alquiler", EntryKind.EXPENSE, pesos), "2026-10", "2.00");
		entry(null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, pesos, "2026-10", "3.00");
		recurring(item("Sueldo", EntryKind.INCOME, pesos), "2026-10", "4.00");

		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2026, 10));

		assertThat(grid.rows()).extracting(Row::name).containsExactly("Sueldo", "Alquiler", "zeta", null);
	}

	@Test
	void pesosAndDollarsAreNeverAddedTogether() {
		BudgetItem sueldo = item("Sueldo", EntryKind.INCOME, pesos);
		BudgetItem renta = item("Renta", EntryKind.INCOME, dollars);
		BudgetItem alquiler = item("Alquiler", EntryKind.EXPENSE, pesos);
		BudgetItem hosting = item("Hosting", EntryKind.EXPENSE, dollars);
		recurring(sueldo, "2026-10", "1000.00");
		recurring(renta, "2026-10", "300.00");
		recurring(alquiler, "2026-10", "400.00");
		recurring(hosting, "2026-10", "20.00");
		recurring(hosting, "2026-11", "25.00");

		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2026, 11));

		assertThat(row(grid, "Renta").currency()).isEqualTo(Currency.USD);
		var october = grid.totals().get(0).totals();
		assertThat(october).hasSize(2);
		assertThat(october.get(0).currency()).isEqualTo(Currency.ARS);
		assertThat(october.get(0).income().forecast()).isEqualByComparingTo("1000.00");
		assertThat(october.get(0).expense().forecast()).isEqualByComparingTo("400.00");
		assertThat(october.get(0).result()).isEqualByComparingTo("600.00");
		assertThat(october.get(1).currency()).isEqualTo(Currency.USD);
		assertThat(october.get(1).result()).isEqualByComparingTo("280.00");
		var november = grid.totals().get(1).totals();
		assertThat(november).hasSize(1);
		assertThat(november.get(0).currency()).isEqualTo(Currency.USD);
		assertThat(november.get(0).income().forecast()).isEqualByComparingTo("0.00");
		assertThat(november.get(0).result()).isEqualByComparingTo("-25.00");
	}

	@Test
	void aFinishedOrScheduledConceptWithoutEntriesInTheRangeHasNoRow() {
		BudgetItem finished = item("Finalizado", EntryKind.EXPENSE, pesos);
		recurring(finished, "2026-08", "100.00");
		BudgetItem scheduled = item("Por comenzar", EntryKind.EXPENSE, pesos);
		recurring(scheduled, "2027-12", "100.00");
		recurring(item("Activo", EntryKind.EXPENSE, pesos), "2026-10", "100.00");

		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 9));

		assertThat(grid.rows()).extracting(Row::name).containsExactly("Activo");
	}

	@Test
	void aConceptWithEntriesOnlyInOneMonthShowsTheEmptyCellsAsNull() {
		recurring(item("Aguinaldo", EntryKind.INCOME, pesos), "2026-12", "500.00");

		Row row = row(service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 1)), "Aguinaldo");

		assertThat(row.cells()).containsExactly(null, null, row.cells().get(2), null);
		assertThat(row.cells().get(2).forecast()).isEqualByComparingTo("500.00");
	}

	@Test
	void everyCellAndEveryTotalMatchesTheMonthView() {
		BudgetItem sueldo = item("Sueldo", EntryKind.INCOME, pesos);
		BudgetItem luz = item("Luz", EntryKind.EXPENSE, pesos);
		BudgetItem hosting = item("Hosting", EntryKind.EXPENSE, dollars);
		for (String month : List.of("2026-10", "2026-11", "2026-12")) {
			recurring(sueldo, month, "1000000.00");
			recurring(hosting, month, "20.00");
		}
		BudgetEntry luzOct = recurring(luz, "2026-10", "45000.00");
		paid(luzOct, "20000.00");
		BudgetEntry luzNov = recurring(luz, "2026-11", "45000.00");
		luzNov.setManual(true);
		consolidated(recurring(luz, "2026-12", "45000.00"), "50000.00");
		entry(null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, pesos, "2026-11", "1234.56");
		entry(null, EntryOrigin.ONE_OFF, EntryKind.EXPENSE, pesos, "2026-11", "100.00");
		paid(stored.get(stored.size() - 1), "150.00");

		Grid grid = service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2026, 12));

		for (int i = 0; i < grid.months().size(); i++) {
			YearMonth month = grid.months().get(i).period();
			PeriodViewService.View view = monthView.view(USER_ID, month);
			final int column = i;
			List<PeriodViewService.EntryRow> viewRows = new ArrayList<>(view.incomes());
			viewRows.addAll(view.expenses());
			for (Row row : grid.rows()) {
				Cell cell = row.cells().get(column);
				List<PeriodViewService.EntryRow> inView = viewRows.stream()
						.filter(r -> row.budgetItemId() != null ? row.budgetItemId().equals(r.budgetItemId())
								: r.budgetItemId() == null && r.origin() == row.origin() && r.kind() == row.kind()
										&& r.currency() == row.currency())
						.toList();
				if (cell == null) {
					assertThat(inView).isEmpty();
					continue;
				}
				assertThat(cell.forecast()).isEqualByComparingTo(
						inView.stream().map(r -> r.amounts().forecast()).reduce(BigDecimal.ZERO, BigDecimal::add));
				if (inView.size() == 1) {
					assertThat(cell.status()).isEqualTo(inView.get(0).amounts().status());
					assertThat(cell.manual()).isEqualTo(inView.get(0).manual());
					assertThat(cell.entryId()).isEqualTo(inView.get(0).id());
				}
			}
			var gridTotals = grid.totals().get(column).totals();
			assertThat(gridTotals).hasSameSizeAs(view.totals());
			for (int c = 0; c < gridTotals.size(); c++) {
				assertThat(gridTotals.get(c).currency()).isEqualTo(view.totals().get(c).currency());
				assertThat(gridTotals.get(c).income().forecast())
						.isEqualByComparingTo(view.totals().get(c).income().forecast());
				assertThat(gridTotals.get(c).expense().forecast())
						.isEqualByComparingTo(view.totals().get(c).expense().forecast());
				assertThat(gridTotals.get(c).result()).isEqualByComparingTo(view.totals().get(c).result());
			}
		}
	}

	@Test
	void aRangeOutsideTheUsersPeriodsIsRejectedBeforeReadingAnyEntry() {
		assertThatThrownBy(() -> service.grid(USER_ID, YearMonth.of(2026, 7), YearMonth.of(2026, 9)))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("from");
				});
		assertThatThrownBy(() -> service.grid(USER_ID, YearMonth.of(2027, 5), YearMonth.of(2027, 1)))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.grid(USER_ID, YearMonth.of(2026, 8), YearMonth.of(2028, 10)))
				.isInstanceOf(BusinessException.class);
		verify(entries, never()).findByUserIdAndPeriodRangeWithDetails(anyLong(), any(), any());
		verify(movements, never()).sumByEntryOfPeriodRange(anyLong(), any(), any());
	}

	@Test
	void aUserWithoutPeriodsIsNotFound() {
		assertThatThrownBy(() -> service.grid(OTHER_USER_ID, null, null)).isInstanceOfSatisfying(
				BusinessException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void otherUsersDataNeverAppearsAndEveryQueryCarriesTheCurrentUser() {
		// Las partidas del otro usuario existen en los datos, pero los repositorios se consultan con otro userId.
		BudgetEntry foreign = recurring(item("Ajeno", EntryKind.INCOME, pesos), "2026-10", "999.00");
		foreign.setUserId(OTHER_USER_ID);
		when(entries.findByUserIdAndPeriodRangeWithDetails(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 9)))
				.thenReturn(List.of());

		Grid grid = service.grid(USER_ID, null, null);

		assertThat(grid.rows()).isEmpty();
		verify(entries, times(1)).findByUserIdAndPeriodRangeWithDetails(USER_ID, YearMonth.of(2026, 10),
				YearMonth.of(2027, 9));
		verify(movements, times(1)).sumByEntryOfPeriodRange(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 9));
		verify(periods, times(1)).findPeriodMonthsByUserId(USER_ID);
		verify(periods, times(1)).findByUserIdAndPeriodMonthIn(any(Long.class), any());
		verify(entries, never()).findByUserIdAndPeriodRangeWithDetails(org.mockito.ArgumentMatchers.eq(OTHER_USER_ID),
				any(), any());
	}

	@Test
	void theQueriesDoNotGrowWithTheRowsOrTheMonths() {
		BudgetItem a = item("A", EntryKind.EXPENSE, pesos);
		BudgetItem b = item("B", EntryKind.EXPENSE, pesos);
		for (int m = 10; m <= 12; m++) {
			recurring(a, "2026-" + m, "1.00");
			recurring(b, "2026-" + m, "1.00");
		}

		service.grid(USER_ID, YearMonth.of(2026, 10), YearMonth.of(2027, 9));

		verify(entries, times(1)).findByUserIdAndPeriodRangeWithDetails(anyLong(), any(), any());
		verify(movements, times(1)).sumByEntryOfPeriodRange(anyLong(), any(), any());
		verify(periods, times(1)).findByUserIdAndPeriodMonthIn(anyLong(), any());
		verify(periods, never()).findByUserIdAndPeriodMonth(anyLong(), any());
	}
}
