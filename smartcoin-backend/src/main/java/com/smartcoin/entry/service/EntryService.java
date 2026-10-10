package com.smartcoin.entry.service;

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
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryDueDateRange;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.movement.repository.MovementRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.period.service.PeriodViewService;
import com.smartcoin.period.service.PeriodViewService.EntryRow;
import com.smartcoin.shared.domain.EntryKind;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partidas sin Concepto (HU-16, RN-18, RN-19): alta de las puntuales y edición de las que no vienen de un Concepto.
 * Toda consulta lleva el {@code userId} del usuario actual.
 *
 * <p>Ninguna operación crea ni modifica otra partida ni un Concepto: no se asegura el horizonte (RN-07 es de los
 * Conceptos) y una partida puntual no se copia a otros períodos (RN-19).
 */
@Service
public class EntryService {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/** Datos de una partida puntual nueva. El formato de cada uno ya lo controló Bean Validation. */
	public record NewOneOff(String name, EntryKind kind, Long accountId, Long categoryId, LocalDate dueDate,
			BigDecimal budgetedAmount) {
	}

	/**
	 * Cambios de una partida. Un campo {@code null} significa «no cambia»; para vaciar la categoría se usa
	 * {@code clearCategory}, porque {@code null} no distingue «no enviado» de «vaciar». Enviar el mismo valor que ya
	 * tiene la partida no es un cambio.
	 */
	public record Changes(String name, EntryKind kind, Long accountId, Long categoryId, boolean clearCategory,
			LocalDate dueDate, BigDecimal budgetedAmount) {
	}

	private final BudgetPeriodRepository periods;
	private final BudgetEntryRepository entries;
	private final MovementRepository movements;
	private final AccountRepository accounts;
	private final CategoryRepository categories;
	private final Clock clock;

	public EntryService(BudgetPeriodRepository periods, BudgetEntryRepository entries, MovementRepository movements,
			AccountRepository accounts, CategoryRepository categories, Clock clock) {
		this.periods = periods;
		this.entries = entries;
		this.movements = movements;
		this.accounts = accounts;
		this.categories = categories;
		this.clock = clock;
	}

