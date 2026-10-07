package com.smartcoin.budgetitem.service;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.budgetitem.domain.DueDateCalculator;
import com.smartcoin.budgetitem.domain.InstallmentPlan;
import com.smartcoin.budgetitem.domain.ScheduleCalculator;
import com.smartcoin.budgetitem.domain.ScheduleCalculator.Schedule;
import com.smartcoin.budgetitem.domain.ScheduleCalculator.ScheduledPeriod;
import com.smartcoin.entry.domain.BudgetEntry;
import com.smartcoin.entry.domain.EntryOrigin;
import com.smartcoin.entry.domain.StoredEntryStatus;
import com.smartcoin.entry.repository.BudgetEntryRepository;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.repository.BudgetPeriodRepository;

import org.springframework.stereotype.Component;

/**
 * Generación de las partidas de un Concepto (RN-13). No abre transacción: corre dentro de la del caso de uso que la
 * llama, que antes tiene que haber asegurado que existen los períodos hasta el horizonte (RN-07).
 */
@Component
public class EntryGenerator {

	private final BudgetPeriodRepository periods;
	private final BudgetEntryRepository entries;

	public EntryGenerator(BudgetPeriodRepository periods, BudgetEntryRepository entries) {
		this.periods = periods;
		this.entries = entries;
	}

	/**
	 * Crea las partidas que le faltan al Concepto hasta el horizonte o su fin y deja anotado hasta dónde generó. Es
	 * idempotente: un período ya procesado no se vuelve a procesar.
	 *
	 * @param item Concepto ya guardado
	 * @param horizon último período que existe
	 * @return las partidas creadas, en orden de período
	 */
	public List<BudgetEntry> generate(BudgetItem item, YearMonth horizon) {
		Schedule schedule = ScheduleCalculator.pending(item.getStartPeriod(), item.getPeriodicity(),
				item.getEndPeriod(), item.getGeneratedUntil(), horizon);
		List<BudgetEntry> created = schedule.periods().isEmpty() ? List.of() : entries.saveAll(build(item, schedule));
		item.setGeneratedUntil(schedule.generatedUntil());
		return created;
	}

	private List<BudgetEntry> build(BudgetItem item, Schedule schedule) {
		List<YearMonth> months = schedule.periods().stream().map(ScheduledPeriod::period).toList();
		InstallmentPlan plan = item.installmentPlan();
		// Una sola consulta para todos los períodos destino, no una por partida.
		Map<YearMonth, BudgetPeriod> byMonth = periods.findByUserIdAndPeriodMonthIn(item.getUserId(), months).stream()
				.collect(Collectors.toMap(BudgetPeriod::getPeriodMonth, Function.identity()));
		return schedule.periods().stream().map(scheduled -> entry(item, plan, scheduled, byMonth.get(scheduled.period())))
				.toList();
	}

	private static BudgetEntry entry(BudgetItem item, InstallmentPlan plan, ScheduledPeriod scheduled,
			BudgetPeriod period) {
		YearMonth month = scheduled.period();
		if (period == null) {
			throw new IllegalStateException("No existe el período " + month + " del usuario " + item.getUserId()
					+ ": hay que asegurar el horizonte antes de generar.");
		}
		// Nombre y categoría quedan nulos: las partidas recurrentes muestran los del Concepto.
		BudgetEntry entry = new BudgetEntry();
		entry.setUserId(item.getUserId());
		entry.setPeriod(period);
		entry.setBudgetItem(item);
		entry.setOrigin(EntryOrigin.RECURRING);
		entry.setKind(item.getKind());
		entry.setAccount(item.getDefaultAccount());
		entry.setDueDate(DueDateCalculator.dueDate(month, item.getDueDay(), item.getDueMonthOffset()));
		entry.setBudgetedAmount(item.getCurrentAmount());
		entry.setManual(false);
		entry.setStatus(StoredEntryStatus.PENDING);
		// El número sale del índice desde el inicio, no de cuántas partidas existen (RN-14).
		entry.setInstallmentNumber(plan == null ? null : plan.installmentNumber(scheduled.index()));
		return entry;
	}
}
