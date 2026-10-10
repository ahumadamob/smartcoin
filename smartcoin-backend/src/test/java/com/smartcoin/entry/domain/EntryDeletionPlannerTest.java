package com.smartcoin.entry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.smartcoin.entry.domain.EntryDeletionPlanner.BlockReason;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Blocker;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Candidate;
import com.smartcoin.entry.domain.EntryDeletionPlanner.ItemFacts;
import com.smartcoin.entry.domain.EntryDeletionPlanner.ItemOutcome;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RN-30, RN-31, RN-32: qué partidas entran en el alcance, cuáles lo impiden y cómo queda el Concepto. Las partidas
 * se escriben como una lista de períodos; {@code :C} marca una consolidada y {@code :M} una con movimientos. El id de
 * cada una es su posición en la lista, empezando en 1.
 */
class EntryDeletionPlannerTest {

	private static YearMonth ym(String text) {
		return YearMonth.parse(text);
	}

	private static YearMonth ymOrNull(String text) {
		return text == null ? null : YearMonth.parse(text);
	}

	private static List<Candidate> entries(String spec) {
		String[] tokens = spec.trim().split("\\s+");
		List<Candidate> list = new ArrayList<>();
		for (int i = 0; i < tokens.length; i++) {
			String[] parts = tokens[i].split(":");
			String flag = parts.length > 1 ? parts[1] : "";
			list.add(new Candidate(i + 1, ym(parts[0]), flag.equals("C"), flag.equals("M")));
		}
		return list;
	}

	private static long idOf(List<Candidate> entries, String period) {
		return entries.stream().filter(entry -> entry.period().equals(ym(period))).findFirst().orElseThrow().id();
	}

	private static List<String> periods(List<Candidate> list) {
		return list.stream().map(entry -> entry.period().toString()).toList();
	}

	private static List<String> split(String text) {
		return text == null ? List.of() : Arrays.asList(text.trim().split("\\s+"));
	}

	private static Plan recurring(DeletionScope scope, String chosen, String entriesSpec, String end,
			String generatedUntil) {
		List<Candidate> list = entries(entriesSpec);
		return EntryDeletionPlanner.forRecurring(scope, idOf(list, chosen),
				new ItemFacts(ymOrNull(end), ymOrNull(generatedUntil)), list);
	}

	// ---------------------------------------------------------------- Este mes y los siguientes

