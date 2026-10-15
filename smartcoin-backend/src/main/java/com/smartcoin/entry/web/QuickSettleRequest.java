package com.smartcoin.entry.web;

import java.time.LocalDate;

import com.smartcoin.entry.domain.ManualEntriesPolicy;
import com.smartcoin.entry.service.QuickSettleService.Request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "QuickSettleRequest", description = "Cuerpo opcional del pago rápido (RN-24). Los tres campos son "
		+ "opcionales. El monto no se envía: es siempre el pendiente.")
public record QuickSettleRequest(

		@Schema(nullable = true, type = "string", format = "date", example = "2026-11-05",
				description = "Fecha del movimiento, con las mismas condiciones que al registrarlo (RN-21). Sin "
						+ "fecha, hoy según el reloj del backend.")
		LocalDate date,

		@Schema(nullable = true, example = "12", description = "Cuenta del movimiento, de la moneda de la partida. "
				+ "Sin cuenta, la de la partida.")
		Long accountId,

		@Schema(nullable = true, description = "Qué hacer con las partidas editadas de los meses siguientes: KEEP "
				+ "(respetarlas) u OVERWRITE (pisarlas). Hace falta solo si hay editadas en el destino; si no, se "
				+ "ignora.")
		ManualEntriesPolicy manualEntriesPolicy) {

	Request toRequest() {
		return new Request(date, accountId, manualEntriesPolicy);
	}
}