	/**
	 * Agrega una partida puntual a un período abierto (RN-19). Orden: el período (404 si no existe para el usuario,
	 * RN-06; 409 si está cerrado, RN-09) y después lo que está mal en el cuerpo (400: el vencimiento, la cuenta y la
	 * categoría, que no existen o son de otro usuario, D-24). La respuesta tiene la forma de la vista del mes.
	 */
	@Transactional
	public EntryRow createOneOff(long userId, YearMonth periodMonth, NewOneOff values) {
		BudgetPeriod period = periods.findByUserIdAndPeriodMonth(userId, periodMonth)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "El período " + periodMonth
						+ " no existe: es anterior al período inicial o posterior al horizonte."));
		verifyOpen(period);
		verifyDueDate(periodMonth, values.dueDate());
		Account account = findAccount(userId, values.accountId());
		Category category = values.categoryId() == null ? null : findCategory(userId, values.categoryId());

		BudgetEntry entry = new BudgetEntry();
		entry.setUserId(userId);
		entry.setPeriod(period);
		entry.setOrigin(EntryOrigin.ONE_OFF);
		entry.setName(values.name().strip());
		entry.setKind(values.kind());
		entry.setCategory(category);
		entry.setAccount(account);
		entry.setDueDate(values.dueDate());
		entry.setBudgetedAmount(amount(values.budgetedAmount()));
		entry.setManual(false);
		entry.setStatus(StoredEntryStatus.PENDING);
		BudgetEntry saved = entries.save(entry);
		// Recién creada: no tiene movimientos.
		return PeriodViewService.row(saved, null, LocalDate.now(clock));
	}

	/**
	 * Edita una partida pendiente de un período abierto (RN-18). Orden: lo que está mal en el pedido mismo (400: una
	 * categoría y a la vez vaciarla); la partida (404 si no existe o es de otro usuario, RN-01); su estado (409
	 * {@code PERIOD_CLOSED} y después {@code ENTRY_NOT_PENDING}: en un período cerrado todas están consolidadas, y el
	 * motivo útil es el mes); lo que no se edita (409 {@code FIELD_NOT_EDITABLE}); lo que está mal en el cuerpo (400:
	 * vencimiento, cuenta y categoría); y por último la moneda con movimientos (409 {@code CURRENCY_MISMATCH}). Con un
	 * pedido rechazado no cambia nada.
	 *
	 * <p>Las partidas recurrentes toman sus datos del Concepto: cualquier cambio es 409 {@code FIELD_NOT_EDITABLE}.
	 * Editar su presupuestado, marcándola editada, es HU-17: ahí se levanta esa restricción para ese único campo.
	 * Editar nunca marca la partida como editada en las que no tienen Concepto (no se propaga, RN-18).
	 */
	@Transactional
	public EntryRow update(long userId, long id, Changes changes) {
		if (changes.categoryId() != null && changes.clearCategory()) {
			throw BusinessException.invalidField("clearCategory",
					"No se puede indicar una categoría y pedir que se vacíe a la vez.");
		}
		BudgetEntry entry = entries.findByIdAndUserIdWithDetails(id, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "La partida no existe."));
		BudgetPeriod period = entry.getPeriod();
		verifyOpen(period);
		if (entry.getStatus() != StoredEntryStatus.PENDING) {
			throw new BusinessException(ErrorCode.ENTRY_NOT_PENDING,
					"La partida está consolidada: no se puede editar. Desconsolidala primero.");
		}
		verifyEditable(entry, changes);

		// Van en el cuerpo, no en la ruta: una referencia inexistente o ajena es un dato inválido (D-24).
		if (changes.dueDate() != null) {
			verifyDueDate(period.getPeriodMonth(), changes.dueDate());
		}
		Account account = changes.accountId() == null ? null : findAccount(userId, changes.accountId());
		Category category = changes.categoryId() == null ? null : findCategory(userId, changes.categoryId());
		if (account != null && !account.getId().equals(entry.getAccount().getId())) {
			verifyCurrencyWithMovements(userId, entry, account);
		}

		if (changes.name() != null) {
			entry.setName(changes.name().strip());
		}
		if (account != null) {
			entry.setAccount(account);
		}
		if (category != null) {
			entry.setCategory(category);
		}
		else if (changes.clearCategory()) {
			entry.setCategory(null);
		}
		if (changes.dueDate() != null) {
			entry.setDueDate(changes.dueDate());
		}
		if (changes.budgetedAmount() != null) {
			entry.setBudgetedAmount(amount(changes.budgetedAmount()));
		}
		return PeriodViewService.row(entry, movements.sumByEntry(userId, entry.getId()), LocalDate.now(clock));
	}

	/** RN-18: una recurrente no se edita acá; el tipo de una partida no se edita nunca. */
	private static void verifyEditable(BudgetEntry entry, Changes changes) {
		if (entry.getBudgetItem() != null) {
			if (changesSomething(entry, changes)) {
				throw new BusinessException(ErrorCode.FIELD_NOT_EDITABLE,
						"Los datos de una partida recurrente se editan en su Concepto.");
			}
			return;
		}
		if (changes.kind() != null && changes.kind() != entry.getKind()) {
			throw new BusinessException(ErrorCode.FIELD_NOT_EDITABLE,
					"El tipo no se puede cambiar: para cambiarlo, eliminá la partida y creá otra.");
		}
	}

	/** Si el pedido cambia algún dato de una recurrente, cuyos valores propios salen del Concepto. */
	private static boolean changesSomething(BudgetEntry entry, Changes changes) {
		Long currentCategoryId = entry.getBudgetItem().getCategory() == null ? null
				: entry.getBudgetItem().getCategory().getId();
		return changes.name() != null && !changes.name().strip().equals(entry.getBudgetItem().getName())
				|| changes.kind() != null && changes.kind() != entry.getKind()
				|| changes.accountId() != null && !changes.accountId().equals(entry.getAccount().getId())
				|| changes.categoryId() != null && !changes.categoryId().equals(currentCategoryId)
				|| changes.clearCategory() && currentCategoryId != null
				|| changes.dueDate() != null && !changes.dueDate().equals(entry.getDueDate())
				|| changes.budgetedAmount() != null
						&& changes.budgetedAmount().compareTo(entry.getBudgetedAmount()) != 0;
	}

	/** RN-19: con movimientos, la cuenta solo cambia por otra de la misma moneda. Sin movimientos es libre (D-30). */
	private void verifyCurrencyWithMovements(long userId, BudgetEntry entry, Account target) {
		if (Objects.equals(target.getCurrency(), entry.getAccount().getCurrency())) {
			return;
		}
		if (!movements.findEntryIdsWithMovements(userId, List.of(entry.getId())).isEmpty()) {
			throw new BusinessException(ErrorCode.CURRENCY_MISMATCH, "La partida ya tiene movimientos: su cuenta solo "
					+ "puede cambiarse por otra en " + entry.getAccount().getCurrency() + ".");
		}
	}

	private static void verifyOpen(BudgetPeriod period) {
		if (period.getStatus() == PeriodStatus.CLOSED) {
			throw new BusinessException(ErrorCode.PERIOD_CLOSED,
					"El período " + period.getPeriodMonth() + " está cerrado y no admite cambios.");
		}
	}

	private static void verifyDueDate(YearMonth period, LocalDate dueDate) {
		if (!EntryDueDateRange.contains(period, dueDate)) {
			throw BusinessException.invalidField("dueDate", "El vencimiento debe estar entre el "
					+ DATE.format(EntryDueDateRange.earliest(period)) + " y el "
					+ DATE.format(EntryDueDateRange.latest(period)) + ".");
		}
	}

	private Account findAccount(long userId, long accountId) {
		return accounts.findByIdAndUserId(accountId, userId)
				.orElseThrow(() -> BusinessException.invalidField("accountId", "La cuenta no existe."));
	}

	private Category findCategory(long userId, long categoryId) {
		return categories.findByIdAndUserId(categoryId, userId)
				.orElseThrow(() -> BusinessException.invalidField("categoryId", "La categoría no existe."));
	}

	private static BigDecimal amount(BigDecimal value) {
		return value.setScale(2, RoundingMode.UNNECESSARY);
	}
}