	@ParameterizedTest(name = "{0}: elegida {1} → elimina {4}; {5}, fin {6}")
	@CsvSource(nullValues = "-", delimiter = '|', value = {
			// P igual al inicio del Concepto: desaparece.
			"mensual | 2026-10 | 2026-10 2026-11 2026-12 2027-01 2027-02 | 2027-02 | 2026-10 2026-11 2026-12 2027-01 2027-02 | REMOVES_ITEM | -",
			// P posterior: el fin pasa al mes anterior y las anteriores no se tocan.
			"mensual | 2026-12 | 2026-10 2026-11 2026-12 2027-01 2027-02 | 2027-02 | 2026-12 2027-01 2027-02 | ENDS_ITEM | 2026-11",
			// Cruce de año hacia atrás: el fin es diciembre del año anterior.
			"mensual | 2027-01 | 2026-10 2026-11 2026-12 2027-01 2027-02 | 2027-02 | 2027-01 2027-02 | ENDS_ITEM | 2026-12",
			// La última partida sola.
			"mensual | 2027-02 | 2026-10 2026-11 2026-12 2027-01 2027-02 | 2027-02 | 2027-02 | ENDS_ITEM | 2027-01",
			// Sin fin propio: el horizonte ya generó hasta 2028-10.
			"sin fin | 2026-12 | 2026-10 2026-11 2026-12 2027-01 | 2028-10 | 2026-12 2027-01 | ENDS_ITEM | 2026-11",
			// Bimestral: el mes anterior a P no tiene partida y igual es el fin.
			"bimestral | 2027-02 | 2026-10 2026-12 2027-02 2027-04 | 2027-04 | 2027-02 2027-04 | ENDS_ITEM | 2027-01",
			// Plan de cuotas: el fin guardado es el calculado; el plan se recorta, no se completa.
			"cuotas | 2027-01 | 2026-10 2026-11 2026-12 2027-01 2027-02 2027-03 2027-04 2027-05 2027-06 | 2027-06"
					+ " | 2027-01 2027-02 2027-03 2027-04 2027-05 2027-06 | ENDS_ITEM | 2026-12",
			// Las anteriores a P ya habían sido eliminadas: no queda ninguna y el Concepto desaparece aunque P no sea el inicio.
			"sin anteriores | 2026-12 | 2026-12 2027-01 | 2027-01 | 2026-12 2027-01 | REMOVES_ITEM | -",
			// Una sola partida.
			"una | 2026-10 | 2026-10 | 2026-10 | 2026-10 | REMOVES_ITEM | -",
	})
	void thisAndFutureDeletesFromThePeriodAndEndsTheItem(String name, String chosen, String entriesSpec,
			String generatedUntil, String expectedDeleted, ItemOutcome outcome, String newEnd) {
		Plan plan = recurring(DeletionScope.THIS_AND_FUTURE, chosen, entriesSpec, generatedUntil, generatedUntil);

		assertThat(plan.allowed()).isTrue();
		assertThat(periods(plan.toDelete())).containsExactlyElementsOf(split(expectedDeleted));
		assertThat(plan.itemOutcome()).isEqualTo(outcome);
		assertThat(plan.newEndPeriod()).isEqualTo(ymOrNull(newEnd));
		assertThat(plan.blockers()).isEmpty();
		assertThat(plan.primaryReason()).isNull();
	}

	@Test
	void entriesBeforeThePeriodAreNeverInTheScopeNorBlockNothing() {
		// Una consolidada y una con movimientos anteriores a P no impiden ni se eliminan.
		Plan plan = recurring(DeletionScope.THIS_AND_FUTURE, "2026-12", "2026-10:C 2026-11:M 2026-12 2027-01", null,
				"2027-01");

		assertThat(plan.allowed()).isTrue();
		assertThat(periods(plan.toDelete())).containsExactly("2026-12", "2027-01");
		assertThat(plan.itemOutcome()).isEqualTo(ItemOutcome.ENDS_ITEM);
		assertThat(plan.newEndPeriod()).isEqualTo(ym("2026-11"));
	}

	@Test
	void aConsolidatedEntryInTheScopeBlocksEverything() {
		Plan plan = recurring(DeletionScope.THIS_AND_FUTURE, "2026-11", "2026-10 2026-11 2026-12:C 2027-01", null,
				"2027-01");

		assertThat(plan.allowed()).isFalse();
		assertThat(plan.blockers()).containsExactly(new Blocker(3, ym("2026-12"), BlockReason.CONSOLIDATED));
		assertThat(plan.primaryReason()).isEqualTo(BlockReason.CONSOLIDATED);
		assertThat(plan.blockerIds()).containsExactly(3L);
	}

	@Test
	void anEntryWithMovementsInTheScopeBlocksEverything() {
		Plan plan = recurring(DeletionScope.THIS_AND_FUTURE, "2026-11", "2026-10 2026-11 2026-12 2027-01:M", null,
				"2027-01");

		assertThat(plan.allowed()).isFalse();
		assertThat(plan.blockers()).containsExactly(new Blocker(4, ym("2027-01"), BlockReason.HAS_MOVEMENTS));
		assertThat(plan.primaryReason()).isEqualTo(BlockReason.HAS_MOVEMENTS);
	}

