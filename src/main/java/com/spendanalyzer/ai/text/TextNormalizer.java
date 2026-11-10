package com.spendanalyzer.ai.text;

import java.lang.Character.UnicodeScript;
import java.text.Normalizer;
import java.util.Locale;

/**
 * First step of the pipeline: turns whatever the user typed into lower-case text made only of
 * letters, digits and single spaces. "JUICE!!", "Juíce" and "ｊｕｉｃｅ" all become "juice".
 */
public final class TextNormalizer {

	/** Only the first part of a long text is looked at; a spend description is a few words. */
	public static final int MAX_CHARS = 120;

	private TextNormalizer() {
	}

	public static String normalize(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		// Cut before any real work so a 10,000 character paste costs the same as a short one.
		String head = raw.length() > MAX_CHARS ? raw.substring(0, MAX_CHARS) : raw;
		String text = Normalizer.normalize(head, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			appendClean(out, cp);
		}
		return squeezeAndTrim(out);
	}

	private static void appendClean(StringBuilder out, int cp) {
		if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
			out.append((char) cp);
		}
		else if (cp == '\'' || cp == '’' || cp == '`') {
			// "domino's" should read "dominos", not "domino s"
		}
		else if (Character.isDigit(cp)) {
			int digit = Character.digit(cp, 10);
			if (digit >= 0) {
				out.append((char) ('0' + digit));
			}
		}
		else if (Character.isLetter(cp)) {
			if (isLatin(cp)) {
				appendWithoutAccents(out, cp);
			}
			else {
				out.appendCodePoint(cp);
			}
		}
		else if (isCombiningMark(cp)) {
			// Tamil and Hindi vowel signs belong to the word; an accent left on a Latin letter does not.
			if (!out.isEmpty() && !isAsciiLetterOrDigitOrSpace(out.charAt(out.length() - 1))) {
				out.appendCodePoint(cp);
			}
		}
		else {
			out.append(' ');
		}
	}

	private static boolean isLatin(int cp) {
		try {
			return UnicodeScript.of(cp) == UnicodeScript.LATIN;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static void appendWithoutAccents(StringBuilder out, int cp) {
		String decomposed = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFD);
		for (int i = 0; i < decomposed.length(); i++) {
			char c = decomposed.charAt(i);
			if (c >= 'a' && c <= 'z') {
				out.append(c);
			}
		}
	}

	private static boolean isCombiningMark(int cp) {
		int type = Character.getType(cp);
		return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
				|| type == Character.ENCLOSING_MARK;
	}

	private static boolean isAsciiLetterOrDigitOrSpace(char c) {
		return c == ' ' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
	}

	/** "juuuuice" becomes "juuice": a held key is never three real letters. Spaces are collapsed too. */
	private static String squeezeAndTrim(StringBuilder in) {
		StringBuilder out = new StringBuilder(in.length());
		for (int i = 0; i < in.length(); i++) {
			char c = in.charAt(i);
			int n = out.length();
			if (c == ' ') {
				if (n == 0 || out.charAt(n - 1) == ' ') {
					continue;
				}
			}
			else if (c >= 'a' && c <= 'z' && n >= 2 && out.charAt(n - 1) == c && out.charAt(n - 2) == c) {
				continue;
			}
			out.append(c);
		}
		int end = out.length();
		while (end > 0 && out.charAt(end - 1) == ' ') {
			end--;
		}
		return out.substring(0, end);
	}

}
