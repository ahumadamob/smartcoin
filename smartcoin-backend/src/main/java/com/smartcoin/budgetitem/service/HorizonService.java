package com.smartcoin.budgetitem.service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.budgetitem.repository.BudgetItemRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodRange;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.user.domain.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Asegurar el horizonte (RN-07): crea los períodos que faltan y genera las partidas de cada Concepto. */
@Service
public class HorizonService {

	private final BudgetPeriodRepository periods;
	private final BudgetItemRepository items;
	private final EntryGenerator generator;
	private final AppProperties properties;
	private final Clock clock;

	public HorizonService(BudgetPeriodRepository periods, BudgetItemRepository items, EntryGenerator generator,
			AppProperties properties, Clock clock) {
		this.periods = periods;
		this.items = items;
		this.generator = generator;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Crea, abiertos, los períodos que le faltan al usuario hasta el horizonte y después genera, con el monto
	 * vigente de ese momento, las partidas que les faltan a sus Conceptos (RN-13). Todo en la transacción del caso
	 * de uso. Es idempotente: ejecutarla dos veces seguidas no cambia nada, porque la segunda vez ningún Concepto
	 * está pendiente.
	 */
	@Transactional
	public void ensureHorizon(User user) {
		YearMonth current = YearMonth.now(clock);
		List<YearMonth> missing = PeriodRange.missing(user.getStartPeriod(), current,
				properties.budget().horizonMonths(), periods.findPeriodMonthsByUserId(user.getId()));
		if (!missing.isEmpty()) {
			periods.saveAll(missing.stream().map(month -> BudgetPeriod.open(user.getId(), month)).toList());
		}

		// Los Conceptos son entidades gestionadas: generate actualiza generated_until y se guarda al confirmar.
		YearMonth horizon = PeriodRange.horizon(current, properties.budget().horizonMonths());
		items.findPendingGeneration(user.getId(), horizon).forEach(item -> generator.generate(item, horizon));
	}
}
