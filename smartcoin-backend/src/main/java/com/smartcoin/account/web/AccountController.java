package com.smartcoin.account.web;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import com.smartcoin.account.domain.AccountValues;
import com.smartcoin.account.service.AccountService;
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
@RequestMapping("/api/accounts")
@Tag(name = "Cuentas", description = "Cuentas del usuario: bancos, billeteras virtuales y efectivo (RN-33).")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

	private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

	private final AccountService accounts;
	private final CurrentUser currentUser;

	public AccountController(AccountService accounts, CurrentUser currentUser) {
		this.accounts = accounts;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(operationId = "listAccounts", summary = "Listar las cuentas",
			description = "Todas las cuentas del usuario, ordenadas por moneda y nombre, sin paginación. Cada una "
					+ "indica qué campos se pueden editar y por qué. El saldo actual y los subtotales llegan con HU-08.")
	@ApiResponse(responseCode = "200", description = "Cuentas del usuario.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public List<AccountResponse> list() {
		return accounts.list(currentUser.id()).stream().map(AccountResponse::from).toList();
	}

	@GetMapping("/{id}")
	@Operation(operationId = "getAccount", summary = "Ver una cuenta",
			description = "Una cuenta del usuario. Si no existe o es de otro usuario, responde 404.")
	@ApiResponse(responseCode = "200", description = "La cuenta.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la cuenta no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public AccountResponse get(@PathVariable long id) {
		return AccountResponse.from(accounts.get(currentUser.id(), id));
	}

	@PostMapping
	@Operation(operationId = "createAccount", summary = "Crear una cuenta",
			description = "La fecha de apertura va entre el primer día del período inicial del usuario y hoy, ambos "
					+ "inclusive. El nombre no puede repetirse entre las cuentas del usuario, sin distinguir mayúsculas.")
	@ApiResponse(responseCode = "201", description = "Cuenta creada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: faltan datos, tienen formato inválido o la "
			+ "fecha de apertura está fuera de rango.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "ACCOUNT_NAME_TAKEN: ya existe una cuenta con ese nombre.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<AccountResponse> create(@Valid @RequestBody AccountRequest request) {
		AccountResponse created = AccountResponse
				.from(accounts.create(currentUser.id(), toValues(request)));
		return ResponseEntity.created(URI.create("/api/accounts/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Operation(operationId = "updateAccount", summary = "Editar una cuenta",
			description = "Reemplaza todos los campos. Nombre y tipo se editan siempre. La moneda, solo si la cuenta "
					+ "no está referenciada por Conceptos, partidas, movimientos, transferencias ni cierres. El saldo "
					+ "inicial y la fecha de apertura, solo si no tiene cierres, y la fecha no puede quedar después de "
					+ "su primer movimiento o transferencia. Enviar el mismo valor que ya tiene no es un cambio.")
	@ApiResponse(responseCode = "200", description = "Cuenta actualizada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: faltan datos, tienen formato inválido o la "
			+ "fecha de apertura está fuera de rango.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la cuenta no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "FIELD_NOT_EDITABLE: se cambió un dato que no se puede editar "
			+ "en el estado actual de la cuenta. ACCOUNT_NAME_TAKEN: ya existe otra cuenta con ese nombre.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public AccountResponse update(@PathVariable long id, @Valid @RequestBody AccountRequest request) {
		return AccountResponse.from(accounts.update(currentUser.id(), id, toValues(request)));
	}

	@DeleteMapping("/{id}")
	@Operation(operationId = "deleteAccount", summary = "Eliminar una cuenta",
			description = "Solo si no está referenciada por Conceptos, partidas, movimientos, transferencias ni cierres.")
	@ApiResponse(responseCode = "204", description = "Cuenta eliminada.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: la cuenta no existe o es de otro usuario.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "ACCOUNT_IN_USE: la cuenta está referenciada.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
	public ResponseEntity<Void> delete(@PathVariable long id) {
		accounts.delete(currentUser.id(), id);
		return ResponseEntity.noContent().build();
	}

	private static AccountValues toValues(AccountRequest request) {
		return new AccountValues(request.name(), request.type(), request.currency(), request.openingDate(),
				request.initialBalance());
	}
}
