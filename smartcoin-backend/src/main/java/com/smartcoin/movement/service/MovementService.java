package com.smartcoin.movement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.domain.Movement;
import com.smartcoin.movement.domain.MovementDateValidator;
import com.smartcoin.movement.domain.MovementDateValidator.MonthState;
import com.smartcoin.movement.domain.MovementDateValidator.Outcome;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Movimientos de una partida: registrar, listar, corregir y eliminar (HU-19, HU-21, RN-21, RN-22, RN-23). Toda
 * consulta lleva el {@code userId} del usuario actual. El pago rápido es HU-22.
 *
 * <p>Ni registrar, ni corregir, ni eliminar consolidan o cambian el presupuestado, aunque el real alcance o supere al
 * presupuestado (RN-23): no guardan nada en la partida. El estado Parcial sale de que tiene movimientos (RN-16); al
 * eliminar el último, vuelve a Estimada sin guardar nada.
 */
@Service
public class MovementService {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/** Datos de un movimiento nuevo. El formato de cada uno ya lo controló Bean Validation. */
	public record NewMovement(LocalDate date, BigDecimal amount, Long accountId, String note) {
	}

	/** Un movimiento con su cuenta. Se arma dentro de la transacción: la respuesta no lee asociaciones diferidas. */
	public record MovementRow(Long id, Long entryId, LocalDate date, BigDecimal amount, String note, Long accountId,
			String accountName, Currency currency) {

		static MovementRow of(Movement movement) {
			Account account = movement.getAccount();
			return new MovementRow(movement.getId(), movement.getEntry().getId(), movement.getMovementDate(),
					movement.getAmount(), movement.getNote(), account.getId(), account.getName(),
					account.getCurrency());
		}
	}

	/** El movimiento registrado o corregido y la partida con sus valores derivados ya actualizados. */
	public record Registered(MovementRow movement, EntryRow entry) {
	}

	/**
	 * Entre qué fechas admite un movimiento una partida con una cuenta (RN-21): {@code earliestDate} es la más
	 * temprana (ventana y apertura de la cuenta), {@code latestDate} es hoy. Si {@code earliestDate} es posterior a
	 * {@code latestDate}, todavía no se admite ninguna. No mira el estado de la partida ni de los meses.
	 */
	public record MovementDates(LocalDate earliestDate, LocalDate latestDate, int earlyDays) {
	}

	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final AccountRepository accounts;
	private final BudgetPeriodRepository periods;
	private final AppProperties properties;
	private final Clock clock;

