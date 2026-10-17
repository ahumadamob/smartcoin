package com.smartcoin.period.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.service.ClosingService.ConsolidatedEntry;
import com.smartcoin.period.service.ClosingService.CreatedEntry;
import com.smartcoin.period.service.ClosingService.Result;
import com.smartcoin.period.service.ClosingService.SavedClosing;
import com.smartcoin.period.web.ClosingPreviewResponse.ClosingNewEntry;
import com.smartcoin.shared.domain.Currency;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ClosingResult", description = "Lo que aplicó el cierre de un período (RN-40), en su orden: las "
		+ "partidas pendientes consolidadas con su resolución, las partidas creadas en el período siguiente y los "
		+ "cierres de cuenta guardados. El período queda cerrado para siempre (D-23).")
public record ClosingResultResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10")
		YearMonth period,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Siempre CLOSED.")
		PeriodStatus status,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-11-02T14:30:00Z",
				description = "Fecha de cierre, en UTC. Es también la fecha de consolidación de las partidas que "
						+ "consolidó el cierre.")
		Instant closedAt,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-11",
				description = "El período siguiente: donde se crearon las partidas, y el próximo que se puede cerrar.")
		YearMonth nextPeriod,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Las partidas que estaban pendientes, consolidadas con su real y su resolución, en el "
						+ "orden de la simulación. No propagan ni cuentan para la estimación (RN-26).")
		List<ClosingConsolidatedEntry> consolidatedEntries,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Las partidas creadas en el período siguiente: primero las de saldo postergado y "
						+ "después las de diferencia de cierre, como en `newEntries` de la simulación.")
		List<ClosingCreatedEntry> createdEntries,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Un cierre de cuenta por cada cuenta conciliada, también con diferencia 0, en el orden "
						+ "de `accounts` de la simulación.")
		List<ClosingAccountClosing> accountClosings) {

	@Schema(name = "ClosingConsolidatedEntry", description = "Una partida consolidada por el cierre (RN-40, pasos 1 "
			+ "y 2).")
	public record ClosingConsolidatedEntry(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512")
			Long id,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Luz",
					description = "En una partida recurrente, el nombre de su Concepto.")
			String name,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			ClosingResolution resolution,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "20000.00",
					description = "Su real: 0 si no tuvo movimientos.")
			BigDecimal consolidatedAmount) {

		static ClosingConsolidatedEntry from(ConsolidatedEntry entry) {
			return new ClosingConsolidatedEntry(entry.entryId(), entry.name(), entry.resolution(),
					entry.consolidatedAmount());
		}
	}

	@Schema(name = "ClosingCreatedEntry", description = "Una partida creada por el cierre en el período siguiente, "
			+ "pendiente y sin editar.")
	public record ClosingCreatedEntry(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "940")
			Long id,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Sus datos, los mismos que anunciaba la simulación.")
			ClosingNewEntry entry) {

		static ClosingCreatedEntry from(CreatedEntry created) {
			return new ClosingCreatedEntry(created.id(), ClosingNewEntry.from(created.entry()));
		}
	}

	@Schema(name = "ClosingAccountClosing", description = "Un cierre de cuenta guardado (RN-40, paso 3).")
	public record ClosingAccountClosing(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "31")
			Long id,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
			Long accountId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
			String accountName,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512300.00")
			BigDecimal computedBalance,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "509800.00")
			BigDecimal realBalance,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "-2500.00",
					description = "Real menos calculado.")
			BigDecimal difference,

			@Schema(nullable = true, example = "941", description = "La partida de diferencia de cierre creada, que "
					+ "queda referida a este cierre de cuenta. Nula con diferencia 0.")
			Long differenceEntryId) {

		static ClosingAccountClosing from(SavedClosing closing) {
			var reconciliation = closing.reconciliation();
			return new ClosingAccountClosing(closing.id(), closing.account().id(), closing.account().name(),
					closing.account().currency(), reconciliation.computedBalance(), reconciliation.realBalance(),
					reconciliation.difference(), closing.differenceEntryId());
		}
	}

	static ClosingResultResponse from(Result result) {
		return new ClosingResultResponse(result.period(), PeriodStatus.CLOSED, result.closedAt(), result.nextPeriod(),
				result.consolidatedEntries().stream().map(ClosingConsolidatedEntry::from).toList(),
				result.createdEntries().stream().map(ClosingCreatedEntry::from).toList(),
				result.accountClosings().stream().map(ClosingAccountClosing::from).toList());
	}
}
