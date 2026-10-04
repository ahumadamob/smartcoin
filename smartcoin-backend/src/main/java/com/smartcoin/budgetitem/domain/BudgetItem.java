package com.smartcoin.budgetitem.domain;

import java.math.BigDecimal;
import java.time.YearMonth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.smartcoin.account.domain.Account;
import com.smartcoin.category.domain.Category;
import com.smartcoin.shared.domain.AuditedEntity;
import com.smartcoin.shared.domain.EntryKind;

/** Concepto: la regla de algo que se repite. */
@Entity
@Table(name = "budget_item")
public class BudgetItem extends AuditedEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 10)
	private EntryKind kind;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id")
	private Category category;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "default_account_id")
	private Account defaultAccount;

	@Enumerated(EnumType.STRING)
	@Column(name = "periodicity", nullable = false, length = 12)
	private Periodicity periodicity;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "due_day", nullable = false)
	private Integer dueDay;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "due_month_offset", nullable = false)
	private Integer dueMonthOffset;

	@Column(name = "start_period", nullable = false, length = 7)
	private YearMonth startPeriod;

	@Column(name = "end_period", length = 7)
	private YearMonth endPeriod;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "installments_total", nullable = true)
	private Integer installmentsTotal;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "first_installment_number", nullable = true)
	private Integer firstInstallmentNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "estimation_rule", nullable = false, length = 20)
	private EstimationRule estimationRule;

	@Column(name = "current_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal currentAmount;

	@Column(name = "generated_until", length = 7)
	private YearMonth generatedUntil;

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

	public EntryKind getKind() {
		return kind;
	}

	public void setKind(EntryKind kind) {
		this.kind = kind;
	}

	public Category getCategory() {
		return category;
	}

	public void setCategory(Category category) {
		this.category = category;
	}

	public Account getDefaultAccount() {
		return defaultAccount;
	}

	public void setDefaultAccount(Account defaultAccount) {
		this.defaultAccount = defaultAccount;
	}

	public Periodicity getPeriodicity() {
		return periodicity;
	}

	public void setPeriodicity(Periodicity periodicity) {
		this.periodicity = periodicity;
	}

	public Integer getDueDay() {
		return dueDay;
	}

	public void setDueDay(Integer dueDay) {
		this.dueDay = dueDay;
	}

	public Integer getDueMonthOffset() {
		return dueMonthOffset;
	}

	public void setDueMonthOffset(Integer dueMonthOffset) {
		this.dueMonthOffset = dueMonthOffset;
	}

	public YearMonth getStartPeriod() {
		return startPeriod;
	}

	public void setStartPeriod(YearMonth startPeriod) {
		this.startPeriod = startPeriod;
	}

	public YearMonth getEndPeriod() {
		return endPeriod;
	}

	public void setEndPeriod(YearMonth endPeriod) {
		this.endPeriod = endPeriod;
	}

	public Integer getInstallmentsTotal() {
		return installmentsTotal;
	}

	public void setInstallmentsTotal(Integer installmentsTotal) {
		this.installmentsTotal = installmentsTotal;
	}

	public Integer getFirstInstallmentNumber() {
		return firstInstallmentNumber;
	}

	public void setFirstInstallmentNumber(Integer firstInstallmentNumber) {
		this.firstInstallmentNumber = firstInstallmentNumber;
	}

	public EstimationRule getEstimationRule() {
		return estimationRule;
	}

	public void setEstimationRule(EstimationRule estimationRule) {
		this.estimationRule = estimationRule;
	}

	public BigDecimal getCurrentAmount() {
		return currentAmount;
	}

	public void setCurrentAmount(BigDecimal currentAmount) {
		this.currentAmount = currentAmount;
	}

	public YearMonth getGeneratedUntil() {
		return generatedUntil;
	}

	public void setGeneratedUntil(YearMonth generatedUntil) {
		this.generatedUntil = generatedUntil;
	}
}
