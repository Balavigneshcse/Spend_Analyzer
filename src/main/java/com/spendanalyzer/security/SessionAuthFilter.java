package com.spendanalyzer.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the logged-in {@link AuthUser} into the security context when the request carries a valid
 * session cookie. Only API calls are checked, static files never touch the database.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

	private static final List<SimpleGrantedAuthority> USER = List.of(new SimpleGrantedAuthority("ROLE_USER"));

	private final ObjectProvider<SessionAuthenticator> authenticator;

	public SessionAuthFilter(ObjectProvider<SessionAuthenticator> authenticator) {
		this.authenticator = authenticator;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			SessionAuthenticator delegate = authenticator.getIfAvailable();
			if (delegate != null) {
				Optional<AuthUser> user = delegate.authenticate(request);
				if (user.isPresent()) {
					SecurityContext context = SecurityContextHolder.createEmptyContext();
					context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.get(), null, USER));
					SecurityContextHolder.setContext(context);
				}
			}
		}
		chain.doFilter(request, response);
	}

}
