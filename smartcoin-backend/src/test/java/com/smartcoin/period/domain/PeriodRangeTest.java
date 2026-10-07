package com.smartcoin.period.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodRangeTest {

	private static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	private static final int HORIZON = 24;

	private static YearMonth currentAt(String instant) {
		return YearMonth.now(Clock.fixed(Instant.parse(instant), ZONE));
	}

	@Test
	void horizonIsCurrentPlus24Months() {
		assertThat(PeriodRange.horizon(YearMonth.of(2026, 10), HORIZON)).isEqualTo(YearMonth.of(2028, 10));
	}

	@Test
	void startEqualToCurrentCreatesCurrentPlus24() {
		YearMonth current = currentAt("2026-10-15T12:00:00Z");

		List<YearMonth> required = PeriodRange.required(current, current, HORIZON);

		assertThat(required).hasSize(25);
		assertThat(required.getFirst()).isEqualTo(YearMonth.of(2026, 10));
		assertThat(required.getLast()).isEqualTo(YearMonth.of(2028, 10));
	}

	@Test
	void startInThePastIncludesThePastMonths() {
		YearMonth current = currentAt("2026-10-15T12:00:00Z");

		List<YearMonth> required = PeriodRange.required(YearMonth.of(2026, 1), current, HORIZON);

		assertThat(required).hasSize(34);
		assertThat(required.getFirst()).isEqualTo(YearMonth.of(2026, 1));
		assertThat(required.getLast()).isEqualTo(YearMonth.of(2028, 10));
	}

	@Test
	void rangeCrossesYearBoundaries() {
		YearMonth current = currentAt("2026-12-20T12:00:00Z");

		List<YearMonth> required = PeriodRange.required(YearMonth.of(2026, 11), current, HORIZON);

		assertThat(required).startsWith(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1));
		assertThat(required).contains(YearMonth.of(2027, 12), YearMonth.of(2028, 1));
		assertThat(required.getLast()).isEqualTo(YearMonth.of(2028, 12));
		assertThat(required).doesNotHaveDuplicates().isSorted();
	}

	@Test
	void businessTodayUsesTheMendozaZoneAtTheMonthBoundary() {
		// 2026-11-01 01:00 UTC todavía es 31 de octubre en Mendoza (UTC-3): el período actual es 2026-10.
		YearMonth current = currentAt("2026-11-01T01:00:00Z");

		assertThat(current).isEqualTo(YearMonth.of(2026, 10));
		assertThat(PeriodRange.horizon(current, HORIZON)).isEqualTo(YearMonth.of(2028, 10));
	}

	@Test
	void startAfterTheHorizonRequiresNothing() {
		assertThat(PeriodRange.required(YearMonth.of(2030, 1), YearMonth.of(2026, 10), HORIZON)).isEmpty();
	}

	@Test
	void missingWithNothingExistingIsTheWholeRange() {
		YearMonth current = YearMonth.of(2026, 10);

		assertThat(PeriodRange.missing(current, current, HORIZON, List.of()))
				.isEqualTo(PeriodRange.required(current, current, HORIZON));
	}

	@Test
	void missingSkipsExistingPeriodsAndFillsGaps() {
		YearMonth current = YearMonth.of(2026, 10);
		List<YearMonth> existing = List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2027, 1));

		List<YearMonth> missing = PeriodRange.missing(current, current, HORIZON, existing);

		assertThat(missing).hasSize(22).doesNotContainAnyElementsOf(existing);
		assertThat(missing).contains(YearMonth.of(2026, 12), YearMonth.of(2027, 2), YearMonth.of(2028, 10));
	}

	@Test
	void missingIsEmptyWhenTheHorizonIsAlreadyEnsured() {
		YearMonth current = YearMonth.of(2026, 10);
		List<YearMonth> existing = PeriodRange.required(current, current, HORIZON);

		assertThat(PeriodRange.missing(current, current, HORIZON, existing)).isEmpty();
	}

	@Test
	void missingExtendsTheHorizonWhenTheMonthAdvances() {
		YearMonth start = YearMonth.of(2026, 10);
		List<YearMonth> existing = PeriodRange.required(start, YearMonth.of(2026, 10), HORIZON);

		assertThat(PeriodRange.missing(start, YearMonth.of(2026, 11), HORIZON, existing))
				.containsExactly(YearMonth.of(2028, 11));
	}

	@Test
	void periodsOutsideTheRangeDoNotAffectMissing() {
		YearMonth current = YearMonth.of(2026, 10);
		List<YearMonth> existing = List.of(YearMonth.of(2020, 1));

		assertThat(PeriodRange.missing(current, current, HORIZON, existing)).hasSize(25);
	}

	@Test
	void withoutClosedPeriodsTheFirstOpenIsTheStartPeriod() {
		assertThat(PeriodRange.firstOpen(YearMonth.of(2026, 8), null)).isEqualTo(YearMonth.of(2026, 8));
	}

	@Test
	void theFirstOpenPeriodIsTheOneAfterTheLastClosed() {
		assertThat(PeriodRange.firstOpen(YearMonth.of(2026, 8), YearMonth.of(2026, 9)))
				.isEqualTo(YearMonth.of(2026, 10));
		assertThat(PeriodRange.firstOpen(YearMonth.of(2026, 8), YearMonth.of(2026, 12)))
				.isEqualTo(YearMonth.of(2027, 1));
	}
}
