package com.smartcoin.movement.web;

import com.smartcoin.movement.service.MovementService.Registered;
import com.smartcoin.period.web.PeriodViewResponse.PeriodEntry;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MovementRegistered", description = "Un movimiento recién registrado y su partida con los valores "
		+ "derivados ya actualizados (real, pendiente, estimado y estado), calculados por el backend.")
public record MovementRegisteredResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		MovementResponse movement,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "La partida, con la misma forma que en la vista del mes. Sigue pendiente (RN-23).")
		PeriodEntry entry) {

	static MovementRegisteredResponse from(Registered registered) {
		return new MovementRegisteredResponse(MovementResponse.from(registered.movement()),
				PeriodEntry.from(registered.entry()));
	}
}
