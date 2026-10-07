package com.smartcoin.budgetitem.web;

import java.math.BigDecimal;
import java.time.YearMonth;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.smartcoin.budgetitem.domain.BudgetItemValues;
import com.smartcoin.budgetitem.domain.EstimationRule;
import com.smartcoin.budgetitem.domain.InstallmentPlan;
import com.smartcoin.budgetitem.domain.Periodicity;
import com.smartcoin.shared.domain.EntryKind;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Datos para crear un Concepto (RN-10), recurrente o en cuotas (RN-14). Con "
		+ "`installmentsTotal` es un plan de cuotas: el período de fin no se informa, se calcula como el inicio más "
		+ "(total − primera cuota) pasos de la periodicidad.")
public record BudgetItemRequest(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100, example = "Monotributo",
				description = "Nombre. Se guarda sin espacios en los extremos. Puede repetirse.")
		@NotBlank(message = "El nombre es obligatorio.")
		@Size(max = 100, message = "El nombre no puede superar los 100 caracteres.")
		String name,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Tipo: ingreso o gasto. No se edita después.")
		@NotNull(message = "El tipo es obligatorio.")
		EntryKind kind,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12",
				description = "Cuenta por defecto, del usuario. Define la moneda del Concepto y de sus partidas.")
		@NotNull(message = "La cuenta por defecto es obligatoria.")
		Long defaultAccountId,

		@Schema(nullable = true, example = "3", description = "Categoría del usuario. Opcional.")
		Long categoryId,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Periodicidad (RN-11). No se edita después.")
		@NotNull(message = "La periodicidad es obligatoria.")
		Periodicity periodicity,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "31", example = "20",
				description = "Día de vencimiento. Si el mes no lo tiene, vence su último día (RN-12).")
		@NotNull(message = "El día de vencimiento es obligatorio.")
		@Min(value = 1, message = "El día de vencimiento debe estar entre 1 y 31.")
		@Max(value = 31, message = "El día de vencimiento debe estar entre 1 y 31.")
		Integer dueDay,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = { "0", "-1" }, example = "0",
				description = "Desfase de mes: 0 si vence en el mes del período, −1 si vence el mes anterior.")
		@NotNull(message = "El desfase de mes es obligatorio.")
		@Min(value = -1, message = "El desfase de mes debe ser 0 o −1.")
		@Max(value = 0, message = "El desfase de mes debe ser 0 o −1.")
		Integer dueMonthOffset,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "^\\d{4}-\\d{2}$",
				example = "2026-10",
				description = "Primer período con partida (YYYY-MM): entre el primer período abierto y el horizonte. "
						+ "No se edita después.")
		@NotNull(message = "El período de inicio es obligatorio.")
		YearMonth startPeriod,

		@Schema(nullable = true, type = "string", pattern = "^\\d{4}-\\d{2}$", example = "2027-12",
				description = "Último período posible (YYYY-MM), igual o posterior al de inicio. Puede superar el "
						+ "horizonte. Sin él, el Concepto no tiene fin. En un Concepto en cuotas no se informa: se calcula.")
		YearMonth endPeriod,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Cómo se estiman las partidas siguientes al consolidar (RN-26).")
		@NotNull(message = "La regla de estimación es obligatoria.")
		EstimationRule estimationRule,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", example = "85000.00",
				description = "Monto vigente, con hasta 2 decimales: el presupuestado de las partidas que se generan.")
		@NotNull(message = "El monto vigente es obligatorio.")
		@DecimalMin(value = "0", message = "El monto vigente no puede ser negativo.")
		@Digits(integer = 17, fraction = 2, message = "El monto vigente admite hasta 2 decimales.")
		BigDecimal currentAmount,

		@Schema(nullable = true, minimum = "1", maximum = "" + InstallmentPlan.MAX_TOTAL, example = "12",
				description = "Total de cuotas (RN-14). Si se informa, el Concepto es en cuotas y el fin se calcula. "
						+ "No se edita después.")
		@Min(value = 1, message = "El total de cuotas debe ser al menos 1.")
		@Max(value = InstallmentPlan.MAX_TOTAL,
				message = "El total de cuotas no puede superar " + InstallmentPlan.MAX_TOTAL + ".")
		Integer installmentsTotal,

		@Schema(nullable = true, minimum = "1", example = "4",
				description = "Número de la primera cuota, la que corresponde al período de inicio: entre 1 y el "
						+ "total. Permite cargar un plan ya empezado. Por defecto 1; solo se informa junto con el "
						+ "total. No se edita después.")
		@Min(value = 1, message = "La primera cuota debe ser al menos 1.")
		Integer firstInstallmentNumber) {

	BudgetItemValues toValues() {
		return new BudgetItemValues(name, kind, defaultAccountId, categoryId, periodicity, dueDay, dueMonthOffset,
				startPeriod, endPeriod, estimationRule, currentAmount, installmentsTotal, firstInstallmentNumber);
	}
}
