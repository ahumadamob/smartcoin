package com.smartcoin.movement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
 * Movimientos de una partida: registrar y listar (HU-19, RN-21, RN-23). Toda consulta lleva el {@code userId} del
 * usuario actual. Editar y eliminar movimientos es HU-21; el pago rápido, HU-22.
 *
 * <p>Registrar no consolida ni cambia el presupuestado, aunque el real alcance o supere al presupuestado (RN-23): no
 * guarda nada en la partida. El estado Parcial sale de que ahora tiene movimientos (RN-16).
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

	/** El movimiento registrado y la partida con sus valores derivados ya actualizados. */
	public record Registered(MovementRow movement, EntryRow entry) {
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
		Account account = accounts.findByIdAndUserId(values.accountId(), userId)
				.orElseThrow(() -> BusinessException.invalidField("accountId", "La cuenta no existe."));
		Currency currency = entry.getAccount().getCurrency();
		if (account.getCurrency() != currency) {
			throw new BusinessException(ErrorCode.CURRENCY_MISMATCH, "La partida es en " + currency + " y la cuenta «"
					+ account.getName() + "» es en " + account.getCurrency() + ".");
		}
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
	 * Los movimientos de una partida del usuario, por fecha y orden de creación. Se pueden leer también los de una
	 * partida consolidada o de un período cerrado: leer no cambia nada.
	 */
	@Transactional(readOnly = true)
	public List<MovementRow> list(long userId, long entryId) {
		BudgetEntry entry = findEntry(userId, entryId);
		return movements.findByUserIdAndEntryIdWithAccount(userId, entry.getId()).stream()
				.map(MovementRow::of).toList();
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
					+ " días antes de su inicio.";
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
