package com.smartcoin.shared.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.annotations.UpdateTimestamp;

/** Agrega la fecha de última modificación. Todas las tablas la tienen salvo {@code account_closing}. */
@MappedSuperclass
public abstract class AuditedEntity extends BaseEntity {

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
