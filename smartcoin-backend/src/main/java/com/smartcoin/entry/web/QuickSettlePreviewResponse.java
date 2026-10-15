package com.smartcoin.entry.web;

import java.math.BigDecimal;

import com.smartcoin.entry.domain.QuickSettleBlockReason;
import com.smartcoin.entry.service.QuickSettleService.Preview;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "QuickSettlePreview", description = "Qué pasaría al pagar o cobrar todo lo pendiente de una partida "
		+ "(RN-24), sin modificar nada. La consolidación sale del mismo plan que usa `POST "
		+ "/api/entries/{id}/quick-settle`, suponiendo el movimiento por el pendiente ya registrado. No depende de la "
		+ "fecha ni de la cuenta. Si no se puede, `allowed` es falso y `blockedBy` y `blockedDetail` dicen por qué.")
public record QuickSettlePreviewResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512")
		Long entryId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Si se puede pagar o cobrar todo ahora. Falso: `blockedBy` dice por qué.")
		boolean allowed,

		@Schema(nullable = true, description = "Por qué no se puede. PERIOD_CLOSED: el período está cerrado. "
				+ "ENTRY_NOT_PENDING: ya está consolidada. NOTHING_PENDING: no hay pendiente. Se informa el primero "
				+ "que falla, en ese orden.")
		QuickSettleBlockReason blockedBy,

		@Schema(nullable = true, description = "Explicación en español de `blockedBy`.")
		String blockedDetail,

		@Schema(nullable = true, example = "1300000.00",
				description = "El monto del movimiento que se registraría: el pendiente de hoy. Nulo si no se puede.")
		BigDecimal amount,

		@Schema(nullable = true, description = "La consolidación que seguiría al movimiento, con la forma de "
				+ "`GET /api/entries/{id}/consolidation-preview`: su `actualAmount` es el real de hoy más `amount`, "
				+ "que es el monto que quedaría consolidado. Nulo si no se puede.")
		ConsolidationPreviewResponse consolidation) {

	static QuickSettlePreviewResponse from(Preview preview) {
		return new QuickSettlePreviewResponse(preview.entryId(), preview.allowed(), preview.blockedBy(),
				preview.blockedDetail(), preview.amount(),
				preview.consolidation() == null ? null : ConsolidationPreviewResponse.from(preview.consolidation()));
	}
}
