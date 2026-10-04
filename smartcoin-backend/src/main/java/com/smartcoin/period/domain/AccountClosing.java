package com.smartcoin.period.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.smartcoin.account.domain.Account;
import com.smartcoin.shared.domain.BaseEntity;

/** Cierre de cuenta: saldo calculado y saldo real de una cuenta al cerrar el mes. No se modifica. */
@Entity
@Table(name = "account_closing")
public class AccountClosing extends BaseEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "period_id")
	private BudgetPeriod period;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id")
	private Account account;

	@Column(name = "computed_balance", nullable = false, precision = 19, scale = 2)
	private BigDecimal computedBalance;

	@Column(name = "real_balance", nullable = false, precision = 19, scale = 2)
	private BigDecimal realBalance;

	@Column(name = "difference", nullable = false, precision = 19, scale = 2)
	private BigDecimal difference;

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public BudgetPeriod getPeriod() {
		return period;
	}

	public void setPeriod(BudgetPeriod period) {
		this.period = period;
	}

	public Account getAccount() {
		return account;
	}

	public void setAccount(Account account) {
		this.account = account;
	}

	public BigDecimal getComputedBalance() {
		return computedBalance;
	}

	public void setComputedBalance(BigDecimal computedBalance) {
		this.computedBalance = computedBalance;
	}

	public BigDecimal getRealBalance() {
		return realBalance;
	}

	public void setRealBalance(BigDecimal realBalance) {
		this.realBalance = realBalance;
	}

	public BigDecimal getDifference() {
		return difference;
	}

	public void setDifference(BigDecimal difference) {
		this.difference = difference;
	}
}
