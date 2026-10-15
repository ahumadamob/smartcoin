package com.smartcoin.transfer.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.transfer.domain.ExchangeRate;
import com.smartcoin.transfer.domain.Transfer;
import com.smartcoin.transfer.domain.TransferDateValidator;
import com.smartcoin.transfer.domain.TransferDateValidator.MonthState;
import com.smartcoin.transfer.domain.TransferDateValidator.Outcome;
import com.smartcoin.transfer.repository.TransferRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transferencias entre cuentas propias: alta, lista, edición y eliminación (HU-27, HU-29, RN-36). Toda consulta lleva
 * el {@code userId} del usuario actual. Una transferencia no es ingreso ni gasto: no toca partidas ni períodos; solo
 * cambia los saldos de las dos cuentas (RN-35, D-20), que se calculan en vivo.
 */
@Service
public class TransferService {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/** Datos de una transferencia nueva. El formato de cada uno ya lo controló Bean Validation. */
	public record NewTransfer(Long sourceAccountId, Long targetAccountId, LocalDate date, BigDecimal sourceAmount,
			BigDecimal targetAmount, String note) {
	}

	/**
	 * Una transferencia con sus dos cuentas. Se arma dentro de la transacción: la respuesta no lee diferidos.
	 * {@code editable} es informativo (D-41): el mes de su fecha es un período abierto.
	 */
	public record TransferRow(Long id, LocalDate date, Long sourceAccountId, String sourceAccountName,
			Currency sourceCurrency, BigDecimal sourceAmount, Long targetAccountId, String targetAccountName,
			Currency targetCurrency, BigDecimal targetAmount, String note, boolean editable) {

		static TransferRow of(Transfer transfer, boolean editable) {
			Account source = transfer.getSourceAccount();
			Account target = transfer.getTargetAccount();
			return new TransferRow(transfer.getId(), transfer.getTransferDate(), source.getId(), source.getName(),
					source.getCurrency(), transfer.getSourceAmount(), target.getId(), target.getName(),
					target.getCurrency(), transfer.getTargetAmount(), transfer.getNote(), editable);
		}

		/** Tipo de cambio (RN-37): nulo entre cuentas de la misma moneda. No se guarda. */
		public BigDecimal exchangeRate() {
			return ExchangeRate.of(sourceCurrency, sourceAmount, targetCurrency, targetAmount).orElse(null);
		}
	}

	private final TransferRepository transfers;
	private final AccountRepository accounts;
	private final BudgetPeriodRepository periods;
	private final Clock clock;

	public TransferService(TransferRepository transfers, AccountRepository accounts, BudgetPeriodRepository periods,
			Clock clock) {
		this.transfers = transfers;
		this.accounts = accounts;
		this.periods = periods;
		this.clock = clock;
	}

	/**
	 * Registra una transferencia (RN-36). Orden en que se evalúa, y se informa el primero que falla (D-39): origen
	 * igual a destino (400 en {@code targetAccountId}); cuenta de origen y de destino que no existen o son de otro
	 * usuario (400 en su campo, D-24); montos distintos con la misma moneda (409 {@code TRANSFER_AMOUNTS_MISMATCH});
	 * y la fecha ({@link TransferDateValidator}). Un pedido rechazado no guarda nada.
	 */
	@Transactional
	public TransferRow create(long userId, NewTransfer values) {
		Resolved resolved = resolve(userId, values);

		Transfer transfer = new Transfer();
		transfer.setUserId(userId);
		apply(transfer, resolved, values);
		// El mes de la fecha se verificó abierto: la transferencia nace editable.
		return TransferRow.of(transfers.save(transfer), true);
	}

