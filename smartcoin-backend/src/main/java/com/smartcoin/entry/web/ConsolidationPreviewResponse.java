package com.smartcoin.entry.web;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.entry.domain.ConsolidationBlockReason;
import com.smartcoin.entry.domain.EstimationCalculator.BaseEntry;
import com.smartcoin.entry.domain.EstimationCalculator.Target;
import com.smartcoin.entry.service.ConsolidationService.Preview;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ConsolidationPreview", description = "Qué pasaría al consolidar una partida (RN-27), sin modificar "
		+ "nada. Sale del mismo plan que usa `POST /api/entries/{id}/consolidate`. Si la partida no se puede "
		+ "consolidar, `allowed` es falso y `blockedBy` y `blockedDetail` dicen por qué; sin plan, `estimate`, "
		+ "`currentAmount` y `estimationRule` son nulos y `targets` está vacío. Una partida sin Concepto "
		+ "(`recurring` falso) se consolida sin propagar.")
public record ConsolidationPreviewResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512")
		Long entryId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-12",
				description = "Período de la partida.")
		YearMonth period,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Si viene de un Concepto: entonces propaga al consolidar.")
		boolean recurring,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Si se puede consolidar ahora. Falso: `blockedBy` dice por qué.")
		boolean allowed,

		@Schema(nullable = true, description = "Por qué no se puede consolidar. PERIOD_CLOSED: el período está "
				+ "cerrado. ENTRY_NOT_PENDING: ya está consolidada. CONSOLIDATION_REQUIRES_MOVEMENT: no tiene "
				+ "movimientos. Se informa el primero que falla, en ese orden.")
		ConsolidationBlockReason blockedBy,

		@Schema(nullable = true, description = "Explicación en español de `blockedBy`.")
		String blockedDetail,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1850000.00",
				description = "Monto real: la suma de sus movimientos. Es el monto que quedaría consolidado.")
		BigDecimal actualAmount,

		@Schema(nullable = true, description = "Regla de estimación del Concepto.")
		EstimationRule estimationRule,

		@Schema(nullable = true, example = "1850000.00",
				description = "Nueva estimación E: lo que pasaría a ser el monto vigente del Concepto.")
		BigDecimal estimate,

		@Schema(nullable = true, example = "1800000.00", description = "Monto vigente actual del Concepto.")
		BigDecimal currentAmount,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Las consolidaciones que entran en `estimate`, de la más reciente a la más antigua: "
						+ "una con «Último valor» y hasta 3 con «Promedio de los últimos 3» (las que haya si son "
						+ "menos). Incluye la partida que se consolida, con su monto real. Vacía si no hay plan.")
		List<EstimationBasis> estimateBasis,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Partidas de destino, por período: pendientes de períodos abiertos posteriores al de "
						+ "la primera consolidada de la base. Incluye las que ya valen E.")
		List<ConsolidationTarget> targets,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Hay partidas editadas en el destino: hay que elegir `manualEntriesPolicy` para "
						+ "consolidar (409 MANUAL_POLICY_REQUIRED si no).")
		boolean manualPolicyRequired) {

	@Schema(name = "EstimationBasis", description = "Una consolidación que entra en la nueva estimación.")
	public record EstimationBasis(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10")
			YearMonth period,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "45310.00",
					description = "Monto consolidado de esa partida.")
			BigDecimal amount) {

		static EstimationBasis from(BaseEntry entry) {
			return new EstimationBasis(entry.period(), entry.amount());
		}
	}

	@Schema(name = "ConsolidationTarget", description = "Una partida de destino de la propagación.")
	public record ConsolidationTarget(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "513")
			Long entryId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2027-01")
			YearMonth period,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1800000.00",
					description = "Presupuestado actual.")
			BigDecimal currentBudgetedAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1850000.00",
					description = "Presupuestado nuevo: E. En una editada, solo si se elige OVERWRITE.")
			BigDecimal newBudgetedAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Partida editada: el usuario fijó su presupuestado a mano.")
			boolean manual,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "El presupuestado cambiaría. En una editada, solo con OVERWRITE (que además le "
							+ "quita la marca de editada, aunque el monto coincida).")
			boolean changes) {

		static ConsolidationTarget from(Target target) {
			return new ConsolidationTarget(target.entryId(), target.period(), target.currentBudgeted(),
					target.newBudgeted(), target.manual(), target.changes());
		}
	}

	static ConsolidationPreviewResponse from(Preview preview) {
		return new ConsolidationPreviewResponse(preview.entryId(), preview.period(), preview.recurring(),
				preview.allowed(), preview.blockedBy(), preview.blockedDetail(), preview.actualAmount(),
				preview.estimationRule(), preview.estimate(), preview.currentAmount(),
				preview.estimateBasis().stream().map(EstimationBasis::from).toList(),
				preview.targets().stream().map(ConsolidationTarget::from).toList(), preview.manualPolicyRequired());
	}
}
