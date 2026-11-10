package com.spendanalyzer.auth;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reads the registration / reset ids the browser sends back. */
final class Ids {

	// UUID.fromString also accepts odd short forms; only the canonical form is taken here.
	private static final Pattern CANONICAL = Pattern
		.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private Ids() {
	}

	static Optional<UUID> parse(String text) {
		if (text == null || !CANONICAL.matcher(text.strip()).matches()) {
			return Optional.empty();
		}
		return Optional.of(UUID.fromString(text.strip()));
	}

}