	@Test
	void consolidatedAndWithMovementsAtTheSameTimeReportsAllAndTheCodeIsConsolidated() {
		Plan plan = recurring(DeletionScope.THIS_AND_FUTURE, "2026-11", "2026-10 2026-11:M 2026-12:C 2027-01:M 2027-02",
				null, "2027-02");

		assertThat(plan.allowed()).isFalse();
		assertThat(plan.blockers()).containsExactly(new Blocker(2, ym("2026-11"), BlockReason.HAS_MOVEMENTS),
				new Blocker(3, ym("2026-12"), BlockReason.CONSOLIDATED),
				new Blocker(4, ym("2027-01"), BlockReason.HAS_MOVEMENTS));
		assertThat(plan.primaryReason()).isEqualTo(BlockReason.CONSOLIDATED);
		assertThat(plan.count(BlockReason.CONSOLIDATED)).isEqualTo(1);
		assertThat(plan.count(BlockReason.HAS_MOVEMENTS)).isEqualTo(2);
	}

	@Test
	void aConsolidatedEntryAlwaysReportsConsolidatedEvenIfItAlsoHasMovements() {
		List<Candidate> list = List.of(new Candidate(1, ym("2026-10"), true, true));

		Plan plan = EntryDeletionPlanner.forRecurring(DeletionScope.THIS_AND_FUTURE, 1,
				new ItemFacts(null, ym("2026-10")), list);

		assertThat(plan.blockers()).containsExactly(new Blocker(1, ym("2026-10"), BlockReason.CONSOLIDATED));
	}

	@Test
	void theScopeIsOrderedByPeriodEvenIfTheInputIsNot() {
		List<Candidate> list = List.of(new Candidate(1, ym("2027-01"), false, false),
				new Candidate(2, ym("2026-10"), false, false), new Candidate(3, ym("2026-12"), false, false));

		Plan plan = EntryDeletionPlanner.forRecurring(DeletionScope.THIS_AND_FUTURE, 3, new ItemFacts(null, ym("2027-01")),
				list);

		assertThat(periods(plan.toDelete())).containsExactly("2026-12", "2027-01");
	}

	// ---------------------------------------------------------------- Solo este mes

	@Test
	void onlyThisDeletesJustThatEntryAndTheItemStaysTheSame() {
		Plan plan = recurring(DeletionScope.ONLY_THIS, "2026-12", "2026-10 2026-11 2026-12 2027-01 2027-02", "2027-02",
				"2027-02");

		assertThat(plan.allowed()).isTrue();
		assertThat(periods(plan.toDelete())).containsExactly("2026-12");
		assertThat(plan.itemOutcome()).isEqualTo(ItemOutcome.KEEPS_ITEM);
		assertThat(plan.newEndPeriod()).isNull();
	}

	@Test
	void onlyThisIgnoresTheStateOfTheOtherEntries() {
		Plan plan = recurring(DeletionScope.ONLY_THIS, "2026-12", "2026-10:C 2026-11:M 2026-12 2027-01:C", null,
				"2027-01");

		assertThat(plan.allowed()).isTrue();
		assertThat(plan.toDeleteIds()).containsExactly(3L);
	}

	@Test
	void onlyThisOnAConsolidatedEntryIsBlocked() {
		Plan plan = recurring(DeletionScope.ONLY_THIS, "2026-12", "2026-11 2026-12:C", null, "2026-12");

		assertThat(plan.allowed()).isFalse();
		assertThat(plan.blockers()).containsExactly(new Blocker(2, ym("2026-12"), BlockReason.CONSOLIDATED));
	}

	@Test
	void onlyThisOnAnEntryWithMovementsIsBlocked() {
		Plan plan = recurring(DeletionScope.ONLY_THIS, "2026-12", "2026-11 2026-12:M", null, "2026-12");

		assertThat(plan.blockers()).containsExactly(new Blocker(2, ym("2026-12"), BlockReason.HAS_MOVEMENTS));
		assertThat(plan.primaryReason()).isEqualTo(BlockReason.HAS_MOVEMENTS);
	}

