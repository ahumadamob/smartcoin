package com.smartcoin.entry.web;

import com.smartcoin.entry.domain.ManualEntriesPolicy;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ConsolidateRequest", description = "Cuerpo opcional de la consolidación (RN-26).")
public record ConsolidateRequest(

		@Schema(nullable = true, description = "Qué hacer con las partidas editadas de los meses siguientes: "
				+ "KEEP (respetarlas: no cambian y siguen editadas) u OVERWRITE (pisarlas: toman la estimación y "
				+ "dejan de estar editadas). Una sola elección para todas. Hace falta solo si hay editadas en el "
				+ "destino; si no, se ignora.")
		ManualEntriesPolicy manualEntriesPolicy) {
}
