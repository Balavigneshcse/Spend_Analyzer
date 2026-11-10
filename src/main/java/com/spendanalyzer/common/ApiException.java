package com.spendanalyzer.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * An error the client should see. Rendered by {@link GlobalExceptionHandler} as
 * {@code {"error": code, "message": message, "fields": {...}}}.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final Map<String, String> fields;

	private final Long retryAfterSeconds;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, null, null);
	}

	public ApiException(HttpStatus status, String code, String message, Map<String, String> fields,
			Long retryAfterSeconds) {
		super(message);
		this.status = status;
		this.code = code;
		this.fields = fields;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public static ApiException badRequest(String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, message);
	}

	/** A 400 that points at one input field, e.g. {@code field("amount", "Enter an amount above zero")}. */
	public static ApiException field(String field, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation", message, Map.of(field, message), null);
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, "not_found", message);
	}

	public static ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}

	public static ApiException tooManyRequests(String code, String message, long retryAfterSeconds) {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message, null, Math.max(1, retryAfterSeconds));
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public Map<String, String> fields() {
		return fields;
	}

	public Long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
