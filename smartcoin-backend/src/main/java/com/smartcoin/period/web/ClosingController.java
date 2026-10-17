package com.smartcoin.period.web;

import java.time.YearMonth;

import com.smartcoin.period.service.ClosingService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/periods")
@Tag(name = "Cierre de mes", description = "Simulación y cierre de un período (RN-38 a RN-43).")
@SecurityRequirement(name = "bearerAuth")
public class ClosingController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final ClosingService closing;
	private final CurrentUser currentUser;

	public ClosingController(ClosingService closing, CurrentUser currentUser) {
		this.closing = closing;
		this.currentUser = currentUser;
	}

	@PostMapping("/{period}/closing-preview")
	@Operation(operationId = "previewClosing", summary = "Simular el cierre de un mes",
			description = "Qué falta para cerrar el período, sin modificar nada (RN-43): si se cumplen las "
					+ "precondiciones de RN-38 y, si no, todas las que fallan, en el orden de RN-38; las partidas "
					+ "pendientes con real y pendiente, y las cuentas abiertas al último día del período con su "
					+ "saldo calculado a ese día (RN-35, RN-41). Un período cerrado responde 200 con "
					+ "`PERIOD_CLOSED` y las listas vacías. No lleva cuerpo: si se envía uno, se ignora.")
	@ApiResponse(responseCode = "200", description = "La simulación del cierre.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: el período no tiene el formato YYYY-MM.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: el período no existe para el usuario: es anterior "
			+ "a su período inicial o posterior al horizonte (RN-06).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ClosingPreviewResponse preview(
			@Parameter(description = "Período, como YYYY-MM.", example = "2026-10",
					schema = @Schema(type = "string", pattern = "^\\d{4}-(0[1-9]|1[0-2])$"))
			@PathVariable YearMonth period) {
		return ClosingPreviewResponse.from(closing.preview(currentUser.id(), period));
	}
}
