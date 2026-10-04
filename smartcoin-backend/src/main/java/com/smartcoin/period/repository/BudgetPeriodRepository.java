package com.smartcoin.period.repository;

import java.time.YearMonth;
import java.util.List;

import com.smartcoin.period.domain.BudgetPeriod;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetPeriodRepository extends JpaRepository<BudgetPeriod, Long> {

	@Query("select p.periodMonth from BudgetPeriod p where p.userId = :userId")
	List<YearMonth> findPeriodMonthsByUserId(@Param("userId") Long userId);
}
