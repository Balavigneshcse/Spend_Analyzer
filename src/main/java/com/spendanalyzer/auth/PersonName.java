package com.spendanalyzer.auth;

import java.util.Optional;

/** Name rules: 2 to 60 characters, no control characters, outer and repeated spaces removed. */
public final class PersonName {

	private PersonName() {
	}

	public static Optional<String> normalise(String raw) {
		if (raw == null || raw.codePoints().anyMatch(Character::isISOControl)) {
			return Optional.empty();
		}
		String value = raw.strip().replaceAll("\\s+", " ");
		int length = value.codePointCount(0, value.length());
		if (length < 2 || length > 60) {
			return Optional.empty();
		}
		return Optional.of(value);
	}

}
