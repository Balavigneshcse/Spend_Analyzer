package com.spendanalyzer.security;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cross-site request forgery defence for the cookie-authenticated API.
 * <p>
 * Every state-changing {@code /api/**} call must (1) come from this site, judged by the browser's
 * {@code Sec-Fetch-Site} and {@code Origin} headers, and (2) carry a JSON body if it has a body at
 * all (an HTML form cannot send {@code application/json}). Together with the SameSite=Strict
 * session cookie this replaces a CSRF token. There is deliberately no CORS configuration.
 */
public class SameOriginApiFilter extends OncePerRequestFilter {

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	private final String configuredOrigin;

	public SameOriginApiFilter(String configuredOrigin) {
		this.configuredOrigin = normalise(configuredOrigin);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return SAFE_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String fetchSite = request.getHeader("Sec-Fetch-Site");
		if (fetchSite != null && !"same-origin".equalsIgnoreCase(fetchSite)) {
			reject(response, HttpServletResponse.SC_FORBIDDEN, "cross_site", "Cross-site requests are not allowed.");
			return;
		}
		String origin = request.getHeader("Origin");
		if (origin != null && !isOwnOrigin(origin, request)) {
			reject(response, HttpServletResponse.SC_FORBIDDEN, "cross_site", "Cross-site requests are not allowed.");
			return;
		}
		if (hasBody(request)) {
			String contentType = request.getContentType();
			if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
				reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "json_required",
						"Requests must be sent as JSON.");
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private boolean isOwnOrigin(String origin, HttpServletRequest request) {
		String candidate = normalise(origin);
		if (candidate == null) {
			return false;
		}
		if (configuredOrigin != null && configuredOrigin.equals(candidate)) {
			return true;
		}
		// Same host and port as the request itself (the Host header cannot be forged by another site).
		String host = request.getHeader("Host");
		if (host == null) {
			return false;
		}
		try {
			String authority = URI.create(candidate).getAuthority();
			return authority != null && authority.equalsIgnoreCase(host.trim());
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean hasBody(HttpServletRequest request) {
		return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
	}

	private static String normalise(String origin) {
		if (origin == null || origin.isBlank()) {
			return null;
		}
		String value = origin.trim().toLowerCase(Locale.ROOT);
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

	private static void reject(HttpServletResponse response, int status, String code, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}

}
