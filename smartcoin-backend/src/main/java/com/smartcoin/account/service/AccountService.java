package com.smartcoin.account.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.domain.AccountEditability;
import com.smartcoin.account.domain.AccountUsage;
import com.smartcoin.account.domain.AccountValues;
import com.smartcoin.account.domain.OpeningDateRule;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.repository.AccountClosingRepository;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.transfer.repository.TransferRepository;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrar cuentas (HU-07, RN-33). Toda consulta lleva el {@code userId} del usuario actual. */
@Service
public class AccountService {

	/** Una cuenta con lo que se puede editar de ella. */
	public record AccountView(Account account, AccountEditability editability) {
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
	public List<AccountView> list(long userId) {
		return accounts.findByUserIdOrderByCurrencyAscNameAsc(userId).stream()
				.map(account -> view(userId, account))
				.toList();
	}

	@Transactional(readOnly = true)
	public AccountView get(long userId, long id) {
		return view(userId, find(userId, id));
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
		return new AccountView(saved, AccountEditability.of(AccountUsage.unused()));
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
		return new AccountView(save(account), editability);
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

	private AccountView view(long userId, Account account) {
		return new AccountView(account, AccountEditability.of(usageOf(userId, account)));
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
