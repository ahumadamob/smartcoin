package com.smartcoin.entry.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

import com.smartcoin.budgetitem.domain.EstimationRule;

/**
 * Propagar (RN-26): a partir de las partidas de un Concepto, la estimación E y las partidas de destino. Regla pura:
 * sin Spring ni JPA, recibe valores y devuelve un plan. La vista previa (RN-27) y la consolidación (RN-25) usan el
 * mismo plan.
 *
 * <p>Pasos: la base son las consolidadas sin resolución de cierre, de la más reciente a la más antigua por período
 * (S-04, S-05); E es el último valor o el promedio de hasta 3, redondeado a 2 decimales con {@code HALF_UP}; el
 * destino son las pendientes de períodos abiertos con período posterior al de la primera de la base (S-06).
 */
public final class EstimationCalculator {

	private static final int AVERAGE_SIZE = 3;

	private EstimationCalculator() {
	}

	/**
	 * Una partida del Concepto, con lo que la regla necesita.
	 *
	 * @param consolidated       si está consolidada; la que se está consolidando se informa ya consolidada, con su real
	 * @param consolidatedAmount monto consolidado, solo en una consolidada
	 * @param closingResolution  resolución de cierre, si se consolidó durante el cierre del mes (HU-31)
	 * @param periodOpen         si su período no está cerrado
	 * @param manual             si el usuario editó su presupuestado
	 * @param budgeted           presupuestado actual
	 */
	public record EntryFacts(long id, YearMonth period, boolean periodOpen, boolean consolidated,
			BigDecimal consolidatedAmount, ClosingResolution closingResolution, boolean manual,
			BigDecimal budgeted) {
	}

	/**
	 * Una partida de destino.
	 *
	 * @param newBudgeted lo que tomaría: E. En una editada, solo si se elige Pisar
	 * @param changes     si el presupuestado cambiaría. En una editada, solo con Pisar; con Pisar además pierde la
	 *                    marca de editada, aunque el monto coincida
	 */
	public record Target(long entryId, YearMonth period, BigDecimal currentBudgeted, BigDecimal newBudgeted,
			boolean manual, boolean changes) {
	}

	/** Lo que hace una partida de destino al aplicar el plan con una política. */
	public record Change(long entryId, BigDecimal budgeted, boolean manual) {
	}

	/** Una consolidación que entró en E: su período y su monto consolidado. */
	public record BaseEntry(YearMonth period, BigDecimal amount) {
	}

	/**
	 * @param estimate    E, que pasa a ser el monto vigente del Concepto
	 * @param basePeriod  período de la primera de la base: el destino es posterior
	 * @param basis       las consolidaciones que entraron en E, de la más reciente a la más antigua: una con el
	 *                    último valor, hasta 3 con el promedio
	 * @param targets     partidas de destino, por período
	 */
	public record Plan(BigDecimal estimate, YearMonth basePeriod, List<BaseEntry> basis, List<Target> targets) {

		/** Si hay editadas en el destino: hay que elegir política, aunque su monto ya sea E. */
		public boolean manualPolicyRequired() {
			return targets.stream().anyMatch(Target::manual);
		}

		/** Las partidas de destino que se editan al aplicar el plan, por período. Las que no cambian no figuran. */
		public List<Change> changes(ManualEntriesPolicy policy) {
			if (manualPolicyRequired() && policy == null) {
				throw new IllegalArgumentException("Hay partidas editadas en el destino: falta la política.");
			}
			return targets.stream().filter(target -> !target.manual() || policy == ManualEntriesPolicy.OVERWRITE)
					.filter(target -> target.manual() || target.changes())
					.map(target -> new Change(target.entryId(), estimate, false)).toList();
		}
	}

	/**
	 * @param entries todas las partidas del Concepto, en cualquier orden
	 * @throws IllegalArgumentException si ninguna es consolidada sin resolución de cierre: no hay base
	 */
	public static Plan plan(EstimationRule rule, List<EntryFacts> entries) {
		List<EntryFacts> base = entries.stream()
				.filter(entry -> entry.consolidated() && entry.closingResolution() == null)
				.sorted(Comparator.comparing(EntryFacts::period).reversed()).toList();
		if (base.isEmpty()) {
			throw new IllegalArgumentException("No hay partidas consolidadas en las que basar la estimación.");
		}
		YearMonth basePeriod = base.getFirst().period();
		List<EntryFacts> used = base.stream().limit(rule == EstimationRule.LAST_VALUE ? 1 : AVERAGE_SIZE).toList();
		BigDecimal estimate = switch (rule) {
			case LAST_VALUE -> used.getFirst().consolidatedAmount().setScale(2, RoundingMode.HALF_UP);
			case AVERAGE_LAST_3 -> average(used);
		};
		List<Target> targets = entries.stream()
				.filter(entry -> !entry.consolidated() && entry.periodOpen() && entry.period().isAfter(basePeriod))
				.sorted(Comparator.comparing(EntryFacts::period))
				.map(entry -> new Target(entry.id(), entry.period(), entry.budgeted(), estimate, entry.manual(),
						entry.budgeted().compareTo(estimate) != 0))
				.toList();
		List<BaseEntry> basis = used.stream()
				.map(entry -> new BaseEntry(entry.period(), entry.consolidatedAmount())).toList();
		return new Plan(estimate, basePeriod, basis, targets);
	}

	private static BigDecimal average(List<EntryFacts> entries) {
		BigDecimal sum = entries.stream().map(EntryFacts::consolidatedAmount).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		return sum.divide(BigDecimal.valueOf(entries.size()), 2, RoundingMode.HALF_UP);
	}
}
