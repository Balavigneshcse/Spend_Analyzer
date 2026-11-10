package com.spendanalyzer.ai.model;

import java.util.Random;
import java.util.function.IntPredicate;

/**
 * Invents plausible misspellings of a word for the training data, using the same slips real people
 * make on a phone keyboard: a wrong or missing vowel, a doubled or un-doubled letter, two letters
 * swapped, an "h" added or lost, a sound-alike consonant. The first letter is left alone because
 * people almost always get it right.
 */
public final class TypoAugmenter {

	private static final String VOWELS = "aeiou";

	private static final String[] SOUND_ALIKE = { "td", "dt", "kg", "gk", "pb", "bp", "sz", "zs", "cs", "sc", "ck",
			"kc", "vw", "wv", "jz", "zj", "iy", "yi" };

	private TypoAugmenter() {
	}

	/** @return a misspelling, or the word itself when it is too short to damage safely */
	public static String misspell(String word, Random random) {
		if (word.length() < 4) {
			return word;
		}
		for (int attempt = 0; attempt < 6; attempt++) {
			String changed = switch (random.nextInt(9)) {
				case 0 -> swapVowel(word, random);
				case 1 -> dropVowel(word, random);
				case 2 -> doubleLetter(word, random);
				case 3 -> undoubleLetter(word);
				case 4 -> transpose(word, random);
				case 5 -> toggleH(word, random);
				case 6 -> soundAlike(word, random);
				case 7 -> stretchVowel(word, random);
				default -> dropLetter(word, random);
			};
			if (!changed.equals(word)) {
				return changed;
			}
		}
		return word;
	}

	private static boolean isVowel(char c) {
		return VOWELS.indexOf(c) >= 0;
	}

	private static boolean isLetter(char c) {
		return c >= 'a' && c <= 'z';
	}

	/** A random position after the first letter that satisfies the test, or -1. */
	private static int pick(String word, Random random, IntPredicate test) {
		int start = 1 + random.nextInt(word.length() - 1);
		for (int step = 0; step < word.length() - 1; step++) {
			int i = 1 + (start - 1 + step) % (word.length() - 1);
			if (test.test(i)) {
				return i;
			}
		}
		return -1;
	}

	private static String swapVowel(String word, Random random) {
		int i = pick(word, random, k -> isVowel(word.charAt(k)));
		if (i < 0) {
			return word;
		}
		char replacement = VOWELS.charAt(random.nextInt(VOWELS.length()));
		return word.substring(0, i) + replacement + word.substring(i + 1);
	}

	private static String dropVowel(String word, Random random) {
		int i = pick(word, random, k -> isVowel(word.charAt(k)));
		return i < 0 ? word : word.substring(0, i) + word.substring(i + 1);
	}

	private static String doubleLetter(String word, Random random) {
		int i = pick(word, random, k -> isLetter(word.charAt(k)) && word.charAt(k) != word.charAt(k - 1));
		return i < 0 ? word : word.substring(0, i) + word.charAt(i) + word.substring(i);
	}

	private static String undoubleLetter(String word) {
		for (int i = 1; i < word.length(); i++) {
			if (word.charAt(i) == word.charAt(i - 1) && isLetter(word.charAt(i))) {
				return word.substring(0, i) + word.substring(i + 1);
			}
		}
		return word;
	}

	private static String transpose(String word, Random random) {
		int i = pick(word, random, k -> k + 1 < word.length() && word.charAt(k) != word.charAt(k + 1)
				&& isLetter(word.charAt(k)) && isLetter(word.charAt(k + 1)));
		if (i < 0) {
			return word;
		}
		return word.substring(0, i) + word.charAt(i + 1) + word.charAt(i) + word.substring(i + 2);
	}

	/** "chapati" to "chapathi", "dosa" to "dhosa", and back. */
	private static String toggleH(String word, Random random) {
		int withH = pick(word, random, k -> word.charAt(k) == 'h' && !isVowel(word.charAt(k - 1)));
		if (withH >= 0 && random.nextBoolean()) {
			return word.substring(0, withH) + word.substring(withH + 1);
		}
		int i = pick(word, random,
				k -> "tdkgbs".indexOf(word.charAt(k)) >= 0 && (k + 1 == word.length() || word.charAt(k + 1) != 'h'));
		return i < 0 ? word : word.substring(0, i + 1) + 'h' + word.substring(i + 1);
	}

	private static String soundAlike(String word, Random random) {
		int i = pick(word, random, k -> {
			for (String pair : SOUND_ALIKE) {
				if (pair.charAt(0) == word.charAt(k)) {
					return true;
				}
			}
			return false;
		});
		if (i < 0) {
			return word;
		}
		int start = random.nextInt(SOUND_ALIKE.length);
		for (int step = 0; step < SOUND_ALIKE.length; step++) {
			String pair = SOUND_ALIKE[(start + step) % SOUND_ALIKE.length];
			if (pair.charAt(0) == word.charAt(i)) {
				return word.substring(0, i) + pair.charAt(1) + word.substring(i + 1);
			}
		}
		return word;
	}

	/** "tea" to "teaa", "dosa" to "doosa". */
	private static String stretchVowel(String word, Random random) {
		int i = pick(word, random, k -> isVowel(word.charAt(k)) && word.charAt(k) != word.charAt(k - 1));
		return i < 0 ? word : word.substring(0, i) + word.charAt(i) + word.substring(i);
	}

	private static String dropLetter(String word, Random random) {
		if (word.length() < 6) {
			return word;
		}
		int i = pick(word, random, k -> isLetter(word.charAt(k)));
		return i < 0 ? word : word.substring(0, i) + word.substring(i + 1);
	}

}
