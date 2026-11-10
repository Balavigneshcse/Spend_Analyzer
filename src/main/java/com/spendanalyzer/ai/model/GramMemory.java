package com.spendanalyzer.ai.model;

import java.util.Collection;

/**
 * Remembers which four-letter groups (word boundaries included) occur in known words, in a
 * fixed bit table. A typed word whose letter groups were mostly never seen ("asdf", "qwxz") is
 * noise, and the model's guess for it must not be trusted, however confident the numbers look.
 * Immutable after construction.
 */
public final class GramMemory {

	private static final int BITS = 20;

	private static final int SEED = 0x5bd1e995;

	/** Every group sets two bits (a small Bloom filter), so a chance collision is very unlikely. */
	private static final int SECOND_SEED = 0x7f4a7c15;

	/** Letters per group. Three is too forgiving: "qwerty" is made of common triples. */
	private static final int GROUP = 4;

	private final long[] table = new long[1 << (BITS - 6)];

	public GramMemory(Collection<String> knownWords) {
		for (String word : knownWords) {
			String marked = "^" + word + "$";
			for (int i = 0; i + GROUP <= marked.length(); i++) {
				set(slot(marked, i, SEED));
				set(slot(marked, i, SECOND_SEED));
			}
		}
	}

	/**
	 * Share (0..1) of the word's letter groups that occur in some known word. A word of one or
	 * two letters has no group to judge and counts as unfamiliar.
	 */
	public double familiarity(String word) {
		String marked = "^" + word + "$";
		int groups = marked.length() - GROUP + 1;
		if (groups <= 0) {
			return 0.0;
		}
		int seen = 0;
		for (int i = 0; i + GROUP <= marked.length(); i++) {
			if (isSet(slot(marked, i, SEED)) && isSet(slot(marked, i, SECOND_SEED))) {
				seen++;
			}
		}
		return (double) seen / groups;
	}

	private void set(int slot) {
		table[slot >>> 6] |= 1L << (slot & 63);
	}

	private boolean isSet(int slot) {
		return (table[slot >>> 6] & (1L << (slot & 63))) != 0;
	}

	private static int slot(String marked, int from, int seed) {
		return Murmur3.hash(marked, from, from + GROUP, seed) & ((1 << BITS) - 1);
	}

}
