package com.spendanalyzer.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static final Logger log = LoggerFactory.getLogger(SessionAuthFilter.class);

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
				Optional<AuthUser> user;
				try {
					user = delegate.authenticate(request);
				}
				catch (RuntimeException ex) {
					// The lookup is a database query. A failure here is outside Spring MVC, so the JSON
					// error handler never sees it: answer in the API's error shape ourselves.
					log.error("Could not check the session of a request", ex);
					serverError(response);
					return;
				}
				if (user.isPresent()) {
					SecurityContext context = SecurityContextHolder.createEmptyContext();
					context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.get(), null, USER));
					SecurityContextHolder.setContext(context);
				}
			}
		}
		chain.doFilter(request, response);
	}

	private static void serverError(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter()
			.write("{\"error\":\"server_error\",\"message\":\"Something went wrong. Please try again.\"}");
	}

}
