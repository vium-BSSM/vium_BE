package com.vium.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(csrf -> csrf.disable())
			// 인증은 CLAUDE.md 7단계(마지막)에서 구현 — 그 전까지 전체 개방
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.formLogin(form -> form.disable())
			.httpBasic(httpBasic -> httpBasic.disable())
			.build();
	}
}
