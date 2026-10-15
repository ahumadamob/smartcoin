package com.smartcoin.entry.web;

import com.smartcoin.entry.service.ConsolidationService;
import com.smartcoin.period.web.PeriodViewResponse.PeriodEntry;
import com.smartcoin.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Consolidación", description = "Consolidar una partida y propagar la estimación (RN-25 a RN-28).")
@SecurityRequirement(name = "bearerAuth")
public class ConsolidationController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final ConsolidationService consolidation;
	private final CurrentUser currentUser;

	public ConsolidationController(ConsolidationService consolidation, CurrentUser currentUser) {
		this.consolidation = consolidation;
		this.currentUser = currentUser;
	}

	@GetMapping("/entries/{id}/consolidation-preview")
	@Operation(operationId = "getConsolidationPreview", summary = "Vista previa de la consolidación de una partida",
			description = "Informa, sin modificar nada, qué pasaría al consolidar: el monto real, la nueva "
					+ "estimación, el monto vigente actual del Concepto, las partidas de destino con su presupuestado "
					+ "actual y nuevo, y si hace falta elegir política para las editadas. Sale del mismo plan que "
					+ "`POST /api/entries/{id}/consolidate`. Si la partida no se puede consolidar responde 200 con "
					+ "`allowed` en falso y el motivo.")
	@ApiResponse(responseCode = "200", description = "Vista previa de la consolidación.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ConsolidationPreviewResponse preview(@PathVariable long id) {
		return ConsolidationPreviewResponse.from(consolidation.preview(currentUser.id(), id));
	}

	@PostMapping("/entries/{id}/consolidate")
	@Operation(operationId = "consolidateEntry", summary = "Consolidar una partida",
			description = "Da por terminada una partida pendiente de un período abierto con al menos un movimiento: "
					+ "su monto consolidado es el real. Si es recurrente, propaga (RN-26): el monto vigente del "
					+ "Concepto pasa a la nueva estimación y las partidas pendientes de los meses siguientes la "
					+ "toman. Si hay partidas editadas en el destino hace falta `manualEntriesPolicy`. Una partida "
					+ "sin Concepto no propaga. Todo o nada: con un error no cambia nada. Orden en que se evalúa: "
					+ "formato del cuerpo (400), partida inexistente o ajena (404), período cerrado (409), partida "
					+ "consolidada (409), sin movimientos (409) y política faltante (409).")
	@ApiResponse(responseCode = "200", description = "Partida consolidada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: `manualEntriesPolicy` con un valor "
			+ "inválido.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período está cerrado (RN-09). "
			+ "ENTRY_NOT_PENDING: ya está consolidada. CONSOLIDATION_REQUIRES_MOVEMENT: no tiene movimientos. "
			+ "MANUAL_POLICY_REQUIRED: hay editadas en el destino y no se indicó política; `entries` trae sus ids.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ConsolidationResponse consolidate(@PathVariable long id,
			@RequestBody(required = false) ConsolidateRequest request) {
		return ConsolidationResponse.from(consolidation.consolidate(currentUser.id(), id,
				request == null ? null : request.manualEntriesPolicy()));
	}

	@PostMapping("/entries/{id}/unconsolidate")
	@Operation(operationId = "unconsolidateEntry", summary = "Desconsolidar una partida",
			description = "Vuelve a pendiente una partida consolidada de un período abierto: queda Parcial, porque "
					+ "tiene movimientos. Su presupuestado y su marca de editada no cambian. No revierte la "
					+ "propagación: el monto vigente del Concepto y las partidas de otros meses conservan lo que "
					+ "dejó la consolidación hasta la próxima consolidación del Concepto (RN-29). Orden en que se "
					+ "evalúa: partida inexistente o ajena (404), período cerrado (409) y partida no consolidada "
					+ "(409).")
	@ApiResponse(responseCode = "200", description = "La partida desconsolidada, con la forma de la vista del mes.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la partida no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "PERIOD_CLOSED: el período está cerrado (RN-09). "
			+ "ENTRY_NOT_CONSOLIDATED: la partida está pendiente.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public PeriodEntry unconsolidate(@PathVariable long id) {
		return PeriodEntry.from(consolidation.unconsolidate(currentUser.id(), id));
	}
}
