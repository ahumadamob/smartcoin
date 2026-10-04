package com.smartcoin.user.web;

import jakarta.validation.Valid;

import com.smartcoin.shared.security.CurrentUser;
import com.smartcoin.user.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticación", description = "Inicio de sesión y datos del usuario actual.")
public class AuthController {

	private final AuthService auth;
	private final CurrentUser currentUser;

	public AuthController(AuthService auth, CurrentUser currentUser) {
		this.auth = auth;
		this.currentUser = currentUser;
	}

	@PostMapping("/login")
	@Operation(summary = "Iniciar sesión",
			description = "Valida el email (sin distinguir mayúsculas) y la contraseña, asegura que existan los "
					+ "períodos hasta el horizonte y devuelve un JWT válido por 8 horas. No hay refresh token: al "
					+ "vencer hay que iniciar sesión de nuevo.")
	@ApiResponse(responseCode = "200", description = "Sesión iniciada.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: falta el email o la contraseña.",
			content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: usuario inexistente, contraseña incorrecta o "
			+ "usuario deshabilitado. El mensaje es el mismo en los tres casos.",
			content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		AuthService.Login login = auth.login(request.email(), request.password());
		return new LoginResponse(login.token().value(), login.token().expiresAt(), login.mustChangePassword());
	}

	@GetMapping("/me")
	@Operation(summary = "Datos del usuario actual",
			description = "Devuelve los datos del usuario del token, incluido si debe cambiar la contraseña.",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponse(responseCode = "200", description = "Usuario actual.")
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: token ausente, inválido, vencido o revocado.",
			content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	public UserResponse me() {
		return UserResponse.from(auth.me(currentUser.id()));
	}
}
