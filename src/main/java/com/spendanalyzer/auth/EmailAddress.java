package com.spendanalyzer.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Email rules: trimmed, lower-case, at most 254 characters, plain ASCII "name@host.tld". */
public final class EmailAddress {

	public static final int MAX_LENGTH = 254;

	private static final int MAX_LOCAL_PART = 64;

	// Deliberately strict: no quotes, no comments, no control characters, so nothing odd reaches the mail API.
	private static final Pattern VALID = Pattern
		.compile("^[a-z0-9_%+-]+(\\.[a-z0-9_%+-]+)*@([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$");

	private EmailAddress() {
	}

	/** The stored form of an address, or empty when it is not acceptable. */
	public static Optional<String> normalise(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String value = raw.strip().toLowerCase(Locale.ROOT);
		if (value.isEmpty() || value.length() > MAX_LENGTH || !VALID.matcher(value).matches()) {
			return Optional.empty();
		}
		if (value.indexOf('@') > MAX_LOCAL_PART) {
			return Optional.empty();
		}
		return Optional.of(value);
	}

	/** The part before the @ of a normalised address. */
	public static String localPart(String email) {
		int at = email.indexOf('@');
		return at < 0 ? email : email.substring(0, at);
	}

	/** For logs: "b***@gmail.com". Never log a full address. */
	public static String mask(String email) {
		if (email == null) {
			return "***";
		}
		int at = email.indexOf('@');
		if (at <= 0) {
			return "***";
		}
		return email.charAt(0) + "***" + email.substring(at);
	}

}
