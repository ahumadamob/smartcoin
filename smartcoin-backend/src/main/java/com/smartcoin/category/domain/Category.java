package com.smartcoin.category.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.smartcoin.shared.domain.AuditedEntity;

/** Agrupación opcional para ordenar y filtrar. */
@Entity
@Table(name = "category")
public class Category extends AuditedEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "name", nullable = false, length = 60)
	private String name;

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
}
