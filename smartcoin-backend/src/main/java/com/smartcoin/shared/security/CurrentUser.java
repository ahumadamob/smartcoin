package com.smartcoin.shared.security;

import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Usuario autenticado del pedido en curso. El id sale siempre del token, nunca de la ruta, el cuerpo o la consulta. */
@Component
public class CurrentUser {

	/** Id del usuario del token. {@link CurrentUserFilter} ya verificó que el usuario existe y que el token sigue vigente. */
	public long id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwt) {
			try {
				return Long.parseLong(jwt.getToken().getSubject());
			}
			catch (NumberFormatException e) {
				// Cae al 401 de abajo: un sub que no es un id no identifica a nadie.
			}
		}
		throw new BusinessException(ErrorCode.UNAUTHORIZED, "Credenciales inválidas o ausentes.");
	}
}
