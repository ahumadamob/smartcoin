package com.smartcoin.shared.error;

import org.springframework.http.HttpStatus;

/** Códigos de error de la API, con el HTTP y el título de la tabla de {@code docs/reglas-de-negocio.md}. */
public enum ErrorCode {

	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Datos inválidos"),
	INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "Contraseña actual incorrecta"),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "No autenticado"),
	PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "Cambio de contraseña obligatorio"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "No encontrado"),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido"),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado"),
	EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email ya registrado"),
	ACCOUNT_NAME_TAKEN(HttpStatus.CONFLICT, "Nombre de cuenta repetido"),
	CATEGORY_NAME_TAKEN(HttpStatus.CONFLICT, "Nombre de categoría repetido"),
	ACCOUNT_IN_USE(HttpStatus.CONFLICT, "Cuenta en uso"),
	CATEGORY_IN_USE(HttpStatus.CONFLICT, "Categoría en uso"),
	FIELD_NOT_EDITABLE(HttpStatus.CONFLICT, "Dato no editable"),
	PERIOD_NOT_AVAILABLE(HttpStatus.CONFLICT, "Período no disponible"),
	PERIOD_CLOSED(HttpStatus.CONFLICT, "Período cerrado"),
	ENTRY_NOT_PENDING(HttpStatus.CONFLICT, "La partida no está pendiente"),
	ENTRY_HAS_MOVEMENTS(HttpStatus.CONFLICT, "La partida tiene movimientos"),
	CURRENCY_MISMATCH(HttpStatus.CONFLICT, "Moneda distinta"),
	DATE_OUT_OF_RANGE(HttpStatus.CONFLICT, "Fecha fuera de rango"),
	CONSOLIDATION_REQUIRES_MOVEMENT(HttpStatus.CONFLICT, "Consolidar requiere movimientos"),
	MANUAL_POLICY_REQUIRED(HttpStatus.CONFLICT, "Falta la política de partidas editadas"),
	NOTHING_PENDING(HttpStatus.CONFLICT, "No hay nada pendiente"),
	TRANSFER_AMOUNTS_MISMATCH(HttpStatus.CONFLICT, "Los montos de la transferencia no coinciden"),
	PREVIOUS_PERIOD_OPEN(HttpStatus.CONFLICT, "El período anterior está abierto"),
	PERIOD_NOT_FINISHED(HttpStatus.CONFLICT, "El período no terminó"),
	UNRESOLVED_PENDING_ENTRIES(HttpStatus.CONFLICT, "Hay partidas pendientes sin resolver"),
	MISSING_REAL_BALANCE(HttpStatus.CONFLICT, "Falta el saldo real de alguna cuenta"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno");

	private final HttpStatus status;
	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}
}
