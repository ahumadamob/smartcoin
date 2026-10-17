package com.smartcoin.period.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.period.domain.ClosingBlockReason;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.EntryEffect;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.ClosingPlanner.InvalidResolution;
import com.smartcoin.period.domain.ClosingPlanner.NewEntry;
import com.smartcoin.period.domain.InvalidResolutionReason;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.service.ClosingService.Blocker;
import com.smartcoin.period.service.ClosingService.Preview;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ClosingPreview", description = "Simulación del cierre de un período (RN-43), sin modificar nada: si "
		+ "se cumplen las precondiciones (RN-38) y, si no, cuáles fallan; las partidas pendientes, que necesitarán "
		+ "una resolución, y las cuentas a conciliar con su saldo calculado al último día del período (RN-39). Con "
		+ "resoluciones, qué pasaría con cada pendiente, las partidas que se crearían y las pendientes que faltan "
		+ "resolver. Todos los montos los calcula el backend. Un período cerrado no tiene nada que resolver ni "
		+ "conciliar: sus listas están vacías.")
public record ClosingPreviewResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-10")
		YearMonth period,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "OPEN o CLOSED.")
		PeriodStatus status,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-31",
				description = "Último día del período: el saldo calculado es a esa fecha, y desde ese día el período "
						+ "se puede cerrar.")
		LocalDate lastDay,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-09",
				description = "Primer período abierto del usuario (RN-08): los meses se cierran en orden, así que es "
						+ "el que hay que cerrar primero.")
		YearMonth firstOpenPeriod,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Si se cumplen todas las precondiciones de RN-38. Falso: `blockers` dice cuáles fallan.")
		boolean preconditionsMet,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Todas las precondiciones que fallan, en el orden de RN-38. Vacía si se cumplen. El "
						+ "cierre responderá el 409 de la primera.")
		List<ClosingBlocker> blockers,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Partidas pendientes del período (Estimadas y Parciales), ingresos y gastos juntos, por "
						+ "vencimiento, nombre y orden de creación, como en la vista del mes.")
		List<ClosingPendingEntry> pendingEntries,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Cuentas a conciliar: las abiertas el último día del período o antes, por moneda (ARS, "
						+ "USD) y nombre.")
		List<ClosingAccount> accounts,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Partidas que crearía el cierre en el período siguiente, en el orden de "
						+ "`pendingEntries`. HU-31: las de saldo postergado. Vacía sin resoluciones.")
		List<ClosingNewEntry> newEntries,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Ids de las partidas pendientes que todavía no tienen resolución, en el orden de "
						+ "`pendingEntries`. El cierre exige que esté vacía (`UNRESOLVED_PENDING_ENTRIES`).")
		List<Long> unresolvedEntryIds,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Resoluciones recibidas que no corresponden a una partida pendiente del período, en el "
						+ "orden en que llegaron. No se aplican; el cierre las rechazará con `VALIDATION_ERROR`.")
		List<ClosingInvalidResolution> invalidResolutions) {

	@Schema(name = "ClosingBlocker", description = "Una precondición de RN-38 que no se cumple.")
	public record ClosingBlocker(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "PERIOD_CLOSED: el período ya está cerrado. PREVIOUS_PERIOD_OPEN: el período "
							+ "anterior está abierto (`firstOpenPeriod` es el que hay que cerrar primero). "
							+ "PERIOD_NOT_FINISHED: hoy es anterior a `lastDay`.")
			ClosingBlockReason code,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Explicación en español.",
					example = "El período 2026-10 termina el 31/10/2026: se puede cerrar desde ese día.")
			String detail) {

		static ClosingBlocker from(Blocker blocker) {
			return new ClosingBlocker(blocker.reason(), blocker.detail());
		}
	}

	@Schema(name = "ClosingPendingEntry", description = "Una partida pendiente del período, con sus valores derivados "
			+ "calculados como en la vista del mes (RN-16, RN-17).")
	public record ClosingPendingEntry(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512")
			Long id,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Luz",
					description = "En una partida recurrente, el nombre de su Concepto.")
			String name,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			EntryKind kind,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
			Long accountId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
			String accountName,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "Moneda de la partida: la de su cuenta (RN-04).")
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-18")
			LocalDate dueDate,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "45000.00")
			BigDecimal budgetedAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "20000.00",
					description = "Real: suma de sus movimientos.")
			BigDecimal actualAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "25000.00",
					description = "Pendiente: presupuestado menos real, nunca negativo.")
			BigDecimal pendingAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "ESTIMATED: sin movimientos. PARTIAL: con movimientos.")
			EntryStatus status,

			@Schema(nullable = true, description = "La resolución recibida para esta partida; nula si todavía no "
					+ "tiene.")
			ClosingResolution resolution,

			@Schema(nullable = true, example = "20000.00", description = "Monto con que quedaría consolidada: su "
					+ "real, 0 si no tuvo movimientos. Nulo sin resolución.")
			BigDecimal consolidatedAmount,

			@Schema(nullable = true, description = "La partida de saldo postergado que se crearía en el mes "
					+ "siguiente. Solo con CARRY_OVER y pendiente mayor que 0.")
			ClosingNewEntry carriedOverEntry) {

		static ClosingPendingEntry from(EntryLine line, EntryEffect effect) {
			return new ClosingPendingEntry(line.id(), line.name(), line.kind(), line.accountId(), line.accountName(),
					line.currency(), line.dueDate(), line.budgetedAmount(), line.amounts().actual(),
					line.amounts().pending(), line.amounts().status(),
					effect == null ? null : effect.resolution(),
					effect == null ? null : effect.consolidatedAmount(),
					effect == null || effect.carriedOver() == null ? null : ClosingNewEntry.from(effect.carriedOver()));
		}
	}

	@Schema(name = "ClosingNewEntry", description = "Una partida que crearía el cierre en el período siguiente, "
			+ "pendiente y sin editar. HU-31: las de saldo postergado (RN-40, paso 1).")
	public record ClosingNewEntry(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-11")
			YearMonth period,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "CARRIED_OVER: saldo postergado.")
			EntryOrigin origin,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Saldo pendiente: Luz",
					description = "«Saldo pendiente: <nombre>», o el mismo nombre si la original ya era un saldo "
							+ "postergado. Si supera los 100 caracteres, el nombre original se recorta y termina en "
							+ "«…».")
			String name,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			EntryKind kind,

			@Schema(nullable = true, example = "3")
			Long categoryId,

			@Schema(nullable = true, example = "Servicios")
			String categoryName,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
			Long accountId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
			String accountName,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-11-18",
					description = "El vencimiento de la original más un mes, ajustado al último día del mes.")
			LocalDate dueDate,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "25000.00",
					description = "Presupuestado: el pendiente de la original.")
			BigDecimal budgetedAmount,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512",
					description = "La partida cuyo saldo se posterga.")
			Long sourceEntryId) {

		static ClosingNewEntry from(NewEntry entry) {
			return new ClosingNewEntry(entry.period(), entry.origin(), entry.name(), entry.kind(), entry.categoryId(),
					entry.categoryName(), entry.accountId(), entry.accountName(), entry.currency(), entry.dueDate(),
					entry.budgetedAmount(), entry.sourceEntryId());
		}
	}

	@Schema(name = "ClosingInvalidResolution", description = "Una resolución recibida que no corresponde (RN-39).")
	public record ClosingInvalidResolution(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "513")
			Long entryId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "NOT_IN_PERIOD: la partida no es del período (es de otro mes, de otro usuario o no "
							+ "existe). NOT_PENDING: ya está consolidada.")
			InvalidResolutionReason reason) {

		static ClosingInvalidResolution from(InvalidResolution invalid) {
			return new ClosingInvalidResolution(invalid.entryId(), invalid.reason());
		}
	}

	@Schema(name = "ClosingAccount", description = "Una cuenta a conciliar, con su saldo calculado al último día del "
			+ "período (RN-35): saldo inicial más los movimientos y transferencias con fecha hasta ese día, también "
			+ "los cobros anticipados de partidas del mes siguiente (RN-41).")
	public record ClosingAccount(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
			Long id,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Banco Nación")
			String name,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
			Currency currency,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-08-01")
			LocalDate openingDate,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "512300.00",
					description = "Saldo calculado al último día del período. Puede ser negativo.")
			BigDecimal computedBalance) {

		static ClosingAccount from(AccountLine line) {
			return new ClosingAccount(line.id(), line.name(), line.currency(), line.openingDate(),
					line.computedBalance());
		}
	}

	static ClosingPreviewResponse from(Preview preview) {
		var plan = preview.plan();
		Map<Long, EntryEffect> effects = plan.effects().stream()
				.collect(Collectors.toMap(EntryEffect::entryId, Function.identity()));
		return new ClosingPreviewResponse(plan.period(), preview.status(), plan.lastDay(), preview.firstOpenPeriod(),
				plan.preconditionsMet(), preview.blockers().stream().map(ClosingBlocker::from).toList(),
				plan.pendingEntries().stream().map(line -> ClosingPendingEntry.from(line, effects.get(line.id())))
						.toList(),
				plan.accounts().stream().map(ClosingAccount::from).toList(),
				plan.newEntries().stream().map(ClosingNewEntry::from).toList(), plan.unresolvedEntryIds(),
				plan.invalidResolutions().stream().map(ClosingInvalidResolution::from).toList());
	}
}