	public MovementService(BudgetEntryRepository entries, MovementRepository movements, AccountRepository accounts,
			BudgetPeriodRepository periods, AppProperties properties, Clock clock) {
		this.entries = entries;
		this.movements = movements;
		this.accounts = accounts;
		this.periods = periods;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Registra un cobro o un pago en una partida (RN-21). Orden en que se evalúa, y se informa el primero que falla
	 * (D-33): la partida (404 si no existe o es de otro usuario, RN-01); su período (409 {@code PERIOD_CLOSED}) y su
	 * estado (409 {@code ENTRY_NOT_PENDING}), en ese orden porque en un período cerrado todas están consolidadas y el
	 * motivo útil es el mes (D-30); la cuenta (400 en {@code accountId} si no existe o es de otro usuario, D-24); la
	 * moneda (409 {@code CURRENCY_MISMATCH}); y la fecha ({@link MovementDateValidator}).
	 *
	 * <p>No cambia nada de la partida: ni el estado ni el presupuestado (RN-23). Con un pedido rechazado no se
	 * guarda nada.
	 */
	@Transactional
	public Registered register(long userId, long entryId, NewMovement values) {
		BudgetEntry entry = findEntry(userId, entryId);
		BudgetPeriod period = entry.getPeriod();
		if (period.getStatus() == PeriodStatus.CLOSED) {
			throw new BusinessException(ErrorCode.PERIOD_CLOSED,
					"El período " + period.getPeriodMonth() + " está cerrado y no admite cambios.");
		}
		if (entry.getStatus() != StoredEntryStatus.PENDING) {
			throw new BusinessException(ErrorCode.ENTRY_NOT_PENDING,
					"La partida está consolidada: no admite movimientos. Desconsolidala primero.");
		}
		Account account = findAccount(userId, entry, values.accountId());
		verifyDate(userId, entry, account, values.date());

		Movement movement = new Movement();
		movement.setUserId(userId);
		movement.setEntry(entry);
		movement.setAccount(account);
		movement.setMovementDate(values.date());
		movement.setAmount(values.amount().setScale(2, RoundingMode.UNNECESSARY));
		movement.setNote(normalize(values.note()));
		Movement saved = movements.save(movement);

		BigDecimal total = movements.sumByEntry(userId, entry.getId());
		return new Registered(MovementRow.of(saved),
				PeriodViewService.row(entry, total, LocalDate.now(clock)));
	}

	/**
	 * Corrige fecha, monto, cuenta y nota de un movimiento (RN-22). La partida no cambia: un movimiento no se mueve
	 * de una partida a otra. Orden en que se evalúa, y se informa el primero que falla (D-35): el movimiento (404,
	 * RN-01); el período de la partida (409 {@code PERIOD_CLOSED}) y su estado (409 {@code ENTRY_NOT_PENDING}); el
	 * mes de la fecha actual del movimiento (409 {@code PERIOD_CLOSED}); y, sobre los valores nuevos, la cuenta (400
	 * en {@code accountId}), la moneda (409 {@code CURRENCY_MISMATCH}) y la fecha (RN-21, con la misma regla pura
	 * que el alta).
	 *
	 * <p>Sin cambios es un pedido válido que no guarda nada. La fecha se revalida solo si cambia la fecha o la
	 * cuenta: corregir el monto o la nota de un movimiento viejo no depende de la ventana configurada hoy (D-35).
	 */
	@Transactional
	public Registered update(long userId, long movementId, NewMovement values) {
		Movement movement = findMovement(userId, movementId);
		BudgetEntry entry = findEditableEntry(userId, movement);
		Account account = movement.getAccount();
		BigDecimal amount = values.amount().setScale(2, RoundingMode.UNNECESSARY);
		String note = normalize(values.note());
		boolean accountChanged = !account.getId().equals(values.accountId());
		boolean dateChanged = !movement.getMovementDate().equals(values.date());
		if (accountChanged) {
			account = findAccount(userId, entry, values.accountId());
		}
		if (accountChanged || dateChanged) {
			verifyDate(userId, entry, account, values.date());
		}
		boolean changed = accountChanged || dateChanged || movement.getAmount().compareTo(amount) != 0
				|| !Objects.equals(movement.getNote(), note);
		if (changed) {
			movement.setAccount(account);
			movement.setMovementDate(values.date());
			movement.setAmount(amount);
			movement.setNote(note);
			movement = movements.save(movement);
		}
		BigDecimal total = movements.sumByEntry(userId, entry.getId());
		return new Registered(MovementRow.of(movement), PeriodViewService.row(entry, total, LocalDate.now(clock)));
	}

	/**
	 * Elimina un movimiento (RN-22). Mismas condiciones sobre el movimiento actual que {@link #update}. No toca la
	 * partida: si era su último movimiento, vuelve a mostrarse Estimada porque ya no tiene (RN-16).
	 */
	@Transactional
	public void delete(long userId, long movementId) {
		Movement movement = findMovement(userId, movementId);
		findEditableEntry(userId, movement);
		movements.delete(movement);
	}

	/**
	 * Los movimientos de una partida del usuario, por fecha y orden de creación. Se pueden leer también los de una
	 * partida consolidada o de un período cerrado: leer no cambia nada.
	 */
	@Transactional(readOnly = true)
	public List<MovementRow> list(long userId, long entryId) {
		BudgetEntry entry = findEntry(userId, entryId);
		return movements.findByUserIdAndEntryIdWithAccount(userId, entry.getId()).stream()
				.map(MovementRow::of).toList();
	}

	/**
	 * El rango de fechas que admite la partida con la cuenta {@code accountId}, o con la cuenta prevista de la partida
	 * si no se indica (HU-20). Usa la misma regla pura que valida el registro. Una cuenta que no existe o es de otro
	 * usuario es un error de campo, igual que al registrar (D-24).
	 */
	@Transactional(readOnly = true)
	public MovementDates dates(long userId, long entryId, Long accountId) {
		BudgetEntry entry = findEntry(userId, entryId);
		Account account = accountId == null ? entry.getAccount()
				: accounts.findByIdAndUserId(accountId, userId)
						.orElseThrow(() -> BusinessException.invalidField("accountId", "La cuenta no existe."));
		int earlyDays = properties.budget().earlyDays();
		return new MovementDates(MovementDateValidator.earliestDate(entry.getPeriod().getPeriodMonth(),
				account.getOpeningDate(), earlyDays), LocalDate.now(clock), earlyDays);
	}

	private Movement findMovement(long userId, long movementId) {
		return movements.findByIdAndUserIdWithAccount(movementId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "El movimiento no existe."));
	}

