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
}
