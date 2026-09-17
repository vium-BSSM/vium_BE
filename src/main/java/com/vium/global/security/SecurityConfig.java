package com.vium.global.security;

import com.vium.global.common.ApiResponse;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper mapper) throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
			response.setStatus(401);
			response.setHeader("WWW-Authenticate", "Bearer");
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(
				new ApiResponse.ErrorObject("UNAUTHORIZED", "인증이 필요합니다."))));
		};
		return http
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.disable())
			.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health", "/actuator/health/**").permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler((request, response, exception) -> {
					response.setStatus(403);
					response.setContentType("application/json;charset=UTF-8");
					response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail(
						new ApiResponse.ErrorObject("FORBIDDEN", "접근 권한이 없습니다."))));
				}))
			.oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()).authenticationEntryPoint(unauthorized))
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.logout(logout -> logout.disable())
			.build();
	}
}
