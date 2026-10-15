package com.smartcoin.transfer.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.smartcoin.transfer.service.TransferService.NewTransfer;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Una transferencia entre dos cuentas propias (RN-36). El usuario sale del token. Si las dos "
		+ "cuentas son de la misma moneda, los dos montos tienen que ser iguales.")
public record TransferRequest(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12",
				description = "Cuenta de donde sale la plata, del usuario.")
		@NotNull(message = "La cuenta de origen es obligatoria.")
		Long sourceAccountId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "15",
				description = "Cuenta a donde entra la plata, del usuario y distinta de la de origen.")
		@NotNull(message = "La cuenta de destino es obligatoria.")
		Long targetAccountId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date", example = "2026-10-15",
				description = "Fecha de la transferencia. No posterior a hoy, ni anterior a la apertura de ninguna "
						+ "de las dos cuentas, y en un mes que sea un período abierto.")
		@NotNull(message = "La fecha es obligatoria.")
		LocalDate date,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, exclusiveMinimum = true, minimum = "0",
				example = "50000.00", description = "Monto que sale de la cuenta de origen, en su moneda. Mayor que "
						+ "0, con hasta 2 decimales.")
		@NotNull(message = "El monto de origen es obligatorio.")
		@DecimalMin(value = "0", inclusive = false, message = "El monto de origen tiene que ser mayor que 0.")
		@Digits(integer = 17, fraction = 2, message = "El monto de origen admite hasta 2 decimales.")
		BigDecimal sourceAmount,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, exclusiveMinimum = true, minimum = "0",
				example = "50000.00", description = "Monto que entra a la cuenta de destino, en su moneda. Mayor "
						+ "que 0, con hasta 2 decimales. Igual al de origen si las monedas coinciden.")
		@NotNull(message = "El monto de destino es obligatorio.")
		@DecimalMin(value = "0", inclusive = false, message = "El monto de destino tiene que ser mayor que 0.")
		@Digits(integer = 17, fraction = 2, message = "El monto de destino admite hasta 2 decimales.")
		BigDecimal targetAmount,

		@Schema(nullable = true, maxLength = 200, example = "Extracción para gastos de la semana",
				description = "Nota opcional. Se guarda sin espacios en los extremos; en blanco es sin nota.")
		@Size(max = 200, message = "La nota no puede superar los 200 caracteres.")
		String note) {

	NewTransfer toValues() {
		return new NewTransfer(sourceAccountId, targetAccountId, date, sourceAmount, targetAmount, note);
	}
}
