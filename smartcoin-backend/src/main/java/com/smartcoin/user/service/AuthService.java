package com.smartcoin.user.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.smartcoin.budgetitem.service.HorizonService;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.shared.security.IssuedToken;
import com.smartcoin.shared.security.TokenService;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Inicio de sesión (RN-49) y consulta del usuario actual. */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	/** Límite de BCrypt: una contraseña más larga nunca pudo guardarse (RN-51). */
	private static final int MAX_PASSWORD_BYTES = 72;

	/** Mismo mensaje con usuario inexistente, contraseña incorrecta o usuario deshabilitado. */
	static final String INVALID_CREDENTIALS = "Email o contraseña incorrectos.";

	/** Resultado del inicio de sesión: el token y si el usuario debe cambiar la contraseña. */
	public record Login(IssuedToken token, boolean mustChangePassword) {
	}

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final HorizonService horizon;
	private final TokenService tokens;
	/** Se compara contra este hash cuando el email no existe, para que el tiempo de respuesta no delate si existe. */
	private final String unknownUserHash;

	public AuthService(UserRepository users, PasswordEncoder passwordEncoder, HorizonService horizon,
			TokenService tokens) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.horizon = horizon;
		this.tokens = tokens;
		this.unknownUserHash = passwordEncoder.encode("contraseña-de-un-usuario-inexistente");
	}

	/**
	 * Inicia sesión (RN-49): con credenciales válidas y usuario habilitado asegura el horizonte (RN-07) y emite el
	 * token. Cualquier otro caso es un 401 con el mismo mensaje.
	 */
	@Transactional
	public Login login(String email, String rawPassword) {
		User user = users.findByEmail(email.toLowerCase(Locale.ROOT)).orElse(null);
		String hash = user == null ? unknownUserHash : user.getPasswordHash();
		boolean passwordMatches = fitsBcrypt(rawPassword) && passwordEncoder.matches(rawPassword, hash);
		if (user == null || !passwordMatches || !user.isEnabled()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
		}

		horizon.ensureHorizon(user);
		log.info("Sesión iniciada: id={}", user.getId());
		return new Login(tokens.issue(user), user.isMustChangePassword());
	}

	/** El usuario del token. Ya fue verificado por el filtro de usuario actual; el 401 cubre un borrado entre medio. */
	@Transactional(readOnly = true)
	public User me(long userId) {
		return users.findById(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "La sesión no es válida."));
	}

	private static boolean fitsBcrypt(String rawPassword) {
		return rawPassword.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
	}
}
