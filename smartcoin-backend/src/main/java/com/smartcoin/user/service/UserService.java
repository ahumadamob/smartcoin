package com.smartcoin.user.service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Locale;

import com.smartcoin.budgetitem.service.HorizonService;
import com.smartcoin.shared.error.BusinessException;
import com.smartcoin.shared.error.ErrorCode;
import com.smartcoin.user.domain.User;
import com.smartcoin.user.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final HorizonService horizon;
	private final Clock clock;

	public UserService(UserRepository users, PasswordEncoder passwordEncoder, HorizonService horizon, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.horizon = horizon;
		this.clock = clock;
	}

	/**
	 * Alta de usuario por el administrador (RN-47). Queda habilitado y con cambio de contraseña obligatorio, y se
	 * le asegura el horizonte (RN-07).
	 *
	 * @param startPeriod período inicial; si es {@code null}, el actual
	 */
	@Transactional
	public User create(String email, String rawPassword, YearMonth startPeriod) {
		YearMonth current = YearMonth.now(clock);
		YearMonth start = startPeriod == null ? current : startPeriod;
		if (start.isAfter(current)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR,
					"El período inicial no puede ser posterior al período actual (" + current + ").");
		}

		String normalizedEmail = email.toLowerCase(Locale.ROOT);
		if (users.existsByEmail(normalizedEmail)) {
			throw emailAlreadyExists();
		}

		User user = new User();
		user.setEmail(normalizedEmail);
		user.setPasswordHash(passwordEncoder.encode(rawPassword));
		user.setMustChangePassword(true);
		user.setCredentialsVersion(0);
		user.setStartPeriod(start);
		user.setEnabled(true);
		try {
			user = users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			// Otro alta con el mismo email ganó la carrera entre existsByEmail y el insert.
			throw emailAlreadyExists();
		}

		horizon.ensureHorizon(user);
		log.info("Usuario creado: id={}", user.getId());
		return user;
	}

	/**
	 * Restablecimiento de contraseña por el administrador (RN-48). Deja el cambio de contraseña obligatorio e
	 * incrementa la versión de credenciales, con lo que los tokens anteriores dejan de servir (RN-50). No cambia
	 * si el usuario está habilitado.
	 */
	@Transactional
	public void resetPassword(String email, String temporaryPassword) {
		User user = users.findByEmail(email.toLowerCase(Locale.ROOT))
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No existe un usuario con ese email."));

		user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
		user.setMustChangePassword(true);
		user.setCredentialsVersion(user.getCredentialsVersion() + 1);
		users.save(user);
		log.info("Contraseña restablecida: id={}", user.getId());
	}

	private static BusinessException emailAlreadyExists() {
		return new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Ya existe un usuario con ese email.");
	}
}
