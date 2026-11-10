package com.spendanalyzer.ledger;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.spendanalyzer.common.ApiException;

/**
 * Strict readers for single request values. Each one either returns a clean value or throws a
 * 400 "validation" error that names the field. Nothing is ever guessed or rounded.
 */
final class Inputs {

	static final int MAX_DESCRIPTION = 120;

	private static final Pattern UUID_TEXT = Pattern
		.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private static final Pattern DATE_TEXT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

	private Inputs() {
	}

	static boolean isBlank(String raw) {
		return raw == null || raw.isBlank();
	}

	/** A UUID in the usual 36-character form, or empty. (UUID.fromString alone accepts odd short forms.) */
	static Optional<UUID> uuid(String raw) {
		if (raw == null || !UUID_TEXT.matcher(raw.trim()).matches()) {
			return Optional.empty();
		}
		return Optional.of(UUID.fromString(raw.trim()));
	}

	/** Exact enum name, e.g. {@code "UPI"}. Lower case, numbers or unknown names are refused. */
	static <E extends Enum<E>> E enumValue(String field, String raw, Class<E> type) {
		if (isBlank(raw)) {
			throw ApiException.field(field, "Choose a value for " + field + ".");
		}
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(raw)) {
				return constant;
			}
		}
		String allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
		throw ApiException.field(field, "Unknown " + field + ". Use one of: " + allowed + ".");
	}

	/** A real calendar date written as YYYY-MM-DD. */
	static LocalDate date(String field, String raw) {
		String value = raw.trim();
		if (!DATE_TEXT.matcher(value).matches()) {
			throw ApiException.field(field, "Write the date as YYYY-MM-DD.");
		}
		try {
			return LocalDate.parse(value);
		}
		catch (DateTimeParseException ex) {
			throw ApiException.field(field, "That date does not exist.");
		}
	}

	/**
	 * Removes control characters (tabs and line breaks become a space) and surrounding spaces.
	 * Everything else stays exactly as typed: no HTML escaping here, the page shows the text with
	 * textContent.
	 */
	static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length();) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '\t' || cp == '\n' || cp == '\r') {
				out.append(' ');
			}
			else if (Character.isISOControl(cp) || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) {
				// control characters and broken half-characters cannot be stored or shown
				continue;
			}
			else {
				out.appendCodePoint(cp);
			}
		}
		return out.toString().strip();
	}

	/** Cleaned text of 1 to 120 characters. */
	static String requiredText(String field, String raw, String missingMessage) {
		String text = clean(raw);
		if (text.isEmpty()) {
			throw ApiException.field(field, missingMessage);
		}
		checkLength(field, text);
		return text;
	}

	/** Cleaned text of at most 120 characters, or {@code null} when nothing was typed. */
	static String optionalText(String field, String raw) {
		String text = clean(raw);
		if (text.isEmpty()) {
			return null;
		}
		checkLength(field, text);
		return text;
	}

	private static void checkLength(String field, String text) {
		// counted in characters as the database counts them, so an emoji is one character
		if (text.codePointCount(0, text.length()) > MAX_DESCRIPTION) {
			throw ApiException.field(field, "Keep it to " + MAX_DESCRIPTION + " characters or fewer.");
		}
	}

}
