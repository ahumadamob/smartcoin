package com.smartcoin.user.web;

import jakarta.validation.Valid;

import com.smartcoin.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Administración de usuarios",
		description = "Operaciones del administrador. Se autentican con el header X-Admin-Key, no con JWT.")
public class AdminUserController {

	private final UserService users;

	public AdminUserController(UserService users) {
		this.users = users;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Crear un usuario",
			description = "Crea un usuario habilitado, con cambio de contraseña obligatorio y con sus períodos "
					+ "abiertos desde el período inicial hasta el horizonte (el período actual más 24 meses). "
					+ "No existe registro público: solo el administrador da de alta usuarios.",
			parameters = @Parameter(name = "X-Admin-Key", in = ParameterIn.HEADER, required = true,
					description = "Clave de administración (variable de entorno APP_ADMIN_KEY). Si falta, es "
							+ "incorrecta o el servidor no la tiene configurada, la respuesta es siempre 401.",
					schema = @Schema(type = "string")))
	@ApiResponse(responseCode = "201", description = "Usuario creado. No devuelve la contraseña.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: email inválido, contraseña que no cumple "
			+ "la política o período inicial posterior al actual.",
			content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: clave de administración ausente, incorrecta "
			+ "o no configurada. No se crea nada.",
			content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_EXISTS: ya hay un usuario con ese email, "
			+ "sin distinguir mayúsculas.",
			content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	public CreatedUserResponse create(@Valid @RequestBody CreateUserRequest request) {
		return CreatedUserResponse.from(users.create(request.email(), request.password(), request.startPeriod()));
	}
}
