package com.smartcoin.entry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.entry.domain.EstimationCalculator.Change;
import com.smartcoin.entry.domain.EstimationCalculator.EntryFacts;
import com.smartcoin.entry.domain.EstimationCalculator.Plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** RN-26: base, estimación y destino, sin Spring ni base de datos. */
class EstimationCalculatorTest {

	static long nextId = 1;

	static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	static YearMonth ym(String text) {
		return YearMonth.parse(text);
	}

	static EntryFacts consolidated(String period, String amount) {
		return new EntryFacts(nextId++, ym(period), true, true, amount(amount), null, false, amount(amount));
	}

	static EntryFacts consolidatedByClosing(String period, String amount) {
		return new EntryFacts(nextId++, ym(period), true, true, amount(amount), ClosingResolution.CLOSE_AS_IS, false,
				amount(amount));
	}

	static EntryFacts pending(String period, String budgeted) {
		return new EntryFacts(nextId++, ym(period), true, false, null, null, false, amount(budgeted));
	}

	static EntryFacts edited(String period, String budgeted) {
		return new EntryFacts(nextId++, ym(period), true, false, null, null, true, amount(budgeted));
	}

	static EntryFacts pendingInClosedPeriod(String period, String budgeted) {
		return new EntryFacts(nextId++, ym(period), false, false, null, null, false, amount(budgeted));
	}

	static List<String> periodsOf(Plan plan) {
		return plan.targets().stream().map(t -> t.period().toString()).toList();
	}

