package com.smartcoin.period.web;

import java.math.BigDecimal;
import java.util.List;

import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.period.service.ClosingService.RealBalance;
import com.smartcoin.period.service.ClosingService.Resolution;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

@Schema(name = "ClosingPreviewRequest", description = "Cuerpo opcional de la simulación del cierre (RN-43). Sin "
		+ "cuerpo, la simulación es el diagnóstico de HU-30.")
public record ClosingPreviewRequest(

		@ArraySchema(arraySchema = @Schema(nullable = true, description = "Las resoluciones elegidas, a lo sumo una "
				+ "por partida: puede faltar alguna pendiente (se informa en `unresolvedEntryIds`). Una partida "
				+ "repetida responde 400. Una partida que no es del período o no está pendiente no es un error: se "
				+ "informa en `invalidResolutions` y no se aplica."))
		@Valid
		List<@NotNull(message = "La resolución es obligatoria.") @Valid ClosingResolutionChoice> resolutions,

		@ArraySchema(arraySchema = @Schema(nullable = true, description = "Los saldos reales informados, a lo sumo "
				+ "uno por cuenta: puede faltar alguna cuenta (se informa en `missingRealBalanceAccountIds`). Una "
				+ "cuenta repetida responde 400. Un saldo para una cuenta que no se concilia en el período (abierta "
				+ "después de su último día, de otro usuario o inexistente) no es un error: se informa en "
				+ "`invalidBalances` y no se aplica."))
		List<@NotNull(message = "El saldo real es obligatorio.") @Valid RealBalanceEntry> realBalances) {

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

	@Schema(name = "RealBalanceEntry", description = "El saldo real de una cuenta al último día del período: el que "
			+ "figura en el banco o la billetera (RN-39).")
	public record RealBalanceEntry(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
			@NotNull(message = "La cuenta es obligatoria.")
			Long accountId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "509800.00",
					description = "Con hasta 2 decimales (RN-03). Puede ser 0 o negativo.")
			@NotNull(message = "El saldo real es obligatorio.")
			@Digits(integer = 17, fraction = 2,
					message = "El saldo real tiene hasta 17 enteros y 2 decimales.")
			BigDecimal amount) {
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

	/** Los saldos reales como los recibe el servicio; sin cuerpo o sin lista, ninguno. */
	static List<RealBalance> realBalancesOf(ClosingPreviewRequest request) {
		if (request == null || request.realBalances() == null) {
			return List.of();
		}
		return request.realBalances().stream()
				.map(balance -> new RealBalance(balance.accountId(), balance.amount()))
				.toList();
	}
}
