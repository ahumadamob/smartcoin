package com.smartcoin.entry.web;

import java.math.BigDecimal;

import com.smartcoin.entry.service.QuickSettleService.Result;
import com.smartcoin.movement.web.MovementResponse;
import com.smartcoin.period.web.PeriodViewResponse.PeriodEntry;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "QuickSettle", description = "Resultado del pago rápido (RN-24): el movimiento creado y la partida "
		+ "ya consolidada, con lo que propagó la consolidación (RN-26).")
public record QuickSettleResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "El movimiento creado, por el pendiente.")
		MovementResponse movement,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "La partida ya consolidada, con la forma de la vista del mes.")
		PeriodEntry entry,

		@Schema(nullable = true, example = "1850000.00",
				description = "Nueva estimación E, ahora monto vigente del Concepto. Nulo si la partida no tiene "
						+ "Concepto y no propagó (RN-28).")
		BigDecimal estimate,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "24",
				description = "Cuántas partidas de los meses siguientes cambiaron.")
		int updatedEntries) {

	static QuickSettleResponse from(Result result) {
		return new QuickSettleResponse(MovementResponse.from(result.movement()),
				PeriodEntry.from(result.consolidation().entry()), result.consolidation().estimate(),
				result.consolidation().updatedEntries());
	}
}
