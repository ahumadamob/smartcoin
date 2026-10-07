package com.smartcoin.budgetitem.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodRange;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.user.domain.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HorizonServiceTest {

	private static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");

	@Mock
	BudgetPeriodRepository periods;

	@Mock
	BudgetItemRepository items;

	@Mock
	EntryGenerator generator;

	@Captor
	ArgumentCaptor<List<BudgetPeriod>> saved;

	HorizonService service;

	@BeforeEach
	void setUp() {
		AppProperties properties = new AppProperties(ZONE, new AppProperties.Budget(24, 10),
				new AppProperties.Security("0123456789abcdef0123456789abcdef", Duration.ofHours(8), "", 10));
		Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZONE);
		service = new HorizonService(periods, items, generator, properties, clock);
	}

	private static User user(long id, YearMonth start) {
		User user = new User();
		ReflectionTestUtils.setField(user, "id", id);
		user.setStartPeriod(start);
		return user;
	}

	@Test
	void createsOpenPeriodsFromStartToHorizon() {
		when(periods.findPeriodMonthsByUserId(7L)).thenReturn(List.of());

		service.ensureHorizon(user(7L, YearMonth.of(2026, 10)));

		verify(periods).saveAll(saved.capture());
		assertThat(saved.getValue()).hasSize(25);
		assertThat(saved.getValue()).allSatisfy(period -> {
			assertThat(period.getUserId()).isEqualTo(7L);
			assertThat(period.getStatus()).isEqualTo(PeriodStatus.OPEN);
			assertThat(period.getClosedAt()).isNull();
		});
		assertThat(saved.getValue().getFirst().getPeriodMonth()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(saved.getValue().getLast().getPeriodMonth()).isEqualTo(YearMonth.of(2028, 10));
	}

	@Test
	void createsOnlyTheMissingPeriods() {
		when(periods.findPeriodMonthsByUserId(7L))
				.thenReturn(List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 11)));

		service.ensureHorizon(user(7L, YearMonth.of(2026, 10)));

		verify(periods).saveAll(saved.capture());
		assertThat(saved.getValue()).hasSize(23);
		assertThat(saved.getValue().getFirst().getPeriodMonth()).isEqualTo(YearMonth.of(2026, 12));
	}

	@Test
	void whenNothingIsMissingItSavesNothing() {
		when(periods.findPeriodMonthsByUserId(7L))
				.thenReturn(PeriodRange.required(YearMonth.of(2026, 10), YearMonth.of(2026, 10), 24));

		service.ensureHorizon(user(7L, YearMonth.of(2026, 10)));

		verify(periods, never()).saveAll(anyList());
	}

	@Test
	void generatesEachPendingItemAfterCreatingThePeriodsWithTheHorizonOfTheClock() {
		when(periods.findPeriodMonthsByUserId(7L))
				.thenReturn(PeriodRange.required(YearMonth.of(2026, 10), YearMonth.of(2026, 10), 24));
		BudgetItem first = new BudgetItem();
		BudgetItem second = new BudgetItem();
		YearMonth horizon = YearMonth.of(2028, 10);
		when(items.findPendingGeneration(7L, horizon)).thenReturn(List.of(first, second));

		service.ensureHorizon(user(7L, YearMonth.of(2026, 10)));

		verify(generator).generate(first, horizon);
		verify(generator).generate(second, horizon);
	}

	@Test
	void periodsAreCreatedBeforeTheItemsAreGenerated() {
		when(periods.findPeriodMonthsByUserId(7L)).thenReturn(List.of());
		when(items.findPendingGeneration(7L, YearMonth.of(2028, 10))).thenReturn(List.of(new BudgetItem()));

		service.ensureHorizon(user(7L, YearMonth.of(2026, 10)));

		InOrder order = inOrder(periods, generator);
		order.verify(periods).saveAll(anyList());
		order.verify(generator).generate(any(BudgetItem.class), eq(YearMonth.of(2028, 10)));
	}
}
