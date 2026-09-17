package com.vium.global.security;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {
	public Long getCurrentUserId() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
			try {
				long userId = Long.parseLong(jwt.getToken().getSubject());
				if (userId > 0) { return userId; }
			} catch (NumberFormatException ignored) { }
		}
		throw new BusinessException(ErrorCode.UNAUTHORIZED);
	}
}
