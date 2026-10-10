package com.smartcoin.entry.web;

import java.time.YearMonth;
import java.util.List;

import com.smartcoin.entry.domain.EntryDeletionPlanner.BlockReason;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Blocker;
import com.smartcoin.entry.domain.EntryDeletionPlanner.ItemOutcome;
import com.smartcoin.entry.domain.EntryDeletionPlanner.Plan;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.service.EntryService.DeletionPreview;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "EntryDeletionPreview", description = "Qué pasaría al eliminar una partida (HU-18, RN-30 a RN-32), "
		+ "sin modificar nada. Una partida sin Concepto trae solo `removal`; una recurrente, `onlyThis` y "
		+ "`thisAndFuture`. Es la misma regla que aplica la eliminación: si un plan dice `allowed`, "
		+ "`DELETE /api/entries/{id}` lo hace.")
public record EntryDeletionPreviewResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512")
		Long entryId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Si viene de un Concepto: entonces la eliminación exige un alcance.")
		boolean recurring,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		EntryOrigin origin,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-12",
				description = "Período de la partida.")
		YearMonth period,

		@Schema(nullable = true, description = "Eliminar la partida, si no tiene Concepto (RN-30).")
		DeletionPlan removal,

		@Schema(nullable = true, description = "«Solo este mes», si es recurrente (RN-31).")
		DeletionPlan onlyThis,

		@Schema(nullable = true, description = "«Este mes y los siguientes», si es recurrente (RN-31).")
		DeletionPlan thisAndFuture) {

	@Schema(name = "DeletionItemOutcome", description = "Qué pasa con el Concepto. KEEPS_ITEM: sigue igual. "
			+ "ENDS_ITEM: sigue, y su fin pasa al mes anterior. REMOVES_ITEM: se elimina, porque no le queda "
			+ "ninguna partida ni nada por generar.")
	public enum Outcome {
		KEEPS_ITEM, ENDS_ITEM, REMOVES_ITEM
	}

	@Schema(name = "DeletionBlockReason", description = "Por qué una partida impide eliminar. CONSOLIDATED: está "
			+ "consolidada (RN-32), aunque además tenga movimientos. HAS_MOVEMENTS: está pendiente pero tiene "
			+ "movimientos.")
	public enum Reason {
		CONSOLIDATED, HAS_MOVEMENTS
	}

	@Schema(name = "DeletionPlan", description = "Lo que haría un alcance.")
	public record DeletionPlan(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Si se puede eliminar: ninguna partida del alcance impide.")
			boolean allowed,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "3",
					description = "Cuántas partidas se eliminarían.")
			int entryCount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-12",
					description = "Período de la primera partida que se eliminaría.")
			YearMonth fromPeriod,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2027-02",
					description = "Período de la última partida que se eliminaría.")
			YearMonth toPeriod,

			@Schema(nullable = true, description = "Qué pasa con el Concepto; nulo si la partida no tiene Concepto.")
			Outcome itemOutcome,

			@Schema(nullable = true, type = "string", example = "2026-11",
					description = "Fin nuevo del Concepto; solo con ENDS_ITEM.")
			YearMonth newEndPeriod,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Las partidas del alcance que impiden eliminar, en orden de período.")
			List<DeletionBlocker> blockers) {

		static DeletionPlan from(Plan plan) {
			return new DeletionPlan(plan.allowed(), plan.toDelete().size(), plan.toDelete().getFirst().period(),
					plan.toDelete().getLast().period(), outcome(plan.itemOutcome()), plan.newEndPeriod(),
					plan.blockers().stream().map(DeletionBlocker::from).toList());
		}

		private static Outcome outcome(ItemOutcome outcome) {
			return outcome == null ? null : Outcome.valueOf(outcome.name());
		}
	}

	@Schema(name = "DeletionBlocker", description = "Una partida que impide eliminar.")
	public record DeletionBlocker(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "514")
			Long entryId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2027-01")
			YearMonth period,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			Reason reason) {

		static DeletionBlocker from(Blocker blocker) {
			return new DeletionBlocker(blocker.entryId(), blocker.period(), Reason.valueOf(blocker.reason().name()));
		}
	}

	public static EntryDeletionPreviewResponse from(DeletionPreview preview) {
		return new EntryDeletionPreviewResponse(preview.entryId(), preview.recurring(), preview.origin(),
				preview.period(), plan(preview.removal()), plan(preview.onlyThis()), plan(preview.thisAndFuture()));
	}

	private static DeletionPlan plan(Plan plan) {
		return plan == null ? null : DeletionPlan.from(plan);
	}
}
