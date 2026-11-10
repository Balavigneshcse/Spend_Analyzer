package com.spendanalyzer.ledger;

import java.util.Locale;

import com.spendanalyzer.common.ApiException;

/**
 * The name of a friend money is borrowed from. Two forms: the name as typed (cleaned, shown to the
 * user) and its key (lower case), which decides whether two entries are about the same friend:
 * "Ravi", "ravi" and " RAVI " are one person.
 */
final class PersonName {

	static final int MAX_LENGTH = 40;

	private PersonName() {
	}

	/**
	 * The cleaned name: no control characters, no spaces around it, every run of spaces inside is
	 * one space, 1 to 40 characters. Otherwise a 400 that names the field. Like every other text it
	 * is stored exactly as typed apart from that: no HTML escaping, the page shows it with textContent.
	 */
	static String parse(String field, String raw) {
		String name = collapseSpaces(Inputs.clean(raw));
		if (!hasVisibleCharacter(name)) {
			throw ApiException.field(field, "Type your friend's name.");
		}
		// counted in characters as the database counts them, so an emoji is one character
		if (length(name) > MAX_LENGTH) {
			throw ApiException.field(field, "Keep the name to " + MAX_LENGTH + " characters or fewer.");
		}
		// Lower-casing can add a character (only "İ" does); the key must fit its column too.
		if (length(key(name)) > MAX_LENGTH) {
			throw ApiException.field(field, "That name is too long. Shorten it a little.");
		}
		return name;
	}

	/** The form used to recognise the same friend again. Locale.ROOT: the same key on every server. */
	static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	/** Any kind of space (also a no-break space) counts; "Ravi   Kumar" and "Ravi Kumar" are the same name. */
	private static String collapseSpaces(String text) {
		StringBuilder out = new StringBuilder(text.length());
		boolean spacePending = false;
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				spacePending = out.length() > 0;
				continue;
			}
			if (spacePending) {
				out.append(' ');
				spacePending = false;
			}
			out.appendCodePoint(cp);
		}
		return out.toString();
	}

	/** A name made only of invisible characters (zero-width joiners and the like) is no name. */
	private static boolean hasVisibleCharacter(String name) {
		return name.codePoints().anyMatch(cp -> cp != ' ' && Character.getType(cp) != Character.FORMAT);
	}

	private static int length(String text) {
		return text.codePointCount(0, text.length());
	}

}
