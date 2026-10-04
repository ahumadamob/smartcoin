package com.smartcoin.shared.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import com.smartcoin.shared.config.AppProperties;

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
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Base de seguridad: sin sesión, JWT HS256 firmado con {@code APP_JWT_SECRET}, Swagger público y
 * {@code /api/admin/**} protegido con la clave de administración en lugar del JWT.
 * El login y el filtro de usuario (RN-50) llegan con las historias siguientes.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

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
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	JwtDecoder jwtDecoder(AppProperties properties) {
		byte[] secret = properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
		return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
	}
}
