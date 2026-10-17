package com.smartcoin.period.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.domain.AccountTotal;
import com.smartcoin.account.domain.AccountType;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.account.service.AccountService;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.ClosingPlanner.AccountLine;
import com.smartcoin.period.repository.AccountClosingRepository;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.transfer.repository.TransferRepository;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-30, criterio 3, RN-35 y RN-41: el saldo calculado de cada cuenta a conciliar es el del último día del período,
 * con los movimientos y transferencias por su fecha. Octubre de 2026, con el reloj el 05/11.
 *
 * <p>Los repositorios simulados filtran por fecha y por usuario en memoria, como las consultas de suma: este test
 * prueba que la simulación pide los saldos al último día y los arma con {@code BalanceCalculator}. Que el SQL excluya
 * lo posterior se verifica con datos (notas de HU-30).
 */
class ClosingBalanceTest {

	static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
	static final long USER_ID = 7;
	static final long OTHER_USER_ID = 8;
	static final YearMonth OCTOBER = YearMonth.of(2026, 10);
	static final LocalDate LAST_DAY = LocalDate.of(2026, 10, 31);

	/** Un movimiento: cuenta la fecha; el período de su partida está para mostrar que no influye. */
	record StoredMovement(long userId, long accountId, LocalDate date, EntryKind kind, String amount,
			YearMonth entryPeriod) {
	}

	record StoredTransfer(long userId, long sourceId, long targetId, LocalDate date, String sourceAmount,
			String targetAmount) {
	}

	Account bank = account(10, "Banco", Currency.ARS, "2026-08-01", "100000.00");
	Account dollars = account(11, "Dólares", Currency.USD, "2026-09-01", "500.00");
	Account wallet = account(12, "Billetera", Currency.ARS, "2026-11-01", "0.00");

	List<StoredMovement> movementsData = List.of(
			// Del último día: entra.
			new StoredMovement(USER_ID, 10, LAST_DAY, EntryKind.EXPENSE, "20000.00", OCTOBER),
			// Del día siguiente, de una partida de octubre: no entra.
			new StoredMovement(USER_ID, 10, LAST_DAY.plusDays(1), EntryKind.EXPENSE, "7000.00", OCTOBER),
			// Sueldo de noviembre cobrado el 25/10 (RN-41): entra.
			new StoredMovement(USER_ID, 10, LocalDate.of(2026, 10, 25), EntryKind.INCOME, "300000.00",
					YearMonth.of(2026, 11)),
			// De otro usuario, sobre un id de cuenta igual: no entra.
			new StoredMovement(OTHER_USER_ID, 10, LocalDate.of(2026, 10, 10), EntryKind.INCOME, "999999.00",
					OCTOBER));

	List<StoredTransfer> transfersData = List.of(
			// Compra de dólares: salen pesos del banco, entran dólares.
			new StoredTransfer(USER_ID, 10, 11, LocalDate.of(2026, 10, 20), "125000.00", "100.00"),
			// Del día siguiente: no entra.
			new StoredTransfer(USER_ID, 11, 10, LAST_DAY.plusDays(1), "50.00", "60000.00"),
			new StoredTransfer(OTHER_USER_ID, 10, 11, LocalDate.of(2026, 10, 20), "1.00", "1.00"));

