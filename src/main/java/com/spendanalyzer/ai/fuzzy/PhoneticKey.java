package com.spendanalyzer.ai.fuzzy;

/**
 * A sound key tuned for how Indian words get spelt in Latin letters. Spellings that sound alike
 * share a key: "juse" and "juice" are both JUS, "cofee" and "coffee" are both KUFI, "chapathi"
 * and "chapati" are both CAPATI.
 *
 * <p>Two keys exist per word. The full key keeps one of three vowel classes (A, I, U) for every
 * run of vowels. The skeleton key drops all vowels after the first letter, so "briyany",
 * "biriyani" and "biryani" all become BRN and "tkt" meets "ticket" at TKT.
 */
public final class PhoneticKey {

	private PhoneticKey() {
	}

	/** The full key (consonant codes plus vowel classes) of a lower-case a-z0-9 word. */
	public static String full(String word) {
		int n = word.length();
		StringBuilder out = new StringBuilder(n);
		int i = 0;
		while (i < n) {
			char c = word.charAt(i);
			char next = i + 1 < n ? word.charAt(i + 1) : 0;
			if (isVowelAt(word, i)) {
				int j = i;
				boolean hasOU = false;
				boolean hasEIY = false;
				while (j < n && isVowelAt(word, j)) {
					char v = word.charAt(j);
					if (v == 'o' || v == 'u') {
						hasOU = true;
					}
					else if (v != 'a') {
						hasEIY = true;
					}
					j++;
				}
				// the "e" of "juice" and "cake" is silent; the "e" of "tee" or "me" is not
				boolean silentE = j == n && j - i == 1 && c == 'e' && hasVowelClass(out);
				if (!silentE) {
					append(out, hasOU ? 'U' : hasEIY ? 'I' : 'A');
				}
				i = j;
				continue;
			}
			switch (c) {
				case 'p' -> {
					if (next == 'h') {
						append(out, 'F');
						i++;
					}
					else {
						append(out, 'P');
					}
				}
				case 'b', 'd', 'k', 'g' -> {
					// aspirated pairs are one sound for a typist: "dhosa", "ghee", "khana"
					append(out, Character.toUpperCase(c));
					if (next == 'h') {
						i++;
					}
				}
				case 't' -> {
					if (next == 'c' && i + 2 < n && word.charAt(i + 2) == 'h') {
						append(out, 'C');
						i += 2;
					}
					else {
						append(out, 'T');
						if (next == 'h') {
							i++;
						}
					}
				}
				case 'c' -> {
					if (next == 'h') {
						append(out, 'C');
						i++;
					}
					else if (next == 'k') {
						append(out, 'K');
						i++;
					}
					else if (next == 'e' || next == 'i' || next == 'y') {
						append(out, 'S');
					}
					else {
						append(out, 'K');
					}
				}
				case 's' -> {
					append(out, 'S');
					if (next == 'h') {
						i++;
					}
				}
				case 'z' -> {
					if (next == 'h') {
						// Tamil "zh" (pazham, kozhi) is usually said and typed as "l"
						append(out, 'L');
						i++;
					}
					else {
						append(out, 'J');
					}
				}
				case 'q' -> append(out, 'K');
				case 'w' -> append(out, 'V');
				case 'x' -> {
					if (i == 0) {
						append(out, 'J');
					}
					else {
						append(out, 'K');
						append(out, 'S');
					}
				}
				case 'h' -> {
					// only a leading "h" is a sound of its own: "chai", "sahi", "delhi" lose theirs
					if (i == 0) {
						append(out, 'H');
					}
				}
				case 'y' -> append(out, 'Y');
				default -> append(out, Character.toUpperCase(c));
			}
			i++;
		}
		return out.toString();
	}

	/** The skeleton key: the full key without vowel classes after the first letter. */
	public static String skeleton(String fullKey) {
		StringBuilder out = new StringBuilder(fullKey.length());
		for (int i = 0; i < fullKey.length(); i++) {
			char c = fullKey.charAt(i);
			if (i == 0 || !isVowelClass(c)) {
				// dropping a vowel can bring two equal consonants together: collapse them again
				if (out.isEmpty() || out.charAt(out.length() - 1) != c) {
					out.append(c);
				}
			}
		}
		return out.toString();
	}

	/** A "y" inside a word acts as a vowel ("briyani", "curry"); at the start it is a consonant. */
	private static boolean isVowelAt(String word, int i) {
		char c = word.charAt(i);
		return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || (c == 'y' && i > 0);
	}

	private static boolean isVowelClass(char c) {
		return c == 'A' || c == 'I' || c == 'U';
	}

	private static boolean hasVowelClass(StringBuilder key) {
		for (int i = 0; i < key.length(); i++) {
			if (isVowelClass(key.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	/** Doubled sounds collapse: "tiffin" is TIFIN, "pizza" is PIJA. */
	private static void append(StringBuilder out, char code) {
		if (out.isEmpty() || out.charAt(out.length() - 1) != code) {
			out.append(code);
		}
	}

}
