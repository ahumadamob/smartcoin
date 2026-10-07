package com.smartcoin.budgetitem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.smartcoin.account.domain.Account;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodRange;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.user.domain.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-12 (RN-07, RN-13): asegurar el horizonte con un {@link EntryGenerator} real sobre repositorios simulados con
 * estado. El repositorio de Conceptos aplica el mismo criterio que la consulta {@code findPendingGeneration}.
 * Los tests no prueban el SQL.
 */
class HorizonGenerationTest {

	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetItemRepository items = mock(BudgetItemRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);

	final List<BudgetItem> stored = new ArrayList<>();
	final List<BudgetEntry> created = new ArrayList<>();
	final List<YearMonth> existingPeriods = new ArrayList<>();

	@BeforeEach
	void setUp() {
		when(items.findPendingGeneration(anyLong(), org.mockito.ArgumentMatchers.any(YearMonth.class)))
				.thenAnswer(call -> {
					long userId = call.getArgument(0);
					YearMonth horizon = call.getArgument(1);
					return stored.stream().filter(i -> i.getUserId() == userId).filter(i -> {
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
						.map(month -> BudgetPeriod.open(call.getArgument(0), month)).toList());
		when(entries.saveAll(anyList())).thenAnswer(call -> {
			List<BudgetEntry> saved = call.getArgument(0);
			created.addAll(saved);
			return saved;
		});
	}

	private HorizonService serviceAt(String instant) {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, 10),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		return new HorizonService(periods, items, new EntryGenerator(periods, entries), properties,
				Clock.fixed(Instant.parse(instant), ZONE));
	}

	private static YearMonth ym(String text) {
		return YearMonth.parse(text);
	}

	private static User user(long id) {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", id);
		user.setStartPeriod(ym("2026-10"));
		return user;
	}

	/** Ya generado hasta {@code generatedUntil}, como si se hubiera creado con ese horizonte. */
	private BudgetItem item(long userId, Periodicity periodicity, String start, String end, String generatedUntil,
			String amount) {
		BudgetItem item = new BudgetItem();
		item.setUserId(userId);
		item.setName("Concepto");
		item.setKind(EntryKind.EXPENSE);
		item.setDefaultAccount(new Account());
		item.setPeriodicity(periodicity);
		item.setDueDay(10);
		item.setDueMonthOffset(0);
		item.setStartPeriod(ym(start));
		item.setEndPeriod(end == null ? null : ym(end));
		item.setEstimationRule(EstimationRule.LAST_VALUE);
		item.setCurrentAmount(new BigDecimal(amount));
		item.setGeneratedUntil(generatedUntil == null ? null : ym(generatedUntil));
		stored.add(item);
		return item;
	}

	private void periodsExistUntil(String last) {
		existingPeriods.addAll(PeriodRange.required(ym("2026-10"), ym("2026-10"), 0));
		for (YearMonth m = ym("2026-10"); !m.isAfter(ym(last)); m = m.plusMonths(1)) {
			if (!existingPeriods.contains(m)) {
				existingPeriods.add(m);
			}
		}
	}

	private List<YearMonth> createdMonths(BudgetItem item) {
		return created.stream().filter(e -> e.getBudgetItem() == item).map(e -> e.getPeriod().getPeriodMonth()).toList();
	}

	@Test
	void theExampleOfTheStoryCreatesPeriod202811AndOneEntryPerMonthlyItemWithTheCurrentAmount() {
		periodsExistUntil("2028-10");
		BudgetItem rent = item(USER_ID, Periodicity.MONTHLY, "2026-10", null, "2028-10", "350000.00");
		BudgetItem salary = item(USER_ID, Periodicity.MONTHLY, "2026-10", null, "2028-10", "1200000.00");
		// El monto vigente cambió después de generar el resto: la partida nueva toma el de hoy.
		rent.setCurrentAmount(new BigDecimal("400000.00"));

		serviceAt("2026-11-03T12:00:00Z").ensureHorizon(user(USER_ID));

		assertThat(existingPeriods).contains(ym("2028-11")).hasSize(26);
		assertThat(createdMonths(rent)).containsExactly(ym("2028-11"));
		assertThat(createdMonths(salary)).containsExactly(ym("2028-11"));
		assertThat(created).extracting(BudgetEntry::getBudgetedAmount)
				.containsExactly(new BigDecimal("400000.00"), new BigDecimal("1200000.00"));
		assertThat(rent.getGeneratedUntil()).isEqualTo(ym("2028-11"));
	}

	@Test
	void periodicItemsOnlyGetAnEntryInThePeriodsThatFitTheirSchedule() {
		periodsExistUntil("2028-10");
		// Inicio 2026-10: bimestral toca en 2028-12, trimestral en 2028-10 y 2029-01, semestral en 2029-04, anual en 2029-10.
		BudgetItem bimonthly = item(USER_ID, Periodicity.BIMONTHLY, "2026-10", null, "2028-10", "10.00");
		BudgetItem quarterly = item(USER_ID, Periodicity.QUARTERLY, "2026-10", null, "2028-10", "10.00");
		BudgetItem semiannual = item(USER_ID, Periodicity.SEMIANNUAL, "2026-10", null, "2028-10", "10.00");
		BudgetItem annual = item(USER_ID, Periodicity.ANNUAL, "2026-10", null, "2028-10", "10.00");

		serviceAt("2026-11-03T12:00:00Z").ensureHorizon(user(USER_ID)); // horizonte 2028-11: ninguno toca
		assertThat(created).isEmpty();
		assertThat(bimonthly.getGeneratedUntil()).isEqualTo(ym("2028-11"));

		serviceAt("2026-12-03T12:00:00Z").ensureHorizon(user(USER_ID)); // horizonte 2028-12: el bimestral
		assertThat(createdMonths(bimonthly)).containsExactly(ym("2028-12"));
		assertThat(createdMonths(quarterly)).isEmpty();

		serviceAt("2027-01-03T12:00:00Z").ensureHorizon(user(USER_ID)); // 2029-01: el trimestral
		assertThat(createdMonths(quarterly)).containsExactly(ym("2029-01"));
		assertThat(createdMonths(semiannual)).isEmpty();

		serviceAt("2027-04-03T12:00:00Z").ensureHorizon(user(USER_ID)); // 2029-04: el semestral (el bimestral no: 2029-02, -04)
		assertThat(createdMonths(semiannual)).containsExactly(ym("2029-04"));
		assertThat(createdMonths(annual)).isEmpty();

		serviceAt("2027-10-03T12:00:00Z").ensureHorizon(user(USER_ID)); // 2029-10: el anual
		assertThat(createdMonths(annual)).containsExactly(ym("2029-10"));
	}

	@Test
	void anItemThatEndedBeforeTheNewPeriodGetsNothingAndIsNotEvenFetched() {
		periodsExistUntil("2028-10");
		BudgetItem ended = item(USER_ID, Periodicity.MONTHLY, "2026-10", "2027-03", "2027-03", "10.00");

		serviceAt("2026-11-03T12:00:00Z").ensureHorizon(user(USER_ID));

		assertThat(created).isEmpty();
		assertThat(ended.getGeneratedUntil()).isEqualTo(ym("2027-03"));
		verify(items).findPendingGeneration(USER_ID, ym("2028-11"));
		assertThat(items.findPendingGeneration(USER_ID, ym("2028-11"))).isEmpty();
	}

	@Test
	void anInstallmentPlanCutByTheHorizonContinuesWithTheRightNumberAndStopsAtTheLastOne() {
		periodsExistUntil("2028-10");
		// 60 cuotas desde 2026-10: con horizonte 2028-10 se generaron las 25 primeras; el fin es 2031-09.
		BudgetItem plan = item(USER_ID, Periodicity.MONTHLY, "2026-10", "2031-09", "2028-10", "10.00");
		plan.setInstallmentsTotal(60);
		plan.setFirstInstallmentNumber(1);

		serviceAt("2026-11-03T12:00:00Z").ensureHorizon(user(USER_ID));

		assertThat(createdMonths(plan)).containsExactly(ym("2028-11"));
		assertThat(created.getFirst().getInstallmentNumber()).isEqualTo(26);

		// Faltan 3 años: al volver, genera hasta la última cuota (la 60, en 2031-09) y ni una más.
		serviceAt("2031-10-03T12:00:00Z").ensureHorizon(user(USER_ID));
		assertThat(created.getLast().getPeriod().getPeriodMonth()).isEqualTo(ym("2031-09"));
		assertThat(created.getLast().getInstallmentNumber()).isEqualTo(60);
		assertThat(plan.getGeneratedUntil()).isEqualTo(ym("2031-09"));

		int before = created.size();
		serviceAt("2031-11-03T12:00:00Z").ensureHorizon(user(USER_ID));
		assertThat(created).hasSize(before);
	}

	@Test
	void aQuarterlyPlanWithAFirstInstallmentOtherThanOneGetsItsLastInstallmentAndNothingMore() {
		periodsExistUntil("2028-10");
		// 12 cuotas trimestrales, primera cuota 4, inicio 2026-10: la cuota 4 es 2026-10 y la 12 es 2028-10 (el fin).
		BudgetItem plan = item(USER_ID, Periodicity.QUARTERLY, "2026-10", "2028-10", "2028-07", "10.00");
		plan.setInstallmentsTotal(12);
		plan.setFirstInstallmentNumber(4);

		serviceAt("2026-11-03T12:00:00Z").ensureHorizon(user(USER_ID));

		assertThat(createdMonths(plan)).containsExactly(ym("2028-10"));
		assertThat(created.getFirst().getInstallmentNumber()).isEqualTo(12);
		assertThat(plan.getGeneratedUntil()).isEqualTo(ym("2028-10"));

		serviceAt("2027-10-03T12:00:00Z").ensureHorizon(user(USER_ID));
		assertThat(created).hasSize(1);
	}

	@Test
	void afterSeveralMonthsAwayItCreatesEveryMissingPeriodAndEntry() {
		periodsExistUntil("2028-10");
		BudgetItem monthly = item(USER_ID, Periodicity.MONTHLY, "2026-10", null, "2028-10", "10.00");
		BudgetItem bimonthly = item(USER_ID, Periodicity.BIMONTHLY, "2026-10", null, "2028-10", "10.00");

		serviceAt("2027-01-20T12:00:00Z").ensureHorizon(user(USER_ID)); // vuelve 3 meses después: horizonte 2029-01

		assertThat(existingPeriods).contains(ym("2028-11"), ym("2028-12"), ym("2029-01"));
		assertThat(createdMonths(monthly)).containsExactly(ym("2028-11"), ym("2028-12"), ym("2029-01"));
		assertThat(createdMonths(bimonthly)).containsExactly(ym("2028-12"));
		assertThat(monthly.getGeneratedUntil()).isEqualTo(ym("2029-01"));
	}

	@Test
	void aJumpOfMonthsWithInstallmentsAcrossTheYearNumbersEachOne() {
		periodsExistUntil("2028-10");
		BudgetItem plan = item(USER_ID, Periodicity.MONTHLY, "2026-10", "2040-01", "2028-10", "10.00");
		plan.setInstallmentsTotal(160);
		plan.setFirstInstallmentNumber(1);

		serviceAt("2027-02-01T12:00:00Z").ensureHorizon(user(USER_ID)); // horizonte 2029-02: 4 períodos, cruza 2029

		assertThat(createdMonths(plan)).containsExactly(ym("2028-11"), ym("2028-12"), ym("2029-01"), ym("2029-02"));
		assertThat(created).extracting(BudgetEntry::getInstallmentNumber).containsExactly(26, 27, 28, 29);
	}

	@Test
	void theSecondRunInARowCreatesNothingAndSavesNothing() {
		periodsExistUntil("2028-10");
		BudgetItem monthly = item(USER_ID, Periodicity.MONTHLY, "2026-10", null, "2028-10", "10.00");
		HorizonService service = serviceAt("2026-11-03T12:00:00Z");

		service.ensureHorizon(user(USER_ID));
		int periodsAfterFirst = existingPeriods.size();
		int entriesAfterFirst = created.size();
		YearMonth until = monthly.getGeneratedUntil();
		org.mockito.Mockito.clearInvocations(periods, entries);

		service.ensureHorizon(user(USER_ID));

		assertThat(existingPeriods).hasSize(periodsAfterFirst);
		assertThat(created).hasSize(entriesAfterFirst);
		assertThat(monthly.getGeneratedUntil()).isEqualTo(until);
		verify(periods, never()).saveAll(anyList());
		verify(entries, never()).saveAll(anyList());
	}

	@Test
	void aProcessedPeriodIsNotProcessedAgainEvenWithoutAnEntry() {
		periodsExistUntil("2028-11");
		// 2027-05 no tiene partida (la eliminó el usuario, HU-18) pero generated_until ya pasó por ahí.
		BudgetItem monthly = item(USER_ID, Periodicity.MONTHLY, "2026-10", null, "2028-11", "10.00");

		serviceAt("2026-11-03T12:00:00Z").ensureHorizon(user(USER_ID));

		assertThat(created).isEmpty();
		assertThat(createdMonths(monthly)).doesNotContain(ym("2027-05"));
		assertThat(monthly.getGeneratedUntil()).isEqualTo(ym("2028-11"));
	}

	@Test
	void itemsOfAnotherUserAreNotTouched() {
		periodsExistUntil("2028-10");
		BudgetItem mine = item(USER_ID, Periodicity.MONTHLY, "2026-10", null, "2028-10", "10.00");
		BudgetItem theirs = item(OTHER_USER_ID, Periodicity.MONTHLY, "2026-10", null, "2028-10", "10.00");

		serviceAt("2026-11-03T12:00:00Z").ensureHorizon(user(USER_ID));

		assertThat(createdMonths(mine)).containsExactly(ym("2028-11"));
		assertThat(createdMonths(theirs)).isEmpty();
		assertThat(theirs.getGeneratedUntil()).isEqualTo(ym("2028-10"));
		verify(items).findPendingGeneration(eq(USER_ID), org.mockito.ArgumentMatchers.any(YearMonth.class));
		verify(items, never()).findPendingGeneration(eq(OTHER_USER_ID), org.mockito.ArgumentMatchers.any(YearMonth.class));
	}

	@Test
	void anItemThatNeverGeneratedIsGeneratedUpToTheHorizon() {
		BudgetItem fresh = item(USER_ID, Periodicity.MONTHLY, "2026-10", null, null, "10.00");

		serviceAt("2026-10-03T12:00:00Z").ensureHorizon(user(USER_ID));

		assertThat(createdMonths(fresh)).hasSize(25).first().isEqualTo(ym("2026-10"));
	}
}
