package com.smartcoin.entry.domain;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/**
 * Qué pasa al eliminar una partida (RN-30, RN-31, RN-32): qué partidas entran en el alcance, cuáles lo impiden y
 * cómo queda el Concepto. Regla pura: recibe valores y devuelve resultados, sin consultar nada. La usan por igual la
 * vista previa y la eliminación, así el diálogo nunca promete algo que la eliminación rechace.
 *
 * <p>No decide el período cerrado (RN-09): lo controla el servicio sobre la partida elegida. En un período abierto
 * todos los posteriores también lo están (RN-08).
 */
public final class EntryDeletionPlanner {

	/** Por qué una partida impide eliminar. Una consolidada siempre tiene movimientos, pero se informa como consolidada. */
	public enum BlockReason {
		CONSOLIDATED, HAS_MOVEMENTS
	}

	/** Qué pasa con el Concepto al eliminar. */
	public enum ItemOutcome {
		/** El Concepto sigue igual. */
		KEEPS_ITEM,
		/** El Concepto sigue, pero su fin pasa al mes anterior ({@link Plan#newEndPeriod()}). */
		ENDS_ITEM,
		/** El Concepto se elimina: no le queda ninguna partida ni nada por generar. */
		REMOVES_ITEM
	}

	/**
	 * Una partida a considerar. {@code hasMovements} solo importa en las pendientes que pueden entrar en el alcance
	 * (período igual o posterior al de la partida elegida): en las demás no se mira.
	 */
	public record Candidate(long id, YearMonth period, boolean consolidated, boolean hasMovements) {
	}

	/**
	 * Lo que se sabe del Concepto.
	 *
	 * @param endPeriod      fin, o {@code null} si no tiene
	 * @param generatedUntil último período ya procesado por la generación (RN-13), o {@code null}
	 */
	public record ItemFacts(YearMonth endPeriod, YearMonth generatedUntil) {

		/** El Concepto tiene fin y ya generó hasta él: el horizonte no le va a crear ninguna partida más. */
		boolean hasNothingLeftToGenerate() {
			return endPeriod != null && generatedUntil != null && !generatedUntil.isBefore(endPeriod);
		}
	}

	/** Una partida que impide eliminar. */
	public record Blocker(long entryId, YearMonth period, BlockReason reason) {
	}

	/**
	 * @param toDelete     las partidas del alcance, en orden de período; no se eliminan si hay impedimentos
	 * @param blockers     las partidas del alcance que impiden eliminar, en orden de período
	 * @param itemOutcome  qué pasa con el Concepto, o {@code null} si la partida no tiene Concepto
	 * @param newEndPeriod fin nuevo del Concepto; solo con {@link ItemOutcome#ENDS_ITEM}
	 */
	public record Plan(List<Candidate> toDelete, List<Blocker> blockers, ItemOutcome itemOutcome,
			YearMonth newEndPeriod) {

		public boolean allowed() {
			return blockers.isEmpty();
		}

		public List<Long> toDeleteIds() {
			return toDelete.stream().map(Candidate::id).toList();
		}

		public List<Long> blockerIds() {
			return blockers.stream().map(Blocker::entryId).toList();
		}

		/** El motivo que da el código del error (consolidada antes que movimientos), o {@code null} si no hay. */
		public BlockReason primaryReason() {
			if (blockers.isEmpty()) {
				return null;
			}
			return blockers.stream().anyMatch(blocker -> blocker.reason() == BlockReason.CONSOLIDATED)
					? BlockReason.CONSOLIDATED : BlockReason.HAS_MOVEMENTS;
		}

		public long count(BlockReason reason) {
			return blockers.stream().filter(blocker -> blocker.reason() == reason).count();
		}
	}

	private static final Comparator<Candidate> BY_PERIOD = Comparator.comparing(Candidate::period)
			.thenComparingLong(Candidate::id);

	private EntryDeletionPlanner() {
	}

	/** RN-30: una partida sin Concepto. Debe estar pendiente y sin movimientos. */
	public static Plan forEntryWithoutItem(Candidate entry) {
		return new Plan(List.of(entry), blockersOf(List.of(entry)), null, null);
	}

	/**
	 * RN-31: una partida recurrente.
	 *
	 * <ul>
	 * <li>{@code ONLY_THIS}: solo la elegida. El Concepto sigue igual, salvo que no le quede ninguna partida y no
	 * tenga nada por generar: entonces desaparece, porque ya no habría forma de darlo de baja.</li>
	 * <li>{@code THIS_AND_FUTURE}: la elegida y todas las del Concepto con período posterior. El fin pasa al mes
	 * anterior al de la elegida y, si no queda ninguna partida, el Concepto desaparece. Las anteriores no se tocan.</li>
	 * </ul>
	 *
	 * @param itemEntries todas las partidas del Concepto, de cualquier estado y período, incluida la elegida
	 */
	public static Plan forRecurring(DeletionScope scope, long entryId, ItemFacts item, List<Candidate> itemEntries) {
		Candidate chosen = itemEntries.stream().filter(entry -> entry.id() == entryId).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("La partida " + entryId + " no es del Concepto."));
		return switch (scope) {
			case ONLY_THIS -> {
				boolean othersRemain = itemEntries.stream().anyMatch(entry -> entry.id() != entryId);
				ItemOutcome outcome = !othersRemain && item.hasNothingLeftToGenerate() ? ItemOutcome.REMOVES_ITEM
						: ItemOutcome.KEEPS_ITEM;
				yield new Plan(List.of(chosen), blockersOf(List.of(chosen)), outcome, null);
			}
			case THIS_AND_FUTURE -> {
				YearMonth from = chosen.period();
				List<Candidate> inScope = itemEntries.stream().filter(entry -> !entry.period().isBefore(from))
						.sorted(BY_PERIOD).toList();
				boolean earlierRemain = itemEntries.stream().anyMatch(entry -> entry.period().isBefore(from));
				yield earlierRemain
						? new Plan(inScope, blockersOf(inScope), ItemOutcome.ENDS_ITEM, from.minusMonths(1))
						: new Plan(inScope, blockersOf(inScope), ItemOutcome.REMOVES_ITEM, null);
			}
		};
	}

	private static List<Blocker> blockersOf(List<Candidate> inScope) {
		return inScope.stream().sorted(BY_PERIOD).filter(entry -> entry.consolidated() || entry.hasMovements())
				.map(entry -> new Blocker(entry.id(), entry.period(),
						entry.consolidated() ? BlockReason.CONSOLIDATED : BlockReason.HAS_MOVEMENTS))
				.toList();
	}
}
