package com.smartcoin.budgetitem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.InstallmentPlan;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.DeletionScope;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.entry.service.EntryService;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.user.domain.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-18 con HU-12 (RN-13, D-09): después de eliminar, asegurar el horizonte no vuelve a crear lo eliminado ni genera
 * más para un Concepto que terminó. Usa un {@link EntryService}, un {@link HorizonService} y un {@link EntryGenerator}
 * reales sobre repositorios simulados con estado. No prueba el SQL de las consultas.
 */
class EntryDeletionHorizonTest {

	static final long USER_ID = 7;
	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final YearMonth START = YearMonth.of(2026, 10);

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetItemRepository items = mock(BudgetItemRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);

	final List<BudgetItem> storedItems = new ArrayList<>();
	final List<BudgetEntry> storedEntries = new ArrayList<>();
	final List<YearMonth> existingPeriods = new ArrayList<>();
	long nextId = 1000;

	@BeforeEach
	void setUp() {
		when(items.findPendingGeneration(anyLong(), org.mockito.ArgumentMatchers.any(YearMonth.class)))
				.thenAnswer(call -> {
					YearMonth horizon = call.getArgument(1);
					return storedItems.stream().filter(i -> {
						YearMonth until = i.getGeneratedUntil();
						return until == null || (until.isBefore(horizon)
								&& (i.getEndPeriod() == null || until.isBefore(i.getEndPeriod())));
					}).toList();
				});
		when(periods.findPeriodMonthsByUserId(anyLong())).thenAnswer(call -> List.copyOf(existingPeriods));
		when(periods.saveAll(anyList())).thenAnswer(call -> {
			List<BudgetPeriod> saved = call.getArgument(0);
			saved.forEach(p -> existingPeriods.add(p.getPeriodMonth()));
			return saved;
		});
		when(periods.findByUserIdAndPeriodMonthIn(anyLong(), anyCollection()))
				.thenAnswer(call -> call.<Collection<YearMonth>>getArgument(1).stream()
						.map(month -> BudgetPeriod.open(USER_ID, month)).toList());
		when(entries.saveAll(anyList())).thenAnswer(call -> {
			List<BudgetEntry> saved = call.getArgument(0);
			saved.forEach(e -> ReflectionTestUtils.setField(e, "id", nextId++));
			storedEntries.addAll(saved);
			return saved;
		});
		when(entries.findByIdAndUserIdWithDetails(anyLong(), anyLong())).thenAnswer(call -> storedEntries.stream()
				.filter(e -> e.getId().equals(call.<Long>getArgument(0))).findFirst());
		when(entries.findByUserIdAndBudgetItemId(anyLong(), anyLong()))
				.thenAnswer(call -> storedEntries.stream()
						.filter(e -> e.getBudgetItem() != null
								&& e.getBudgetItem().getId().equals(call.<Long>getArgument(1)))
						.toList());
		when(entries.deleteByUserIdAndIdIn(anyLong(), anyCollection())).thenAnswer(call -> {
			Collection<Long> ids = call.getArgument(1);
			int before = storedEntries.size();
			storedEntries.removeIf(e -> ids.contains(e.getId()));
			return before - storedEntries.size();
		});
		when(items.deleteByUserIdAndId(anyLong(), anyLong())).thenAnswer(call -> {
			Long id = call.getArgument(1);
			return storedItems.removeIf(i -> i.getId().equals(id)) ? 1 : 0;
		});
		when(movements.findEntryIdsWithMovements(anyLong(), anyCollection())).thenReturn(List.of());
	}

	private static Clock clockAt(String instant) {
		return Clock.fixed(Instant.parse(instant), ZONE);
	}

