package com.smartcoin.budgetitem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;

import com.smartcoin.account.domain.Account;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.InstallmentPlan;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.domain.EntryKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RN-13 como componente reutilizable: lo que HU-12 va a llamar cada vez que avance el horizonte. */
@ExtendWith(MockitoExtension.class)
class EntryGeneratorTest {

	static final long USER_ID = 7;

	@Mock
	BudgetPeriodRepository periods;
	@Mock
	BudgetEntryRepository entries;

	EntryGenerator generator;

	@BeforeEach
	void setUp() {
		generator = new EntryGenerator(periods, entries);
		lenient().when(periods.findByUserIdAndPeriodMonthIn(eq(USER_ID), anyCollection()))
				.thenAnswer(call -> call.<Collection<YearMonth>>getArgument(1).stream()
						.map(month -> BudgetPeriod.open(USER_ID, month)).toList());
		lenient().when(entries.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));
	}

	private static YearMonth ym(String text) {
		return YearMonth.parse(text);
	}

	private static BudgetItem item(String start, String end, String generatedUntil) {
		BudgetItem item = new BudgetItem();
		item.setUserId(USER_ID);
		item.setName("Sueldo A");
		item.setKind(EntryKind.INCOME);
		item.setDefaultAccount(new Account());
		item.setPeriodicity(Periodicity.MONTHLY);
		item.setDueDay(25);
		item.setDueMonthOffset(-1);
		item.setStartPeriod(ym(start));
		item.setEndPeriod(end == null ? null : ym(end));
		item.setEstimationRule(EstimationRule.LAST_VALUE);
		item.setCurrentAmount(new BigDecimal("1200000.00"));
		item.setGeneratedUntil(generatedUntil == null ? null : ym(generatedUntil));
		return item;
	}

	@Test
	void whenTheHorizonAdvancesItGeneratesOnlyTheNewPeriodsWithTheCurrentAmount() {
		BudgetItem item = item("2026-10", null, "2028-10");

		List<BudgetEntry> created = generator.generate(item, ym("2028-11"));

		assertThat(created).hasSize(1);
		assertThat(created.getFirst().getPeriod().getPeriodMonth()).isEqualTo(ym("2028-11"));
		assertThat(created.getFirst().getDueDate()).isEqualTo(LocalDate.of(2028, 10, 25));
		assertThat(created.getFirst().getBudgetedAmount()).isEqualTo(new BigDecimal("1200000.00"));
		assertThat(item.getGeneratedUntil()).isEqualTo(ym("2028-11"));
	}

	@Test
	void runningItTwiceInARowCreatesNothingNew() {
		BudgetItem item = item("2026-10", null, null);

		assertThat(generator.generate(item, ym("2028-10"))).hasSize(25);
		assertThat(generator.generate(item, ym("2028-10"))).isEmpty();

		verify(entries).saveAll(anyList());
		assertThat(item.getGeneratedUntil()).isEqualTo(ym("2028-10"));
	}

	@Test
	void withNothingToGenerateItDoesNotTouchTheRepositoriesButRecordsWhatItProcessed() {
		BudgetItem item = item("2026-10", "2027-01", "2027-01");

		assertThat(generator.generate(item, ym("2028-11"))).isEmpty();

		verifyNoInteractions(periods, entries);
		assertThat(item.getGeneratedUntil()).isEqualTo(ym("2027-01"));
	}

	@Test
	void aMissingTargetPeriodIsAnInconsistencyAndNothingIsSaved() {
		when(periods.findByUserIdAndPeriodMonthIn(eq(USER_ID), anyCollection())).thenReturn(List.of());
		BudgetItem item = item("2026-10", null, null);

		assertThatThrownBy(() -> generator.generate(item, ym("2028-10")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("2026-10");

		verify(entries, never()).saveAll(anyList());
		assertThat(item.getGeneratedUntil()).isNull();
	}

	// --- Cuotas (RN-14) ---

	private static BudgetItem installmentItem(String start, int total, int first, String generatedUntil) {
		BudgetItem item = item(start, null, generatedUntil);
		item.setName("Heladera");
		item.setKind(EntryKind.EXPENSE);
		item.setDueMonthOffset(0);
		item.setInstallmentsTotal(total);
		item.setFirstInstallmentNumber(first);
		item.setEndPeriod(new InstallmentPlan(total, first).endPeriod(ym(start), item.getPeriodicity()));
		return item;
	}

	private static List<Integer> numbers(List<BudgetEntry> entries) {
		return entries.stream().map(BudgetEntry::getInstallmentNumber).toList();
	}

	private static List<YearMonth> months(List<BudgetEntry> entries) {
		return entries.stream().map(entry -> entry.getPeriod().getPeriodMonth()).toList();
	}

	@Test
	void aConceptWithoutInstallmentsGeneratesEntriesWithoutInstallmentNumber() {
		List<BudgetEntry> created = generator.generate(item("2026-10", null, null), ym("2028-10"));

		assertThat(numbers(created)).hasSize(25).containsOnlyNulls();
	}

	@Test
	void aPlanThatEndsBeforeTheHorizonGeneratesAllItsInstallments() {
		BudgetItem item = installmentItem("2026-10", 12, 4, null);

		List<BudgetEntry> created = generator.generate(item, ym("2028-10"));

		assertThat(numbers(created)).containsExactly(4, 5, 6, 7, 8, 9, 10, 11, 12);
		assertThat(months(created).getFirst()).isEqualTo(ym("2026-10"));
		assertThat(months(created).getLast()).isEqualTo(ym("2027-06"));
		assertThat(item.getGeneratedUntil()).isEqualTo(ym("2027-06"));
	}

	@Test
	void aPlanThatEndsAfterTheHorizonGeneratesUpToTheHorizonWithTheRightNumbers() {
		BudgetItem item = installmentItem("2026-10", 60, 1, null);

		List<BudgetEntry> created = generator.generate(item, ym("2028-10"));

		assertThat(numbers(created)).isEqualTo(java.util.stream.IntStream.rangeClosed(1, 25).boxed().toList());
		assertThat(months(created).getLast()).isEqualTo(ym("2028-10"));
		assertThat(item.getGeneratedUntil()).isEqualTo(ym("2028-10"));
		assertThat(item.getEndPeriod()).isEqualTo(ym("2031-09"));
	}

	@Test
	void withAStartedPlanTheHorizonCutsItInTheMiddleAndTheNumbersAreStillRight() {
		BudgetItem item = installmentItem("2026-10", 40, 4, null);

		List<BudgetEntry> created = generator.generate(item, ym("2028-10"));

		assertThat(numbers(created).getFirst()).isEqualTo(4);
		assertThat(numbers(created).getLast()).isEqualTo(28);
	}

	@Test
	void whenGeneratedUntilIsAlreadyAdvancedTheNextInstallmentsContinueTheNumberingWithoutRepeatingOrSkipping() {
		BudgetItem item = installmentItem("2026-10", 60, 1, null);
		generator.generate(item, ym("2028-10"));

		// El horizonte avanza tres meses: las cuotas 26, 27 y 28.
		List<BudgetEntry> next = generator.generate(item, ym("2029-01"));

		assertThat(numbers(next)).containsExactly(26, 27, 28);
		assertThat(months(next)).containsExactly(ym("2028-11"), ym("2028-12"), ym("2029-01"));
		assertThat(item.getGeneratedUntil()).isEqualTo(ym("2029-01"));
	}

	@Test
	void theContinuationOfAStartedPlanKeepsTheOffsetOfTheFirstInstallment() {
		BudgetItem item = installmentItem("2026-10", 40, 4, "2028-10");

		List<BudgetEntry> next = generator.generate(item, ym("2028-12"));

		assertThat(numbers(next)).containsExactly(29, 30);
	}

	@Test
	void theContinuationOfABimonthlyPlanNumbersOnlyTheMonthsThatCorrespond() {
		BudgetItem item = installmentItem("2026-10", 30, 1, null);
		item.setPeriodicity(Periodicity.BIMONTHLY);
		item.setEndPeriod(new InstallmentPlan(30, 1).endPeriod(ym("2026-10"), Periodicity.BIMONTHLY));
		List<BudgetEntry> first = generator.generate(item, ym("2028-10"));

		List<BudgetEntry> next = generator.generate(item, ym("2029-02"));

		assertThat(numbers(first).getLast()).isEqualTo(13);
		assertThat(months(first).getLast()).isEqualTo(ym("2028-10"));
		assertThat(months(next)).containsExactly(ym("2028-12"), ym("2029-02"));
		assertThat(numbers(next)).containsExactly(14, 15);
	}

	@Test
	void afterTheLastInstallmentNothingMoreIsGeneratedEvenIfTheHorizonAdvances() {
		BudgetItem item = installmentItem("2026-10", 12, 4, "2027-06");

		assertThat(generator.generate(item, ym("2030-01"))).isEmpty();

		verifyNoInteractions(periods, entries);
		assertThat(item.getGeneratedUntil()).isEqualTo(ym("2027-06"));
	}

	@Test
	void aSingleInstallmentGeneratesASingleEntry() {
		List<BudgetEntry> created = generator.generate(installmentItem("2026-10", 1, 1, null), ym("2028-10"));

		assertThat(numbers(created)).containsExactly(1);
		assertThat(months(created)).containsExactly(ym("2026-10"));
	}

	@Test
	void aFirstInstallmentEqualToTheTotalGeneratesASingleEntry() {
		List<BudgetEntry> created = generator.generate(installmentItem("2026-10", 12, 12, null), ym("2028-10"));

		assertThat(numbers(created)).containsExactly(12);
		assertThat(months(created)).containsExactly(ym("2026-10"));
	}
}
