package com.smartcoin.entry.web;

import java.math.BigDecimal;

import com.smartcoin.entry.service.ConsolidationService.Result;
import com.smartcoin.period.web.PeriodViewResponse.PeriodEntry;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Consolidation", description = "Resultado de consolidar una partida (RN-25, RN-26).")
public record ConsolidationResponse(

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

	static ConsolidationResponse from(Result result) {
		return new ConsolidationResponse(PeriodEntry.from(result.entry()), result.estimate(),
				result.updatedEntries());
	}
}
