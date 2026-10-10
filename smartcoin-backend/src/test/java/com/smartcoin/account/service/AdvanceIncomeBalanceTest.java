package com.smartcoin.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.domain.AccountTotal;
import com.smartcoin.account.domain.AccountType;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.AccountClosingRepository;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.transfer.repository.TransferRepository;
import com.smartcoin.user.repository.UserRepository;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-20, criterio 3, RN-35 y RN-41: el saldo suma los movimientos por su fecha, no por el período de su partida. Los
 * dos sueldos de diciembre cobrados el 25/11 y el 30/11 forman parte del saldo de noviembre; los del 02/12, no.
 *
 * <p>El repositorio simulado filtra por fecha en memoria, como lo hace la consulta {@code sumByAccountUpTo}: este test
 * prueba que {@link AccountService} pide el saldo a la fecha del reloj y arma el saldo con {@code BalanceCalculator}.
 * Que el SQL excluya lo posterior no se puede probar sin base (sin Docker); queda para HU-30 a HU-34.
 */
class AdvanceIncomeBalanceTest {

	static final long USER_ID = 7;
	static final long ACCOUNT_ID = 42;

	/** Un movimiento de la cuenta: la fecha cuenta, el período de la partida no figura. */
	record Stored(LocalDate date, EntryKind kind, String amount) {
	}

	/** Dos sueldos de diciembre cobrados por adelantado, un gasto de diciembre y un cobro ya de diciembre. */
	static final List<Stored> MOVEMENTS = List.of(
			new Stored(LocalDate.of(2026, 11, 25), EntryKind.INCOME, "1000000.00"),
			new Stored(LocalDate.of(2026, 11, 30), EntryKind.INCOME, "200000.00"),
			new Stored(LocalDate.of(2026, 11, 28), EntryKind.EXPENSE, "50000.00"),
			new Stored(LocalDate.of(2026, 12, 2), EntryKind.INCOME, "300000.00"));

	/** El saldo inicial de la cuenta es 100.000,00. */
	@ParameterizedTest(name = "hoy {0}: saldo {1}")
	@CsvSource({
			"2026-11-24, 100000.00",
			"2026-11-25, 1100000.00",
			"2026-11-27, 1100000.00",
			"2026-11-28, 1050000.00",
			"2026-11-29, 1050000.00",
			"2026-11-30, 1250000.00",
			"2026-12-01, 1250000.00",
			"2026-12-02, 1550000.00",
	})
	void theBalanceCountsTheDateOfTheMovementNotThePeriodOfItsEntry(String today, String expected) {
		AccountService service = serviceAt(today);

		List<AccountService.AccountView> accounts = service.list(USER_ID).accounts();

		assertThat(accounts).hasSize(1);
		assertThat(accounts.get(0).currentBalance()).isEqualTo(new BigDecimal(expected));
	}

	private static AccountService serviceAt(String today) {
		AccountRepository accounts = mock(AccountRepository.class);
		MovementRepository movements = mock(MovementRepository.class);
		TransferRepository transfers = mock(TransferRepository.class);
		Account account = new Account();
		ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
		account.setUserId(USER_ID);
		account.setName("Banco");
		account.setType(AccountType.BANK);
		account.setCurrency(Currency.ARS);
		account.setOpeningDate(LocalDate.of(2026, 8, 1));
		account.setInitialBalance(new BigDecimal("100000.00"));
		when(accounts.findByUserIdOrderByCurrencyAscNameAsc(USER_ID)).thenReturn(List.of(account));
		when(movements.sumByAccountUpTo(anyLong(), any(LocalDate.class), any(EntryKind.class))).thenAnswer(call -> {
			LocalDate cutoff = call.getArgument(1);
			EntryKind kind = call.getArgument(2);
			BigDecimal sum = MOVEMENTS.stream()
					.filter(m -> m.kind() == kind && !m.date().isAfter(cutoff))
					.map(m -> new BigDecimal(m.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
			return sum.signum() == 0 ? List.<AccountTotal>of() : List.of(new AccountTotal(ACCOUNT_ID, sum));
		});
		when(transfers.sumIncomingByAccountUpTo(anyLong(), any(LocalDate.class))).thenReturn(List.of());
		when(transfers.sumOutgoingByAccountUpTo(anyLong(), any(LocalDate.class))).thenReturn(List.of());
		Clock clock = Clock.fixed(Instant.parse(today + "T15:00:00Z"), ZoneId.of("America/Argentina/Mendoza"));
		return new AccountService(accounts, mock(BudgetItemRepository.class), mock(BudgetEntryRepository.class),
				movements, transfers, mock(AccountClosingRepository.class), mock(UserRepository.class), clock);
	}
}
