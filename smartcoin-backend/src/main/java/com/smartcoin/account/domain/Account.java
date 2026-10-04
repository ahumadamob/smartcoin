package com.smartcoin.account.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.smartcoin.shared.domain.AuditedEntity;
import com.smartcoin.shared.domain.Currency;

/** Donde está la plata: un banco, una billetera virtual o efectivo. Tiene una sola moneda. */
@Entity
@Table(name = "account")
public class Account extends AuditedEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 20)
	private AccountType type;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "currency", nullable = false, length = 3)
	private Currency currency;

	@Column(name = "opening_date", nullable = false)
	private LocalDate openingDate;

	@Column(name = "initial_balance", nullable = false, precision = 19, scale = 2)
	private BigDecimal initialBalance;

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public AccountType getType() {
		return type;
	}

	public void setType(AccountType type) {
		this.type = type;
	}

	public Currency getCurrency() {
		return currency;
	}

	public void setCurrency(Currency currency) {
		this.currency = currency;
	}

	public LocalDate getOpeningDate() {
		return openingDate;
	}

	public void setOpeningDate(LocalDate openingDate) {
		this.openingDate = openingDate;
	}

	public BigDecimal getInitialBalance() {
		return initialBalance;
	}

	public void setInitialBalance(BigDecimal initialBalance) {
		this.initialBalance = initialBalance;
	}
}