	BudgetPeriodRepository periods = mock(BudgetPeriodRepository.class);
	BudgetEntryRepository entries = mock(BudgetEntryRepository.class);
	MovementRepository movements = mock(MovementRepository.class);
	TransferRepository transfers = mock(TransferRepository.class);
	AccountRepository accounts = mock(AccountRepository.class);
	ClosingService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(Instant.parse("2026-11-05T15:00:00Z"), ZONE);
		BudgetPeriod october = BudgetPeriod.open(USER_ID, OCTOBER);
		ReflectionTestUtils.setField(october, "id", 100L);
		when(periods.findByUserIdAndPeriodMonth(USER_ID, OCTOBER)).thenReturn(Optional.of(october));
		when(periods.findPeriodMonthsByUserId(USER_ID)).thenReturn(List.of(OCTOBER, OCTOBER.plusMonths(1)));
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID)).thenReturn(List.of(bank, wallet, dollars));

		when(movements.sumByAccountUpTo(anyLong(), any(LocalDate.class), any(EntryKind.class))).thenAnswer(call -> {
			long userId = call.getArgument(0);
			LocalDate cutoff = call.getArgument(1);
			EntryKind kind = call.getArgument(2);
			return sum(movementsData, m -> m.userId() == userId && m.kind() == kind && !m.date().isAfter(cutoff),
					StoredMovement::accountId, StoredMovement::amount);
		});
		when(transfers.sumIncomingByAccountUpTo(anyLong(), any(LocalDate.class))).thenAnswer(call -> {
			long userId = call.getArgument(0);
			LocalDate cutoff = call.getArgument(1);
			return sum(transfersData, t -> t.userId() == userId && !t.date().isAfter(cutoff),
					StoredTransfer::targetId, StoredTransfer::targetAmount);
		});
		when(transfers.sumOutgoingByAccountUpTo(anyLong(), any(LocalDate.class))).thenAnswer(call -> {
			long userId = call.getArgument(0);
			LocalDate cutoff = call.getArgument(1);
			return sum(transfersData, t -> t.userId() == userId && !t.date().isAfter(cutoff),
					StoredTransfer::sourceId, StoredTransfer::sourceAmount);
		});

		AccountService accountService = new AccountService(accounts, mock(BudgetItemRepository.class), entries,
				movements, transfers, mock(AccountClosingRepository.class), mock(UserRepository.class), clock);
		service = new ClosingService(periods, new PeriodViewService(periods, entries, movements, clock),
				accountService, clock);
	}

	private static <T> List<AccountTotal> sum(List<T> rows, Predicate<T> filter, Function<T, Long> account,
			Function<T, String> amount) {
		Map<Long, BigDecimal> totals = rows.stream().filter(filter).collect(Collectors.groupingBy(account,
				Collectors.reducing(BigDecimal.ZERO, row -> new BigDecimal(amount.apply(row)), BigDecimal::add)));
		return totals.entrySet().stream().map(e -> new AccountTotal(e.getKey(), e.getValue())).toList();
	}

	private Map<String, BigDecimal> balances() {
		return service.preview(USER_ID, OCTOBER, List.of(), List.of()).plan().accounts().stream()
				.collect(Collectors.toMap(AccountLine::name, AccountLine::computedBalance));
	}

	/**
	 * Banco: 100.000,00 − 20.000,00 (pago del 31/10) + 300.000,00 (sueldo de noviembre cobrado el 25/10) − 125.000,00
	 * (compra de dólares) = 255.000,00. No entran el pago ni la venta del 01/11, ni lo de otro usuario.
	 */
	@Test
	void theBankBalanceAtTheLastDay() {
		assertThat(balances().get("Banco")).isEqualTo(new BigDecimal("255000.00"));
	}

	/** Dólares: 500,00 + 100,00 de la compra; la venta del 01/11 no entra. */
	@Test
	void aTransferBetweenCurrenciesAddsInTheTargetCurrency() {
		assertThat(balances().get("Dólares")).isEqualTo(new BigDecimal("600.00"));
	}

	/** La billetera se abrió el 01/11: no se concilia en octubre. */
	@Test
	void anAccountOpenedAfterTheLastDayIsNotListed() {
		assertThat(balances()).containsOnlyKeys("Banco", "Dólares");
	}

	@Test
	void theBalancesAreAskedAtTheLastDayWithTheCurrentUser() {
		service.preview(USER_ID, OCTOBER, List.of(), List.of());

		verify(movements).sumByAccountUpTo(USER_ID, LAST_DAY, EntryKind.INCOME);
		verify(movements).sumByAccountUpTo(USER_ID, LAST_DAY, EntryKind.EXPENSE);
		verify(transfers).sumIncomingByAccountUpTo(eq(USER_ID), eq(LAST_DAY));
		verify(transfers).sumOutgoingByAccountUpTo(eq(USER_ID), eq(LAST_DAY));
	}

	static Account account(long id, String name, Currency currency, String openingDate, String initialBalance) {
		Account account = new Account();
		ReflectionTestUtils.setField(account, "id", id);
		account.setUserId(USER_ID);
		account.setName(name);
		account.setType(AccountType.BANK);
		account.setCurrency(currency);
		account.setOpeningDate(LocalDate.parse(openingDate));
		account.setInitialBalance(new BigDecimal(initialBalance));
		return account;
	}
}