	@ParameterizedTest(name = "fin {1}, generó hasta {2}: última partida → {3}")
	@CsvSource(nullValues = "-", delimiter = '|', value = {
			// Tiene fin y ya generó hasta él: no queda nada por generar, el Concepto desaparece.
			"plan de una cuota | 2026-12 | 2026-12 | REMOVES_ITEM",
			// Terminó antes del horizonte.
			"terminó antes del horizonte | 2026-12 | 2026-12 | REMOVES_ITEM",
			// Generó más allá del fin guardado (después de recortarlo): tampoco queda nada.
			"fin recortado | 2026-12 | 2027-03 | REMOVES_ITEM",
			// Sin fin: el horizonte le va a generar partidas al avanzar.
			"sin fin | - | 2028-10 | KEEPS_ITEM",
			// Con fin posterior al horizonte: todavía le faltan partidas.
			"fin después del horizonte | 2030-01 | 2028-10 | KEEPS_ITEM",
			// Nunca generó.
			"sin generar | 2026-12 | - | KEEPS_ITEM",
	})
	void onlyThisOnTheLastEntryRemovesTheItemOnlyIfThereIsNothingLeftToGenerate(String name, String end,
			String generatedUntil, ItemOutcome expected) {
		Plan plan = recurring(DeletionScope.ONLY_THIS, "2026-12", "2026-12", end, generatedUntil);

		assertThat(plan.toDeleteIds()).containsExactly(1L);
		assertThat(plan.itemOutcome()).isEqualTo(expected);
	}

	@Test
	void onlyThisKeepsTheItemWhileOtherEntriesRemainEvenIfNothingIsLeftToGenerate() {
		Plan plan = recurring(DeletionScope.ONLY_THIS, "2026-12", "2026-12 2027-01", "2027-01", "2027-01");

		assertThat(plan.itemOutcome()).isEqualTo(ItemOutcome.KEEPS_ITEM);
	}

	@Test
	void onlyThisCountsConsolidatedEntriesAsRemainingEntries() {
		// Una consolidada de otro mes es una partida del Concepto: el Concepto no puede desaparecer.
		Plan plan = recurring(DeletionScope.ONLY_THIS, "2026-12", "2026-11:C 2026-12", "2026-12", "2026-12");

		assertThat(plan.itemOutcome()).isEqualTo(ItemOutcome.KEEPS_ITEM);
	}

	@Test
	void anEntryThatIsNotOfTheItemIsAProgrammingError() {
		assertThatThrownBy(() -> EntryDeletionPlanner.forRecurring(DeletionScope.ONLY_THIS, 99,
				new ItemFacts(null, null), entries("2026-10")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ---------------------------------------------------------------- Sin Concepto

	@Test
	void anEntryWithoutAnItemIsDeletedIfPendingAndWithoutMovements() {
		Plan plan = EntryDeletionPlanner.forEntryWithoutItem(new Candidate(5, ym("2026-11"), false, false));

		assertThat(plan.allowed()).isTrue();
		assertThat(plan.toDeleteIds()).containsExactly(5L);
		assertThat(plan.itemOutcome()).isNull();
		assertThat(plan.newEndPeriod()).isNull();
	}

	@Test
	void anEntryWithoutAnItemWithMovementsIsBlocked() {
		Plan plan = EntryDeletionPlanner.forEntryWithoutItem(new Candidate(5, ym("2026-11"), false, true));

		assertThat(plan.blockers()).containsExactly(new Blocker(5, ym("2026-11"), BlockReason.HAS_MOVEMENTS));
		assertThat(plan.primaryReason()).isEqualTo(BlockReason.HAS_MOVEMENTS);
	}

	@Test
	void aConsolidatedEntryWithoutAnItemIsBlocked() {
		Plan plan = EntryDeletionPlanner.forEntryWithoutItem(new Candidate(5, ym("2026-11"), true, true));

		assertThat(plan.blockers()).containsExactly(new Blocker(5, ym("2026-11"), BlockReason.CONSOLIDATED));
		assertThat(plan.primaryReason()).isEqualTo(BlockReason.CONSOLIDATED);
	}
}