	/**
	 * Corrige una transferencia (RN-36, D-41). Orden en que se evalúa, y se informa el primero que falla: inexistente
	 * o ajena (404); mes de la fecha actual cerrado (409 {@code PERIOD_CLOSED}); y los valores nuevos con las mismas
	 * reglas y el mismo orden que el alta. Si no cambia nada, responde igual sin guardar. Un pedido rechazado no
	 * guarda nada.
	 */
	@Transactional
	public TransferRow update(long userId, long id, NewTransfer values) {
		Transfer transfer = find(userId, id);
		verifyCurrentMonthOpen(userId, transfer);
		Resolved resolved = resolve(userId, values);
		if (!isUnchanged(transfer, resolved, values)) {
			apply(transfer, resolved, values);
			transfers.save(transfer);
		}
		return TransferRow.of(transfer, true);
	}

	/** Elimina una transferencia si el mes de su fecha es un período abierto (RN-36). Sus saldos se recalculan solos. */
	@Transactional
	public void delete(long userId, long id) {
		Transfer transfer = find(userId, id);
		verifyCurrentMonthOpen(userId, transfer);
		transfers.delete(transfer);
	}

	/** Origen, destino y montos ya controlados; la fecha se verifica al resolver. */
	private record Resolved(Account source, Account target, BigDecimal sourceAmount, BigDecimal targetAmount) {
	}

	/**
	 * Las reglas de los valores de RN-36, en el orden de D-39, compartidas por el alta y la edición: origen igual a
	 * destino (400 en {@code targetAccountId}); cuentas inexistentes o ajenas (400 en su campo, D-24); montos
	 * distintos con la misma moneda (409 {@code TRANSFER_AMOUNTS_MISMATCH}); y la fecha ({@link TransferDateValidator}).
	 */
	private Resolved resolve(long userId, NewTransfer values) {
		if (values.sourceAccountId().equals(values.targetAccountId())) {
			throw BusinessException.invalidField("targetAccountId",
					"La cuenta de destino tiene que ser distinta de la de origen.");
		}
		Account source = accounts.findByIdAndUserId(values.sourceAccountId(), userId).orElseThrow(
				() -> BusinessException.invalidField("sourceAccountId", "La cuenta de origen no existe."));
		Account target = accounts.findByIdAndUserId(values.targetAccountId(), userId).orElseThrow(
				() -> BusinessException.invalidField("targetAccountId", "La cuenta de destino no existe."));
		BigDecimal sourceAmount = values.sourceAmount().setScale(2, RoundingMode.UNNECESSARY);
		BigDecimal targetAmount = values.targetAmount().setScale(2, RoundingMode.UNNECESSARY);
		if (source.getCurrency() == target.getCurrency() && sourceAmount.compareTo(targetAmount) != 0) {
			throw new BusinessException(ErrorCode.TRANSFER_AMOUNTS_MISMATCH, "Las dos cuentas son en "
					+ source.getCurrency() + ": el monto de origen y el de destino tienen que ser iguales.");
		}
		verifyDate(userId, source, target, values.date());
		return new Resolved(source, target, sourceAmount, targetAmount);
	}

	private static void apply(Transfer transfer, Resolved resolved, NewTransfer values) {
		transfer.setSourceAccount(resolved.source());
		transfer.setTargetAccount(resolved.target());
		transfer.setTransferDate(values.date());
		transfer.setSourceAmount(resolved.sourceAmount());
		transfer.setTargetAmount(resolved.targetAmount());
		transfer.setNote(normalize(values.note()));
	}

	private static boolean isUnchanged(Transfer transfer, Resolved resolved, NewTransfer values) {
		return transfer.getSourceAccount().getId().equals(resolved.source().getId())
				&& transfer.getTargetAccount().getId().equals(resolved.target().getId())
				&& transfer.getTransferDate().equals(values.date())
				&& transfer.getSourceAmount().compareTo(resolved.sourceAmount()) == 0
				&& transfer.getTargetAmount().compareTo(resolved.targetAmount()) == 0
				&& Objects.equals(transfer.getNote(), normalize(values.note()));
	}

	private Transfer find(long userId, long id) {
		return transfers.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La transferencia no existe."));
	}

