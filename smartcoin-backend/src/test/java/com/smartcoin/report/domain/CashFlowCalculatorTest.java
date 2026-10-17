package com.smartcoin.report.domain;

import static com.smartcoin.shared.domain.Currency.ARS;
import static com.smartcoin.shared.domain.Currency.USD;
import static com.smartcoin.shared.domain.EntryKind.EXPENSE;
import static com.smartcoin.shared.domain.EntryKind.INCOME;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import com.smartcoin.report.domain.CashFlowCalculator.AccountOpening;
import com.smartcoin.report.domain.CashFlowCalculator.CashFlowRowStatus;
import com.smartcoin.report.domain.CashFlowCalculator.CashFlowRowType;
import com.smartcoin.report.domain.CashFlowCalculator.Input;
import com.smartcoin.report.domain.CashFlowCalculator.MovementLine;
import com.smartcoin.report.domain.CashFlowCalculator.PendingLine;
import com.smartcoin.report.domain.CashFlowCalculator.Result;
import com.smartcoin.report.domain.CashFlowCalculator.Row;
import com.smartcoin.report.domain.CashFlowCalculator.TransferLine;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** RN-45, RN-04, RN-20, RN-41 y D-46: flujo de caja de una moneda. Hoy es siempre el 17/10/2026. */
class CashFlowCalculatorTest {

	private static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T15:00:00Z"), ZONE);
	private static final LocalDate TODAY = LocalDate.now(CLOCK);

	private static BigDecimal amount(String text) {
		return new BigDecimal(text);
	}

	private static LocalDate date(String text) {
		return LocalDate.parse(text);
	}

	private static MovementLine movement(long id, String date, EntryKind kind, String name, String amount) {
		return new MovementLine(id, date(date), kind, name, "Banco", ARS, amount(amount));
	}

	private static PendingLine pending(long id, EntryKind kind, String name, String due, String amount) {
		return new PendingLine(id, kind, name, "Banco", ARS, date(due), amount(amount));
	}

	private static Input arsInput(String from, String to, String opening, List<MovementLine> movements,
			List<PendingLine> pending) {
		return new Input(ARS, date(from), date(to), TODAY, amount(opening), List.of(), movements, List.of(), pending);
	}

	private static List<String> balances(Result result) {
		return result.rows().stream().map(row -> row.balance().toPlainString()).toList();
	}

	private static List<String> descriptions(Result result) {
		return result.rows().stream().map(Row::description).toList();
	}

	@Test
	void theStoryExampleDecemberSalariesAppearOnNovember25And30() {
		// Dos sueldos de diciembre cobrados el 25/11 y el 30/11 (RN-41): forman parte del flujo de noviembre.
		Input input = new Input(ARS, date("2026-11-01"), date("2026-11-30"), date("2026-11-30"), amount("50000.00"),
				List.of(),
				List.of(movement(1, "2026-11-25", INCOME, "Sueldo diciembre A", "400000.00"),
						movement(2, "2026-11-30", INCOME, "Sueldo diciembre B", "250000.00")),
				List.of(), List.of());

		Result result = CashFlowCalculator.calculate(input);

		assertThat(result.rows()).extracting(Row::date).containsExactly(date("2026-11-25"), date("2026-11-30"));
		assertThat(result.rows()).extracting(Row::status).containsOnly(CashFlowRowStatus.REAL);
		assertThat(balances(result)).containsExactly("450000.00", "700000.00");
		assertThat(result.openingBalance()).isEqualByComparingTo("50000.00");
		assertThat(result.closingBalance()).isEqualByComparingTo("700000.00");
		assertThat(result.openingBalanceProjected()).isFalse();
	}