	private HorizonService horizonAt(String instant) {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, 10),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		return new HorizonService(periods, items, new EntryGenerator(periods, entries), properties, clockAt(instant));
	}

	private EntryService entryService() {
		return new EntryService(periods, entries, movements, mock(AccountRepository.class),
				mock(CategoryRepository.class), items, clockAt("2026-10-08T15:00:00Z"));
	}

	private static User user() {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", USER_ID);
		user.setStartPeriod(START);
		return user;
	}

	/** Un Concepto mensual que empieza en 2026-10, generado con el horizonte de octubre de 2026 (2028-10). */
	private BudgetItem createdItem(String end, InstallmentPlan plan) {
		BudgetItem item = new BudgetItem();
		ReflectionTestUtils.setField(item, "id", 31L);
		item.setUserId(USER_ID);
		item.setName("Concepto");
		item.setKind(EntryKind.EXPENSE);
		item.setDefaultAccount(new Account());
		item.setPeriodicity(Periodicity.MONTHLY);
		item.setDueDay(10);
		item.setDueMonthOffset(0);
		item.setStartPeriod(START);
		item.setEndPeriod(plan != null ? plan.endPeriod(START, Periodicity.MONTHLY) : end == null ? null : YearMonth.parse(end));
		if (plan != null) {
			item.setInstallmentsTotal(plan.total());
			item.setFirstInstallmentNumber(plan.first());
		}
		item.setEstimationRule(EstimationRule.LAST_VALUE);
		item.setCurrentAmount(new BigDecimal("100.00"));
		storedItems.add(item);
		horizonAt("2026-10-08T15:00:00Z").ensureHorizon(user());
		return item;
	}

	private BudgetEntry entryOf(String month) {
		return storedEntries.stream().filter(e -> e.getPeriod().getPeriodMonth().equals(YearMonth.parse(month)))
				.findFirst().orElseThrow();
	}

	private List<YearMonth> months() {
		return storedEntries.stream().map(e -> e.getPeriod().getPeriodMonth()).sorted().toList();
	}

	@Test
	void anEntryDeletedWithOnlyThisMonthDoesNotComeBackWhenTheHorizonIsEnsuredAgain() {
		BudgetItem item = createdItem(null, null);
		assertThat(storedEntries).hasSize(25);

		entryService().delete(USER_ID, entryOf("2026-12").getId(), DeletionScope.ONLY_THIS);

		assertThat(months()).hasSize(24).doesNotContain(YearMonth.of(2026, 12));
		// Mismo mes: no hay nada que generar.
		horizonAt("2026-10-08T15:00:00Z").ensureHorizon(user());
		assertThat(months()).doesNotContain(YearMonth.of(2026, 12));
		// Iniciar sesión de nuevo con el mes siguiente: el horizonte avanza y solo agrega el período nuevo.
		horizonAt("2026-11-03T12:00:00Z").ensureHorizon(user());
		assertThat(months()).hasSize(25).doesNotContain(YearMonth.of(2026, 12)).contains(YearMonth.of(2028, 11));
		assertThat(item.getEndPeriod()).isNull();
		assertThat(item.getGeneratedUntil()).isEqualTo(YearMonth.of(2028, 11));
	}

	@Test
	void afterThisMonthAndTheFollowingOnesTheItemGeneratesNothingMoreAsTheHorizonAdvances() {
		BudgetItem item = createdItem(null, null);

		entryService().delete(USER_ID, entryOf("2027-03").getId(), DeletionScope.THIS_AND_FUTURE);

		assertThat(item.getEndPeriod()).isEqualTo(YearMonth.of(2027, 2));
		assertThat(months()).hasSize(5).first().isEqualTo(START);
		assertThat(months()).last().isEqualTo(YearMonth.of(2027, 2));
		// El horizonte sigue avanzando mes a mes, también mucho después del fin: el Concepto no se vuelve a traer.
		for (String now : List.of("2026-10-08T15:00:00Z", "2026-11-03T12:00:00Z", "2027-03-03T12:00:00Z",
				"2028-10-03T12:00:00Z")) {
			horizonAt(now).ensureHorizon(user());
		}
		assertThat(months()).hasSize(5);
		assertThat(item.getEndPeriod()).isEqualTo(YearMonth.of(2027, 2));
		assertThat(items.findPendingGeneration(USER_ID, YearMonth.of(2030, 1))).isEmpty();
	}

	@Test
	void afterDeletingFromTheFirstMonthTheItemIsGoneAndTheHorizonHasNothingToGenerate() {
		createdItem(null, null);

		entryService().delete(USER_ID, entryOf("2026-10").getId(), DeletionScope.THIS_AND_FUTURE);

		assertThat(storedEntries).isEmpty();
		assertThat(storedItems).isEmpty();
		horizonAt("2026-11-03T12:00:00Z").ensureHorizon(user());
		assertThat(storedEntries).isEmpty();
	}

	@Test
	void anInstallmentPlanCutShortIsNotCompletedAgain() {
		// 12 cuotas desde la 4: termina en 2027-06 y entra entero en el horizonte.
		BudgetItem plan = createdItem(null, new InstallmentPlan(12, 4));
		assertThat(storedEntries).hasSize(9);

		entryService().delete(USER_ID, entryOf("2027-01").getId(), DeletionScope.THIS_AND_FUTURE);

		assertThat(plan.getEndPeriod()).isEqualTo(YearMonth.of(2026, 12));
		assertThat(plan.getInstallmentsTotal()).isEqualTo(12);
		assertThat(months()).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12));
		assertThat(storedEntries).extracting(BudgetEntry::getInstallmentNumber).containsExactlyInAnyOrder(4, 5, 6);
		horizonAt("2026-11-03T12:00:00Z").ensureHorizon(user());
		horizonAt("2027-07-03T12:00:00Z").ensureHorizon(user());
		assertThat(storedEntries).hasSize(3);
	}

	@Test
	void aSingleInstallmentPlanDeletedWithOnlyThisMonthDisappearsAndIsNotRegenerated() {
		createdItem(null, new InstallmentPlan(1, 1));
		assertThat(storedEntries).hasSize(1);

		entryService().delete(USER_ID, storedEntries.getFirst().getId(), DeletionScope.ONLY_THIS);

		assertThat(storedEntries).isEmpty();
		assertThat(storedItems).isEmpty();
		assertThat(Optional.ofNullable(items.findPendingGeneration(USER_ID, YearMonth.of(2030, 1)))).hasValue(List.of());
	}
}
