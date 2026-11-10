package com.spendanalyzer.auth;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Indian mobile numbers. Accepted: 9876543210, 09876543210, 919876543210, +91 98765 43210
 * (spaces, dashes and brackets are ignored). Stored as +919876543210. The number is profile data
 * only: it is never verified, never unique and never used to log in.
 */
public final class IndianPhone {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s\\u00A0()-]");

	private static final Pattern DIGITS = Pattern.compile("[0-9]{10,12}");

	private static final Pattern NATIONAL = Pattern.compile("[6-9][0-9]{9}");

	private IndianPhone() {
	}

	/** The stored form (+91 and ten digits), or empty when it is not an Indian mobile number. */
	public static Optional<String> normalise(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String value = raw.strip();
		if (value.isEmpty() || value.length() > 24) {
			return Optional.empty();
		}
		String compact = SEPARATORS.matcher(value).replaceAll("");
		boolean international = compact.startsWith("+");
		String digits = international ? compact.substring(1) : compact;
		if (!DIGITS.matcher(digits).matches()) {
			return Optional.empty();
		}
		String national;
		if (digits.length() == 12 && digits.startsWith("91")) {
			national = digits.substring(2);
		}
		else if (!international && digits.length() == 11 && digits.startsWith("0")) {
			national = digits.substring(1);
		}
		else if (!international && digits.length() == 10) {
			national = digits;
		}
		else {
			return Optional.empty();
		}
		if (!NATIONAL.matcher(national).matches()) {
			return Optional.empty();
		}
		return Optional.of("+91" + national);
	}

	/** The ten digits after +91 of a stored number. */
	public static String national(String stored) {
		return stored.startsWith("+91") ? stored.substring(3) : stored;
	}

	/** What the page may show: "+91 ••••••3210". */
	public static String mask(String stored) {
		String national = national(stored);
		String lastFour = national.length() >= 4 ? national.substring(national.length() - 4) : "";
		return "+91 ••••••" + lastFour;
	}

}
