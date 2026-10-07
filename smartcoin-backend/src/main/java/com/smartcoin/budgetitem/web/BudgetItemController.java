package com.smartcoin.budgetitem.web;

import java.net.URI;

import jakarta.validation.Valid;

import com.smartcoin.budgetitem.service.BudgetItemService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/budget-items")
@Tag(name = "Conceptos", description = "Conceptos: la regla de algo que se repite y que genera las partidas de cada "
		+ "período (RN-10 a RN-13).")
@SecurityRequirement(name = "bearerAuth")
public class BudgetItemController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final BudgetItemService items;
	private final CurrentUser currentUser;

	public BudgetItemController(BudgetItemService items, CurrentUser currentUser) {
		this.items = items;
		this.currentUser = currentUser;
	}

	@PostMapping
	@Operation(operationId = "createBudgetItem", summary = "Crear un Concepto",
			description = "Guarda el Concepto y, en la misma operación, genera sus partidas desde el período de inicio "
					+ "hasta el horizonte o el fin, según la periodicidad, con el vencimiento de RN-12 y el monto "
					+ "vigente como presupuestado. Con `installmentsTotal` el Concepto es en cuotas: el fin se calcula "
					+ "(RN-14) y cada partida lleva su número de cuota. Si el plan termina después del horizonte, se "
					+ "generan solo las cuotas que entran.")
	@ApiResponse(responseCode = "201", description = "Concepto creado, con el resumen de las partidas generadas.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: faltan datos, tienen formato inválido, el "
			+ "período de fin es anterior al de inicio, la cuenta por defecto o la categoría no existen para el "
			+ "usuario, o los datos de cuotas son inválidos (primera cuota sin total o mayor que el total, o fin "
			+ "informado junto con cuotas). Trae `errors` por campo.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_NOT_AVAILABLE: el período de inicio es anterior al primer "
			+ "período abierto o posterior al horizonte.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<BudgetItemResponse> create(@Valid @RequestBody BudgetItemRequest request) {
		BudgetItemResponse created = BudgetItemResponse.from(items.create(currentUser.id(), request.toValues()));
		return ResponseEntity.created(URI.create("/api/budget-items/" + created.id())).body(created);
	}
}
