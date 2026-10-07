package com.smartcoin.budgetitem.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.stream.IntStream;

import com.smartcoin.budgetitem.domain.ScheduleCalculator.Schedule;
import com.smartcoin.budgetitem.domain.ScheduleCalculator.ScheduledPeriod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** RN-14: número de cuota de cada partida y período de la última, junto con el calendario de RN-11. */
class InstallmentPlanTest {

	/** Horizonte con el período actual en 2026-10 y 24 meses. */
	static final YearMonth HORIZON = YearMonth.of(2028, 10);

	private static YearMonth ym(String text) {
		return YearMonth.parse(text);
	}

	/** El calendario completo del plan, como lo arma el generador: fin calculado y sin tope de horizonte. */
	private static Schedule scheduleOf(InstallmentPlan plan, String start, Periodicity periodicity, YearMonth horizon) {
		YearMonth end = plan.endPeriod(ym(start), periodicity);
		return ScheduleCalculator.pending(ym(start), periodicity, end, null, horizon);
	}

	@ParameterizedTest(name = "{0} cuotas, primera {1}, {2}, inicio {3} → fin {4}")
	@CsvSource({
			// Ejemplo de RN-14: Heladera, cuotas 4 a 12 en 2026-10 a 2027-06.
			"12, 4, MONTHLY,     2026-10, 2027-06",
			"12, 1, MONTHLY,     2026-10, 2027-09",
			"6,  1, MONTHLY,     2026-10, 2027-03",
			// Una sola cuota: el fin es el inicio, sea cual sea la periodicidad.
			"1,  1, MONTHLY,     2026-10, 2026-10",
			"1,  1, ANNUAL,      2026-10, 2026-10",
			// Primera cuota igual al total: queda una sola partida.
			"12, 12, MONTHLY,    2026-10, 2026-10",
			"12, 12, BIMONTHLY,  2026-10, 2026-10",
			// El paso es el de la periodicidad.
			"6,  1, BIMONTHLY,   2026-10, 2027-08",
			"6,  3, BIMONTHLY,   2026-10, 2027-04",
			"4,  1, QUARTERLY,   2026-11, 2027-08",
			"3,  1, SEMIANNUAL,  2026-11, 2027-11",
			"3,  1, ANNUAL,      2026-10, 2028-10",
			"5,  2, ANNUAL,      2026-10, 2029-10",
			// Cruce de año.
			"6,  1, MONTHLY,     2026-11, 2027-04",
			"3,  1, MONTHLY,     2026-11, 2027-01",
			"12, 1, MONTHLY,     2026-12, 2027-11",
			"4,  2, BIMONTHLY,   2026-11, 2027-03",
			// Con el máximo de cuotas.
			"360, 1, MONTHLY,    2026-10, 2056-09",
	})
	void endPeriodIsTheStartPlusTheRemainingInstallmentsTimesTheStep(int total, int first, Periodicity periodicity,
			YearMonth start, YearMonth expectedEnd) {
		assertThat(new InstallmentPlan(total, first).endPeriod(start, periodicity)).isEqualTo(expectedEnd);
	}

	@Test
	void theHeladeraExampleOfTheRule() {
		InstallmentPlan plan = new InstallmentPlan(12, 4);

		Schedule schedule = scheduleOf(plan, "2026-10", Periodicity.MONTHLY, HORIZON);

		assertThat(plan.endPeriod(ym("2026-10"), Periodicity.MONTHLY)).isEqualTo(ym("2027-06"));
		assertThat(schedule.periods()).extracting(ScheduledPeriod::period).containsExactly(ym("2026-10"),
				ym("2026-11"), ym("2026-12"), ym("2027-01"), ym("2027-02"), ym("2027-03"), ym("2027-04"),
				ym("2027-05"), ym("2027-06"));
		assertThat(schedule.periods().stream().map(p -> plan.installmentNumber(p.index())).toList())
				.containsExactly(4, 5, 6, 7, 8, 9, 10, 11, 12);
	}

	@ParameterizedTest(name = "{0} cuotas, primera {1}, {2}")
	@CsvSource({
			"12, 1, MONTHLY",
			"12, 4, MONTHLY",
			"1,  1, MONTHLY",
			"12, 12, MONTHLY",
			"6,  1, BIMONTHLY",
			"6,  3, BIMONTHLY",
			"5,  2, QUARTERLY",
			"4,  1, SEMIANNUAL",
			"3,  1, ANNUAL",
			"3,  2, ANNUAL",
	})
	void eachScheduledPeriodGetsTheNextInstallmentAndTheLastOneIsTheTotal(int total, int first,
			Periodicity periodicity) {
		InstallmentPlan plan = new InstallmentPlan(total, first);
		// Horizonte lejano: el plan entero entra.
		Schedule schedule = scheduleOf(plan, "2026-11", periodicity, ym("2090-12"));

		assertThat(schedule.periods()).hasSize(total - first + 1);
		assertThat(schedule.periods().stream().map(p -> plan.installmentNumber(p.index())).toList())
				.isEqualTo(IntStream.rangeClosed(first, total).boxed().toList());
		assertThat(schedule.periods().getLast().period()).isEqualTo(plan.endPeriod(ym("2026-11"), periodicity));
	}

	@Test
	void aPlanThatEndsBeforeTheHorizonIsGeneratedWhole() {
		InstallmentPlan plan = new InstallmentPlan(12, 1);

		Schedule schedule = scheduleOf(plan, "2026-10", Periodicity.MONTHLY, HORIZON);

		assertThat(schedule.periods()).hasSize(12);
		assertThat(schedule.generatedUntil()).isEqualTo(ym("2027-09"));
	}

