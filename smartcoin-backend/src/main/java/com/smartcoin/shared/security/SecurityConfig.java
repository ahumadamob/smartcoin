package com.smartcoin.shared.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcoin.shared.config.AppProperties;
import com.smartcoin.user.repository.UserRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Base de seguridad: sin sesión, JWT HS256 firmado con {@code APP_JWT_SECRET}, login y Swagger públicos y
 * {@code /api/admin/**} protegido con la clave de administración en lugar del JWT. Con JWT, cada pedido pasa por
 * {@link CurrentUserFilter} (RN-50), que también aplica el 403 por cambio de contraseña obligatorio.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

	/** Único endpoint autenticado con credenciales en lugar de token. */
	static final String LOGIN_PATH = "/api/auth/login";

	/** Con el cambio de contraseña pendiente, estos dos son los únicos endpoints autenticados permitidos (RN-50). */
	static final String CHANGE_PASSWORD_PATH = "/api/auth/change-password";
	static final String ME_PATH = "/api/auth/me";

	/** Rutas de administración: se autentican con {@code X-Admin-Key}, no con JWT. */
	@Bean
	@Order(1)
	SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, AppProperties properties,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
		return http
				.securityMatcher("/api/admin/**")
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a.anyRequest().permitAll())
				.addFilterBefore(new AdminKeyFilter(properties.security().adminKey(), resolver),
						AuthorizationFilter.class)
				.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository users,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
		// Los 401 pasan por el manejador global para salir como Problem Details con `code`.
		AuthenticationEntryPoint entryPoint = (request, response, e) -> resolver.resolveException(request, response, null, e);
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers(LOGIN_PATH).permitAll()
						.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
				.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
				.addFilterAfter(new CurrentUserFilter(users, resolver), BearerTokenAuthenticationFilter.class)
				.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	JwtDecoder jwtDecoder(AppProperties properties) {
		return NimbusJwtDecoder.withSecretKey(secretKey(properties))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
	}

	@Bean
	JwtEncoder jwtEncoder(AppProperties properties) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
	}

	private static SecretKeySpec secretKey(AppProperties properties) {
		return new SecretKeySpec(properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}
}
