package com.spendanalyzer.security;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Finds the logged-in user of a request from its session cookie.
 * Implemented by the auth module; {@link SessionAuthFilter} calls it on every request.
 */
public interface SessionAuthenticator {

	Optional<AuthUser> authenticate(HttpServletRequest request);

}
