package com.smartcoin.budgetitem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

import com.smartcoin.budgetitem.domain.ScheduleCalculator.Schedule;
import com.smartcoin.budgetitem.domain.ScheduleCalculator.ScheduledPeriod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** RN-11 y RN-13: qué períodos corresponden entre lo ya generado, el horizonte y el fin. */
class ScheduleCalculatorTest {

	/** Horizonte con el período actual en 2026-10 y 24 meses. */
	static final YearMonth HORIZON = YearMonth.of(2028, 10);

	private static YearMonth ym(String text) {
		return YearMonth.parse(text);
	}

	private static List<YearMonth> months(Schedule schedule) {
		return schedule.periods().stream().map(ScheduledPeriod::period).toList();
	}

	private static List<YearMonth> months(String list) {
		return Arrays.stream(list.split(" ")).map(YearMonth::parse).toList();
	}

	@Test
	void semiannualInsuranceOfTheRule() {
		Schedule schedule = ScheduleCalculator.pending(ym("2026-11"), Periodicity.SEMIANNUAL, null, null, HORIZON);

		assertThat(months(schedule)).isEqualTo(months("2026-11 2027-05 2027-11 2028-05"));
		assertThat(schedule.generatedUntil()).isEqualTo(HORIZON);
	}

	@ParameterizedTest(name = "{0} desde {1} → {2}")
	@CsvSource({
			"MONTHLY,    2028-07, 2028-07 2028-08 2028-09 2028-10",
			"MONTHLY,    2026-11, 2026-11 2026-12 2027-01 2027-02 2027-03 2027-04 2027-05 2027-06 2027-07 2027-08 "
					+ "2027-09 2027-10 2027-11 2027-12 2028-01 2028-02 2028-03 2028-04 2028-05 2028-06 2028-07 2028-08 "
					+ "2028-09 2028-10",
			"BIMONTHLY,  2026-10, 2026-10 2026-12 2027-02 2027-04 2027-06 2027-08 2027-10 2027-12 2028-02 2028-04 "
					+ "2028-06 2028-08 2028-10",
			"BIMONTHLY,  2026-11, 2026-11 2027-01 2027-03 2027-05 2027-07 2027-09 2027-11 2028-01 2028-03 2028-05 "
					+ "2028-07 2028-09",
			"BIMONTHLY,  2027-12, 2027-12 2028-02 2028-04 2028-06 2028-08 2028-10",
			"QUARTERLY,  2026-10, 2026-10 2027-01 2027-04 2027-07 2027-10 2028-01 2028-04 2028-07 2028-10",
			"QUARTERLY,  2026-11, 2026-11 2027-02 2027-05 2027-08 2027-11 2028-02 2028-05 2028-08",
			"QUARTERLY,  2026-12, 2026-12 2027-03 2027-06 2027-09 2027-12 2028-03 2028-06 2028-09",
			"SEMIANNUAL, 2026-10, 2026-10 2027-04 2027-10 2028-04 2028-10",
			"SEMIANNUAL, 2027-08, 2027-08 2028-02 2028-08",
			"ANNUAL,     2026-10, 2026-10 2027-10 2028-10",
			"ANNUAL,     2026-12, 2026-12 2027-12",
			"ANNUAL,     2027-02, 2027-02 2028-02",
	})
	void everyPeriodicityFromDifferentStartMonths(Periodicity periodicity, YearMonth start, String expected) {
		Schedule schedule = ScheduleCalculator.pending(start, periodicity, null, null, HORIZON);

		assertThat(months(schedule)).isEqualTo(months(expected));
		assertThat(schedule.generatedUntil()).isEqualTo(HORIZON);
	}

	@Test
	void theIndexCountsEveryStepFromTheStart() {
		Schedule schedule = ScheduleCalculator.pending(ym("2026-11"), Periodicity.SEMIANNUAL, null, null, HORIZON);

		assertThat(schedule.periods()).extracting(ScheduledPeriod::index).containsExactly(0, 1, 2, 3);
	}

	@Test
	void endBeforeTheHorizonStopsAtTheEnd() {
		Schedule schedule = ScheduleCalculator.pending(ym("2026-10"), Periodicity.MONTHLY, ym("2027-01"), null, HORIZON);

		assertThat(months(schedule)).isEqualTo(months("2026-10 2026-11 2026-12 2027-01"));
		assertThat(schedule.generatedUntil()).isEqualTo(ym("2027-01"));
	}

