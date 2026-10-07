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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

	@GetMapping("/{id}")
	@Operation(operationId = "getBudgetItem", summary = "Ver un Concepto",
			description = "Un Concepto del usuario, con qué datos no se pueden editar y por qué, y cuántas de sus "
					+ "partidas pendientes de períodos abiertos alcanza un cambio de monto vigente: las no editadas "
					+ "(que reemplaza) y las editadas (que no cambian). Los conteos los calcula el backend. Si no "
					+ "existe o es de otro usuario, responde 404.")
	@ApiResponse(responseCode = "200", description = "El Concepto.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el Concepto no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public BudgetItemDetailResponse get(@PathVariable long id) {
		return BudgetItemDetailResponse.from(items.get(currentUser.id(), id));
	}

	@PutMapping("/{id}")
	@Operation(operationId = "updateBudgetItem", summary = "Editar un Concepto",
			description = "Reemplaza todos los campos y propaga el cambio a las partidas (RN-15), todo en una sola "
					+ "transacción. Nombre, categoría y regla de estimación no tocan partidas. El día de vencimiento y "
					+ "el desfase recalculan el vencimiento de las partidas pendientes de períodos abiertos. La cuenta "
					+ "por defecto, solo por otra de la misma moneda, cambia la de las pendientes sin movimientos de "
					+ "períodos abiertos. El monto vigente reemplaza el presupuestado de las pendientes no editadas "
					+ "de períodos abiertos. Nunca se tocan partidas consolidadas ni de períodos cerrados. Tipo, "
					+ "periodicidad, período de inicio, período de fin y cuotas no se editan; enviar el mismo valor "
					+ "que ya tienen no es un cambio (en un Concepto en cuotas, el fin se puede omitir). Al editar se "
					+ "asegura el horizonte (RN-07): las partidas que genere salen con los datos nuevos.")
	@ApiResponse(responseCode = "200", description = "Concepto actualizado.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: faltan datos, tienen formato inválido, o la "
			+ "cuenta por defecto o la categoría no existen para el usuario. Trae `errors` por campo.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el Concepto no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "FIELD_NOT_EDITABLE: se cambió el tipo, la periodicidad, el "
			+ "período de inicio, el de fin o las cuotas. CURRENCY_MISMATCH: la cuenta por defecto nueva es de otra "
			+ "moneda.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public BudgetItemDetailResponse update(@PathVariable long id, @Valid @RequestBody BudgetItemRequest request) {
		return BudgetItemDetailResponse.from(items.update(currentUser.id(), id, request.toValues()));
	}
}
