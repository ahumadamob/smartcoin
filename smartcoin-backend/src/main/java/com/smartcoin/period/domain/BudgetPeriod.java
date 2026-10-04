package com.smartcoin.period.domain;

import java.time.Instant;
import java.time.YearMonth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.smartcoin.shared.domain.AuditedEntity;

/** Período: un mes calendario, abierto o cerrado. */
@Entity
@Table(name = "budget_period")
public class BudgetPeriod extends AuditedEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "period_month", nullable = false, length = 7)
	private YearMonth periodMonth;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private PeriodStatus status;

	@Column(name = "closed_at", nullable = true)
	private Instant closedAt;

	/** Período abierto de un usuario. */
	public static BudgetPeriod open(Long userId, YearMonth periodMonth) {
		BudgetPeriod period = new BudgetPeriod();
		period.userId = userId;
		period.periodMonth = periodMonth;
		period.status = PeriodStatus.OPEN;
		return period;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public YearMonth getPeriodMonth() {
		return periodMonth;
	}

	public void setPeriodMonth(YearMonth periodMonth) {
		this.periodMonth = periodMonth;
	}

	public PeriodStatus getStatus() {
		return status;
	}

	public void setStatus(PeriodStatus status) {
		this.status = status;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

	public void setClosedAt(Instant closedAt) {
		this.closedAt = closedAt;
	}
}
