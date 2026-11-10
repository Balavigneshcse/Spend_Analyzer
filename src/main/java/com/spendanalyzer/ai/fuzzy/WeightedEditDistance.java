package com.spendanalyzer.ai.fuzzy;

/**
 * Damerau-Levenshtein distance (optimal string alignment) where a mistake costs what it is worth:
 * swapping two vowels or forgetting a doubled letter is a small slip, a wrong consonant is not.
 * This is what lets "juse" reach "juice" (cost 1.0) while "soap" stays far from "shop".
 */
public final class WeightedEditDistance {

	static final double CONSONANT = 1.0;

	static final double VOWEL_SWAP = 0.4;

	static final double VOWEL_EDIT = 0.5;

	static final double DOUBLED_LETTER = 0.2;

	static final double SILENT_H = 0.2;

	static final double TRANSPOSITION = 0.7;

	static final double CONFUSABLE = 0.5;

	static final double FIRST_LETTER = 0.5;

	/** Consonant pairs that Indian typists use for the same sound, as a 26x26 lookup. */
	private static final boolean[] CONFUSABLE_PAIR = new boolean[26 * 26];

	static {
		String[] pairs = { "td", "kg", "pb", "sz", "cs", "ck", "vw", "jz", "xz", "qk", "fp", "jg" };
		for (String pair : pairs) {
			int a = pair.charAt(0) - 'a';
			int b = pair.charAt(1) - 'a';
			CONFUSABLE_PAIR[a * 26 + b] = true;
			CONFUSABLE_PAIR[b * 26 + a] = true;
		}
	}

	private WeightedEditDistance() {
	}

	public static double distance(String a, String b) {
		int n = a.length();
		int m = b.length();
		if (n == 0 || m == 0) {
			return Math.max(n, m);
		}
		double[] twoBack = new double[m + 1];
		double[] previous = new double[m + 1];
		double[] current = new double[m + 1];
		for (int j = 1; j <= m; j++) {
			previous[j] = previous[j - 1] + editCost(b, j - 1);
		}
		for (int i = 1; i <= n; i++) {
			char ca = a.charAt(i - 1);
			current[0] = previous[0] + editCost(a, i - 1);
			for (int j = 1; j <= m; j++) {
				char cb = b.charAt(j - 1);
				double best = previous[j - 1] + substitutionCost(ca, cb);
				best = Math.min(best, previous[j] + editCost(a, i - 1));
				best = Math.min(best, current[j - 1] + editCost(b, j - 1));
				if (i > 1 && j > 1 && ca != cb && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
					best = Math.min(best, twoBack[j - 2] + TRANSPOSITION);
				}
				current[j] = best;
			}
			double[] recycled = twoBack;
			twoBack = previous;
			previous = current;
			current = recycled;
		}
		double total = previous[m];
		char firstA = a.charAt(0);
		char firstB = b.charAt(0);
		if (firstA != firstB && !confusable(firstA, firstB)) {
			// people rarely get the first letter wrong, so a different one is strong evidence
			total += FIRST_LETTER;
		}
		return total;
	}

	/** 1.0 for identical words, falling towards 0 as the weighted distance grows. */
	public static double similarity(String a, String b) {
		int longest = Math.max(a.length(), b.length());
		if (longest == 0) {
			return 1.0;
		}
		return Math.max(0.0, 1.0 - distance(a, b) / longest);
	}

	private static double substitutionCost(char a, char b) {
		if (a == b) {
			return 0.0;
		}
		boolean vowelA = isVowel(a) || a == 'y';
		boolean vowelB = isVowel(b) || b == 'y';
		if (vowelA && vowelB) {
			return VOWEL_SWAP;
		}
		if (confusable(a, b)) {
			return CONFUSABLE;
		}
		return CONSONANT;
	}

	/** Cost of inserting or deleting the character at {@code k} of {@code s}. */
	private static double editCost(String s, int k) {
		char c = s.charAt(k);
		char before = k > 0 ? s.charAt(k - 1) : 0;
		char after = k + 1 < s.length() ? s.charAt(k + 1) : 0;
		if (c == before || c == after) {
			return DOUBLED_LETTER;
		}
		if (c == 'h' && before != 0 && !isVowel(before)) {
			return SILENT_H;
		}
		if (isVowel(c) || (c == 'y' && k > 0)) {
			return VOWEL_EDIT;
		}
		return CONSONANT;
	}

	private static boolean confusable(char a, char b) {
		if (a < 'a' || a > 'z' || b < 'a' || b > 'z') {
			return false;
		}
		return CONFUSABLE_PAIR[(a - 'a') * 26 + (b - 'a')];
	}

	static boolean isVowel(char c) {
		return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
	}

}
