package com.smartcoin.entry.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
import com.smartcoin.budgetitem.domain.BudgetItem;
import com.smartcoin.category.domain.Category;
import com.smartcoin.period.domain.AccountClosing;
import com.smartcoin.period.domain.BudgetPeriod;
import com.smartcoin.shared.domain.AuditedEntity;
import com.smartcoin.shared.domain.EntryKind;

/** Partida: lo que se espera cobrar o pagar en un período. */
@Entity
@Table(name = "budget_entry")
public class BudgetEntry extends AuditedEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "period_id")
	private BudgetPeriod period;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "budget_item_id")
	private BudgetItem budgetItem;

	@Enumerated(EnumType.STRING)
	@Column(name = "origin", nullable = false, length = 20)
	private EntryOrigin origin;

	@Column(name = "name", length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 10)
	private EntryKind kind;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id")
	private Category category;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id")
	private Account account;

	@Column(name = "due_date", nullable = false)
	private LocalDate dueDate;

	@Column(name = "budgeted_amount", nullable = false, precision = 19, scale = 2)
	private BigDecimal budgetedAmount;

	@Column(name = "is_manual", nullable = false)
	private boolean manual;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 15)
	private StoredEntryStatus status;

	@Column(name = "consolidated_amount", precision = 19, scale = 2)
	private BigDecimal consolidatedAmount;

	@Column(name = "consolidated_at", nullable = true)
	private Instant consolidatedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "closing_resolution", length = 15)
	private ClosingResolution closingResolution;

	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "installment_number", nullable = true)
	private Integer installmentNumber;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_entry_id")
	private BudgetEntry sourceEntry;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "source_closing_id")
	private AccountClosing sourceClosing;

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

	public BudgetItem getBudgetItem() {
		return budgetItem;
	}

	public void setBudgetItem(BudgetItem budgetItem) {
		this.budgetItem = budgetItem;
	}

	public EntryOrigin getOrigin() {
		return origin;
	}

	public void setOrigin(EntryOrigin origin) {
		this.origin = origin;
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

	public Account getAccount() {
		return account;
	}

	public void setAccount(Account account) {
		this.account = account;
	}

	public LocalDate getDueDate() {
		return dueDate;
	}

	public void setDueDate(LocalDate dueDate) {
		this.dueDate = dueDate;
	}

	public BigDecimal getBudgetedAmount() {
		return budgetedAmount;
	}

	public void setBudgetedAmount(BigDecimal budgetedAmount) {
		this.budgetedAmount = budgetedAmount;
	}

	public boolean isManual() {
		return manual;
	}

	public void setManual(boolean manual) {
		this.manual = manual;
	}

	public StoredEntryStatus getStatus() {
		return status;
	}

	public void setStatus(StoredEntryStatus status) {
		this.status = status;
	}

	public BigDecimal getConsolidatedAmount() {
		return consolidatedAmount;
	}

	public void setConsolidatedAmount(BigDecimal consolidatedAmount) {
		this.consolidatedAmount = consolidatedAmount;
	}

	public Instant getConsolidatedAt() {
		return consolidatedAt;
	}

	public void setConsolidatedAt(Instant consolidatedAt) {
		this.consolidatedAt = consolidatedAt;
	}

	public ClosingResolution getClosingResolution() {
		return closingResolution;
	}

	public void setClosingResolution(ClosingResolution closingResolution) {
		this.closingResolution = closingResolution;
	}

	public Integer getInstallmentNumber() {
		return installmentNumber;
	}

	public void setInstallmentNumber(Integer installmentNumber) {
		this.installmentNumber = installmentNumber;
	}

	public BudgetEntry getSourceEntry() {
		return sourceEntry;
	}

	public void setSourceEntry(BudgetEntry sourceEntry) {
		this.sourceEntry = sourceEntry;
	}

	public AccountClosing getSourceClosing() {
		return sourceClosing;
	}

	public void setSourceClosing(AccountClosing sourceClosing) {
		this.sourceClosing = sourceClosing;
	}
}
