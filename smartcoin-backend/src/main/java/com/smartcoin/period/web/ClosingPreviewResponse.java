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
import com.smartcoin.period.domain.ClosingPlanner.InvalidBalance;
import com.smartcoin.period.domain.ClosingPlanner.InvalidResolution;
import com.smartcoin.period.domain.ClosingPlanner.NewEntry;
import com.smartcoin.period.domain.ClosingPlanner.Reconciliation;
import com.smartcoin.period.domain.InvalidBalanceReason;
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
		+ "resolver. Con saldos reales, la diferencia de cada cuenta, las partidas de diferencia que se crearían y "
		+ "las cuentas que todavía no tienen saldo real. Todos los montos los calcula el backend. Un período cerrado no tiene nada que resolver ni "
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
						+ "USD) y nombre. Con saldo real, suman su diferencia y la partida de diferencia que se crearía.")
		List<ClosingAccount> accounts,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Partidas que crearía el cierre en el período siguiente: primero las de saldo "
						+ "postergado, en el orden de `pendingEntries` (HU-31), y después las de diferencia de cierre, "
						+ "en el orden de `accounts` (HU-32). Vacía sin resoluciones ni diferencias.")
		List<ClosingNewEntry> newEntries,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Ids de las partidas pendientes que todavía no tienen resolución, en el orden de "
						+ "`pendingEntries`. El cierre exige que esté vacía (`UNRESOLVED_PENDING_ENTRIES`).")
		List<Long> unresolvedEntryIds,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Resoluciones recibidas que no corresponden a una partida pendiente del período, en el "
						+ "orden en que llegaron. No se aplican; el cierre las rechazará con `VALIDATION_ERROR`.")
		List<ClosingInvalidResolution> invalidResolutions,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Ids de las cuentas a conciliar que todavía no tienen saldo real, en el orden de "
						+ "`accounts`. El cierre exige que esté vacía (`MISSING_REAL_BALANCE`).")
		List<Long> missingRealBalanceAccountIds,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Saldos reales recibidos para cuentas que no se concilian en el período, en el orden en "
						+ "que llegaron. No se aplican; el cierre los rechazará con `VALIDATION_ERROR`.")
		List<ClosingInvalidBalance> invalidBalances) {

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
			+ "pendiente y sin editar. HU-31: las de saldo postergado (RN-40, paso 1). HU-32: las de diferencia de "
			+ "cierre (paso 3).")
	public record ClosingNewEntry(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "2026-11")
			YearMonth period,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "CARRIED_OVER: saldo postergado. CLOSING_DIFFERENCE: diferencia de cierre.")
			EntryOrigin origin,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Saldo pendiente: Luz",
					description = "Saldo postergado: «Saldo pendiente: <nombre>», o el mismo nombre si la original "
							+ "ya era un saldo postergado; si supera los 100 caracteres, el nombre original se "
							+ "recorta y termina en «…». Diferencia de cierre: «Diferencia de cierre: <cuenta> (<mes> "
							+ "<año>)», con el mes (ene a dic) y el año del período que se cierra, por ejemplo "
							+ "«Diferencia de cierre: Banco (oct 2026)»; si supera los 100 caracteres, se recorta el "
							+ "nombre de la cuenta y el mes queda intacto.")
			String name,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "INCOME o EXPENSE. En una diferencia de cierre, ingreso si la diferencia es "
							+ "positiva y gasto si es negativa.")
			EntryKind kind,

			@Schema(nullable = true, example = "3", description = "Las diferencias de cierre no llevan categoría.")
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
					description = "Saldo postergado: el vencimiento de la original más un mes, ajustado al último "
							+ "día del mes. Diferencia de cierre: el día 1 del período siguiente.")
			LocalDate dueDate,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "25000.00",
					description = "Saldo postergado: el pendiente de la original. Diferencia de cierre: el valor "
							+ "absoluto de la diferencia.")
			BigDecimal budgetedAmount,

			@Schema(nullable = true, example = "512",
					description = "Saldo postergado: la partida cuyo saldo se posterga. Nulo en una diferencia de "
							+ "cierre.")
			Long sourceEntryId,

			@Schema(nullable = true, example = "12",
					description = "Diferencia de cierre: la cuenta conciliada, a cuyo cierre de cuenta quedará "
							+ "referida. Nulo en un saldo postergado.")
			Long sourceAccountId) {

		static ClosingNewEntry from(NewEntry entry) {
			return new ClosingNewEntry(entry.period(), entry.origin(), entry.name(), entry.kind(), entry.categoryId(),
					entry.categoryName(), entry.accountId(), entry.accountName(), entry.currency(), entry.dueDate(),
					entry.budgetedAmount(), entry.sourceEntryId(), entry.sourceAccountId());
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

	@Schema(name = "ClosingInvalidBalance", description = "Un saldo real recibido que no corresponde (RN-39).")
	public record ClosingInvalidBalance(

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "99")
			Long accountId,

			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "NOT_RECONCILABLE: la cuenta no se concilia en el período (se abrió después de su "
							+ "último día, es de otro usuario o no existe).")
			InvalidBalanceReason reason) {

		static ClosingInvalidBalance from(InvalidBalance invalid) {
			return new ClosingInvalidBalance(invalid.accountId(), invalid.reason());
		}
	}

	@Schema(name = "ClosingAccount", description = "Una cuenta a conciliar, con su saldo calculado al último día del "
			+ "período (RN-35): saldo inicial más los movimientos y transferencias con fecha hasta ese día, también "
			+ "los cobros anticipados de partidas del mes siguiente (RN-41). Con saldo real informado, suma la "
			+ "diferencia y la partida de diferencia de cierre que se crearía (RN-40, paso 3).")
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
			BigDecimal computedBalance,

			@Schema(nullable = true, example = "509800.00", description = "Saldo real informado para esta cuenta; "
					+ "nulo si todavía no se informó.")
			BigDecimal realBalance,

			@Schema(nullable = true, example = "-2500.00", description = "Real menos calculado. Negativa si la "
					+ "cuenta tiene menos de lo que la aplicación espera; 0 si coinciden. Nula sin saldo real.")
			BigDecimal difference,

			@Schema(nullable = true, description = "La partida de diferencia de cierre que se crearía en el mes "
					+ "siguiente. Nula sin saldo real o con diferencia 0.")
			ClosingNewEntry differenceEntry) {

		static ClosingAccount from(AccountLine line, Reconciliation reconciliation) {
			return new ClosingAccount(line.id(), line.name(), line.currency(), line.openingDate(),
					line.computedBalance(),
					reconciliation == null ? null : reconciliation.realBalance(),
					reconciliation == null ? null : reconciliation.difference(),
					reconciliation == null || reconciliation.differenceEntry() == null ? null
							: ClosingNewEntry.from(reconciliation.differenceEntry()));
		}
	}

	static ClosingPreviewResponse from(Preview preview) {
		var plan = preview.plan();
		Map<Long, EntryEffect> effects = plan.effects().stream()
				.collect(Collectors.toMap(EntryEffect::entryId, Function.identity()));
		Map<Long, Reconciliation> reconciliations = plan.reconciliations().stream()
				.collect(Collectors.toMap(Reconciliation::accountId, Function.identity()));
		return new ClosingPreviewResponse(plan.period(), preview.status(), plan.lastDay(), preview.firstOpenPeriod(),
				plan.preconditionsMet(), preview.blockers().stream().map(ClosingBlocker::from).toList(),
				plan.pendingEntries().stream().map(line -> ClosingPendingEntry.from(line, effects.get(line.id())))
						.toList(),
				plan.accounts().stream().map(line -> ClosingAccount.from(line, reconciliations.get(line.id())))
						.toList(),
				plan.newEntries().stream().map(ClosingNewEntry::from).toList(), plan.unresolvedEntryIds(),
				plan.invalidResolutions().stream().map(ClosingInvalidResolution::from).toList(),
				plan.missingRealBalanceAccountIds(),
				plan.invalidBalances().stream().map(ClosingInvalidBalance::from).toList());
	}
}
