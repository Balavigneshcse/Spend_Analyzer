package com.spendanalyzer.auth;

import java.time.Duration;
import java.time.Instant;

import com.spendanalyzer.common.ApiException;

import org.springframework.http.HttpStatus;

/**
 * The few error answers of the auth module. Each has ONE wording on purpose: the answer must not
 * tell whether an email has an account, which limit was hit or why a code was refused.
 */
final class AuthErrors {

	private AuthErrors() {
	}

	static ApiException invalidCode() {
		return ApiException.badRequest("invalid_code", "That code is wrong or has expired.");
	}

	static ApiException invalidCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Wrong email or password.");
	}

	static ApiException unauthorized() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Please log in.");
	}

	static ApiException registrationClosed() {
		return new ApiException(HttpStatus.FORBIDDEN, "registration_closed", "Registration is closed.");
	}

	static ApiException tooMany(Instant now, Instant until) {
		return tooMany(Duration.between(now, until));
	}

	static ApiException tooMany(Duration wait) {
		// round up, so that retrying after exactly this many seconds works
		long seconds = Math.max(1, wait.plusMillis(999).toSeconds());
		return ApiException.tooManyRequests("too_many_requests", "Too many attempts. Please try again later.", seconds);
	}

}
