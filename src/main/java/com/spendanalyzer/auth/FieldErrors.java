package com.spendanalyzer.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import com.spendanalyzer.common.ApiException;

import org.springframework.http.HttpStatus;

/** Collects the problems of a form so the page can mark every wrong field at once. */
final class FieldErrors {

	private final Map<String, String> fields = new LinkedHashMap<>();

	void add(String field, String message) {
		fields.putIfAbsent(field, message);
	}

	/** @throws ApiException 400 "validation" when at least one field is wrong */
	void throwIfAny() {
		if (!fields.isEmpty()) {
			String first = fields.values().iterator().next();
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", first, new LinkedHashMap<>(fields), null);
		}
	}

}
