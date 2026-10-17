package com.smartcoin.period.web;

import java.util.List;

import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.period.service.ClosingService.Resolution;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Schema(name = "ClosingPreviewRequest", description = "Cuerpo opcional de la simulación del cierre (RN-43). Sin "
		+ "cuerpo, la simulación es el diagnóstico de HU-30. Los saldos reales (HU-32) todavía no se reciben: si "
		+ "llegan, se ignoran.")
public record ClosingPreviewRequest(

		@ArraySchema(arraySchema = @Schema(nullable = true, description = "Las resoluciones elegidas, a lo sumo una "
				+ "por partida: puede faltar alguna pendiente (se informa en `unresolvedEntryIds`). Una partida "
				+ "repetida responde 400. Una partida que no es del período o no está pendiente no es un error: se "
				+ "informa en `invalidResolutions` y no se aplica."))
		@Valid
		List<@NotNull(message = "La resolución es obligatoria.") @Valid ClosingResolutionChoice> resolutions) {

	@Schema(name = "ClosingResolutionChoice", description = "Qué hacer con una partida pendiente al cerrar (RN-40).")
	public record ClosingResolutionChoice(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512")
			@NotNull(message = "La partida es obligatoria.")
			Long entryId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "CARRY_OVER (Postergar saldo): se consolida con su real y, si tenía pendiente, "
							+ "crea en el mes siguiente una partida por ese pendiente. CLOSE_AS_IS (Cerrar con lo "
							+ "registrado): se consolida con su real, que puede ser 0, y el pendiente se descarta.")
			@NotNull(message = "La resolución es obligatoria.")
			ClosingResolution resolution) {
	}

	/** Las resoluciones como las recibe el servicio; sin cuerpo o sin lista, ninguna. */
	static List<Resolution> resolutionsOf(ClosingPreviewRequest request) {
		if (request == null || request.resolutions() == null) {
			return List.of();
		}
		return request.resolutions().stream()
				.map(choice -> new Resolution(choice.entryId(), choice.resolution()))
				.toList();
	}
}
