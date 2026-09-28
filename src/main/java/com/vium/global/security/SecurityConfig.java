package com.vium.global.security;

import com.vium.global.common.ApiResponse;
import com.vium.global.exception.ErrorCode;
import jakarta.servlet.DispatcherType;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
	@Bean
	public UrlBasedCorsConfigurationSource corsConfigurationSource(
			@Value("${security.cors.allowed-origins:}") String allowedOrigins) {
		var configuration = new CorsConfiguration();
		var origins = Arrays.stream(allowedOrigins.split(","))
			.map(String::strip).filter(origin -> !origin.isEmpty()).toList();
		if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
			throw new IllegalArgumentException("CORS_ALLOWED_ORIGINS must contain exact origins, not wildcards");
		}
		configuration.setAllowedOrigins(origins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(3600L);
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper mapper) throws Exception {
		var paths = PathPatternRequestMatcher.withDefaults();
		var publicAuth = new OrRequestMatcher(
			paths.matcher(HttpMethod.POST, "/api/auth/login"),
			paths.matcher(HttpMethod.POST, "/api/auth/register"),
			paths.matcher(HttpMethod.POST, "/api/auth/token/refresh"));
		var bearerTokenResolver = new DefaultBearerTokenResolver();
		AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
			response.setStatus(ErrorCode.UNAUTHORIZED.getStatus().value());
			response.setHeader("WWW-Authenticate", "Bearer");
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(
				new ApiResponse.ErrorObject(ErrorCode.UNAUTHORIZED.name(), ErrorCode.UNAUTHORIZED.getDefaultMessage()))));
		};
		return http
			.cors(Customizer.withDefaults())
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.disable())
			.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(publicAuth).permitAll()
				.requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health", "/actuator/health/**").permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler((request, response, exception) -> {
					response.setStatus(ErrorCode.FORBIDDEN.getStatus().value());
					response.setContentType("application/json;charset=UTF-8");
					response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(
						new ApiResponse.ErrorObject(ErrorCode.FORBIDDEN.name(), ErrorCode.FORBIDDEN.getDefaultMessage()))));
				}))
			.oauth2ResourceServer(resource -> resource
				.bearerTokenResolver(request -> publicAuth.matches(request) ? null : bearerTokenResolver.resolve(request))
				.jwt(Customizer.withDefaults()).authenticationEntryPoint(unauthorized))
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.logout(logout -> logout.disable())
			.build();
	}
}