	@Test
	void endBetweenTwoStepsLeavesOutTheStepAfterIt() {
		Schedule schedule = ScheduleCalculator.pending(ym("2026-10"), Periodicity.QUARTERLY, ym("2027-03"), null,
				HORIZON);

		assertThat(months(schedule)).isEqualTo(months("2026-10 2027-01"));
		assertThat(schedule.generatedUntil()).isEqualTo(ym("2027-03"));
	}

	@Test
	void endEqualToTheHorizonIncludesTheHorizon() {
		Schedule schedule = ScheduleCalculator.pending(ym("2028-08"), Periodicity.MONTHLY, HORIZON, null, HORIZON);

		assertThat(months(schedule)).isEqualTo(months("2028-08 2028-09 2028-10"));
		assertThat(schedule.generatedUntil()).isEqualTo(HORIZON);
	}

	@Test
	void endAfterTheHorizonStopsAtTheHorizon() {
		Schedule schedule = ScheduleCalculator.pending(ym("2028-08"), Periodicity.MONTHLY, ym("2030-12"), null,
				HORIZON);

		assertThat(months(schedule)).isEqualTo(months("2028-08 2028-09 2028-10"));
		assertThat(schedule.generatedUntil()).isEqualTo(HORIZON);
	}

	@ParameterizedTest
	@EnumSource(Periodicity.class)
	void endEqualToTheStartGivesASingleEntry(Periodicity periodicity) {
		Schedule schedule = ScheduleCalculator.pending(ym("2027-03"), periodicity, ym("2027-03"), null, HORIZON);

		assertThat(schedule.periods()).containsExactly(new ScheduledPeriod(ym("2027-03"), 0));
		assertThat(schedule.generatedUntil()).isEqualTo(ym("2027-03"));
	}

	@ParameterizedTest
	@EnumSource(Periodicity.class)
	void startEqualToTheHorizonGivesASingleEntry(Periodicity periodicity) {
		Schedule schedule = ScheduleCalculator.pending(HORIZON, periodicity, null, null, HORIZON);

		assertThat(schedule.periods()).containsExactly(new ScheduledPeriod(HORIZON, 0));
		assertThat(schedule.generatedUntil()).isEqualTo(HORIZON);
	}

	@Test
	void periodsAlreadyProcessedAreNotGeneratedAgain() {
		// El horizonte avanzó dos meses desde la última generación.
		Schedule schedule = ScheduleCalculator.pending(ym("2026-10"), Periodicity.MONTHLY, null, HORIZON,
				ym("2028-12"));

		assertThat(schedule.periods())
				.containsExactly(new ScheduledPeriod(ym("2028-11"), 25), new ScheduledPeriod(ym("2028-12"), 26));
		assertThat(schedule.generatedUntil()).isEqualTo(ym("2028-12"));
	}

	@Test
	void withAnotherPeriodicityTheNewPeriodsKeepTheStepsFromTheStart() {
		Schedule schedule = ScheduleCalculator.pending(ym("2026-11"), Periodicity.SEMIANNUAL, null, HORIZON,
				ym("2029-06"));

		assertThat(schedule.periods())
				.containsExactly(new ScheduledPeriod(ym("2028-11"), 4), new ScheduledPeriod(ym("2029-05"), 5));
		assertThat(schedule.generatedUntil()).isEqualTo(ym("2029-06"));
	}

	@Test
	void generatingTwiceInARowGivesNothingNew() {
		Schedule first = ScheduleCalculator.pending(ym("2026-10"), Periodicity.BIMONTHLY, null, null, HORIZON);
		Schedule second = ScheduleCalculator.pending(ym("2026-10"), Periodicity.BIMONTHLY, null,
				first.generatedUntil(), HORIZON);

		assertThat(second.periods()).isEmpty();
		assertThat(second.generatedUntil()).isEqualTo(HORIZON);
	}

	@Test
	void whenTheHorizonAdvancesWithoutANewStepNothingIsGeneratedButItIsRecordedAsProcessed() {
		Schedule schedule = ScheduleCalculator.pending(ym("2026-10"), Periodicity.ANNUAL, null, HORIZON,
				ym("2028-11"));

		assertThat(schedule.periods()).isEmpty();
		assertThat(schedule.generatedUntil()).isEqualTo(ym("2028-11"));
	}

	@Test
	void afterTheEndNothingMoreIsGeneratedEvenIfTheHorizonAdvances() {
		Schedule schedule = ScheduleCalculator.pending(ym("2026-10"), Periodicity.MONTHLY, ym("2027-01"),
				ym("2027-01"), ym("2029-01"));

		assertThat(schedule.periods()).isEmpty();
		assertThat(schedule.generatedUntil()).isEqualTo(ym("2027-01"));
	}
}
