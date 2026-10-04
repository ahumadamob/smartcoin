package com.smartcoin.shared.security;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * RN-50: en cada pedido con JWT válido carga el usuario del token y responde 401 si no existe, está
 * deshabilitado o su versión de credenciales no coincide con la del token (contraseña cambiada o restablecida).
 * Si el usuario debe cambiar la contraseña, responde 403 {@code PASSWORD_CHANGE_REQUIRED} a todo pedido que no esté
 * en {@link #ALLOWED_WHILE_PASSWORD_CHANGE_PENDING}. Es una lista de permitidos: los endpoints futuros quedan
 * cubiertos sin tocar nada. No es un bean: lo arma {@link SecurityConfig} dentro de su cadena, después de la validación del JWT.
 */
class CurrentUserFilter extends OncePerRequestFilter {

	/** Pedido permitido con el cambio de contraseña pendiente: método HTTP y ruta exacta. */
	record Endpoint(String method, String path) {
	}

	/** Único acceso con el cambio pendiente: cambiar la contraseña y consultar el usuario actual (RN-50). */
	static final Set<Endpoint> ALLOWED_WHILE_PASSWORD_CHANGE_PENDING = Set.of(
			new Endpoint("POST", SecurityConfig.CHANGE_PASSWORD_PATH),
			new Endpoint("GET", SecurityConfig.ME_PATH));

	private final UserRepository users;
	private final HandlerExceptionResolver resolver;

	CurrentUserFilter(UserRepository users, HandlerExceptionResolver resolver) {
		this.users = users;
		this.resolver = resolver;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// El login se autentica con email y contraseña: un token viejo en el pedido no debe impedirlo.
		return request.getRequestURI().equals(SecurityConfig.LOGIN_PATH);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwt) {
			User user = load(jwt);
			if (user == null || !user.isEnabled() || !credentialsMatch(user, jwt)) {
				// Mismo mensaje en los tres casos: no revela cuál fue la causa.
				resolver.resolveException(request, response, null,
						new BusinessException(ErrorCode.UNAUTHORIZED, "La sesión no es válida. Iniciá sesión de nuevo."));
				return;
			}
			if (user.isMustChangePassword() && !isAllowedWhilePasswordChangePending(request)) {
				resolver.resolveException(request, response, null, new BusinessException(
						ErrorCode.PASSWORD_CHANGE_REQUIRED, "Tenés que cambiar la contraseña antes de seguir."));
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private static boolean isAllowedWhilePasswordChangePending(HttpServletRequest request) {
		return ALLOWED_WHILE_PASSWORD_CHANGE_PENDING.contains(new Endpoint(request.getMethod(), request.getRequestURI()));
	}

	/** El usuario del {@code sub}, o {@code null} si el claim no es un id o no existe tal usuario. */
	private User load(JwtAuthenticationToken jwt) {
		try {
			return users.findById(Long.parseLong(jwt.getToken().getSubject())).orElse(null);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean credentialsMatch(User user, JwtAuthenticationToken jwt) {
		Object version = jwt.getToken().getClaim(TokenService.CREDENTIALS_VERSION_CLAIM);
		return version instanceof Number number && number.intValue() == user.getCredentialsVersion();
	}
}