	static Stream<Arguments> estimates() {
		return Stream.of(
				// último valor: toma la más reciente por período, no la última cargada
				Arguments.of("último valor", EstimationRule.LAST_VALUE,
						List.of(consolidated("2026-09", "100.00"), consolidated("2026-10", "130.00")), "130.00"),
				// promedio con 1, 2 y 3 consolidaciones
				Arguments.of("promedio de 1", EstimationRule.AVERAGE_LAST_3,
						List.of(consolidated("2026-10", "45310.00")), "45310.00"),
				Arguments.of("promedio de 2", EstimationRule.AVERAGE_LAST_3,
						List.of(consolidated("2026-09", "100.00"), consolidated("2026-10", "200.00")), "150.00"),
				Arguments.of("promedio de 3", EstimationRule.AVERAGE_LAST_3,
						List.of(consolidated("2026-08", "30.00"), consolidated("2026-09", "60.00"),
								consolidated("2026-10", "90.00")),
						"60.00"),
				// RN-26: la de julio no entra
				Arguments.of("la luz de RN-26", EstimationRule.AVERAGE_LAST_3,
						List.of(consolidated("2026-07", "50000.00"), consolidated("2026-08", "41200.00"),
								consolidated("2026-09", "38750.00"), consolidated("2026-10", "45310.00")),
						"41753.33"),
				// 0,015 termina en medio centavo: HALF_UP sube
				Arguments.of("medio centavo", EstimationRule.AVERAGE_LAST_3,
						List.of(consolidated("2026-09", "0.01"), consolidated("2026-10", "0.02")), "0.02"),
				Arguments.of("medio centavo en 3", EstimationRule.AVERAGE_LAST_3,
						List.of(consolidated("2026-08", "10.00"), consolidated("2026-09", "10.00"),
								consolidated("2026-10", "10.01")),
						"10.00"),
				// una consolidación de cierre no entra en la base
				Arguments.of("el cierre no cuenta, último valor", EstimationRule.LAST_VALUE,
						List.of(consolidated("2026-09", "100.00"), consolidatedByClosing("2026-10", "0.00")),
						"100.00"),
				Arguments.of("el cierre no cuenta, promedio", EstimationRule.AVERAGE_LAST_3,
						List.of(consolidated("2026-08", "100.00"), consolidatedByClosing("2026-09", "10.00"),
								consolidated("2026-10", "200.00")),
						"150.00"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("estimates")
	void estimatesFromTheBase(String name, EstimationRule rule, List<EntryFacts> entries, String expected) {
		List<EntryFacts> shuffled = new ArrayList<>(entries);
		Collections.reverse(shuffled);

		assertThat(EstimationCalculator.plan(rule, entries).estimate()).isEqualByComparingTo(expected);
		// consolidar fuera de orden da el mismo resultado (S-05)
		assertThat(EstimationCalculator.plan(rule, shuffled).estimate()).isEqualByComparingTo(expected);
	}

	@Test
	void theEstimateHasTwoDecimals() {
		Plan plan = EstimationCalculator.plan(EstimationRule.AVERAGE_LAST_3,
				List.of(consolidated("2026-09", "100.00"), consolidated("2026-10", "200.00")));

		assertThat(plan.estimate().scale()).isEqualTo(2);
	}

	@Test
	void withMoreThanThreeConsolidationsOnlyTheThreeMostRecentCount() {
		Plan plan = EstimationCalculator.plan(EstimationRule.AVERAGE_LAST_3,
				List.of(consolidated("2026-06", "1000.00"), consolidated("2026-07", "50000.00"),
						consolidated("2026-08", "41200.00"), consolidated("2026-09", "38750.00"),
						consolidated("2026-10", "45310.00")));

		assertThat(plan.estimate()).isEqualByComparingTo("41753.33");
	}

	@Test
	void theSalaryExampleOfRn26MovesEveryPendingEntryFromTheNextPeriodOn() {
		// Sueldo A de 2026-12 consolidado en 1.850.000,00; las pendientes desde 2027-01 estaban en 1.800.000,00.
		List<EntryFacts> entries = new ArrayList<>();
		entries.add(consolidated("2026-12", "1850000.00"));
		for (String period : List.of("2027-01", "2027-02", "2027-03", "2028-11")) {
			entries.add(pending(period, "1800000.00"));
		}

		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE, entries);

		assertThat(plan.estimate()).isEqualByComparingTo("1850000.00");
		assertThat(periodsOf(plan)).containsExactly("2027-01", "2027-02", "2027-03", "2028-11");
		assertThat(plan.manualPolicyRequired()).isFalse();
		assertThat(plan.changes(null)).hasSize(4).allSatisfy(change -> {
			assertThat(change.budgeted()).isEqualByComparingTo("1850000.00");
			assertThat(change.manual()).isFalse();
		});
	}

	@Test
	void theTargetExcludesConsolidatedClosedAndNotLaterPeriods() {
		EntryFacts base = consolidated("2026-11", "500.00");
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(base, consolidated("2027-02", "700.00"), consolidated("2026-12", "999.00"),
						pending("2026-10", "100.00"), pending("2026-11", "100.00"), pending("2026-12", "100.00"),
						pendingInClosedPeriod("2026-09", "100.00"), pendingInClosedPeriod("2027-03", "100.00"),
						pending("2027-01", "100.00")));

		// La base es 2027-02 (la más reciente): solo destino posterior; ninguna lo es.
		assertThat(plan.basePeriod()).isEqualTo(ym("2027-02"));
		assertThat(plan.estimate()).isEqualByComparingTo("700.00");
		assertThat(plan.targets()).isEmpty();
	}

	@Test
	void theTargetIsOnlyPendingEntriesOfOpenPeriodsAfterTheFirstOfTheBase() {
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-10", "500.00"), consolidated("2026-11", "600.00"),
						pending("2026-10", "1.00"), pending("2026-11", "2.00"), pending("2026-12", "3.00"),
						pendingInClosedPeriod("2027-01", "4.00"), consolidated("2027-02", "5.00")));

		assertThat(plan.basePeriod()).isEqualTo(ym("2027-02"));
		assertThat(plan.targets()).isEmpty();

