package com.smartcoin.period.repository;

import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.period.domain.PeriodStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetPeriodRepository extends JpaRepository<BudgetPeriod, Long> {

	@Query("select p.periodMonth from BudgetPeriod p where p.userId = :userId")
	List<YearMonth> findPeriodMonthsByUserId(@Param("userId") Long userId);

	/** HU-10 (RN-08): con {@code CLOSED}, el último período cerrado del usuario. */
	Optional<BudgetPeriod> findFirstByUserIdAndStatusOrderByPeriodMonthDesc(Long userId, PeriodStatus status);

	/** HU-10 (RN-13): los períodos destino de una generación, en una sola consulta. */
	List<BudgetPeriod> findByUserIdAndPeriodMonthIn(Long userId, Collection<YearMonth> periodMonths);
}
