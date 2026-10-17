package com.smartcoin.report.web;

import java.time.LocalDate;

import com.smartcoin.report.service.CashFlowService;
import com.smartcoin.shared.domain.Currency;
import com.smartcoin.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cashflow")
@Tag(name = "Reportes", description = "Flujo de caja, proyección y vista de varios meses (Épica 9).")
@SecurityRequirement(name = "bearerAuth")
public class CashFlowController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final CashFlowService cashFlow;
	private final CurrentUser currentUser;

	public CashFlowController(CashFlowService cashFlow, CurrentUser currentUser) {
		this.cashFlow = cashFlow;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(operationId = "getCashFlow", summary = "Flujo de caja por fecha",
			description = "Para un rango de fechas y una moneda, lista en orden cronológico (RN-45): los movimientos de "
					+ "cuentas de esa moneda por su fecha, con signo según el tipo de la partida; las transferencias "
					+ "entre monedas distintas, con el signo de la moneda pedida; y el pendiente de cada partida "
					+ "pendiente de esa moneda en su vencimiento, o en hoy si ya venció. Arranca con el saldo de las "
					+ "cuentas al día anterior al rango (proyectado si el rango empieza después de hoy, D-46) y trae "
					+ "el saldo acumulado en cada fila. Sin `from` ni `to`, el mes actual; sin `to`, el último día "
					+ "del mes de `from`; sin `from`, el primer día del mes de `to`. El rango va del primer día del "
					+ "período inicial al último del horizonte y tiene hasta 366 días. Nunca suma monedas.")
	@ApiResponse(responseCode = "200", description = "Flujo de caja del rango; `rows` puede ser vacía.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: falta la moneda o no es `ARS` ni `USD`, una "
			+ "fecha mal formada, `from` posterior a `to` (error en `from`), `from` anterior al período inicial "
			+ "(error en `from`), `to` posterior al horizonte o rango de más de 366 días (error en `to`).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public CashFlowResponse get(
			@Parameter(description = "Desde esta fecha, inclusive (AAAA-MM-DD).") @RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@Parameter(description = "Hasta esta fecha, inclusive (AAAA-MM-DD).") @RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@Parameter(description = "Moneda del flujo. Obligatoria: nunca se suman monedas.", required = true)
			@RequestParam Currency currency) {
		return CashFlowResponse.from(cashFlow.cashFlow(currentUser.id(), from, to, currency));
	}
}