	@Test
	void aRealPaymentAndARealCollectionHaveTheSignOfTheirEntryKind() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-10", "80000.00",
				List.of(movement(1, "2026-10-05", INCOME, "Cobro", "30000.00"),
						movement(2, "2026-10-08", EXPENSE, "Pago", "10000.00")),
				List.of()));

		assertThat(result.rows()).extracting(Row::amount).containsExactly(amount("30000.00"), amount("-10000.00"));
		assertThat(balances(result)).containsExactly("110000.00", "100000.00");
		assertThat(result.rows()).extracting(Row::type).containsOnly(CashFlowRowType.MOVEMENT);
		assertThat(result.rows().get(0).description()).isEqualTo("Cobro · Banco");
	}

	@Test
	void aPendingEntryDueInsideTheRangeIsPlannedOnItsDueDate() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-31", "100000.00", List.of(),
				List.of(pending(1, EXPENSE, "Tarjeta", "2026-10-20", "40000.00"),
						pending(2, INCOME, "Sueldo", "2026-10-30", "300000.00"))));

		assertThat(result.rows()).extracting(Row::date).containsExactly(date("2026-10-20"), date("2026-10-30"));
		assertThat(result.rows()).extracting(Row::status).containsOnly(CashFlowRowStatus.PLANNED);
		assertThat(result.rows()).extracting(Row::overdue).containsOnly(false);
		assertThat(result.rows().get(0).dueDate()).isEqualTo(date("2026-10-20"));
		assertThat(balances(result)).containsExactly("60000.00", "360000.00");
	}

	@Test
	void anOverduePendingEntryGoesToTodayAndKeepsItsDueDate() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-31", "100000.00", List.of(),
				List.of(pending(1, EXPENSE, "Luz", "2026-10-10", "20000.00"))));

		Row luz = result.rows().get(0);
		assertThat(luz.date()).isEqualTo(TODAY);
		assertThat(luz.dueDate()).isEqualTo(date("2026-10-10"));
		assertThat(luz.overdue()).isTrue();
		assertThat(luz.status()).isEqualTo(CashFlowRowStatus.PLANNED);
	}

	@Test
	void aPendingEntryDueTodayIsNotOverdue() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-31", "0.00", List.of(),
				List.of(pending(1, EXPENSE, "Luz", "2026-10-17", "20000.00"))));

		assertThat(result.rows().get(0).overdue()).isFalse();
		assertThat(result.rows().get(0).date()).isEqualTo(TODAY);
	}

	@Test
	void aPendingEntryWithNothingPendingIsNotListed() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-31", "0.00", List.of(),
				List.of(pending(1, EXPENSE, "Pagada sin consolidar", "2026-10-20", "0.00"))));

		assertThat(result.rows()).isEmpty();
		assertThat(result.closingBalance()).isEqualByComparingTo("0.00");
	}

	// Duda a (D-46): rango que empieza después de hoy. Saldo hoy 100.000; Luz vencida 20.000 (va a hoy), Tarjeta
	// 40.000 el 20/10, Sueldo 300.000 el 30/10 y Alquiler 150.000 el 05/11.
	@Test
	void aRangeStartingAfterTodayOpensWithAProjectedBalance() {
		Input input = arsInput("2026-11-01", "2026-11-30", "100000.00", List.of(),
				List.of(pending(1, EXPENSE, "Luz", "2026-10-10", "20000.00"),
						pending(2, EXPENSE, "Tarjeta", "2026-10-20", "40000.00"),
						pending(3, INCOME, "Sueldo", "2026-10-30", "300000.00"),
						pending(4, EXPENSE, "Alquiler", "2026-11-05", "150000.00")));

		Result result = CashFlowCalculator.calculate(input);

		assertThat(result.openingBalance()).isEqualByComparingTo("340000.00");
		assertThat(result.openingBalanceProjected()).isTrue();
		assertThat(descriptions(result)).containsExactly("Alquiler · Banco");
		assertThat(balances(result)).containsExactly("190000.00");
	}

	@Test
	void aRangeStartingTodayIsNotProjectedAndKeepsOverdueEntriesInTheRange() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-17", "2026-10-31", "100000.00", List.of(),
				List.of(pending(1, EXPENSE, "Luz", "2026-10-10", "20000.00"))));

		assertThat(result.openingBalanceProjected()).isFalse();
		assertThat(result.openingBalance()).isEqualByComparingTo("100000.00");
		assertThat(balances(result)).containsExactly("80000.00");
	}

	@Test
	void aRangeStartingTomorrowIsProjectedAndTodaysPendingEntriesGoIntoTheOpening() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-18", "2026-10-31", "100000.00", List.of(),
				List.of(pending(1, EXPENSE, "Vence hoy", "2026-10-17", "5000.00"))));

		assertThat(result.openingBalanceProjected()).isTrue();
		assertThat(result.openingBalance()).isEqualByComparingTo("95000.00");
		assertThat(result.rows()).isEmpty();
	}

	// Duda b (D-46): un rango pasado muestra solo hechos; Luz (vencida el 10/10) va a hoy, fuera del rango.
	@Test
	void aRangeEndingBeforeTodayDoesNotListOverdueEntries() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-10", "80000.00",
				List.of(movement(1, "2026-10-05", INCOME, "Cobro", "30000.00"),
						movement(2, "2026-10-08", EXPENSE, "Pago", "10000.00")),
				List.of(pending(3, EXPENSE, "Luz", "2026-10-10", "20000.00"))));

		assertThat(descriptions(result)).containsExactly("Cobro · Banco", "Pago · Banco");
		assertThat(result.closingBalance()).isEqualByComparingTo("100000.00");
		assertThat(result.openingBalanceProjected()).isFalse();
	}

	@Test
	void aRangeCrossingTodayListsRealThenOverdueAtTodayThenTheRest() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-31", "80000.00",
				List.of(movement(1, "2026-10-05", INCOME, "Cobro", "30000.00"),
						movement(2, "2026-10-08", EXPENSE, "Pago", "10000.00")),
				List.of(pending(3, EXPENSE, "Luz", "2026-10-10", "20000.00"),
						pending(4, EXPENSE, "Tarjeta", "2026-10-20", "40000.00"),
						pending(5, INCOME, "Sueldo", "2026-10-30", "300000.00"))));

		assertThat(result.rows()).extracting(Row::date).containsExactly(date("2026-10-05"), date("2026-10-08"),
				date("2026-10-17"), date("2026-10-20"), date("2026-10-30"));
		assertThat(result.rows()).extracting(Row::status).containsExactly(CashFlowRowStatus.REAL, CashFlowRowStatus.REAL, CashFlowRowStatus.PLANNED,
				CashFlowRowStatus.PLANNED, CashFlowRowStatus.PLANNED);
		assertThat(result.closingBalance()).isEqualByComparingTo("340000.00");
	}

	@Test
	void aPendingEntryAfterTheRangeIsNotListed() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-31", "0.00", List.of(),
				List.of(pending(1, EXPENSE, "Alquiler", "2026-11-05", "150000.00"))));

		assertThat(result.rows()).isEmpty();
		assertThat(result.closingBalance()).isEqualByComparingTo("0.00");
	}

	// Duda c (D-46): una cuenta que abre dentro del rango suma su saldo inicial como una fila real en su apertura.
	@Test
	void anAccountOpenedInsideTheRangeShowsItsInitialBalanceOnTheOpeningDate() {
		Input input = new Input(ARS, date("2026-10-10"), date("2026-10-31"), TODAY, amount("80000.00"),
				List.of(new AccountOpening(1, "Billetera", ARS, date("2026-10-12"), amount("20000.00"))),
				List.of(), List.of(), List.of());

		Result result = CashFlowCalculator.calculate(input);

		assertThat(result.openingBalance()).isEqualByComparingTo("80000.00");
		Row opening = result.rows().get(0);
		assertThat(opening.type()).isEqualTo(CashFlowRowType.ACCOUNT_OPENING);
		assertThat(opening.status()).isEqualTo(CashFlowRowStatus.REAL);
		assertThat(opening.date()).isEqualTo(date("2026-10-12"));
		assertThat(opening.description()).isEqualTo("Saldo inicial de Billetera");
		assertThat(opening.balance()).isEqualByComparingTo("100000.00");
	}

	@Test
	void aNegativeInitialBalanceSubtractsAndAZeroOneIsNotListed() {
		Input input = new Input(ARS, date("2026-10-01"), date("2026-10-31"), TODAY, amount("10000.00"),
				List.of(new AccountOpening(1, "Tarjeta", ARS, date("2026-10-03"), amount("-4000.00")),
						new AccountOpening(2, "Vacía", ARS, date("2026-10-04"), amount("0.00"))),
				List.of(), List.of(), List.of());

		Result result = CashFlowCalculator.calculate(input);

		assertThat(descriptions(result)).containsExactly("Saldo inicial de Tarjeta");
		assertThat(balances(result)).containsExactly("6000.00");
	}

	@Test
	void anAccountOpenedOnTheFirstDayOfTheRangeIsARowNotPartOfTheOpening() {
		Input input = new Input(ARS, date("2026-10-01"), date("2026-10-31"), TODAY, amount("0.00"),
				List.of(new AccountOpening(1, "Nueva", ARS, date("2026-10-01"), amount("5000.00"))),
				List.of(), List.of(), List.of());

		Result result = CashFlowCalculator.calculate(input);

		assertThat(result.openingBalance()).isEqualByComparingTo("0.00");
		assertThat(result.rows()).hasSize(1);
		assertThat(result.rows().get(0).date()).isEqualTo(date("2026-10-01"));
	}

	@Test
	void anAccountOpenedAfterTheRangeAndOneOfAnotherCurrencyAreIgnored() {
		Input input = new Input(ARS, date("2026-10-01"), date("2026-10-15"), TODAY, amount("0.00"),
				List.of(new AccountOpening(1, "Posterior", ARS, date("2026-10-16"), amount("5000.00")),
						new AccountOpening(2, "Dólares", USD, date("2026-10-05"), amount("100.00"))),
				List.of(), List.of(), List.of());

		assertThat(CashFlowCalculator.calculate(input).rows()).isEmpty();
	}

	// Duda d (D-46): saldo inicial, real, prevista; en cada grupo ingresos antes que gastos; descripción; creación.
	@Test
	void withinTheSameDayRealComesBeforePlannedAndIncomesBeforeExpenses() {
		Input input = new Input(ARS, date("2026-10-17"), date("2026-10-17"), TODAY, amount("10000.00"),
				List.of(new AccountOpening(9, "Nueva", ARS, TODAY, amount("1000.00"))),
				List.of(movement(1, "2026-10-17", EXPENSE, "Supermercado", "30000.00"),
						movement(2, "2026-10-17", INCOME, "Venta", "5000.00")),
				List.of(),
				List.of(pending(3, INCOME, "Cobro atrasado", "2026-10-12", "50000.00"),
						pending(4, EXPENSE, "Luz", "2026-10-17", "20000.00")));

		Result result = CashFlowCalculator.calculate(input);

		assertThat(descriptions(result)).containsExactly("Saldo inicial de Nueva", "Venta · Banco",
				"Supermercado · Banco", "Cobro atrasado · Banco", "Luz · Banco");
		assertThat(balances(result)).containsExactly("11000.00", "16000.00", "-14000.00", "36000.00", "16000.00");
	}

	@Test
	void theExampleOfTheDoubtDEndsAtTheSameBalanceAndMarksTheNegativeRealRow() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-17", "2026-10-17", "10000.00",
				List.of(movement(1, "2026-10-17", EXPENSE, "Supermercado", "30000.00")),
				List.of(pending(2, INCOME, "Cobro atrasado", "2026-10-12", "50000.00"),
						pending(3, EXPENSE, "Luz", "2026-10-12", "20000.00"))));

		assertThat(balances(result)).containsExactly("-20000.00", "30000.00", "10000.00");
	}

	@Test
	void tiesBreakByDescriptionIgnoringCaseAndThenByCreationOrder() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-10", "0.00",
				List.of(movement(7, "2026-10-05", EXPENSE, "beta", "1.00"),
						movement(5, "2026-10-05", EXPENSE, "Alfa", "2.00"),
						movement(3, "2026-10-05", EXPENSE, "Gamma", "3.00"),
						movement(2, "2026-10-05", EXPENSE, "Gamma", "4.00")),
				List.of()));

		assertThat(result.rows()).extracting(Row::amount).containsExactly(amount("-2.00"), amount("-1.00"),
				amount("-4.00"), amount("-3.00"));
	}

	// Transferencias entre monedas (RN-37, RN-45).
	private static TransferLine buy() {
		return new TransferLine(1, date("2026-10-06"), "Banco", ARS, amount("1250000.00"), "Caja USD", USD,
				amount("1000.00"));
	}

	private static TransferLine sell() {
		return new TransferLine(2, date("2026-10-07"), "Caja USD", USD, amount("500.00"), "Banco", ARS,
				amount("610000.00"));
	}

	private static Input transfersInput(Currency currency, TransferLine... transfers) {
		return new Input(currency, date("2026-10-01"), date("2026-10-31"), TODAY, amount("0.00"), List.of(), List.of(),
				List.of(transfers), List.of());
	}

	@Test
	void aPurchaseOfDollarsSubtractsInTheArsFlowAndAddsInTheUsdFlow() {
		Result ars = CashFlowCalculator.calculate(transfersInput(ARS, buy()));
		Result usd = CashFlowCalculator.calculate(transfersInput(USD, buy()));

		assertThat(ars.rows()).hasSize(1);
		assertThat(ars.rows().get(0).amount()).isEqualByComparingTo("-1250000.00");
		assertThat(ars.rows().get(0).type()).isEqualTo(CashFlowRowType.TRANSFER);
		assertThat(ars.rows().get(0).status()).isEqualTo(CashFlowRowStatus.REAL);
		assertThat(ars.rows().get(0).description()).isEqualTo("Compra de dólares: Banco → Caja USD");
		assertThat(usd.rows().get(0).amount()).isEqualByComparingTo("1000.00");
		assertThat(usd.rows().get(0).balance()).isEqualByComparingTo("1000.00");
	}

	@Test
	void aSaleOfDollarsAddsInTheArsFlowAndSubtractsInTheUsdFlow() {
		Result ars = CashFlowCalculator.calculate(transfersInput(ARS, sell()));
		Result usd = CashFlowCalculator.calculate(transfersInput(USD, sell()));

		assertThat(ars.rows().get(0).amount()).isEqualByComparingTo("610000.00");
		assertThat(ars.rows().get(0).description()).isEqualTo("Venta de dólares: Caja USD → Banco");
		assertThat(usd.rows().get(0).amount()).isEqualByComparingTo("-500.00");
	}

	@Test
	void aTransferBetweenAccountsOfTheSameCurrencyIsNotListed() {
		TransferLine same = new TransferLine(3, date("2026-10-08"), "Banco", ARS, amount("5000.00"), "Billetera", ARS,
				amount("5000.00"));
		TransferLine sameUsd = new TransferLine(4, date("2026-10-08"), "Caja USD", USD, amount("5.00"), "Otra USD", USD,
				amount("5.00"));

		assertThat(CashFlowCalculator.calculate(transfersInput(ARS, same, sameUsd)).rows()).isEmpty();
		assertThat(CashFlowCalculator.calculate(transfersInput(USD, same, sameUsd)).rows()).isEmpty();
	}

	@Test
	void transfersOutsideTheRangeAreNotListed() {
		TransferLine before = new TransferLine(5, date("2026-09-30"), "Banco", ARS, amount("1000.00"), "Caja USD", USD,
				amount("1.00"));

		assertThat(CashFlowCalculator.calculate(transfersInput(ARS, before)).rows()).isEmpty();
	}

	// RN-04: nunca se mezclan monedas.
	@Test
	void linesOfAnotherCurrencyNeverEnterTheFlow() {
		Input input = new Input(USD, date("2026-10-01"), date("2026-10-31"), TODAY, amount("100.00"), List.of(),
				List.of(new MovementLine(1, date("2026-10-05"), INCOME, "Cobro en USD", "Caja USD", USD, amount("50.00")),
						movement(2, "2026-10-06", INCOME, "Cobro en ARS", "30000.00")),
				List.of(),
				List.of(new PendingLine(3, EXPENSE, "Suscripción", "Caja USD", USD, date("2026-10-20"), amount("10.00")),
						pending(4, EXPENSE, "Luz", "2026-10-20", "20000.00")));

		Result result = CashFlowCalculator.calculate(input);

		assertThat(descriptions(result)).containsExactly("Cobro en USD · Caja USD", "Suscripción · Caja USD");
		assertThat(balances(result)).containsExactly("150.00", "140.00");
	}

	@Test
	void aPendingEntryOfAnotherCurrencyDoesNotTouchTheProjectedOpening() {
		Input input = new Input(USD, date("2026-11-01"), date("2026-11-30"), TODAY, amount("100.00"), List.of(),
				List.of(), List.of(), List.of(pending(1, EXPENSE, "Luz", "2026-10-20", "20000.00")));

		assertThat(CashFlowCalculator.calculate(input).openingBalance()).isEqualByComparingTo("100.00");
	}

	// El acumulado fila por fila, incluido uno negativo.
	@Test
	void theRunningBalanceIsComputedRowByRowAndCanGoNegative() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-31", "20000.00",
				List.of(movement(1, "2026-10-02", EXPENSE, "Compra", "50000.00")),
				List.of(pending(2, INCOME, "Sueldo", "2026-10-30", "300000.00"),
						pending(3, EXPENSE, "Tarjeta", "2026-10-20", "40000.00"))));

		assertThat(balances(result)).containsExactly("-30000.00", "-70000.00", "230000.00");
		assertThat(result.rows()).extracting(Row::balance).extracting(b -> b.signum() < 0)
				.containsExactly(true, true, false);
		assertThat(result.closingBalance()).isEqualByComparingTo("230000.00");
	}

	@Test
	void withoutRowsTheClosingBalanceIsTheOpeningBalance() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-01", "2026-10-31", "1234.50", List.of(), List.of()));

		assertThat(result.rows()).isEmpty();
		assertThat(result.closingBalance()).isEqualByComparingTo("1234.50");
		assertThat(result.closingBalance().scale()).isEqualTo(2);
	}

	@Test
	void movementsOutsideTheRangeAreIgnored() {
		Result result = CashFlowCalculator.calculate(arsInput("2026-10-05", "2026-10-10", "0.00",
				List.of(movement(1, "2026-10-04", INCOME, "Antes", "1.00"),
						movement(2, "2026-10-05", INCOME, "Primero", "2.00"),
						movement(3, "2026-10-10", INCOME, "Último", "4.00"),
						movement(4, "2026-10-11", INCOME, "Después", "8.00")),
				List.of()));

		assertThat(descriptions(result)).containsExactly("Primero · Banco", "Último · Banco");
		assertThat(result.closingBalance()).isEqualByComparingTo("6.00");
	}

	@ParameterizedTest(name = "rango {0} a {1}: arranque proyectado = {2}")
	@MethodSource("projectedFlags")
	void theOpeningIsProjectedOnlyWhenTheRangeStartsAfterToday(String from, String to, boolean projected) {
		Result result = CashFlowCalculator.calculate(arsInput(from, to, "0.00", List.of(), List.of()));

		assertThat(result.openingBalanceProjected()).isEqualTo(projected);
	}

	static Stream<Arguments> projectedFlags() {
		return Stream.of(
				Arguments.of("2026-10-01", "2026-10-31", false),
				Arguments.of("2026-10-16", "2026-10-31", false),
				Arguments.of("2026-10-17", "2026-10-31", false),
				Arguments.of("2026-10-18", "2026-10-31", true),
				Arguments.of("2026-11-01", "2026-11-30", true));
	}

	// Signo y fecha efectiva de un pendiente, según dónde cae el rango (hoy = 17/10).
	@ParameterizedTest(name = "pendiente {0} {1} con vencimiento {2}, rango {3} a {4}: fila el {5}, arranque {6}")
	@MethodSource("pendingPlacement")
	void aPendingEntryIsPlacedByItsEffectiveDate(EntryKind kind, String amount, String due, String from, String to,
			String rowDate, String opening) {
		Result result = CashFlowCalculator.calculate(arsInput(from, to, "1000.00", List.of(),
				List.of(pending(1, kind, "Partida", due, amount))));

		if (rowDate == null) {
			assertThat(result.rows()).isEmpty();
		}
		else {
			assertThat(result.rows()).extracting(Row::date).containsExactly(date(rowDate));
		}
		assertThat(result.openingBalance()).isEqualByComparingTo(opening);
	}

	static Stream<Arguments> pendingPlacement() {
		return Stream.of(
				// Vence dentro del rango.
				Arguments.of(EXPENSE, "100.00", "2026-10-25", "2026-10-01", "2026-10-31", "2026-10-25", "1000.00"),
				// Vencido: va en hoy.
				Arguments.of(EXPENSE, "100.00", "2026-10-01", "2026-10-01", "2026-10-31", "2026-10-17", "1000.00"),
				// Vencido y el rango termina un día antes de hoy: no se lista.
				Arguments.of(EXPENSE, "100.00", "2026-10-01", "2026-10-01", "2026-10-16", null, "1000.00"),
				// Vencido y el rango termina hoy: se lista.
				Arguments.of(INCOME, "100.00", "2026-10-01", "2026-10-01", "2026-10-17", "2026-10-17", "1000.00"),
				// Vence antes del rango futuro: entra al arranque proyectado con su signo.
				Arguments.of(EXPENSE, "100.00", "2026-10-25", "2026-11-01", "2026-11-30", null, "900.00"),
				Arguments.of(INCOME, "100.00", "2026-10-25", "2026-11-01", "2026-11-30", null, "1100.00"),
				// Vencido y el rango es futuro: también entra al arranque.
				Arguments.of(EXPENSE, "100.00", "2026-09-01", "2026-11-01", "2026-11-30", null, "900.00"),
				// Vence el último día del rango y el primer día del rango.
				Arguments.of(EXPENSE, "100.00", "2026-11-30", "2026-11-01", "2026-11-30", "2026-11-30", "1000.00"),
				Arguments.of(EXPENSE, "100.00", "2026-11-01", "2026-11-01", "2026-11-30", "2026-11-01", "1000.00"),
				// Vence después del rango.
				Arguments.of(EXPENSE, "100.00", "2026-12-01", "2026-11-01", "2026-11-30", null, "1000.00"));
	}
}