	/**
	 * RN-22: la partida del movimiento tiene que estar pendiente en un período abierto, y el mes de la fecha actual
	 * del movimiento, abierto. Se informa el primero que falla; el período de la partida va antes que su estado por
	 * el mismo motivo que en el alta (D-30, D-33).
	 */
	private BudgetEntry findEditableEntry(long userId, Movement movement) {
		BudgetEntry entry = findEntry(userId, movement.getEntry().getId());
		BudgetPeriod period = entry.getPeriod();
		if (period.getStatus() == PeriodStatus.CLOSED) {
			throw new BusinessException(ErrorCode.PERIOD_CLOSED,
					"El período " + period.getPeriodMonth() + " está cerrado y no admite cambios.");
		}
		if (entry.getStatus() != StoredEntryStatus.PENDING) {
			throw new BusinessException(ErrorCode.ENTRY_NOT_PENDING,
					"La partida está consolidada: sus movimientos no se pueden cambiar. Desconsolidala primero.");
		}
		YearMonth month = YearMonth.from(movement.getMovementDate());
		if (monthState(userId, period, month) == MonthState.CLOSED) {
			throw new BusinessException(ErrorCode.PERIOD_CLOSED, "El movimiento tiene fecha en " + month
					+ ", un período cerrado: no se puede corregir ni eliminar.");
		}
		return entry;
	}

	/** La cuenta del usuario, de la moneda de la partida (RN-21, condición 3; D-24 si no existe). */
	private Account findAccount(long userId, BudgetEntry entry, Long accountId) {
		Account account = accounts.findByIdAndUserId(accountId, userId)
				.orElseThrow(() -> BusinessException.invalidField("accountId", "La cuenta no existe."));
		Currency currency = entry.getAccount().getCurrency();
		if (account.getCurrency() != currency) {
			throw new BusinessException(ErrorCode.CURRENCY_MISMATCH, "La partida es en " + currency + " y la cuenta «"
					+ account.getName() + "» es en " + account.getCurrency() + ".");
		}
		return account;
	}

