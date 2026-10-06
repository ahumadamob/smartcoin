package com.smartcoin.account.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.domain.AccountEditability;
import com.smartcoin.account.domain.AccountTotal;
import com.smartcoin.account.domain.AccountUsage;
import com.smartcoin.account.domain.AccountValues;
import com.smartcoin.account.domain.BalanceCalculator;
import com.smartcoin.account.domain.BalanceCalculator.Totals;
import com.smartcoin.account.domain.OpeningDateRule;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.AccountClosingRepository;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.transfer.repository.TransferRepository;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrar cuentas (HU-07, RN-33) y calcular su saldo (HU-08, RN-35). Toda consulta lleva el {@code userId} del usuario actual. */
@Service
public class AccountService {

	/** Una cuenta con lo que se puede editar de ella y su saldo a hoy (RN-35). */
	public record AccountView(Account account, AccountEditability editability, BigDecimal currentBalance) {
	}

	/** Suma de los saldos de las cuentas de una moneda. Nunca se mezclan monedas (RN-04). */
	public record CurrencySubtotal(Currency currency, BigDecimal balance) {
	}

	/** Las cuentas del usuario con su saldo a hoy y un subtotal por cada moneda que tiene cuentas. */
	public record AccountList(List<AccountView> accounts, List<CurrencySubtotal> subtotals) {
	}

	private final AccountRepository accounts;
	private final BudgetItemRepository items;
	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final TransferRepository transfers;
	private final AccountClosingRepository closings;
	private final UserRepository users;
	private final Clock clock;

