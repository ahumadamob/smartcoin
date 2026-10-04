package com.smartcoin.user.domain;

import java.time.YearMonth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.smartcoin.shared.domain.AuditedEntity;

/** Persona que inicia sesión. Es dueña de todos sus datos. */
@Entity
@Table(name = "app_user")
public class User extends AuditedEntity {

	@Column(name = "email", nullable = false, length = 254)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword;

	@Column(name = "credentials_version", nullable = false)
	private int credentialsVersion;

	@Column(name = "start_period", nullable = false, length = 7)
	private YearMonth startPeriod;

	@Column(name = "enabled", nullable = false)
	private boolean enabled;

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public void setMustChangePassword(boolean mustChangePassword) {
		this.mustChangePassword = mustChangePassword;
	}

	public int getCredentialsVersion() {
		return credentialsVersion;
	}

	public void setCredentialsVersion(int credentialsVersion) {
		this.credentialsVersion = credentialsVersion;
	}

	public YearMonth getStartPeriod() {
		return startPeriod;
	}

	public void setStartPeriod(YearMonth startPeriod) {
		this.startPeriod = startPeriod;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
}
