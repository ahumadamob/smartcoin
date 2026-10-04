package com.smartcoin.shared.security;

import java.time.Clock;
import java.time.Instant;

import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.user.domain.User;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Emisión del JWT (RN-49). Claims: {@code sub} (id del usuario), {@code cv} (versión de credenciales), {@code iat} y {@code exp}. */
@Service
public class TokenService {

	/** Claim con la versión de credenciales del usuario al emitir el token (RN-50). */
	static final String CREDENTIALS_VERSION_CLAIM = "cv";

	private final JwtEncoder encoder;
	private final AppProperties properties;
	private final Clock clock;

	public TokenService(JwtEncoder encoder, AppProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public IssuedToken issue(User user) {
		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(properties.security().jwtExpiration());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(String.valueOf(user.getId()))
				.claim(CREDENTIALS_VERSION_CLAIM, user.getCredentialsVersion())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new IssuedToken(token, expiresAt);
	}
}
