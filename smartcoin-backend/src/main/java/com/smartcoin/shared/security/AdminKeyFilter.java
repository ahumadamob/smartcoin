package com.smartcoin.shared.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Protege {@code /api/admin/**} con la clave de administración (RN-47). Actúa antes de que se lea el cuerpo: una
 * clave inválida responde 401 aunque el cuerpo también sea inválido. No es un bean: lo arma {@link SecurityConfig}
 * dentro de su cadena para que no se registre también como filtro global del contenedor.
 */
class AdminKeyFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Admin-Key";

	private final String adminKey;
	private final HandlerExceptionResolver resolver;

	AdminKeyFilter(String adminKey, HandlerExceptionResolver resolver) {
		this.adminKey = adminKey;
		this.resolver = resolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!matches(request.getHeader(HEADER))) {
			// Mismo mensaje con clave ausente, incorrecta o no configurada: no revela cuál fue la causa.
			resolver.resolveException(request, response, null,
					new BusinessException(ErrorCode.UNAUTHORIZED, "Clave de administración inválida o ausente."));
			return;
		}
		chain.doFilter(request, response);
	}

	/** Sin clave configurada (variable ausente o vacía), rechaza siempre. */
	private boolean matches(String provided) {
		if (adminKey == null || adminKey.isEmpty() || provided == null) {
			return false;
		}
		return MessageDigest.isEqual(adminKey.getBytes(StandardCharsets.UTF_8),
				provided.getBytes(StandardCharsets.UTF_8));
	}
}
