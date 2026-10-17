package com.smartcoin.report.web;

import java.time.YearMonth;

import com.smartcoin.report.service.BudgetGridService;
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
@RequestMapping("/api/budget-grid")
@Tag(name = "Reportes", description = "Flujo de caja, proyección y vista de varios meses (Épica 9).")
@SecurityRequirement(name = "bearerAuth")
public class BudgetGridController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final BudgetGridService grid;
	private final CurrentUser currentUser;

	public BudgetGridController(BudgetGridService grid, CurrentUser currentUser) {
		this.grid = grid;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(operationId = "getBudgetGrid", summary = "Vista de varios meses",
			description = "Los Conceptos del usuario en filas y los meses de `from` a `to` en columnas. Cada celda trae "
					+ "el estimado de la partida (RN-17), su estado mostrado (RN-16) y si está editada o consolidada, "
					+ "calculados como en la vista del mes. Las partidas sin Concepto (puntuales, saldos postergados y "
					+ "diferencias de cierre) van en una fila por origen, tipo y moneda con el estimado sumado de cada "
					+ "mes. Solo aparecen las filas con alguna partida en el rango. Al pie, por mes y moneda, el "
					+ "estimado de ingresos, el de gastos y el resultado (RN-44). Nunca suma ARS con USD. Sin "
					+ "parámetros, 12 meses desde el actual.")
	@ApiResponse(responseCode = "200", description = "La grilla; `rows` puede ser vacía.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: `from` o `to` mal formados, `from` posterior a "
			+ "`to`, `from` anterior al período inicial, `to` posterior al horizonte o más de 24 meses (error en "
			+ "`from` o `to`).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public BudgetGridResponse get(
			@Parameter(description = "Primer mes (AAAA-MM). Por defecto, el actual; si solo se pide `to`, 12 meses "
					+ "antes.", example = "2026-10")
			@RequestParam(required = false) YearMonth from,
			@Parameter(description = "Último mes, inclusive (AAAA-MM). Por defecto, 12 meses desde `from`, sin pasar "
					+ "del horizonte.", example = "2027-09")
			@RequestParam(required = false) YearMonth to) {
		return BudgetGridResponse.from(grid.grid(currentUser.id(), from, to));
	}
}