	public AccountService(AccountRepository accounts, BudgetItemRepository items, BudgetEntryRepository entries,
			MovementRepository movements, TransferRepository transfers, AccountClosingRepository closings,
			UserRepository users, Clock clock) {
		this.accounts = accounts;
		this.items = items;
		this.entries = entries;
		this.movements = movements;
		this.transfers = transfers;
		this.closings = closings;
		this.users = users;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AccountList list(long userId) {
		List<Account> all = accounts.findByUserIdOrderByCurrencyAscNameAsc(userId);
		Map<Long, Totals> totals = totalsUpTo(userId, today());
		List<AccountView> views = all.stream()
				.map(account -> view(userId, account, totals))
				.toList();
		return new AccountList(views, subtotals(views));
	}

	@Transactional(readOnly = true)
	public AccountView get(long userId, long id) {
		Account account = find(userId, id);
		return view(userId, account, totalsUpTo(userId, today()));
	}

	@Transactional
	public AccountView create(long userId, AccountValues values) {
		User user = user(userId);
		AccountValues normalized = normalize(values);
		OpeningDateRule.validate(normalized.openingDate(), user.getStartPeriod(), today());
		if (accounts.existsByUserIdAndName(userId, normalized.name())) {
			throw nameTaken();
		}
		Account account = new Account();
		account.setUserId(userId);
		apply(account, normalized);
		Account saved = save(account);
		// Una cuenta nueva no tiene movimientos ni transferencias: su saldo es el inicial.
		return new AccountView(saved, AccountEditability.of(AccountUsage.unused()),
				BalanceCalculator.balance(saved.getInitialBalance(), Totals.none()));
	}

	@Transactional
	public AccountView update(long userId, long id, AccountValues values) {
		Account account = find(userId, id);
		AccountValues current = AccountValues.of(account);
		AccountValues requested = normalize(values);

		// Primero el formato y el rango (400); después lo que el estado de la cuenta no permite (409).
		if (!current.openingDate().equals(requested.openingDate())) {
			OpeningDateRule.validate(requested.openingDate(), user(userId).getStartPeriod(), today());
		}
		AccountEditability editability = AccountEditability.of(usageOf(userId, account));
		editability.verifyChange(current, requested);
		if (accounts.existsByUserIdAndNameAndIdNot(userId, requested.name(), id)) {
			throw nameTaken();
		}

		apply(account, requested);
		Account saved = save(account);
		return new AccountView(saved, editability, balanceOf(saved, totalsUpTo(userId, today())));
	}

	@Transactional
	public void delete(long userId, long id) {
		Account account = find(userId, id);
		if (usageOf(userId, account).referenced()) {
			throw inUse();
		}
		try {
			accounts.delete(account);
			accounts.flush();
		}
		catch (DataIntegrityViolationException e) {
			// Una referencia creada entre la verificación y el borrado: la clave foránea la frena.
			throw inUse();
		}
	}

	private Account find(long userId, long id) {
		return accounts.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La cuenta no existe."));
	}

	private User user(long userId) {
		return users.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Credenciales inválidas o ausentes."));
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	private AccountView view(long userId, Account account, Map<Long, Totals> totals) {
		return new AccountView(account, AccountEditability.of(usageOf(userId, account)), balanceOf(account, totals));
	}

	private static BigDecimal balanceOf(Account account, Map<Long, Totals> totals) {
		return BalanceCalculator.balance(account.getInitialBalance(),
				totals.getOrDefault(account.getId(), Totals.none()));
	}

	/**
	 * Totales de movimientos y transferencias con fecha menor o igual a {@code date}, por cuenta (RN-35): cuatro
	 * consultas agrupadas, sin importar cuántas cuentas haya. El cierre de mes reutiliza esta consulta con otra fecha.
	 */
	private Map<Long, Totals> totalsUpTo(long userId, LocalDate date) {
		Map<Long, BigDecimal> income = byAccount(movements.sumByAccountUpTo(userId, date, EntryKind.INCOME));
		Map<Long, BigDecimal> expense = byAccount(movements.sumByAccountUpTo(userId, date, EntryKind.EXPENSE));
		Map<Long, BigDecimal> incoming = byAccount(transfers.sumIncomingByAccountUpTo(userId, date));
		Map<Long, BigDecimal> outgoing = byAccount(transfers.sumOutgoingByAccountUpTo(userId, date));
		Set<Long> ids = new HashSet<>();
		Stream.of(income, expense, incoming, outgoing).forEach(map -> ids.addAll(map.keySet()));
		Map<Long, Totals> result = new HashMap<>();
		for (Long id : ids) {
			result.put(id, new Totals(income.getOrDefault(id, BigDecimal.ZERO),
					expense.getOrDefault(id, BigDecimal.ZERO), incoming.getOrDefault(id, BigDecimal.ZERO),
					outgoing.getOrDefault(id, BigDecimal.ZERO)));
		}
		return result;
	}

	private static Map<Long, BigDecimal> byAccount(List<AccountTotal> totals) {
		return totals.stream().collect(Collectors.toMap(AccountTotal::accountId, AccountTotal::total));
	}

	/** Un subtotal por moneda con cuentas, en el orden del enum (ARS, USD). */
	private static List<CurrencySubtotal> subtotals(List<AccountView> views) {
		Map<Currency, BigDecimal> byCurrency = new EnumMap<>(Currency.class);
		for (AccountView view : views) {
			byCurrency.merge(view.account().getCurrency(), view.currentBalance(), BigDecimal::add);
		}
		return byCurrency.entrySet().stream()
				.map(e -> new CurrencySubtotal(e.getKey(), e.getValue().setScale(2)))
				.toList();
	}

	private AccountUsage usageOf(long userId, Account account) {
		Long id = account.getId();
		boolean hasClosings = closings.existsByUserIdAndAccountId(userId, id);
		LocalDate firstMovement = movements.findFirstMovementDate(userId, id);
		LocalDate firstTransfer = transfers.findFirstTransferDate(userId, id);
		boolean referenced = hasClosings || firstMovement != null || firstTransfer != null
				|| items.existsByUserIdAndAccountId(userId, id) || entries.existsByUserIdAndAccountId(userId, id);
		LocalDate firstActivity = Stream.of(firstMovement, firstTransfer).filter(Objects::nonNull)
				.min(LocalDate::compareTo).orElse(null);
		return new AccountUsage(referenced, hasClosings, firstActivity);
	}

	/** El nombre se guarda sin espacios en los extremos; el saldo, con 2 decimales. */
	private static AccountValues normalize(AccountValues values) {
		BigDecimal balance = values.initialBalance().setScale(2, RoundingMode.UNNECESSARY);
		return new AccountValues(values.name().strip(), values.type(), values.currency(), values.openingDate(),
				balance);
	}

	private static void apply(Account account, AccountValues values) {
		account.setName(values.name());
		account.setType(values.type());
		account.setCurrency(values.currency());
		account.setOpeningDate(values.openingDate());
		account.setInitialBalance(values.initialBalance());
	}

	private Account save(Account account) {
		try {
			return accounts.saveAndFlush(account);
		}
		catch (DataIntegrityViolationException e) {
			// Otro pedido creó el mismo nombre entre la verificación y el guardado: lo frena el índice único.
			throw nameTaken();
		}
	}

	private static BusinessException nameTaken() {
		return new BusinessException(ErrorCode.ACCOUNT_NAME_TAKEN, "Ya tenés una cuenta con ese nombre.");
	}

	private static BusinessException inUse() {
		return new BusinessException(ErrorCode.ACCOUNT_IN_USE,
				"No se puede eliminar la cuenta porque tiene Conceptos, partidas, movimientos, transferencias o cierres.");
	}
}