		Plan withoutLater = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-10", "500.00"), consolidated("2026-11", "600.00"),
						pending("2026-10", "1.00"), pending("2026-11", "2.00"), pending("2026-12", "3.00"),
						pendingInClosedPeriod("2027-01", "4.00"), pending("2027-02", "5.00")));

		assertThat(withoutLater.basePeriod()).isEqualTo(ym("2026-11"));
		assertThat(periodsOf(withoutLater)).containsExactly("2026-12", "2027-02");
	}

	@Test
	void consolidatingAnEarlierMonthAfterALaterOneUsesTheLaterAsTheFirstOfTheBase() {
		// Noviembre ya estaba consolidada en 210.000; ahora se consolida octubre en 190.000.
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-10", "190000.00"), consolidated("2026-11", "210000.00"),
						pending("2026-12", "210000.00"), pending("2027-01", "210000.00")));

		assertThat(plan.basePeriod()).isEqualTo(ym("2026-11"));
		assertThat(plan.estimate()).isEqualByComparingTo("210000.00");
		assertThat(plan.targets()).noneMatch(target -> target.period().equals(ym("2026-11")));
		assertThat(plan.targets()).noneMatch(target -> target.changes());
		assertThat(plan.changes(null)).isEmpty();
	}

	@Test
	void partialEntriesAreInTheTarget() {
		// Una parcial es una pendiente con movimientos: para la regla es una pendiente más (S-06).
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-10", "300.00"), pending("2026-11", "500.00")));

		assertThat(periodsOf(plan)).containsExactly("2026-11");
		assertThat(plan.changes(null)).containsExactly(new Change(plan.targets().getFirst().entryId(),
				amount("300.00"), false));
	}

	@Test
	void anInstallmentPlanEndsAtItsLastInstallment() {
		// 3 cuotas: las partidas existentes son las únicas que se tocan, no hay nada después de la última.
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-10", "12000.00"), pending("2026-11", "10000.00"),
						pending("2026-12", "10000.00")));

		assertThat(periodsOf(plan)).containsExactly("2026-11", "2026-12");
	}

	@Test
	void theVisaExampleKeepingTheEditedEntry() {
		// Diciembre editada a 300.000; se consolida noviembre en 210.000 (último valor).
		EntryFacts december = edited("2026-12", "300000.00");
		EntryFacts january = pending("2027-01", "200000.00");
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-11", "210000.00"), december, january));

		assertThat(plan.manualPolicyRequired()).isTrue();
		assertThat(plan.changes(ManualEntriesPolicy.KEEP)).containsExactly(
				new Change(january.id(), amount("210000.00"), false));
	}

	@Test
	void theVisaExampleOverwritingTheEditedEntry() {
		EntryFacts december = edited("2026-12", "300000.00");
		EntryFacts january = pending("2027-01", "200000.00");
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-11", "210000.00"), december, january));

		assertThat(plan.changes(ManualEntriesPolicy.OVERWRITE)).containsExactly(
				new Change(december.id(), amount("210000.00"), false),
				new Change(january.id(), amount("210000.00"), false));
	}

	@Test
	void withoutPolicyAndWithEditedEntriesTheChangesCannotBeComputed() {
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-11", "210000.00"), edited("2026-12", "300000.00")));

		assertThatThrownBy(() -> plan.changes(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anEditedEntryAlreadyAtTheEstimateStillRequiresPolicyAndLosesTheMarkWithOverwrite() {
		EntryFacts december = edited("2026-12", "210000.00");
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-11", "210000.00"), december));

		assertThat(plan.manualPolicyRequired()).isTrue();
		assertThat(plan.targets().getFirst().changes()).isFalse();
		assertThat(plan.changes(ManualEntriesPolicy.KEEP)).isEmpty();
		assertThat(plan.changes(ManualEntriesPolicy.OVERWRITE)).containsExactly(
				new Change(december.id(), amount("210000.00"), false));
	}

	@Test
	void entriesAlreadyAtTheEstimateDoNotChange() {
		Plan plan = EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(consolidated("2026-11", "100.00"), pending("2026-12", "100.00")));

		assertThat(plan.targets()).hasSize(1);
		assertThat(plan.targets().getFirst().changes()).isFalse();
		assertThat(plan.changes(null)).isEmpty();
	}

	@Test
	void withoutABaseThereIsNoPlan() {
		assertThatThrownBy(() -> EstimationCalculator.plan(EstimationRule.LAST_VALUE,
				List.of(pending("2026-11", "100.00"), consolidatedByClosing("2026-10", "5.00"))))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
