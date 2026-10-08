package com.smartcoin.period.web;

import java.time.YearMonth;

import com.smartcoin.period.service.PeriodViewService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/periods")
@Tag(name = "Períodos", description = "Períodos y vista del mes (RN-05, RN-06, RN-44).")
@SecurityRequirement(name = "bearerAuth")
public class PeriodController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final PeriodViewService view;
	private final CurrentUser currentUser;

	public PeriodController(PeriodViewService view, CurrentUser currentUser) {
		this.view = view;
		this.currentUser = currentUser;
	}

	@GetMapping("/current")
	@Operation(operationId = "getCurrentPeriod", summary = "Ver el presupuesto del mes actual",
			description = "La misma vista que `GET /api/periods/{period}`, para el período actual: el mes de hoy en "
					+ "la zona de la aplicación. Así quien consulta no decide cuál es el mes actual.")
	@ApiResponse(responseCode = "200", description = "La vista del período actual.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public PeriodViewResponse current() {
		return PeriodViewResponse.from(view.current(currentUser.id()));
	}

	@GetMapping("/{period}")
	@Operation(operationId = "getPeriod", summary = "Ver el presupuesto de un mes",
			description = "El estado del período y sus partidas separadas en ingresos y gastos, ordenadas por "
					+ "vencimiento y nombre, con sus valores derivados: real, pendiente, estimado, estado, si está "
					+ "editada y si está vencida. Las partidas recurrentes muestran el nombre y la categoría de su "
					+ "Concepto. Totales por moneda para ingresos y para gastos, y el resultado de cada moneda "
					+ "(estimado de ingresos menos estimado de gastos). Las transferencias no aparecen. Trae además "
					+ "el período inicial, el actual y el horizonte, que son los límites de la navegación.")
	@ApiResponse(responseCode = "200", description = "La vista del período.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: el período no tiene el formato YYYY-MM.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el período no existe para el usuario: es anterior "
			+ "a su período inicial o posterior al horizonte (RN-06).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public PeriodViewResponse get(
			@Parameter(description = "Período, como YYYY-MM.", example = "2026-11",
					schema = @Schema(type = "string", pattern = "^\\d{4}-(0[1-9]|1[0-2])$"))
			@PathVariable YearMonth period) {
		return PeriodViewResponse.from(view.view(currentUser.id(), period));
	}
}
