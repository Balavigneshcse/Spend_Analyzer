package com.spendanalyzer.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The JSON shape of every error response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String error, String message, Map<String, String> fields, Long retryAfterSeconds) {

	public static ErrorBody of(String error, String message) {
		return new ErrorBody(error, message, null, null);
	}

}
