package com.smartcoin.period.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.ClosingPlanner.Input;
import com.smartcoin.period.domain.ClosingPlanner.Plan;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * HU-30, RN-38 y RN-39, D-42: precondiciones del cierre (todas las que fallan, en el orden de RN-38), partidas
 * pendientes y cuentas a conciliar. «Hoy» sale de un {@code Clock} fijo en la zona de la aplicación, como en el
 * servicio.
 */
class ClosingPlannerTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final YearMonth OCTOBER = YearMonth.of(2026, 10);

	/** Hoy según un reloj fijo en {@code instant} (UTC), en Mendoza (UTC−3). */
	private static LocalDate today(String instant) {
		return LocalDate.now(Clock.fixed(Instant.parse(instant), ZONE));
	}

	private static PeriodStatus status(String text) {
		return "none".equals(text) ? null : PeriodStatus.valueOf(text);
	}

	private static List<ClosingBlockReason> reasons(String text) {
		return text == null ? List.of()
				: Arrays.stream(text.split("\\|")).map(ClosingBlockReason::valueOf).toList();
	}

	private static Plan plan(YearMonth period, PeriodStatus status, PeriodStatus previous, LocalDate today) {
		return ClosingPlanner.plan(new Input(period, status, previous, previous == null, today, List.of(), List.of()));
	}

	/**
	 * Octubre de 2026 termina el 31/10. El reloj marca las 12:00 en Mendoza salvo donde se indica; {@code none}: es el
	 * período inicial y no tiene anterior.
	 */
	@ParameterizedTest(name = "[{index}] {0}, anterior {1}, ahora {2} → {3}")
	@CsvSource(nullValues = "-", value = {
			// Período inicial: no hay anterior y no hace falta que esté cerrado.
			"OPEN,   none,   2026-10-31T15:00:00Z, -",
			"OPEN,   none,   2026-10-30T15:00:00Z, PERIOD_NOT_FINISHED",
			// Anterior cerrado o abierto.
			"OPEN,   CLOSED, 2026-11-05T15:00:00Z, -",
			"OPEN,   OPEN,   2026-11-05T15:00:00Z, PREVIOUS_PERIOD_OPEN",
			// Último día exacto, el anterior y el siguiente.
			"OPEN,   CLOSED, 2026-10-31T15:00:00Z, -",
			"OPEN,   CLOSED, 2026-10-30T15:00:00Z, PERIOD_NOT_FINISHED",
			"OPEN,   CLOSED, 2026-11-01T15:00:00Z, -",
			// El día cuenta en Mendoza (UTC−3): 23:59 del 30/10, 00:00 y 23:59 del 31/10.
			"OPEN,   CLOSED, 2026-10-31T02:59:00Z, PERIOD_NOT_FINISHED",
			"OPEN,   CLOSED, 2026-10-31T03:00:00Z, -",
			"OPEN,   CLOSED, 2026-11-01T02:59:00Z, -",
			// Varias a la vez: todas, en el orden de RN-38.
			"OPEN,   OPEN,   2026-10-16T15:00:00Z, PREVIOUS_PERIOD_OPEN|PERIOD_NOT_FINISHED",
			"OPEN,   OPEN,   2026-10-31T15:00:00Z, PREVIOUS_PERIOD_OPEN",
			// Ya cerrado: cerrar exige las otras dos, así que en la práctica solo falla esta.
			"CLOSED, CLOSED, 2026-11-05T15:00:00Z, PERIOD_CLOSED",
			"CLOSED, none,   2026-11-05T15:00:00Z, PERIOD_CLOSED",
			// Si los datos fueran inconsistentes, igual se evalúan por separado y en orden.
			"CLOSED, OPEN,   2026-10-16T15:00:00Z, PERIOD_CLOSED|PREVIOUS_PERIOD_OPEN|PERIOD_NOT_FINISHED",
	})
	void preconditions(String status, String previous, String now, String expected) {
		Plan plan = plan(OCTOBER, PeriodStatus.valueOf(status), status(previous), today(now));

		assertThat(plan.blockers()).containsExactlyElementsOf(reasons(expected));
		assertThat(plan.preconditionsMet()).isEqualTo(expected == null);
		assertThat(plan.lastDay()).isEqualTo(LocalDate.of(2026, 10, 31));
	}

	/** El último día es el del calendario: 28 o 29 en febrero, 30 o 31 en los demás. */
	@ParameterizedTest(name = "{0}: termina el {1}")
	@CsvSource({
			"2027-02, 2027-02-28",
			"2028-02, 2028-02-29",
			"2026-11, 2026-11-30",
			"2026-12, 2026-12-31",
			"2027-01, 2027-01-31",
	})
	void lastDayOfEachMonth(String period, String lastDay) {
		YearMonth month = YearMonth.parse(period);
		LocalDate last = LocalDate.parse(lastDay);

		assertThat(plan(month, PeriodStatus.OPEN, PeriodStatus.CLOSED, last).blockers()).isEmpty();
		assertThat(plan(month, PeriodStatus.OPEN, PeriodStatus.CLOSED, last.minusDays(1)).blockers())
				.containsExactly(ClosingBlockReason.PERIOD_NOT_FINISHED);
		assertThat(plan(month, PeriodStatus.OPEN, PeriodStatus.CLOSED, last).lastDay()).isEqualTo(last);
	}

	@Test
	void aPeriodThatIsNotTheStartAlwaysHasAPrevious() {
		assertThatThrownBy(() -> new Input(OCTOBER, PeriodStatus.OPEN, null, false, LocalDate.of(2026, 11, 1),
				List.of(), List.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// Partidas pendientes y cuentas a conciliar (RN-39).

	private static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	private static EntryLine entry(long id, String name, EntryKind kind, String budgeted, StoredEntryStatus stored,
			String consolidated, String movements) {
		return new EntryLine(id, name, kind, 42L, "Banco", Currency.ARS, LocalDate.of(2026, 10, 18), amount(budgeted),
				EntryAmounts.of(amount(budgeted), stored, consolidated == null ? null : amount(consolidated),
						movements == null ? null : amount(movements)));
	}

	private static AccountLine account(long id, String name, Currency currency, String openingDate) {
		return new AccountLine(id, name, currency, LocalDate.parse(openingDate), amount("1000.00"));
	}

	EntryLine salary = entry(1, "Sueldo", EntryKind.INCOME, "1200000.00", StoredEntryStatus.CONSOLIDATED,
			"1200000.00", "1200000.00");
	EntryLine bonus = entry(2, "Sueldo B", EntryKind.INCOME, "650000.00", StoredEntryStatus.PENDING, null, null);
	EntryLine power = entry(3, "Luz", EntryKind.EXPENSE, "45000.00", StoredEntryStatus.PENDING, null, "20000.00");
	EntryLine covered = entry(4, "Agua", EntryKind.EXPENSE, "10000.00", StoredEntryStatus.PENDING, null, "12000.00");
	EntryLine zero = entry(5, "Seguro", EntryKind.EXPENSE, "0.00", StoredEntryStatus.PENDING, null, null);
	EntryLine rent = entry(6, "Alquiler", EntryKind.EXPENSE, "450000.00", StoredEntryStatus.CONSOLIDATED,
			"450000.00", "450000.00");

	List<AccountLine> accounts = List.of(
			account(10, "Banco", Currency.ARS, "2026-08-01"),
			account(11, "Billetera", Currency.ARS, "2026-10-31"),
			account(12, "Efectivo", Currency.ARS, "2026-11-01"),
			account(13, "Dólares", Currency.USD, "2026-10-15"));

	private Plan planWith(PeriodStatus status, PeriodStatus previous, LocalDate today) {
		return ClosingPlanner.plan(new Input(OCTOBER, status, previous, false, today,
				List.of(salary, bonus, power, covered, zero, rent), accounts));
	}

	/**
	 * Todas las pendientes, Estimadas y Parciales, también la cubierta (pendiente 0 sin consolidar) y la presupuestada
	 * en 0: cada una necesitará una resolución. Se conserva el orden recibido, que es el de la vista del mes.
	 */
	@Test
	void pendingEntriesAreTheEstimatedAndPartialOnes() {
		Plan plan = planWith(PeriodStatus.OPEN, PeriodStatus.CLOSED, LocalDate.of(2026, 10, 31));

		assertThat(plan.pendingEntries()).containsExactly(bonus, power, covered, zero);
		assertThat(power.amounts().actual()).isEqualTo(amount("20000.00"));
		assertThat(power.amounts().pending()).isEqualTo(amount("25000.00"));
	}

	/** Cuentas abiertas el último día del período o antes; la abierta el 01/11 no se concilia en octubre. */
	@Test
	void accountsToReconcileAreTheOnesOpenedUpToTheLastDay() {
		Plan plan = planWith(PeriodStatus.OPEN, PeriodStatus.CLOSED, LocalDate.of(2026, 11, 3));

		assertThat(plan.accounts()).extracting(AccountLine::name).containsExactly("Banco", "Billetera", "Dólares");
	}

	/** Bloqueado por el anterior o por la fecha, el diagnóstico se arma igual: es lo que falta resolver. */
	@Test
	void aBlockedOpenPeriodStillListsWhatIsMissing() {
		Plan plan = planWith(PeriodStatus.OPEN, PeriodStatus.OPEN, LocalDate.of(2026, 10, 16));

		assertThat(plan.preconditionsMet()).isFalse();
		assertThat(plan.pendingEntries()).hasSize(4);
		assertThat(plan.accounts()).hasSize(3);
	}

	@Test
	void aClosedPeriodHasNothingToResolveOrReconcile() {
		Plan plan = planWith(PeriodStatus.CLOSED, PeriodStatus.CLOSED, LocalDate.of(2026, 11, 3));

		assertThat(plan.blockers()).containsExactly(ClosingBlockReason.PERIOD_CLOSED);
		assertThat(plan.pendingEntries()).isEmpty();
		assertThat(plan.accounts()).isEmpty();
	}

	@Test
	void withoutEntriesOrAccountsTheListsAreEmpty() {
		Plan plan = plan(OCTOBER, PeriodStatus.OPEN, null, LocalDate.of(2026, 10, 31));

		assertThat(plan.preconditionsMet()).isTrue();
		assertThat(plan.pendingEntries()).isEmpty();
		assertThat(plan.accounts()).isEmpty();
	}
}