	private BudgetEntry findEntry(long userId, long entryId) {
		return entries.findByIdAndUserIdWithDetails(entryId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));
	}

	/**
	 * RN-21, condición 4. El estado del mes de la fecha sale de los períodos que existen para el usuario; el de la
	 * partida ya se leyó. Las tres primeras fallas son {@code DATE_OUT_OF_RANGE}; la última, {@code PERIOD_CLOSED}.
	 */
	private void verifyDate(long userId, BudgetEntry entry, Account account, LocalDate date) {
		LocalDate today = LocalDate.now(clock);
		YearMonth entryPeriod = entry.getPeriod().getPeriodMonth();
		int earlyDays = properties.budget().earlyDays();
		Outcome outcome = MovementDateValidator.validate(date, entryPeriod, account.getOpeningDate(), today,
				earlyDays, monthState(userId, entry.getPeriod(), YearMonth.from(date)));
		if (outcome.isValid()) {
			return;
		}
		LocalDate windowStart = MovementDateValidator.windowStart(entryPeriod, earlyDays);
		// Si la ventana todavía no abrió, ninguna fecha sirve: o es anterior a ella o es posterior a hoy.
		boolean windowNotOpenYet = windowStart.isAfter(today)
				&& (outcome == Outcome.BEFORE_WINDOW || outcome == Outcome.FUTURE);
		String detail = windowNotOpenYet
				? "Todavía no se pueden registrar movimientos de esta partida: es del período " + entryPeriod
						+ " y se admiten desde el " + DATE.format(windowStart) + "."
				: detail(outcome, account, entryPeriod, windowStart, earlyDays, today, date);
		throw new BusinessException(outcome.isPeriodClosed() ? ErrorCode.PERIOD_CLOSED : ErrorCode.DATE_OUT_OF_RANGE,
				detail);
	}

	private static String detail(Outcome outcome, Account account, YearMonth entryPeriod, LocalDate windowStart,
			int earlyDays, LocalDate today, LocalDate date) {
		return switch (outcome) {
			case BEFORE_WINDOW -> "La fecha no puede ser anterior al " + DATE.format(windowStart) + ": una partida "
					+ "del período " + entryPeriod + " admite movimientos desde " + earlyDays
					+ " días antes de su inicio." + openingNote(account, entryPeriod, earlyDays);
			case BEFORE_ACCOUNT_OPENING -> "La fecha no puede ser anterior a la apertura de la cuenta «"
					+ account.getName() + "» (" + DATE.format(account.getOpeningDate()) + ").";
			case FUTURE -> "La fecha no puede ser posterior a hoy (" + DATE.format(today) + ").";
			case MONTH_WITHOUT_PERIOD -> "El mes " + YearMonth.from(date) + " es anterior al período inicial: "
					+ "no hay presupuesto para ese mes.";
			case MONTH_CLOSED -> "El período " + YearMonth.from(date) + " está cerrado y no admite movimientos "
					+ "con fecha en ese mes.";
			case VALID -> throw new IllegalStateException("La fecha es válida: no hay error que informar.");
		};
	}

	/**
	 * Si la cuenta se abrió después de que abre la ventana, la fecha más temprana es la apertura: se dice acá para que
	 * corregir la fecha no lleve a un segundo error.
	 */
	private static String openingNote(Account account, YearMonth entryPeriod, int earlyDays) {
		LocalDate earliest = MovementDateValidator.earliestDate(entryPeriod, account.getOpeningDate(), earlyDays);
		if (earliest.equals(MovementDateValidator.windowStart(entryPeriod, earlyDays))) {
			return "";
		}
		return " Con la cuenta «" + account.getName() + "», abierta el " + DATE.format(account.getOpeningDate())
				+ ", la fecha más temprana es el " + DATE.format(earliest) + ".";
	}

	private MonthState monthState(long userId, BudgetPeriod entryPeriod, YearMonth month) {
		BudgetPeriod period = month.equals(entryPeriod.getPeriodMonth()) ? entryPeriod
				: periods.findByUserIdAndPeriodMonth(userId, month).orElse(null);
		if (period == null) {
			return MonthState.NO_PERIOD;
		}
		return period.getStatus() == PeriodStatus.CLOSED ? MonthState.CLOSED : MonthState.OPEN;
	}

	/** La nota se guarda sin espacios en los extremos; vacía o en blanco es sin nota. */
	private static String normalize(String note) {
		if (note == null || note.isBlank()) {
			return null;
		}
		return note.strip();
	}
}
