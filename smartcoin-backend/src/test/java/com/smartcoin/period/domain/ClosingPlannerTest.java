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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.smartcoin.entry.domain.ClosingResolution;
import com.smartcoin.entry.domain.EntryAmounts;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.domain.ClosingPlanner.EntryEffect;
import com.smartcoin.period.domain.ClosingPlanner.EntryLine;
import com.smartcoin.period.domain.ClosingPlanner.Input;
import com.smartcoin.period.domain.ClosingPlanner.InvalidResolution;
import com.smartcoin.period.domain.ClosingPlanner.NewEntry;
import com.smartcoin.period.domain.ClosingPlanner.Plan;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * HU-30, RN-38 y RN-39, D-42: precondiciones del cierre (todas las que fallan, en el orden de RN-38), partidas
 * pendientes y cuentas a conciliar. HU-31, RN-40 pasos 1 y 2, D-43: resoluciones de las pendientes, con qué monto se
 * consolida cada una y las partidas de saldo postergado. «Hoy» sale de un {@code Clock} fijo en la zona de la
 * aplicación, como en el servicio.
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
		return ClosingPlanner.plan(new Input(period, status, previous, previous == null, today, List.of(), List.of(),
				Map.of()));
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
				List.of(), List.of(), Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// Partidas pendientes y cuentas a conciliar (RN-39).

	private static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	private static EntryLine entry(long id, String name, EntryKind kind, String budgeted, StoredEntryStatus stored,
			String consolidated, String movements) {
		return line(id, EntryOrigin.ONE_OFF, name, kind, LocalDate.of(2026, 10, 18), budgeted, stored, consolidated,
				movements);
	}

	private static EntryLine line(long id, EntryOrigin origin, String name, EntryKind kind, LocalDate dueDate,
			String budgeted, StoredEntryStatus stored, String consolidated, String movements) {
		return new EntryLine(id, origin, name, kind, null, null, 42L, "Banco", Currency.ARS, dueDate,
				amount(budgeted), EntryAmounts.of(amount(budgeted), stored,
						consolidated == null ? null : amount(consolidated), movements == null ? null : amount(movements)));
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
				List.of(salary, bonus, power, covered, zero, rent), accounts, Map.of()));
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

	// Resoluciones de las pendientes (HU-31, RN-40 pasos 1 y 2).

	static final LocalDate OCTOBER_31 = LocalDate.of(2026, 10, 31);

	/** Octubre, el período inicial, ya terminado: se cumplen las precondiciones. */
	private static Plan resolve(YearMonth period, List<EntryLine> entries, Map<Long, ClosingResolution> resolutions) {
		return ClosingPlanner.plan(new Input(period, PeriodStatus.OPEN, null, true, period.atEndOfMonth(), entries,
				List.of(), resolutions));
	}

	private static Plan resolve(EntryLine entry, ClosingResolution resolution) {
		return resolve(OCTOBER, List.of(entry), Map.of(entry.id(), resolution));
	}

	/**
	 * Con qué monto queda consolidada y si se crea una partida de saldo postergado, y por cuánto. La luz es el ejemplo
	 * de RN-40: presupuestada en 45.000,00 con 20.000,00 pagados. La cubierta tiene más pagado que presupuestado.
	 */
	@ParameterizedTest(name = "[{index}] {0} con {2} pagados, {3} → consolidada en {4}, posterga {5}")
	@CsvSource(nullValues = "-", value = {
			// Ejemplo de RN-40: con Postergar queda en 20.000,00 y posterga los 25.000,00 que faltan.
			"Luz,      45000.00, 20000.00, CARRY_OVER,  20000.00, 25000.00",
			// Cerrar con lo registrado, con y sin movimientos: el pendiente se descarta.
			"Luz,      45000.00, 20000.00, CLOSE_AS_IS, 20000.00, -",
			"Luz,      45000.00, -,        CLOSE_AS_IS, 0.00,     -",
			// Postergar sin movimientos: consolida en 0 y posterga todo el presupuestado.
			"Luz,      45000.00, -,        CARRY_OVER,  0.00,     45000.00",
			// Postergar con pendiente 0 consolida y no crea nada, también si se pagó de más.
			"Agua,     10000.00, 10000.00, CARRY_OVER,  10000.00, -",
			"Agua,     10000.00, 12000.00, CARRY_OVER,  12000.00, -",
			"Seguro,   0.00,     -,        CARRY_OVER,  0.00,     -",
			"Seguro,   0.00,     -,        CLOSE_AS_IS, 0.00,     -",
	})
	void eachResolutionConsolidatesWithTheActualAmount(String name, String budgeted, String paid,
			ClosingResolution resolution, String consolidated, String carried) {
		EntryLine entry = line(3, EntryOrigin.ONE_OFF, name, EntryKind.EXPENSE, LocalDate.of(2026, 10, 18), budgeted,
				StoredEntryStatus.PENDING, null, paid);

		Plan plan = resolve(entry, resolution);

		EntryEffect effect = plan.effects().getFirst();
		assertThat(effect.entryId()).isEqualTo(3L);
		assertThat(effect.resolution()).isEqualTo(resolution);
		assertThat(effect.consolidatedAmount()).isEqualTo(amount(consolidated));
		assertThat(plan.unresolvedEntryIds()).isEmpty();
		if (carried == null) {
			assertThat(effect.carriedOver()).isNull();
			assertThat(plan.newEntries()).isEmpty();
		} else {
			assertThat(effect.carriedOver().budgetedAmount()).isEqualTo(amount(carried));
			assertThat(plan.newEntries()).containsExactly(effect.carriedOver());
		}
	}

	/** RN-40, el ejemplo de la luz: la partida que se crearía, con todos sus datos. */
	@Test
	void carryingOverThePowerBillCreatesTheCarriedOverEntryInNovember() {
		EntryLine power = new EntryLine(3L, EntryOrigin.ONE_OFF, "Luz", EntryKind.EXPENSE, 8L, "Servicios", 42L,
				"Banco", Currency.ARS, LocalDate.of(2026, 10, 18), amount("45000.00"), EntryAmounts.of(
						amount("45000.00"), StoredEntryStatus.PENDING, null, amount("20000.00")));

		Plan plan = resolve(power, ClosingResolution.CARRY_OVER);

		assertThat(plan.newEntries()).containsExactly(new NewEntry(YearMonth.of(2026, 11), EntryOrigin.CARRIED_OVER,
				"Saldo pendiente: Luz", EntryKind.EXPENSE, 8L, "Servicios", 42L, "Banco", Currency.ARS,
				LocalDate.of(2026, 11, 18), amount("25000.00"), 3L));
	}

	/**
	 * Vencimiento original más un mes, ajustado al último día del mes (RN-40). El mes siguiente es el del período que
	 * se cierra, aunque el vencimiento sea de otro mes (desfase −1). Un día 30 sigue siendo 30 (D-43: no se mira el día
	 * de vencimiento del Concepto).
	 */
	@ParameterizedTest(name = "[{index}] {0}, vence {1} → {2}, vence {3}")
	@CsvSource({
			"2026-10, 2026-10-18, 2026-11, 2026-11-18",
			"2027-01, 2027-01-31, 2027-02, 2027-02-28",
			"2028-01, 2028-01-31, 2028-02, 2028-02-29",
			"2026-12, 2026-12-31, 2027-01, 2027-01-31",
			"2026-11, 2026-11-30, 2026-12, 2026-12-30",
			"2026-10, 2026-09-25, 2026-11, 2026-10-25",
			"2027-01, 2026-12-31, 2027-02, 2027-01-31",
			"2027-03, 2027-02-28, 2027-04, 2027-03-28",
	})
	void theCarriedOverEntryIsDueOneMonthLater(String period, String dueDate, String nextPeriod, String nextDue) {
		EntryLine entry = line(3, EntryOrigin.ONE_OFF, "Alquiler", EntryKind.EXPENSE, LocalDate.parse(dueDate),
				"450000.00", StoredEntryStatus.PENDING, null, null);

		Plan plan = resolve(YearMonth.parse(period), List.of(entry), Map.of(3L, ClosingResolution.CARRY_OVER));

		NewEntry created = plan.newEntries().getFirst();
		assertThat(created.period()).isEqualTo(YearMonth.parse(nextPeriod));
		assertThat(created.dueDate()).isEqualTo(LocalDate.parse(nextDue));
	}

	/**
	 * Una recurrente llega con el nombre y la categoría de su Concepto, como en la vista del mes, y la partida
	 * postergada los toma: no es recurrente, así que no tiene Concepto. Un ingreso sigue siendo ingreso.
	 */
	@Test
	void aRecurringEntryIsCarriedOverWithTheNameAndCategoryOfItsBudgetItem() {
		EntryLine salary = new EntryLine(7L, EntryOrigin.RECURRING, "Sueldo B", EntryKind.INCOME, 5L, "Trabajo", 43L,
				"Billetera", Currency.ARS, LocalDate.of(2026, 9, 30), amount("650000.00"), EntryAmounts.of(
						amount("650000.00"), StoredEntryStatus.PENDING, null, amount("600000.00")));

		NewEntry created = resolve(salary, ClosingResolution.CARRY_OVER).newEntries().getFirst();

		assertThat(created.origin()).isEqualTo(EntryOrigin.CARRIED_OVER);
		assertThat(created.name()).isEqualTo("Saldo pendiente: Sueldo B");
		assertThat(created.kind()).isEqualTo(EntryKind.INCOME);
		assertThat(created.categoryId()).isEqualTo(5L);
		assertThat(created.categoryName()).isEqualTo("Trabajo");
		assertThat(created.accountId()).isEqualTo(43L);
		assertThat(created.dueDate()).isEqualTo(LocalDate.of(2026, 10, 30));
		assertThat(created.budgetedAmount()).isEqualTo(amount("50000.00"));
		assertThat(created.sourceEntryId()).isEqualTo(7L);
	}

	/** Un saldo postergado que se vuelve a postergar conserva su nombre y apunta a la partida inmediata (D-43). */
	@Test
	void aCarriedOverEntryKeepsItsName() {
		EntryLine carried = line(9, EntryOrigin.CARRIED_OVER, "Saldo pendiente: Luz", EntryKind.EXPENSE,
				LocalDate.of(2026, 10, 18), "25000.00", StoredEntryStatus.PENDING, null, "5000.00");

		NewEntry created = resolve(carried, ClosingResolution.CARRY_OVER).newEntries().getFirst();

		assertThat(created.name()).isEqualTo("Saldo pendiente: Luz");
		assertThat(created.budgetedAmount()).isEqualTo(amount("20000.00"));
		assertThat(created.sourceEntryId()).isEqualTo(9L);
	}

	/** Una diferencia de cierre pendiente se posterga como cualquier otra (RN-42, D-43). */
	@Test
	void aClosingDifferenceIsCarriedOverLikeAnyOther() {
		EntryLine difference = line(10, EntryOrigin.CLOSING_DIFFERENCE, "Diferencia de cierre: Banco (sep 2026)",
				EntryKind.EXPENSE, LocalDate.of(2026, 10, 1), "2500.00", StoredEntryStatus.PENDING, null, null);

		NewEntry created = resolve(difference, ClosingResolution.CARRY_OVER).newEntries().getFirst();

		assertThat(created.name()).isEqualTo("Saldo pendiente: Diferencia de cierre: Banco (sep 2026)");
		assertThat(created.origin()).isEqualTo(EntryOrigin.CARRIED_OVER);
		assertThat(created.dueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
	}

	private static String name(int length) {
		return "x".repeat(length);
	}

	/**
	 * El nombre de una partida tiene como máximo 100 caracteres. Con «Saldo pendiente: » (17) entra un original de
	 * hasta 83; uno más largo se recorta a 82 y termina en «…», para que el total sea 100 (D-43).
	 */
	@ParameterizedTest(name = "[{index}] original de {0} caracteres")
	@CsvSource({ "1", "82", "83" })
	void aNameThatFitsIsNotCut(int length) {
		String carried = ClosingPlanner.carriedOverName(line(1, EntryOrigin.ONE_OFF, name(length), EntryKind.EXPENSE,
				OCTOBER_31, "1.00", StoredEntryStatus.PENDING, null, null));

		assertThat(carried).isEqualTo("Saldo pendiente: " + name(length));
		assertThat(carried.length()).isLessThanOrEqualTo(100);
	}

	@ParameterizedTest(name = "[{index}] original de {0} caracteres")
	@CsvSource({ "84", "100" })
	void aLongNameIsCutToOneHundredCharacters(int length) {
		String carried = ClosingPlanner.carriedOverName(line(1, EntryOrigin.ONE_OFF, name(length), EntryKind.EXPENSE,
				OCTOBER_31, "1.00", StoredEntryStatus.PENDING, null, null));

		assertThat(carried).isEqualTo("Saldo pendiente: " + name(82) + "…");
		assertThat(carried).hasSize(100);
	}

	/** El corte no deja un espacio antes de «…» ni parte un carácter que ocupa dos posiciones (un emoji). */
	@Test
	void theCutDropsTrailingSpacesAndDoesNotSplitAnEmoji() {
		String withSpace = name(81) + " " + name(18);
		String withEmoji = name(81) + "💡" + name(17);

		assertThat(ClosingPlanner.carriedOverName(line(1, EntryOrigin.ONE_OFF, withSpace, EntryKind.EXPENSE,
				OCTOBER_31, "1.00", StoredEntryStatus.PENDING, null, null)))
				.isEqualTo("Saldo pendiente: " + name(81) + "…");
		assertThat(ClosingPlanner.carriedOverName(line(1, EntryOrigin.ONE_OFF, withEmoji, EntryKind.EXPENSE,
				OCTOBER_31, "1.00", StoredEntryStatus.PENDING, null, null)))
				.isEqualTo("Saldo pendiente: " + name(81) + "…");
	}

	/** Un saldo postergado de nombre largo ya fue recortado al crearse: lo conserva tal cual. */
	@Test
	void aCarriedOverEntryWithALongNameKeepsIt() {
		String long100 = "Saldo pendiente: " + name(82) + "…";

		assertThat(ClosingPlanner.carriedOverName(line(1, EntryOrigin.CARRIED_OVER, long100, EntryKind.EXPENSE,
				OCTOBER_31, "1.00", StoredEntryStatus.PENDING, null, null))).isEqualTo(long100);
	}

	/** Las pendientes sin resolución se informan, en el orden de la vista del mes; las resueltas tienen su efecto. */
	@Test
	void withSomeResolutionsTheRestAreUnresolved() {
		Plan plan = ClosingPlanner.plan(new Input(OCTOBER, PeriodStatus.OPEN, PeriodStatus.CLOSED, false, OCTOBER_31,
				List.of(salary, bonus, power, covered, zero, rent), accounts,
				Map.of(power.id(), ClosingResolution.CARRY_OVER, zero.id(), ClosingResolution.CLOSE_AS_IS)));

		assertThat(plan.effects()).extracting(EntryEffect::entryId).containsExactly(power.id(), zero.id());
		assertThat(plan.unresolvedEntryIds()).containsExactly(bonus.id(), covered.id());
		assertThat(plan.newEntries()).extracting(NewEntry::sourceEntryId).containsExactly(power.id());
		assertThat(plan.invalidResolutions()).isEmpty();
	}

	@Test
	void withAllResolvedNothingIsMissing() {
		Plan plan = ClosingPlanner.plan(new Input(OCTOBER, PeriodStatus.OPEN, PeriodStatus.CLOSED, false, OCTOBER_31,
				List.of(salary, bonus, power, covered, zero, rent), accounts,
				Map.of(bonus.id(), ClosingResolution.CARRY_OVER, power.id(), ClosingResolution.CARRY_OVER,
						covered.id(), ClosingResolution.CARRY_OVER, zero.id(), ClosingResolution.CLOSE_AS_IS)));

		assertThat(plan.unresolvedEntryIds()).isEmpty();
		assertThat(plan.effects()).hasSize(4);
		assertThat(plan.newEntries()).extracting(NewEntry::name)
				.containsExactly("Saldo pendiente: Sueldo B", "Saldo pendiente: Luz");
	}

	/**
	 * RN-39 y D-43: una resolución para una consolidada del período, o para una partida que no es del período, se
	 * informa en el orden en que llegó y no se aplica. Las demás se aplican igual.
	 */
	@Test
	void resolutionsThatDoNotApplyAreReportedAndIgnored() {
		Map<Long, ClosingResolution> resolutions = new LinkedHashMap<>();
		resolutions.put(999L, ClosingResolution.CARRY_OVER);
		resolutions.put(rent.id(), ClosingResolution.CARRY_OVER);
		resolutions.put(power.id(), ClosingResolution.CARRY_OVER);
		resolutions.put(salary.id(), ClosingResolution.CLOSE_AS_IS);

		Plan plan = ClosingPlanner.plan(new Input(OCTOBER, PeriodStatus.OPEN, PeriodStatus.CLOSED, false, OCTOBER_31,
				List.of(salary, bonus, power, covered, zero, rent), accounts, resolutions));

		assertThat(plan.invalidResolutions()).containsExactly(
				new InvalidResolution(999L, InvalidResolutionReason.NOT_IN_PERIOD),
				new InvalidResolution(rent.id(), InvalidResolutionReason.NOT_PENDING),
				new InvalidResolution(salary.id(), InvalidResolutionReason.NOT_PENDING));
		assertThat(plan.effects()).extracting(EntryEffect::entryId).containsExactly(power.id());
		assertThat(plan.newEntries()).hasSize(1);
	}

	/** Sin resoluciones, todo pendiente falta resolver y no se crea nada: es el diagnóstico de HU-30. */
	@Test
	void withoutResolutionsEveryPendingEntryIsUnresolved() {
		Plan plan = planWith(PeriodStatus.OPEN, PeriodStatus.CLOSED, OCTOBER_31);

		assertThat(plan.unresolvedEntryIds()).containsExactly(bonus.id(), power.id(), covered.id(), zero.id());
		assertThat(plan.effects()).isEmpty();
		assertThat(plan.newEntries()).isEmpty();
	}

	/** Bloqueado por las precondiciones, los efectos se calculan igual (D-43). */
	@Test
	void aBlockedOpenPeriodStillComputesTheEffects() {
		Plan plan = ClosingPlanner.plan(new Input(OCTOBER, PeriodStatus.OPEN, PeriodStatus.OPEN, false,
				LocalDate.of(2026, 10, 16), List.of(power), accounts, Map.of(power.id(), ClosingResolution.CARRY_OVER)));

		assertThat(plan.preconditionsMet()).isFalse();
		assertThat(plan.newEntries()).hasSize(1);
	}

	/** Un período cerrado no evalúa las resoluciones: ni efectos ni inválidas (D-43). */
	@Test
	void aClosedPeriodIgnoresTheResolutions() {
		Plan plan = ClosingPlanner.plan(new Input(OCTOBER, PeriodStatus.CLOSED, PeriodStatus.CLOSED, false,
				LocalDate.of(2026, 11, 3), List.of(power, rent), accounts,
				Map.of(power.id(), ClosingResolution.CARRY_OVER, 999L, ClosingResolution.CLOSE_AS_IS)));

		assertThat(plan.effects()).isEmpty();
		assertThat(plan.newEntries()).isEmpty();
		assertThat(plan.unresolvedEntryIds()).isEmpty();
		assertThat(plan.invalidResolutions()).isEmpty();
	}
}