	@Test
	void aPlanThatEndsAfterTheHorizonGeneratesUpToTheHorizonWithTheRightNumbers() {
		InstallmentPlan plan = new InstallmentPlan(60, 1);

		Schedule schedule = scheduleOf(plan, "2026-10", Periodicity.MONTHLY, HORIZON);

		assertThat(plan.endPeriod(ym("2026-10"), Periodicity.MONTHLY)).isEqualTo(ym("2031-09"));
		assertThat(schedule.periods()).hasSize(25);
		assertThat(schedule.generatedUntil()).isEqualTo(HORIZON);
		assertThat(plan.installmentNumber(schedule.periods().getFirst().index())).isEqualTo(1);
		assertThat(plan.installmentNumber(schedule.periods().getLast().index())).isEqualTo(25);
	}

	@Test
	void whenTheHorizonAdvancesTheNextInstallmentsContinueTheNumberingWithoutRepeatingOrSkipping() {
		// La partida de generated_until = 2028-10 es la cuota 25; con el horizonte en 2029-01 faltan las cuotas 26 a 28.
		InstallmentPlan plan = new InstallmentPlan(60, 1);
		YearMonth end = plan.endPeriod(ym("2026-10"), Periodicity.MONTHLY);

		Schedule next = ScheduleCalculator.pending(ym("2026-10"), Periodicity.MONTHLY, end, HORIZON, ym("2029-01"));

		assertThat(next.periods()).extracting(ScheduledPeriod::period).containsExactly(ym("2028-11"), ym("2028-12"),
				ym("2029-01"));
		assertThat(next.periods().stream().map(p -> plan.installmentNumber(p.index())).toList())
				.containsExactly(26, 27, 28);
		assertThat(next.generatedUntil()).isEqualTo(ym("2029-01"));
	}

	@Test
	void withAStartedPlanTheContinuationKeepsTheOffsetOfTheFirstInstallment() {
		// Cuota 4 en 2026-10: la 25.ª partida (2028-10) es la cuota 28 y la que sigue, la 29.
		InstallmentPlan plan = new InstallmentPlan(40, 4);
		YearMonth end = plan.endPeriod(ym("2026-10"), Periodicity.MONTHLY);

		Schedule next = ScheduleCalculator.pending(ym("2026-10"), Periodicity.MONTHLY, end, HORIZON, ym("2028-12"));

		assertThat(next.periods().stream().map(p -> plan.installmentNumber(p.index())).toList())
				.containsExactly(29, 30);
	}

	@Test
	void afterTheLastInstallmentNothingMoreIsGeneratedEvenIfTheHorizonAdvances() {
		InstallmentPlan plan = new InstallmentPlan(12, 4);
		YearMonth end = plan.endPeriod(ym("2026-10"), Periodicity.MONTHLY);

		Schedule next = ScheduleCalculator.pending(ym("2026-10"), Periodicity.MONTHLY, end, end, ym("2030-01"));

		assertThat(next.periods()).isEmpty();
		assertThat(next.generatedUntil()).isEqualTo(end);
	}

	@Test
	void aBimonthlyPlanThatCrossesTheHorizonNumbersEveryOtherMonth() {
		InstallmentPlan plan = new InstallmentPlan(30, 1);

		Schedule schedule = scheduleOf(plan, "2026-10", Periodicity.BIMONTHLY, HORIZON);

		assertThat(schedule.periods()).hasSize(13);
		assertThat(schedule.periods().getLast().period()).isEqualTo(ym("2028-10"));
		assertThat(plan.installmentNumber(schedule.periods().getLast().index())).isEqualTo(13);
	}

	@ParameterizedTest
	@ValueSource(ints = { 2, 11 })
	void aPartidaAfterTheLastInstallmentIsAnInconsistency(int index) {
		assertThatThrownBy(() -> new InstallmentPlan(3, 2).installmentNumber(index))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theLastPartidaOfAStartedPlanIsTheTotal() {
		assertThat(new InstallmentPlan(3, 2).installmentNumber(1)).isEqualTo(3);
	}

	@Test
	void aNegativeIndexIsAnInconsistency() {
		assertThatThrownBy(() -> new InstallmentPlan(3, 1).installmentNumber(-1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "{0} cuotas, primera {1}")
	@CsvSource({ "0, 1", "-3, 1", "361, 1", "32767, 1", "12, 0", "12, -1", "12, 13", "1, 2" })
	void aPlanOutOfRangeIsRejected(int total, int first) {
		assertThatThrownBy(() -> new InstallmentPlan(total, first)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "{0} cuotas, primera {1}")
	@CsvSource({ "1, 1", "360, 1", "360, 360", "12, 12", "12, 1" })
	void aPlanOnTheLimitsIsAccepted(int total, int first) {
		assertThat(new InstallmentPlan(total, first)).isEqualTo(new InstallmentPlan(total, first));
	}

	@Test
	void theLastPossibleEndOfAnAnnualPlanFitsInAFourDigitYear() {
		YearMonth end = new InstallmentPlan(InstallmentPlan.MAX_TOTAL, 1).endPeriod(YearMonth.of(2028, 10),
				Periodicity.ANNUAL);

		assertThat(end.getYear()).isLessThanOrEqualTo(9999);
	}
}
