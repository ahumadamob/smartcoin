package com.smartcoin.report.web;

import java.time.YearMonth;

import com.smartcoin.report.service.ProjectionService;
import com.smartcoin.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projection")
@Tag(name = "Reportes", description = "Flujo de caja, proyección y vista de varios meses (Épica 9).")
@SecurityRequirement(name = "bearerAuth")
public class ProjectionController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final ProjectionService projection;
	private final CurrentUser currentUser;

	public ProjectionController(ProjectionService projection, CurrentUser currentUser) {
		this.projection = projection;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(operationId = "getProjection", summary = "Proyección de saldos por cuenta",
			description = "Para cada mes desde el actual hasta `until` (por defecto, el horizonte) y cada cuenta, el "
					+ "saldo proyectado al último día del mes (RN-46): el saldo actual más el pendiente de las "
					+ "partidas de ingreso pendientes de esa cuenta con fecha efectiva hasta ese día, menos el de las "
					+ "de gasto. La fecha efectiva es el vencimiento, u hoy si ya venció. Supone que cada pendiente "
					+ "se cobra o paga en la cuenta prevista de su partida y no incluye transferencias futuras. Trae "
					+ "subtotales por moneda; nunca suma ARS con USD.")
	@ApiResponse(responseCode = "200", description = "Proyección de todas las cuentas del usuario; `accounts` puede ser "
			+ "vacía.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: `until` mal formado, anterior al mes actual o "
			+ "posterior al horizonte (error en `until`).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ProjectionResponse get(
			@Parameter(description = "Último mes a proyectar, inclusive (AAAA-MM). Por defecto, el horizonte.",
					example = "2027-12")
			@RequestParam(required = false) YearMonth until) {
		return ProjectionResponse.from(projection.projection(currentUser.id(), until));
	}
}
