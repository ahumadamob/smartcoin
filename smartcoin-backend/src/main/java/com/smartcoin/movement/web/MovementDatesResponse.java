package com.smartcoin.movement.web;

import java.time.LocalDate;

import com.smartcoin.movement.service.MovementService.MovementDates;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MovementDates", description = "Entre qué fechas admite un movimiento una partida con una cuenta "
		+ "(RN-21). Si `earliestDate` es posterior a `latestDate`, todavía no admite ninguno.")
public record MovementDatesResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-11-21",
				description = "La fecha más temprana: la más tardía entre el inicio de la ventana de anticipación "
						+ "y la apertura de la cuenta.")
		LocalDate earliestDate,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-11-30",
				description = "La fecha más tardía: hoy, según el reloj del backend (no se admiten fechas futuras).")
		LocalDate latestDate,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "10",
				description = "Ventana de anticipación en días: cuántos días antes del inicio del período de la "
						+ "partida se puede fechar un movimiento.")
		int earlyDays) {

	static MovementDatesResponse from(MovementDates dates) {
		return new MovementDatesResponse(dates.earliestDate(), dates.latestDate(), dates.earlyDays());
	}
}
