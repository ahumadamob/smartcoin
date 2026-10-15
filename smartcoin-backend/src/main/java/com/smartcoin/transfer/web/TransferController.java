package com.smartcoin.transfer.web;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import com.smartcoin.shared.security.CurrentUser;
import com.smartcoin.transfer.service.TransferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transferencias", description = "Paso de plata entre cuentas propias (RN-36). No es ingreso ni gasto.")
@SecurityRequirement(name = "bearerAuth")
public class TransferController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final TransferService transfers;
	private final CurrentUser currentUser;

	public TransferController(TransferService transfers, CurrentUser currentUser) {
		this.transfers = transfers;
		this.currentUser = currentUser;
	}

	@PostMapping
	@Operation(operationId = "createTransfer", summary = "Registrar una transferencia",
			description = "Resta el monto de origen de la cuenta de origen y suma el de destino a la de destino "
					+ "(RN-35). No es ingreso ni gasto: no aparece en la vista del mes ni cambia sus totales. "
					+ "Orden en que se evalúa, y se informa el primero que falla: formato del cuerpo (400); origen "
					+ "igual a destino (400 en `targetAccountId`); cuenta de origen y de destino inexistentes o "
					+ "ajenas (400 en su campo); montos distintos con la misma moneda (409 "
					+ "TRANSFER_AMOUNTS_MISMATCH); y fecha: posterior a hoy o anterior a la apertura de alguna de "
					+ "las cuentas (409 DATE_OUT_OF_RANGE) y en un mes cerrado (409 PERIOD_CLOSED).")
	@ApiResponse(responseCode = "201", description = "Transferencia registrada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: faltan datos, un monto no es mayor que 0 o "
			+ "tiene más de 2 decimales, la nota supera los 200 caracteres, origen y destino son la misma cuenta, "
			+ "o una cuenta no existe para el usuario. Trae `errors` por campo.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "TRANSFER_AMOUNTS_MISMATCH: las cuentas son de la misma moneda "
			+ "y los montos difieren. DATE_OUT_OF_RANGE: fecha futura, anterior a la apertura de una de las cuentas "
			+ "o en un mes anterior al período inicial. PERIOD_CLOSED: el mes de la fecha está cerrado (RN-09).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<TransferResponse> create(@Valid @RequestBody TransferRequest request) {
		TransferResponse created = TransferResponse
				.from(transfers.create(currentUser.id(), request.toValues()));
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping
	@Operation(operationId = "listTransfers", summary = "Listar transferencias de un rango de fechas",
			description = "Las transferencias del usuario con fecha entre `from` y `to`, ambas inclusive, de la más "
					+ "reciente a la más antigua y, a igual fecha, la última registrada primero. Cada una trae sus "
					+ "dos cuentas con su moneda. Sin `to`, hoy; sin `from`, el primer día del mes de `to`, es "
					+ "decir, el mes actual. Sin paginación.")
	@ApiResponse(responseCode = "200", description = "Transferencias del rango; vacía si no hay.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: una fecha mal formada, o `from` posterior a "
			+ "`to` (error en `from`).",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public List<TransferResponse> list(
			@Parameter(description = "Desde esta fecha, inclusive (AAAA-MM-DD).") @RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@Parameter(description = "Hasta esta fecha, inclusive (AAAA-MM-DD).") @RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return transfers.list(currentUser.id(), from, to).stream().map(TransferResponse::from).toList();
	}
}
