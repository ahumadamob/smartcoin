package com.smartcoin.shared.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import com.smartcoin.shared.config.AppProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Base de seguridad: sin sesión, JWT HS256 firmado con {@code APP_JWT_SECRET}, Swagger público.
 * El login, el filtro de usuario (RN-50) y la clave de administración llegan con las historias de usuarios.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
		// Los 401 pasan por el manejador global para salir como Problem Details con `code`.
		AuthenticationEntryPoint entryPoint = (request, response, e) -> resolver.resolveException(request, response, null, e);
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
				.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
				.build();
	}

	@Bean
	JwtDecoder jwtDecoder(AppProperties properties) {
		byte[] secret = properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
		return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
	}
}
