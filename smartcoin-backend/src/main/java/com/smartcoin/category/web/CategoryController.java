package com.smartcoin.category.web;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import com.smartcoin.category.service.CategoryService;
import com.smartcoin.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
@Tag(name = "Categorías", description = "Categorías opcionales para ordenar y filtrar Conceptos y partidas (RN-34).")
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final CategoryService categories;
	private final CurrentUser currentUser;

	public CategoryController(CategoryService categories, CurrentUser currentUser) {
		this.categories = categories;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(operationId = "listCategories", summary = "Listar las categorías",
			description = "Todas las categorías del usuario, ordenadas por nombre, sin paginación.")
	@ApiResponse(responseCode = "200", description = "Categorías del usuario.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public List<CategoryResponse> list() {
		return categories.list(currentUser.id()).stream().map(CategoryResponse::from).toList();
	}

	@PostMapping
	@Operation(operationId = "createCategory", summary = "Crear una categoría",
			description = "El nombre no puede repetirse entre las categorías del usuario, sin distinguir mayúsculas.")
	@ApiResponse(responseCode = "201", description = "Categoría creada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: el nombre falta, está en blanco o supera los 60 caracteres.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "CATEGORY_NAME_TAKEN: ya existe una categoría con ese nombre.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
		CategoryResponse created = CategoryResponse.from(categories.create(currentUser.id(), request.name()));
		return ResponseEntity.created(URI.create("/api/categories/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Operation(operationId = "updateCategory", summary = "Cambiar el nombre de una categoría",
			description = "Las categorías se renombran libremente. Cambiar solo las mayúsculas del propio nombre no es "
					+ "un conflicto.")
	@ApiResponse(responseCode = "200", description = "Categoría actualizada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: el nombre falta, está en blanco o supera los 60 caracteres.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la categoría no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "CATEGORY_NAME_TAKEN: ya existe otra categoría con ese nombre.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public CategoryResponse update(@PathVariable long id, @Valid @RequestBody CategoryRequest request) {
		return CategoryResponse.from(categories.update(currentUser.id(), id, request.name()));
	}

	@DeleteMapping("/{id}")
	@Operation(operationId = "deleteCategory", summary = "Eliminar una categoría",
			description = "Solo si ningún Concepto ni partida la usa.")
	@ApiResponse(responseCode = "204", description = "Categoría eliminada.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la categoría no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "CATEGORY_IN_USE: un Concepto o una partida usa la categoría.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<Void> delete(@PathVariable long id) {
		categories.delete(currentUser.id(), id);
		return ResponseEntity.noContent().build();
	}
}
