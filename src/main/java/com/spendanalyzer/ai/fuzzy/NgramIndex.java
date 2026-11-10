package com.spendanalyzer.ai.fuzzy;

import java.util.Arrays;

/**
 * Inverted index from letter pairs to the words that contain them. Asking which known words share
 * the most letter pairs with a misspelt word narrows a few thousand words down to a handful, so
 * the costly edit distance runs only on those. Immutable after construction.
 */
final class NgramIndex {

	/** a-z, 0-9, start-of-word, end-of-word. */
	private static final int ALPHABET = 38;

	private static final int START = 36;

	private static final int END = 37;

	private final int[][] postings = new int[ALPHABET * ALPHABET][];

	private final int wordCount;

	NgramIndex(String[] words) {
		this.wordCount = words.length;
		int[] sizes = new int[postings.length];
		int[] grams = new int[64];
		for (String word : words) {
			int count = gramsOf(word, grams);
			for (int g = 0; g < count; g++) {
				sizes[grams[g]]++;
			}
		}
		for (int g = 0; g < postings.length; g++) {
			postings[g] = new int[sizes[g]];
		}
		Arrays.fill(sizes, 0);
		for (int id = 0; id < words.length; id++) {
			int count = gramsOf(words[id], grams);
			for (int g = 0; g < count; g++) {
				postings[grams[g]][sizes[grams[g]]++] = id;
			}
		}
	}

	/**
	 * @return ids of at most roughly {@code limit} words sharing the most letter pairs with the
	 * query, each sharing at least two
	 */
	int[] candidates(String query, int limit) {
		int[] grams = new int[64];
		int gramCount = gramsOf(query, grams);
		byte[] shared = new byte[wordCount];
		int[] touched = new int[256];
		int touchedCount = 0;
		for (int g = 0; g < gramCount; g++) {
			for (int id : postings[grams[g]]) {
				if (shared[id]++ == 0) {
					if (touchedCount == touched.length) {
						touched = Arrays.copyOf(touched, touched.length * 2);
					}
					touched[touchedCount++] = id;
				}
			}
		}
		// histogram of "how many words share k pairs", then keep the best-sharing words up to the limit
		int[] histogram = new int[gramCount + 2];
		for (int t = 0; t < touchedCount; t++) {
			histogram[Math.min(shared[touched[t]], gramCount + 1)]++;
		}
		int threshold = gramCount + 1;
		int taken = 0;
		while (threshold > 2 && taken + histogram[threshold - 1] <= limit) {
			threshold--;
			taken += histogram[threshold];
		}
		if (taken == 0 && threshold > 2) {
			// the best group alone is larger than the limit: take it anyway rather than nothing
			threshold--;
		}
		int[] result = new int[touchedCount];
		int size = 0;
		for (int t = 0; t < touchedCount; t++) {
			if (shared[touched[t]] >= threshold) {
				result[size++] = touched[t];
			}
		}
		return Arrays.copyOf(result, size);
	}

	/** Writes the distinct letter-pair ids of "^word$" into {@code out}; returns how many. */
	private static int gramsOf(String word, int[] out) {
		int count = 0;
		int previous = START;
		int length = Math.min(word.length(), out.length - 2);
		for (int i = 0; i <= length; i++) {
			int code = i == length ? END : code(word.charAt(i));
			if (code < 0) {
				previous = -1;
				continue;
			}
			if (previous >= 0) {
				int gram = previous * ALPHABET + code;
				boolean seen = false;
				for (int k = 0; k < count; k++) {
					if (out[k] == gram) {
						seen = true;
						break;
					}
				}
				if (!seen) {
					out[count++] = gram;
				}
			}
			previous = code;
		}
		return count;
	}

	private static int code(char c) {
		if (c >= 'a' && c <= 'z') {
			return c - 'a';
		}
		if (c >= '0' && c <= '9') {
			return 26 + (c - '0');
		}
		return -1;
	}

}
