package com.smartcoin.budgetitem.service;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.account.domain.Account;
import com.smartcoin.account.repository.AccountRepository;
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.BudgetItemValues;
import com.smartcoin.budgetitem.domain.InstallmentPlan;
import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.category.domain.Category;
import com.smartcoin.category.repository.CategoryRepository;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodRange;
import com.smartcoin.period.domain.PeriodStatus;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Conceptos (HU-10, RN-10). Toda consulta lleva el {@code userId} del usuario actual. */
@Service
public class BudgetItemService {

	/** El Concepto creado y las partidas que generó, en orden de período. */
	public record Created(BudgetItem item, List<BudgetEntry> entries) {
	}

	private final BudgetItemRepository items;
	private final AccountRepository accounts;
	private final CategoryRepository categories;
	private final BudgetPeriodRepository periods;
	private final UserRepository users;
	private final HorizonService horizon;
	private final EntryGenerator generator;
	private final AppProperties properties;
	private final Clock clock;

	public BudgetItemService(BudgetItemRepository items, AccountRepository accounts, CategoryRepository categories,
			BudgetPeriodRepository periods, UserRepository users, HorizonService horizon, EntryGenerator generator,
			AppProperties properties, Clock clock) {
		this.items = items;
		this.accounts = accounts;
		this.categories = categories;
		this.periods = periods;
		this.users = users;
		this.horizon = horizon;
		this.generator = generator;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Crea un Concepto, recurrente o en cuotas, y genera sus partidas (RN-13) en la misma transacción. Primero lo que
	 * está mal en el pedido (400); después lo que el estado de los períodos no permite (409). En un plan de cuotas el
	 * período de fin no se informa: se calcula (RN-14).
	 */
	@Transactional
	public Created create(long userId, BudgetItemValues values) {
		User user = users.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Credenciales inválidas o ausentes."));
		InstallmentPlan plan = installmentPlan(values);
		if (values.endPeriod() != null && values.endPeriod().isBefore(values.startPeriod())) {
			throw BusinessException.invalidField("endPeriod",
					"El período de fin no puede ser anterior al período de inicio.");
		}
		// Van en el cuerpo, no en la ruta: una cuenta o categoría inexistente o ajena es un dato inválido (RN-10).
		Account account = accounts.findByIdAndUserId(values.defaultAccountId(), userId)
				.orElseThrow(() -> BusinessException.invalidField("defaultAccountId", "La cuenta por defecto no existe."));
		Category category = values.categoryId() == null ? null
				: categories.findByIdAndUserId(values.categoryId(), userId)
						.orElseThrow(() -> BusinessException.invalidField("categoryId", "La categoría no existe."));

		YearMonth last = PeriodRange.horizon(YearMonth.now(clock), properties.budget().horizonMonths());
		YearMonth firstOpen = PeriodRange.firstOpen(user.getStartPeriod(),
				periods.findFirstByUserIdAndStatusOrderByPeriodMonthDesc(userId, PeriodStatus.CLOSED)
						.map(BudgetPeriod::getPeriodMonth).orElse(null));
		if (values.startPeriod().isBefore(firstOpen) || values.startPeriod().isAfter(last)) {
			throw new BusinessException(ErrorCode.PERIOD_NOT_AVAILABLE, "El período de inicio debe estar entre "
					+ firstOpen + " (el primer período abierto) y " + last + " (el horizonte).");
		}

		// RN-07: si el mes cambió desde el último inicio de sesión, puede faltar el período del horizonte.
		horizon.ensureHorizon(user);

		BudgetItem item = new BudgetItem();
		item.setUserId(userId);
		item.setName(values.name().strip());
		item.setKind(values.kind());
		item.setDefaultAccount(account);
		item.setCategory(category);
		item.setPeriodicity(values.periodicity());
		item.setDueDay(values.dueDay());
		item.setDueMonthOffset(values.dueMonthOffset());
		item.setStartPeriod(values.startPeriod());
		if (plan == null) {
			item.setEndPeriod(values.endPeriod());
		}
		else {
			item.setEndPeriod(plan.endPeriod(values.startPeriod(), values.periodicity()));
			item.setInstallmentsTotal(plan.total());
			item.setFirstInstallmentNumber(plan.first());
		}
		item.setEstimationRule(values.estimationRule());
		item.setCurrentAmount(values.currentAmount().setScale(2, RoundingMode.UNNECESSARY));
		BudgetItem saved = items.save(item);
		return new Created(saved, generator.generate(saved, last));
	}

	/**
	 * El plan de cuotas del pedido (RN-14), o {@code null} si no es en cuotas. La primera cuota es 1 si no se
	 * informa. El formato de cada número (≥ 1, máximo) ya lo controló Bean Validation; acá, lo que depende de otros
	 * campos.
	 */
	private static InstallmentPlan installmentPlan(BudgetItemValues values) {
		Integer total = values.installmentsTotal();
		Integer first = values.firstInstallmentNumber();
		if (total == null) {
			if (first != null) {
				throw BusinessException.invalidField("installmentsTotal",
						"El total de cuotas es obligatorio si se informa la primera cuota.");
			}
			return null;
		}
		if (first != null && first > total) {
			throw BusinessException.invalidField("firstInstallmentNumber",
					"La primera cuota no puede ser mayor que el total de cuotas.");
		}
		if (values.endPeriod() != null) {
			throw BusinessException.invalidField("endPeriod",
					"En un Concepto en cuotas el período de fin se calcula: no se informa.");
		}
		return new InstallmentPlan(total, first == null ? 1 : first);
	}
}
