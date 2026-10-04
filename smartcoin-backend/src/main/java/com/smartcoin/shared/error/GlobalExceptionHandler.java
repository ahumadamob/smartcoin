package com.smartcoin.shared.error;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Convierte todo error a Problem Details (RFC 9457) con el campo {@code code}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<Object> handleBusiness(BusinessException e, WebRequest request) {
		ProblemDetail problem = problem(e.code(), e.getMessage());
		if (!e.entries().isEmpty()) {
			problem.setProperty("entries", e.entries());
		}
		return respond(problem, request);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException e, WebRequest request) {
		return respond(problem(ErrorCode.UNAUTHORIZED, "Credenciales inválidas o ausentes."), request);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException e, WebRequest request) {
		List<Map<String, String>> errors = e.getConstraintViolations().stream()
				.map(v -> fieldError(v.getPropertyPath().toString(), v.getMessage()))
				.toList();
		return respond(validationProblem(errors), request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception e, WebRequest request) {
		log.error("Error inesperado", e);
		return respond(problem(ErrorCode.INTERNAL_ERROR, "Ocurrió un error inesperado."), request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = e.getBindingResult().getAllErrors().stream()
				.map(error -> fieldError(
						error instanceof FieldError field ? field.getField() : error.getObjectName(),
						error.getDefaultMessage()))
				.toList();
		return respond(validationProblem(errors), request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = e.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> fieldError(result.getMethodParameter().getParameterName(),
								error.getDefaultMessage())))
				.toList();
		return respond(validationProblem(errors), request);
	}

	/** Errores estándar de Spring MVC (JSON ilegible, parámetros, ruta, método, tipo de contenido). */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ErrorCode code = switch (status.value()) {
			case 404 -> ErrorCode.NOT_FOUND;
			case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
			case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
			case 400 -> ErrorCode.VALIDATION_ERROR;
			default -> ErrorCode.INTERNAL_ERROR;
		};
		String detail = switch (code) {
			case NOT_FOUND -> "El recurso solicitado no existe.";
			case METHOD_NOT_ALLOWED -> "La ruta no admite ese método HTTP.";
			case UNSUPPORTED_MEDIA_TYPE -> "El cuerpo debe enviarse como JSON.";
			case VALIDATION_ERROR -> "La solicitud tiene datos inválidos o incompletos.";
			default -> "Ocurrió un error inesperado.";
		};
		if (code == ErrorCode.INTERNAL_ERROR) {
			log.error("Error inesperado de Spring MVC", e);
		}
		return respond(problem(code, detail), headers, request);
	}

	private ResponseEntity<Object> respond(ProblemDetail problem, WebRequest request) {
		return respond(problem, new HttpHeaders(), request);
	}

	private ResponseEntity<Object> respond(ProblemDetail problem, HttpHeaders headers, WebRequest request) {
		return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
	}

	private static ProblemDetail problem(ErrorCode code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
		problem.setType(URI.create("about:blank"));
		problem.setTitle(code.title());
		problem.setProperty("code", code.name());
		return problem;
	}

	private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
		ProblemDetail problem = problem(ErrorCode.VALIDATION_ERROR, "La solicitud tiene datos inválidos.");
		problem.setProperty("errors", errors);
		return problem;
	}

	private static Map<String, String> fieldError(String field, @Nullable String message) {
		return Map.of("field", field, "message", message == null ? "Valor inválido" : message);
	}
}