	/** RN-36: se edita o elimina solo si el mes de su fecha actual es un período abierto. */
	private void verifyCurrentMonthOpen(long userId, Transfer transfer) {
		YearMonth month = YearMonth.from(transfer.getTransferDate());
		switch (monthState(userId, month)) {
			case OPEN -> {
			}
			case CLOSED -> throw new BusinessException(ErrorCode.PERIOD_CLOSED, "La transferencia tiene fecha en "
					+ month + ", un período cerrado: no se puede corregir ni eliminar.");
			case NO_PERIOD -> throw new BusinessException(ErrorCode.DATE_OUT_OF_RANGE, "El mes " + month
					+ " es anterior al período inicial: no hay presupuesto para ese mes.");
		}
	}

	/**
	 * Las transferencias del usuario con fecha entre {@code from} y {@code to}, ambas inclusive, de la más reciente a
	 * la más antigua y, a igual fecha, la última registrada primero (D-39). Sin {@code to}, hoy; sin {@code from}, el
	 * primer día del mes de {@code to}, es decir, el mes actual si tampoco se indica. {@code from} posterior a
	 * {@code to}: 400 en {@code from}.
	 */
	@Transactional(readOnly = true)
	public List<TransferRow> list(long userId, LocalDate from, LocalDate to) {
		LocalDate effectiveTo = to != null ? to : LocalDate.now(clock);
		LocalDate effectiveFrom = from != null ? from : YearMonth.from(effectiveTo).atDay(1);
		if (effectiveFrom.isAfter(effectiveTo)) {
			throw BusinessException.invalidField("from", "La fecha desde no puede ser posterior a la fecha hasta.");
		}
		Map<YearMonth, Boolean> openMonths = new HashMap<>();
		return transfers.findByUserIdAndDateBetween(userId, effectiveFrom, effectiveTo).stream()
				.map(t -> TransferRow.of(t, openMonths.computeIfAbsent(YearMonth.from(t.getTransferDate()),
						month -> monthState(userId, month) == MonthState.OPEN)))
				.toList();
	}

	private void verifyDate(long userId, Account source, Account target, LocalDate date) {
		LocalDate today = LocalDate.now(clock);
		Outcome outcome = TransferDateValidator.validate(date, source.getOpeningDate(), target.getOpeningDate(),
				today, monthState(userId, YearMonth.from(date)));
		if (outcome.isValid()) {
			return;
		}
		throw new BusinessException(outcome.isPeriodClosed() ? ErrorCode.PERIOD_CLOSED : ErrorCode.DATE_OUT_OF_RANGE,
				detail(outcome, source, target, today, date));
	}

	private static String detail(Outcome outcome, Account source, Account target, LocalDate today, LocalDate date) {
		return switch (outcome) {
			case FUTURE -> "La fecha no puede ser posterior a hoy (" + DATE.format(today) + ").";
			case BEFORE_SOURCE_OPENING -> "La fecha no puede ser anterior a la apertura de la cuenta de origen «"
					+ source.getName() + "» (" + DATE.format(source.getOpeningDate()) + ").";
			case BEFORE_TARGET_OPENING -> "La fecha no puede ser anterior a la apertura de la cuenta de destino «"
					+ target.getName() + "» (" + DATE.format(target.getOpeningDate()) + ").";
			case MONTH_WITHOUT_PERIOD -> "El mes " + YearMonth.from(date) + " es anterior al período inicial: "
					+ "no hay presupuesto para ese mes.";
			case MONTH_CLOSED -> "El período " + YearMonth.from(date) + " está cerrado y no admite transferencias "
					+ "con fecha en ese mes.";
			case VALID -> throw new IllegalStateException("La fecha es válida: no hay error que informar.");
		};
	}

	private MonthState monthState(long userId, YearMonth month) {
		return periods.findByUserIdAndPeriodMonth(userId, month)
				.map(period -> period.getStatus() == PeriodStatus.CLOSED ? MonthState.CLOSED : MonthState.OPEN)
				.orElse(MonthState.NO_PERIOD);
	}

	/** La nota se guarda sin espacios en los extremos; vacía o en blanco es sin nota. */
	private static String normalize(String note) {
		if (note == null || note.isBlank()) {
			return null;
		}
		return note.strip();
	}
}
