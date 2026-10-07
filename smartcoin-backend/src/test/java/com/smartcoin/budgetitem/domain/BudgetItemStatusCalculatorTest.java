package com.smartcoin.budgetitem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;

import com.smartcoin.budgetitem.domain.BudgetItemStatusCalculator.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D-27: estado por calendario, con el período actual fijo en 2026-10. */
class BudgetItemStatusCalculatorTest {

	private static final YearMonth NOW = YearMonth.of(2026, 10);

	private static YearMonth ym(String text) {
		return text == null ? null : YearMonth.parse(text);
	}

	@ParameterizedTest(name = "inicio {0}, fin {1} → {2}")
	@CsvSource(nullValues = "-", value = {
			"2026-01, -,       ACTIVE",
			"2026-10, -,       ACTIVE",
			"2026-10, 2026-10, ACTIVE",
			"2026-01, 2026-10, ACTIVE",
			"2026-01, 2027-03, ACTIVE",
			"2026-01, 2026-09, FINISHED",
			"2020-01, 2020-01, FINISHED",
			"2026-11, -,       SCHEDULED",
			"2028-10, 2029-10, SCHEDULED",
	})
	void statusWithoutInstallments(String start, String end, BudgetItemStatus expected) {
		Result result = BudgetItemStatusCalculator.calculate(ym(start), ym(end), Periodicity.MONTHLY, null, NOW);

		assertThat(result.status()).isEqualTo(expected);
		assertThat(result.currentInstallment()).isNull();
		assertThat(result.installmentsRemaining()).isNull();
	}

	@ParameterizedTest(name = "{0} cuotas, primera {1}, {2}, inicio {3} → {4} · cuota {5}, quedan {6}")
	@CsvSource(nullValues = "-", value = {
			// Heladera: 12 cuotas, primera 4, inicio 2026-10 (fin 2027-06). Hoy es la cuota 4.
			"12, 4, MONTHLY,   2026-10, ACTIVE,    4,  8",
			// A mitad del plan: empezó en 2026-04 con la cuota 1.
			"12, 1, MONTHLY,   2026-04, ACTIVE,    7,  5",
			// Última cuota: fin en el período actual, todavía activo y no quedan.
			"12, 1, MONTHLY,   2025-11, ACTIVE,    12, 0",
			"12, 4, MONTHLY,   2026-02, ACTIVE,    12, 0",
			// Una sola cuota, en el período actual.
			"1,  1, MONTHLY,   2026-10, ACTIVE,    1,  0",
			// Terminado: la última cuota fue el mes anterior.
			"12, 1, MONTHLY,   2025-10, FINISHED,  -,  -",
			"12, 4, MONTHLY,   2026-01, FINISHED,  -,  -",
			// Todavía no empezó.
			"12, 4, MONTHLY,   2026-11, SCHEDULED, -,  -",
			// Bimestral, inicio 2026-04 con cuota 1: cuotas en abril, junio, agosto, octubre. Octubre es la 4.
			"6,  1, BIMONTHLY, 2026-04, ACTIVE,    4,  2",
			// Bimestral en un mes sin cuota (inicio 2026-05, cuotas en mayo, julio, septiembre): sigue la de septiembre, la 3.
			"6,  1, BIMONTHLY, 2026-05, ACTIVE,    3,  3",
			// Bimestral, última cuota: inicio 2025-12, la sexta cae en 2026-10 (el fin).
			"6,  1, BIMONTHLY, 2025-12, ACTIVE,    6,  0",
			// Trimestral con primera cuota 2: inicio 2026-01, cuotas 2 (ene), 3 (abr), 4 (jul), 5 (oct).
			"8,  2, QUARTERLY, 2026-01, ACTIVE,    5,  3",
			// Anual, inicio 2024-10, primera cuota 3: 3 (2024), 4 (2025), 5 (2026).
			"10, 3, ANNUAL,    2024-10, ACTIVE,    5,  5",
	})
	void statusWithInstallments(int total, int first, Periodicity periodicity, String start,
			BudgetItemStatus expected, Integer installment, Integer remaining) {
		InstallmentPlan plan = new InstallmentPlan(total, first);
		YearMonth startPeriod = ym(start);
		YearMonth end = plan.endPeriod(startPeriod, periodicity);

		Result result = BudgetItemStatusCalculator.calculate(startPeriod, end, periodicity, plan, NOW);

		assertThat(result.status()).isEqualTo(expected);
		assertThat(result.currentInstallment()).isEqualTo(installment);
		assertThat(result.installmentsRemaining()).isEqualTo(remaining);
	}

	@Test
	void theCurrentInstallmentAdvancesOneStepPerPeriodicityAndNeverPastTheTotal() {
		InstallmentPlan plan = new InstallmentPlan(12, 4);

		Result november = BudgetItemStatusCalculator.calculate(YearMonth.of(2026, 10), YearMonth.of(2027, 6),
				Periodicity.MONTHLY, plan, YearMonth.of(2026, 11));
		Result june = BudgetItemStatusCalculator.calculate(YearMonth.of(2026, 10), YearMonth.of(2027, 6),
				Periodicity.MONTHLY, plan, YearMonth.of(2027, 6));
		Result july = BudgetItemStatusCalculator.calculate(YearMonth.of(2026, 10), YearMonth.of(2027, 6),
				Periodicity.MONTHLY, plan, YearMonth.of(2027, 7));

		assertThat(november).isEqualTo(new Result(BudgetItemStatus.ACTIVE, 5, 7));
		assertThat(june).isEqualTo(new Result(BudgetItemStatus.ACTIVE, 12, 0));
		assertThat(july.status()).isEqualTo(BudgetItemStatus.FINISHED);
	}
}
