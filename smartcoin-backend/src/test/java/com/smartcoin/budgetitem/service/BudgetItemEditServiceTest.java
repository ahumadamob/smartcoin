package com.smartcoin.budgetitem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.BudgetItemValues;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.budgetitem.service.BudgetItemService.Detail;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.entry.service.EntryService;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-13, RN-15 y RN-01: sin base de datos, con repositorios simulados con estado y un reloj fijo. El
 * {@link HorizonService} y el {@link EntryGenerator} son los reales, para verificar el orden entre editar y asegurar
 * el horizonte. Los filtros de "consolidada", "período cerrado" y "con movimientos" se prueban acá con repositorios
 * simulados porque hoy no se pueden producir desde la aplicación (se verifican con datos en HU-19, HU-23 y HU-33).
 *
 * <p>El Concepto «Alquiler» tiene una partida por cada situación que distingue RN-15:
 * <pre>
 * 2026-07  período cerrado, consolidada
 * 2026-08  período cerrado, pendiente (no se puede dar hoy; el filtro la tiene que respetar igual)
 * 2026-09  abierto, consolidada
 * 2026-10  abierto, estimada
 * 2026-11  abierto, parcial (con movimientos)
 * 2026-12  abierto, editada
 * 2027-01  abierto, editada y parcial
 * 2027-02  abierto, estimada
 * </pre>
 */
class BudgetItemEditServiceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final long ITEM_ID = 100;
	static final long ARS_ACCOUNT_ID = 42;
	static final long OTHER_ARS_ACCOUNT_ID = 43;
	static final long USD_ACCOUNT_ID = 44;
	static final long CATEGORY_ID = 5;
	static final YearMonth USER_START = YearMonth.of(2026, 7);
	/** Con el reloj en octubre de 2026 y 24 meses de horizonte. */
	static final YearMonth HORIZON = YearMonth.of(2028, 10);

	static final YearMonth CLOSED_CONSOLIDATED = ym("2026-07");
	static final YearMonth CLOSED_PENDING = ym("2026-08");
	static final YearMonth OPEN_CONSOLIDATED = ym("2026-09");
	static final YearMonth ESTIMATED = ym("2026-10");
	static final YearMonth PARTIAL = ym("2026-11");
	static final YearMonth EDITED = ym("2026-12");
	static final YearMonth EDITED_AND_PARTIAL = ym("2027-01");
	static final YearMonth FEBRUARY = ym("2027-02");

	BudgetItemRepository items = mock(BudgetItemRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	AccountRepository accounts = mock(AccountRepository.class);
	CategoryRepository categories = mock(CategoryRepository.class);
	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	UserRepository users = mock(UserRepository.class);

	final List<BudgetEntry> stored = new ArrayList<>();
	final Map<YearMonth, BudgetPeriod> periodsByMonth = new LinkedHashMap<>();
	final Set<Long> entryIdsWithMovements = new HashSet<>();
	long nextEntryId = 1000;

	BudgetItemService service;
	BudgetItem item;
	Account arsAccount;
	Account otherArsAccount;
	Account usdAccount;
	Category category;
	Map<YearMonth, Snap> before;

	static YearMonth ym(String text) {
		return YearMonth.parse(text);
	}

	/** Lo que una edición puede cambiar de una partida. */
	record Snap(LocalDate dueDate, Long accountId, BigDecimal amount, boolean manual, StoredEntryStatus status) {
	}

	@BeforeEach
	void setUp() {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, 10),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		Clock clock = Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"), ZONE);
		EntryGenerator generator = new EntryGenerator(periods, entries);
		HorizonService horizon = new HorizonService(periods, items, generator, properties, clock);
		service = new BudgetItemService(items, entries, movements, accounts, categories, periods, users, horizon,
				generator, properties, clock);

		User user = new User();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		user.setStartPeriod(USER_START);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));

		arsAccount = account(ARS_ACCOUNT_ID, Currency.ARS);
		otherArsAccount = account(OTHER_ARS_ACCOUNT_ID, Currency.ARS);
		usdAccount = account(USD_ACCOUNT_ID, Currency.USD);
		category = new Category();
		ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
		category.setUserId(USER_ID);
		when(categories.findByIdAndUserId(CATEGORY_ID, USER_ID)).thenReturn(Optional.of(category));

		item = new BudgetItem();
		ReflectionTestUtils.setField(item, "id", ITEM_ID);
		item.setUserId(USER_ID);
		item.setName("Alquiler");
		item.setKind(EntryKind.EXPENSE);
		item.setDefaultAccount(arsAccount);
		item.setPeriodicity(Periodicity.MONTHLY);
		item.setDueDay(10);
		item.setDueMonthOffset(0);
		item.setStartPeriod(USER_START);
		item.setEstimationRule(EstimationRule.LAST_VALUE);
		item.setCurrentAmount(new BigDecimal("100000.00"));
		item.setGeneratedUntil(HORIZON);
		when(items.findByIdAndUserId(ITEM_ID, USER_ID)).thenReturn(Optional.of(item));
		// Igual criterio que la consulta findPendingGeneration (HU-12); acá no se prueba el SQL.
		when(items.findPendingGeneration(anyLong(), any(YearMonth.class))).thenAnswer(call -> {
			YearMonth horizonMonth = call.getArgument(1);
			return Stream.of(item).filter(i -> {
				YearMonth until = i.getGeneratedUntil();
				return until == null || (until.isBefore(horizonMonth)
						&& (i.getEndPeriod() == null || until.isBefore(i.getEndPeriod())));
			}).toList();
		});

		for (YearMonth m = USER_START; !m.isAfter(HORIZON); m = m.plusMonths(1)) {
			BudgetPeriod period = BudgetPeriod.open(USER_ID, m);
			ReflectionTestUtils.setField(period, "id", (long) (m.getYear() * 100 + m.getMonthValue()));
			periodsByMonth.put(m, period);
		}
		periodsByMonth.get(CLOSED_CONSOLIDATED).setStatus(PeriodStatus.CLOSED);
		periodsByMonth.get(CLOSED_PENDING).setStatus(PeriodStatus.CLOSED);
		when(periods.findPeriodMonthsByUserId(anyLong())).thenAnswer(call -> List.copyOf(periodsByMonth.keySet()));
		when(periods.findByUserIdAndPeriodMonthIn(eq(USER_ID), anyCollection()))
				.thenAnswer(call -> call.<Collection<YearMonth>>getArgument(1).stream().map(periodsByMonth::get).toList());

		when(entries.findByUserIdAndBudgetItemId(anyLong(), anyLong())).thenAnswer(call -> stored.stream()
				.filter(e -> e.getUserId().equals(call.getArgument(0))
						&& e.getBudgetItem().getId().equals(call.getArgument(1)))
				.sorted(java.util.Comparator.comparing(e -> e.getPeriod().getPeriodMonth())).toList());
		when(entries.saveAll(anyList())).thenAnswer(call -> {
			List<BudgetEntry> saved = call.getArgument(0);
			saved.forEach(e -> ReflectionTestUtils.setField(e, "id", nextEntryId++));
			stored.addAll(saved);
			return saved;
		});
		when(movements.findEntryIdsWithMovements(anyLong(), anyCollection())).thenAnswer(call -> call
				.<Collection<Long>>getArgument(1).stream().filter(entryIdsWithMovements::contains).toList());

		entry(CLOSED_CONSOLIDATED, StoredEntryStatus.CONSOLIDATED, false, true);
		entry(CLOSED_PENDING, StoredEntryStatus.PENDING, false, false);
		entry(OPEN_CONSOLIDATED, StoredEntryStatus.CONSOLIDATED, false, true);
		entry(ESTIMATED, StoredEntryStatus.PENDING, false, false);
		entry(PARTIAL, StoredEntryStatus.PENDING, false, true);
		entry(EDITED, StoredEntryStatus.PENDING, true, false);
		entry(EDITED_AND_PARTIAL, StoredEntryStatus.PENDING, true, true);
		entry(FEBRUARY, StoredEntryStatus.PENDING, false, false);
		before = snapshots();
	}

	private Account account(long id, Currency currency) {
		Account account = new Account();
		ReflectionTestUtils.setField(account, "id", id);
		account.setUserId(USER_ID);
		account.setCurrency(currency);
		when(accounts.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(account));
		return account;
	}

	/** Una partida del Concepto con los datos que el generador le habría puesto. */
	private BudgetEntry entry(YearMonth month, StoredEntryStatus status, boolean manual, boolean hasMovements) {
		BudgetEntry entry = new BudgetEntry();
		ReflectionTestUtils.setField(entry, "id", nextEntryId++);
		entry.setUserId(USER_ID);
		entry.setPeriod(periodsByMonth.get(month));
		entry.setBudgetItem(item);
		entry.setOrigin(EntryOrigin.RECURRING);
		entry.setKind(EntryKind.EXPENSE);
		entry.setAccount(arsAccount);
		entry.setDueDate(month.atDay(10));
		// Las editadas ya tenían otro monto, corregido a mano.
		entry.setBudgetedAmount(manual ? new BigDecimal("123456.00") : new BigDecimal("100000.00"));
		entry.setManual(manual);
		entry.setStatus(status);
		if (status == StoredEntryStatus.CONSOLIDATED) {
			entry.setConsolidatedAmount(new BigDecimal("100000.00"));
		}
		if (hasMovements) {
			entryIdsWithMovements.add(entry.getId());
		}
		stored.add(entry);
		return entry;
	}

	private Map<YearMonth, Snap> snapshots() {
		Map<YearMonth, Snap> result = new LinkedHashMap<>();
		for (BudgetEntry e : stored) {
			result.put(e.getPeriod().getPeriodMonth(), new Snap(e.getDueDate(), e.getAccount().getId(),
					e.getBudgetedAmount(), e.isManual(), e.getStatus()));
		}
		return result;
	}

	/** Los períodos cuya partida cambió respecto del estado inicial, más las que se agregaron. */
	private Set<YearMonth> changedEntries() {
		Map<YearMonth, Snap> now = snapshots();
		return now.entrySet().stream().filter(e -> !e.getValue().equals(before.get(e.getKey())))
				.map(Map.Entry::getKey).collect(Collectors.toSet());
	}

	private BudgetItemValues requested(UnaryOperator<BudgetItemEditabilityTestBuilder> change) {
		return change.apply(new BudgetItemEditabilityTestBuilder(BudgetItemValues.of(item))).build();
	}

	/** Los datos actuales del Concepto con los cambios pedidos: lo que mandaría la pantalla. */
	static final class BudgetItemEditabilityTestBuilder {
		String name;
		EntryKind kind;
		Long accountId;
		Long categoryId;
		Periodicity periodicity;
		int dueDay;
		int offset;
		YearMonth start;
		YearMonth end;
		EstimationRule rule;
		BigDecimal amount;
		Integer total;
		Integer first;

		BudgetItemEditabilityTestBuilder(BudgetItemValues v) {
			name = v.name();
			kind = v.kind();
			accountId = v.defaultAccountId();
			categoryId = v.categoryId();
			periodicity = v.periodicity();
			dueDay = v.dueDay();
			offset = v.dueMonthOffset();
			start = v.startPeriod();
			end = v.endPeriod();
			rule = v.estimationRule();
			amount = v.currentAmount();
			total = v.installmentsTotal();
			first = v.firstInstallmentNumber();
		}

		BudgetItemValues build() {
			return new BudgetItemValues(name, kind, accountId, categoryId, periodicity, dueDay, offset, start, end,
					rule, amount, total, first);
		}
	}

	private Detail update(UnaryOperator<BudgetItemEditabilityTestBuilder> change) {
		return service.update(USER_ID, ITEM_ID, requested(change));
	}

	// --- Nombre, categoría y regla de estimación: no tocan partidas ---

	@Test
	void renamingAndChangingTheCategoryAndTheRuleLeaveEveryEntryUntouched() {
		Detail result = update(b -> {
			b.name = "  Alquiler del depto  ";
			b.categoryId = CATEGORY_ID;
			b.rule = EstimationRule.AVERAGE_LAST_3;
			return b;
		});

		assertThat(item.getName()).isEqualTo("Alquiler del depto");
		assertThat(item.getCategory()).isSameAs(category);
		assertThat(item.getEstimationRule()).isEqualTo(EstimationRule.AVERAGE_LAST_3);
		assertThat(changedEntries()).isEmpty();
		assertThat(result.item()).isSameAs(item);
		// Ni siquiera hace falta traer las partidas para recalcularlas: solo se leen para contarlas.
		verify(movements, never()).findEntryIdsWithMovements(anyLong(), anyCollection());
	}

	@Test
	void theCategoryCanBeRemoved() {
		item.setCategory(category);

		update(b -> {
			b.categoryId = null;
			return b;
		});

		assertThat(item.getCategory()).isNull();
		assertThat(changedEntries()).isEmpty();
	}

	// --- Día de vencimiento y desfase ---

	@Test
	void changingTheDueDayRecalculatesEveryPendingEntryOfAnOpenPeriodWhateverItsState() {
		update(b -> {
			b.dueDay = 31;
			return b;
		});

		// Estimada, parcial, editada, editada y parcial, y la de febrero. Nada más.
		assertThat(changedEntries()).containsExactlyInAnyOrder(ESTIMATED, PARTIAL, EDITED, EDITED_AND_PARTIAL, FEBRUARY);
		Map<YearMonth, Snap> now = snapshots();
		assertThat(now.get(ESTIMATED).dueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
		assertThat(now.get(PARTIAL).dueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
		assertThat(now.get(EDITED).dueDate()).isEqualTo(LocalDate.of(2026, 12, 31));
		assertThat(now.get(EDITED_AND_PARTIAL).dueDate()).isEqualTo(LocalDate.of(2027, 1, 31));
		assertThat(now.get(FEBRUARY).dueDate()).isEqualTo(LocalDate.of(2027, 2, 28));
		// Lo demás de esas partidas queda como estaba: siguen editadas y con su monto.
		assertThat(now.get(EDITED).manual()).isTrue();
		assertThat(now.get(EDITED).amount()).isEqualByComparingTo("123456.00");
		assertThat(now.get(EDITED).accountId()).isEqualTo(ARS_ACCOUNT_ID);
	}

	@Test
	void theDueDayDoesNotReachConsolidatedEntriesNorThoseOfAClosedPeriod() {
		update(b -> {
			b.dueDay = 31;
			return b;
		});

		Map<YearMonth, Snap> now = snapshots();
		for (YearMonth untouched : List.of(CLOSED_CONSOLIDATED, CLOSED_PENDING, OPEN_CONSOLIDATED)) {
			assertThat(now.get(untouched)).as(untouched.toString()).isEqualTo(before.get(untouched));
		}
	}

	@Test
	void changingTheOffsetInJanuaryMovesTheDueDateToDecemberOfThePreviousYear() {
		update(b -> {
			b.dueDay = 25;
			b.offset = -1;
			return b;
		});

		Map<YearMonth, Snap> now = snapshots();
		assertThat(now.get(EDITED_AND_PARTIAL).dueDate()).isEqualTo(LocalDate.of(2026, 12, 25));
		assertThat(now.get(ESTIMATED).dueDate()).isEqualTo(LocalDate.of(2026, 9, 25));
		assertThat(now.get(EDITED).dueDate()).isEqualTo(LocalDate.of(2026, 11, 25));
		assertThat(now.get(FEBRUARY).dueDate()).isEqualTo(LocalDate.of(2027, 1, 25));
		assertThat(changedEntries()).containsExactlyInAnyOrder(ESTIMATED, PARTIAL, EDITED, EDITED_AND_PARTIAL, FEBRUARY);
	}

	@Test
	void anOffsetOfMinusOneWithDay30StopsAtTheLastDayOfFebruary() {
		item.setDueDay(30);
		update(b -> {
			b.offset = -1;
			return b;
		});

		// El período 2027-03 no existe en el escenario; el de febrero vence el 30 de enero y el de enero, el 30 de dic.
		assertThat(snapshots().get(FEBRUARY).dueDate()).isEqualTo(LocalDate.of(2027, 1, 30));
		assertThat(snapshots().get(EDITED_AND_PARTIAL).dueDate()).isEqualTo(LocalDate.of(2026, 12, 30));
	}

	// --- Cuenta por defecto ---

	@Test
	void changingTheAccountMovesOnlyThePendingEntriesWithoutMovementsOfAnOpenPeriod() {
		update(b -> {
			b.accountId = OTHER_ARS_ACCOUNT_ID;
			return b;
		});

		// Estimada, editada y la de febrero. La parcial y la editada y parcial tienen movimientos: no se mueven.
		assertThat(changedEntries()).containsExactlyInAnyOrder(ESTIMATED, EDITED, FEBRUARY);
		Map<YearMonth, Snap> now = snapshots();
		assertThat(now.get(ESTIMATED).accountId()).isEqualTo(OTHER_ARS_ACCOUNT_ID);
		assertThat(now.get(EDITED).accountId()).isEqualTo(OTHER_ARS_ACCOUNT_ID);
		assertThat(now.get(FEBRUARY).accountId()).isEqualTo(OTHER_ARS_ACCOUNT_ID);
		assertThat(now.get(PARTIAL).accountId()).isEqualTo(ARS_ACCOUNT_ID);
		assertThat(now.get(EDITED_AND_PARTIAL).accountId()).isEqualTo(ARS_ACCOUNT_ID);
		assertThat(now.get(EDITED).manual()).isTrue();
		assertThat(item.getDefaultAccount()).isSameAs(otherArsAccount);
	}

	@Test
	void theMovementsAreOnlyLookedUpForTheEntriesThatCouldChange() {
		update(b -> {
			b.accountId = OTHER_ARS_ACCOUNT_ID;
			return b;
		});

		// Las consolidadas y las de períodos cerrados ni se consultan.
		verify(movements).findEntryIdsWithMovements(eq(USER_ID), org.mockito.ArgumentMatchers.argThat(ids -> {
			Set<Long> expected = stored.stream()
					.filter(e -> Set.of(ESTIMATED, PARTIAL, EDITED, EDITED_AND_PARTIAL, FEBRUARY)
							.contains(e.getPeriod().getPeriodMonth()))
					.map(BudgetEntry::getId).collect(Collectors.toSet());
			return Set.copyOf(ids).equals(expected);
		}));
	}

	@Test
	void anAccountOfAnotherCurrencyIsRejectedAndNothingChanges() {
		BudgetItemValues request = requested(b -> {
			b.name = "Otro nombre";
			b.accountId = USD_ACCOUNT_ID;
			b.dueDay = 31;
			b.amount = new BigDecimal("1.00");
			return b;
		});

		assertThatThrownBy(() -> service.update(USER_ID, ITEM_ID, request))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH);
					assertThat(e.getMessage()).contains("ARS");
				});

		assertThat(changedEntries()).isEmpty();
		assertThat(item.getName()).isEqualTo("Alquiler");
		assertThat(item.getDefaultAccount()).isSameAs(arsAccount);
		assertThat(item.getDueDay()).isEqualTo(10);
		verify(entries, never()).saveAll(anyList());
	}

	@Test
	void sendingTheSameAccountIsNotAChangeEvenIfItsCurrencyIsChecked() {
		update(b -> {
			b.accountId = ARS_ACCOUNT_ID;
			return b;
		});

		assertThat(changedEntries()).isEmpty();
		verify(movements, never()).findEntryIdsWithMovements(anyLong(), anyCollection());
	}

	// --- Monto vigente ---

	@Test
	void changingTheCurrentAmountReplacesTheBudgetOfEveryPendingEntryThatIsNotEdited() {
		update(b -> {
			b.amount = new BigDecimal("120000.50");
			return b;
		});

		// Estimada, parcial y la de febrero. Las editadas no cambian y siguen editadas.
		assertThat(changedEntries()).containsExactlyInAnyOrder(ESTIMATED, PARTIAL, FEBRUARY);
		Map<YearMonth, Snap> now = snapshots();
		for (YearMonth replaced : List.of(ESTIMATED, PARTIAL, FEBRUARY)) {
			assertThat(now.get(replaced).amount()).as(replaced.toString()).isEqualByComparingTo("120000.50");
			assertThat(now.get(replaced).manual()).isFalse();
		}
		for (YearMonth edited : List.of(EDITED, EDITED_AND_PARTIAL)) {
			assertThat(now.get(edited).amount()).as(edited.toString()).isEqualByComparingTo("123456.00");
			assertThat(now.get(edited).manual()).isTrue();
		}
		assertThat(item.getCurrentAmount()).isEqualByComparingTo("120000.50");
	}

	// --- HU-17: editar el monto de un mes y después el monto vigente ---

	@Test
	void anEntryWhoseAmountWasEditedKeepsItWhenTheCurrentAmountChangesAndTheOthersTakeTheNewOne() {
		EntryService entryService = new EntryService(periods, entries, movements, accounts, categories, items,
				Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"), ZONE));
		BudgetEntry february = stored.stream().filter(e -> e.getPeriod().getPeriodMonth().equals(FEBRUARY))
				.findFirst().orElseThrow();
		when(entries.findByIdAndUserIdWithDetails(february.getId(), USER_ID)).thenReturn(Optional.of(february));

		entryService.update(USER_ID, february.getId(),
				new EntryService.Changes(null, null, null, null, false, null, new BigDecimal("240000.00")));

		assertThat(changedEntries()).containsExactly(FEBRUARY);
		assertThat(item.getCurrentAmount()).isEqualByComparingTo("100000.00");
		before = snapshots();

		Detail result = update(b -> {
			b.amount = new BigDecimal("150000.00");
			return b;
		});

		Map<YearMonth, Snap> now = snapshots();
		assertThat(now.get(FEBRUARY).amount()).isEqualByComparingTo("240000.00");
		assertThat(now.get(FEBRUARY).manual()).isTrue();
		assertThat(changedEntries()).containsExactlyInAnyOrder(ESTIMATED, PARTIAL);
		assertThat(now.get(ESTIMATED).amount()).isEqualByComparingTo("150000.00");
		// El aviso cuenta la editada de HU-17 junto con las de antes: EDITED, EDITED_AND_PARTIAL y FEBRUARY.
		assertThat(result.counts().pendingManual()).isEqualTo(3);
		assertThat(result.counts().pendingNotManual()).isEqualTo(2);
	}

	@Test
	void theCurrentAmountDoesNotReachConsolidatedEntriesNorThoseOfAClosedPeriod() {
		update(b -> {
			b.amount = new BigDecimal("1.00");
			return b;
		});

		Map<YearMonth, Snap> now = snapshots();
		for (YearMonth untouched : List.of(CLOSED_CONSOLIDATED, CLOSED_PENDING, OPEN_CONSOLIDATED)) {
			assertThat(now.get(untouched)).as(untouched.toString()).isEqualTo(before.get(untouched));
		}
	}

	@Test
	void sendingTheSameAmountWithAnotherScaleIsNotAChange() {
		update(b -> {
			b.amount = new BigDecimal("100000.0");
			return b;
		});

		assertThat(changedEntries()).isEmpty();
		assertThat(item.getCurrentAmount()).isEqualByComparingTo("100000.00");
		assertThat(item.getCurrentAmount().scale()).isEqualTo(2);
	}

	// --- Varios cambios a la vez ---

	@Test
	void everyChangeAffectsExactlyTheEntriesRn15SaysAndNoOtherOne() {
		update(b -> {
			b.name = "Alquiler 2027";
			b.dueDay = 31;
			b.accountId = OTHER_ARS_ACCOUNT_ID;
			b.amount = new BigDecimal("150000.00");
			return b;
		});

		Map<YearMonth, Snap> now = snapshots();
		// Estimada: los tres cambios.
		assertThat(now.get(ESTIMATED)).isEqualTo(new Snap(LocalDate.of(2026, 10, 31), OTHER_ARS_ACCOUNT_ID,
				new BigDecimal("150000.00"), false, StoredEntryStatus.PENDING));
		// Parcial: vencimiento y monto, no la cuenta.
		assertThat(now.get(PARTIAL)).isEqualTo(new Snap(LocalDate.of(2026, 11, 30), ARS_ACCOUNT_ID,
				new BigDecimal("150000.00"), false, StoredEntryStatus.PENDING));
		// Editada: vencimiento y cuenta, no el monto.
		assertThat(now.get(EDITED)).isEqualTo(new Snap(LocalDate.of(2026, 12, 31), OTHER_ARS_ACCOUNT_ID,
				new BigDecimal("123456.00"), true, StoredEntryStatus.PENDING));
		// Editada y parcial: solo el vencimiento.
		assertThat(now.get(EDITED_AND_PARTIAL)).isEqualTo(new Snap(LocalDate.of(2027, 1, 31), ARS_ACCOUNT_ID,
				new BigDecimal("123456.00"), true, StoredEntryStatus.PENDING));
		// Consolidadas y de período cerrado: nada.
		for (YearMonth untouched : List.of(CLOSED_CONSOLIDATED, CLOSED_PENDING, OPEN_CONSOLIDATED)) {
			assertThat(now.get(untouched)).as(untouched.toString()).isEqualTo(before.get(untouched));
		}
		assertThat(stored).hasSize(8);
	}

	// --- Datos no editables ---

	static Stream<Arguments> lockedFields() {
		return Stream.of(
				Arguments.of("tipo", (UnaryOperator<BudgetItemEditabilityTestBuilder>) b -> {
					b.kind = EntryKind.INCOME;
					return b;
				}),
				Arguments.of("periodicidad", (UnaryOperator<BudgetItemEditabilityTestBuilder>) b -> {
					b.periodicity = Periodicity.QUARTERLY;
					return b;
				}),
				Arguments.of("período de inicio", (UnaryOperator<BudgetItemEditabilityTestBuilder>) b -> {
					b.start = ym("2026-09");
					return b;
				}),
				Arguments.of("período de fin", (UnaryOperator<BudgetItemEditabilityTestBuilder>) b -> {
					b.end = ym("2027-12");
					return b;
				}),
				Arguments.of("cuotas", (UnaryOperator<BudgetItemEditabilityTestBuilder>) b -> {
					b.total = 12;
					b.first = 1;
					return b;
				}));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("lockedFields")
	void changingALockedFieldIsRejectedEvenWithEditableChangesAndNothingIsSaved(String field,
			UnaryOperator<BudgetItemEditabilityTestBuilder> change) {
		BudgetItemValues request = requested(b -> {
			b.name = "Otro nombre";
			b.dueDay = 31;
			b.amount = new BigDecimal("1.00");
			return change.apply(b);
		});

		assertThatThrownBy(() -> service.update(USER_ID, ITEM_ID, request))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_NOT_EDITABLE));

		assertThat(changedEntries()).isEmpty();
		assertThat(item.getName()).isEqualTo("Alquiler");
		assertThat(item.getDueDay()).isEqualTo(10);
		assertThat(item.getCurrentAmount()).isEqualByComparingTo("100000.00");
		verify(entries, never()).saveAll(anyList());
	}

	// --- Edición sin cambios ---

	@Test
	void editingWithoutChangesTouchesNoEntryButStillEnsuresTheHorizon() {
		service.update(USER_ID, ITEM_ID, BudgetItemValues.of(item));

		assertThat(changedEntries()).isEmpty();
		verify(movements, never()).findEntryIdsWithMovements(anyLong(), anyCollection());
		// RN-07: aun sin cambios se asegura el horizonte (con el Concepto al día, no trae nada).
		verify(items).findPendingGeneration(USER_ID, HORIZON);
		verify(entries, never()).saveAll(anyList());
	}

	// --- Referencias inválidas en el cuerpo (D-24) ---

	@Test
	void anAccountThatDoesNotExistForTheUserIsAValidationErrorOnItsField() {
		BudgetItemValues request = requested(b -> {
			b.accountId = 999L;
			b.dueDay = 31;
			return b;
		});

		assertThatThrownBy(() -> service.update(USER_ID, ITEM_ID, request))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("defaultAccountId");
				});

		assertThat(changedEntries()).isEmpty();
		assertThat(item.getDueDay()).isEqualTo(10);
		verify(accounts).findByIdAndUserId(999L, USER_ID);
	}

	@Test
	void aCategoryThatDoesNotExistForTheUserIsAValidationErrorOnItsField() {
		BudgetItemValues request = requested(b -> {
			b.categoryId = 999L;
			b.dueDay = 31;
			return b;
		});

		assertThatThrownBy(() -> service.update(USER_ID, ITEM_ID, request))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
					assertThat(e.field()).isEqualTo("categoryId");
				});

		assertThat(changedEntries()).isEmpty();
		assertThat(item.getCategory()).isNull();
	}

	@Test
	void aBodyReferenceIsCheckedBeforeANonEditableChange() {
		BudgetItemValues request = requested(b -> {
			b.accountId = 999L;
			b.kind = EntryKind.INCOME;
			return b;
		});

		assertThatThrownBy(() -> service.update(USER_ID, ITEM_ID, request))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	// --- Aislamiento (RN-01, HU-06) ---

	@Test
	void anItemOfAnotherUserIsNotFoundOnGetAndOnUpdate() {
		when(items.findByIdAndUserId(ITEM_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(OTHER_USER_ID, ITEM_ID)).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> service.update(OTHER_USER_ID, ITEM_ID, BudgetItemValues.of(item)))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

		verify(items, org.mockito.Mockito.times(2)).findByIdAndUserId(ITEM_ID, OTHER_USER_ID);
		verifyNoInteractions(entries, movements, accounts, categories, periods);
		assertThat(changedEntries()).isEmpty();
	}

	@Test
	void everyQueryOfGetCarriesTheCurrentUser() {
		service.get(USER_ID, ITEM_ID);

		verify(items).findByIdAndUserId(ITEM_ID, USER_ID);
		verify(entries).findByUserIdAndBudgetItemId(USER_ID, ITEM_ID);
	}

	// --- Conteos para el aviso (criterio 3) ---

	@Test
	void getCountsThePendingEntriesOfOpenPeriodsSplitByEdited() {
		Detail detail = service.get(USER_ID, ITEM_ID);

		// No editadas: estimada, parcial y febrero. Editadas: la editada y la editada y parcial.
		assertThat(detail.counts().pendingNotManual()).isEqualTo(3);
		assertThat(detail.counts().pendingManual()).isEqualTo(2);
		assertThat(detail.item()).isSameAs(item);
	}

	@Test
	void theDetailCarriesTheCurrencyReadInsideTheTransaction() {
		// La cuenta por defecto es diferida: el controlador no puede leerla después de la transacción.
		assertThat(service.get(USER_ID, ITEM_ID).currency()).isEqualTo(Currency.ARS);
		assertThat(update(b -> b).currency()).isEqualTo(Currency.ARS);
	}

	@Test
	void theCountsLeaveOutConsolidatedEntriesAndThoseOfAClosedPeriod() {
		Detail detail = service.get(USER_ID, ITEM_ID);

		// Hay 8 partidas; 3 quedan afuera (cerrado consolidada, cerrado pendiente, abierta consolidada).
		assertThat(stored).hasSize(8);
		assertThat(detail.counts().pendingNotManual() + detail.counts().pendingManual()).isEqualTo(5);
	}

	// --- Orden con el horizonte (RN-07) ---

	@Test
	void entriesGeneratedByTheHorizonComeOutWithTheNewDataAndAreNotTouchedTwice() {
		// El horizonte avanzó un mes desde la última generación: falta la partida de 2028-10.
		item.setGeneratedUntil(HORIZON.minusMonths(1));
		// Lo que tienen las partidas nuevas en el instante en que se guardan: no alcanza con mirar cómo terminan.
		List<Snap> atSaveTime = new ArrayList<>();
		org.mockito.Mockito.doAnswer(call -> {
			List<BudgetEntry> saved = call.getArgument(0);
			saved.forEach(e -> atSaveTime.add(new Snap(e.getDueDate(), e.getAccount().getId(), e.getBudgetedAmount(),
					e.isManual(), e.getStatus())));
			saved.forEach(e -> ReflectionTestUtils.setField(e, "id", nextEntryId++));
			stored.addAll(saved);
			return saved;
		}).when(entries).saveAll(anyList());

		Detail detail = update(b -> {
			b.dueDay = 31;
			b.accountId = OTHER_ARS_ACCOUNT_ID;
			b.amount = new BigDecimal("150000.00");
			return b;
		});

		BudgetEntry generated = stored.stream().filter(e -> e.getPeriod().getPeriodMonth().equals(HORIZON))
				.findFirst().orElseThrow();
		assertThat(generated.getDueDate()).isEqualTo(LocalDate.of(2028, 10, 31));
		assertThat(generated.getAccount()).isSameAs(otherArsAccount);
		assertThat(generated.getBudgetedAmount()).isEqualByComparingTo("150000.00");
		assertThat(generated.isManual()).isFalse();
		assertThat(generated.getStatus()).isEqualTo(StoredEntryStatus.PENDING);
		assertThat(item.getGeneratedUntil()).isEqualTo(HORIZON);
		// Las existentes también cambiaron, y la nueva se cuenta como pendiente no editada: 3 + 1.
		assertThat(snapshots().get(ESTIMATED).amount()).isEqualByComparingTo("150000.00");
		assertThat(detail.counts().pendingNotManual()).isEqualTo(4);
		assertThat(detail.counts().pendingManual()).isEqualTo(2);
		// Se generó una sola partida, una sola vez, y ya salió con los datos nuevos.
		assertThat(atSaveTime).containsExactly(new Snap(LocalDate.of(2028, 10, 31), OTHER_ARS_ACCOUNT_ID,
				new BigDecimal("150000.00"), false, StoredEntryStatus.PENDING));
		// Las existentes se recalcularon antes de generar: la partida nueva no pasa por el recálculo.
		org.mockito.InOrder order = org.mockito.Mockito.inOrder(entries);
		order.verify(entries).findByUserIdAndBudgetItemId(USER_ID, ITEM_ID);
		order.verify(entries).saveAll(anyList());
		order.verify(entries).findByUserIdAndBudgetItemId(USER_ID, ITEM_ID);
	}

	@Test
	void aRejectedEditDoesNotGenerateAnything() {
		item.setGeneratedUntil(HORIZON.minusMonths(1));
		BudgetItemValues request = requested(b -> {
			b.accountId = USD_ACCOUNT_ID;
			return b;
		});

		assertThatThrownBy(() -> service.update(USER_ID, ITEM_ID, request)).isInstanceOf(BusinessException.class);

		verify(entries, never()).saveAll(anyList());
		assertThat(item.getGeneratedUntil()).isEqualTo(HORIZON.minusMonths(1));
	}
}
