package com.smartcoin.category.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Datos de una categoría, para el alta y para el cambio de nombre.")
public record CategoryRequest(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 60, example = "Impuestos",
				description = "Nombre, único por usuario sin distinguir mayúsculas. Se guarda sin espacios en los extremos.")
		@NotBlank(message = "El nombre es obligatorio.")
		@Size(max = 60, message = "El nombre no puede superar los 60 caracteres.")
		String name) {
}
