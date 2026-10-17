package com.smartcoin.period.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.entry.domain.EntryStatus;
import com.smartcoin.period.domain.ClosingBlockReason;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.service.ClosingService.Blocker;
import com.smartcoin.period.service.ClosingService.Preview;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ClosingPreview", description = "Simulación del cierre de un período (RN-43), sin modificar nada: si "
		+ "se cumplen las precondiciones (RN-38) y, si no, cuáles fallan; las partidas pendientes, que necesitarán "
		+ "una resolución, y las cuentas a conciliar con su saldo calculado al último día del período (RN-39). Todos "
		+ "los montos los calcula el backend. Un período cerrado no tiene nada que resolver ni conciliar: sus listas "
		+ "están vacías.")
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
		List<ClosingAccount> accounts) {

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
			EntryStatus status) {

		static ClosingPendingEntry from(EntryLine line) {
			return new ClosingPendingEntry(line.id(), line.name(), line.kind(), line.accountId(), line.accountName(),
					line.currency(), line.dueDate(), line.budgetedAmount(), line.amounts().actual(),
					line.amounts().pending(), line.amounts().status());
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
		return new ClosingPreviewResponse(plan.period(), preview.status(), plan.lastDay(), preview.firstOpenPeriod(),
				plan.preconditionsMet(), preview.blockers().stream().map(ClosingBlocker::from).toList(),
				plan.pendingEntries().stream().map(ClosingPendingEntry::from).toList(),
				plan.accounts().stream().map(ClosingAccount::from).toList());
	}
}
