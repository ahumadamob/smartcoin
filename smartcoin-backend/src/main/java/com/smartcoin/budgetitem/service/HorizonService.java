package com.smartcoin.budgetitem.service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodRange;
import com.smartcoin.period.repository.BudgetPeriodRepository;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.user.domain.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Asegurar el horizonte (RN-07). */
@Service
public class HorizonService {

	private final BudgetPeriodRepository periods;
	private final AppProperties properties;
	private final Clock clock;

	public HorizonService(BudgetPeriodRepository periods, AppProperties properties, Clock clock) {
		this.periods = periods;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Crea, abiertos, los períodos que le faltan al usuario hasta el horizonte. Es idempotente: ejecutarla dos
	 * veces seguidas no cambia nada.
	 */
	@Transactional
	public void ensureHorizon(User user) {
		YearMonth current = YearMonth.now(clock);
		List<YearMonth> missing = PeriodRange.missing(user.getStartPeriod(), current,
				properties.budget().horizonMonths(), periods.findPeriodMonthsByUserId(user.getId()));
		periods.saveAll(missing.stream().map(month -> BudgetPeriod.open(user.getId(), month)).toList());

		// TODO(HU-12, RN-07): a continuación de crear los períodos, para que existan los períodos destino, llamar a
		// EntryGenerator.generate(item, horizonte) por cada Concepto del usuario. Hoy solo lo llama el alta (HU-10).
	}
}
