package com.smartcoin.category.web;

import com.smartcoin.category.domain.Category;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Una categoría del usuario.")
public record CategoryResponse(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
		Long id,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Impuestos")
		String name) {

	public static CategoryResponse from(Category category) {
		return new CategoryResponse(category.getId(), category.getName());
	}
}
