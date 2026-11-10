package com.spendanalyzer.ai.model;

/**
 * MurmurHash3 (32 bit), written out by hand and fed with UTF-16 units instead of bytes. Used to
 * turn feature names such as "word:dosa" or the letter group "^do" into a slot of the weight table
 * without keeping a dictionary.
 */
final class Murmur3 {

	private static final int C1 = 0xcc9e2d51;

	private static final int C2 = 0x1b873593;

	private Murmur3() {
	}

	/** Hash of {@code text[from, to)} with a seed that separates feature families. */
	static int hash(CharSequence text, int from, int to, int seed) {
		int h = seed;
		int i = from;
		while (i + 1 < to) {
			int block = text.charAt(i) | (text.charAt(i + 1) << 16);
			h ^= scramble(block);
			h = Integer.rotateLeft(h, 13);
			h = h * 5 + 0xe6546b64;
			i += 2;
		}
		if (i < to) {
			h ^= scramble(text.charAt(i));
		}
		h ^= (to - from) * 2;
		return mix(h);
	}

	static int hash(CharSequence text, int seed) {
		return hash(text, 0, text.length(), seed);
	}

	/** Combines two hashes into one, order-sensitive ("bus ticket" is not "ticket bus"). */
	static int combine(int first, int second, int seed) {
		int h = seed;
		h ^= scramble(first);
		h = Integer.rotateLeft(h, 13);
		h = h * 5 + 0xe6546b64;
		h ^= scramble(second);
		h = Integer.rotateLeft(h, 13);
		h = h * 5 + 0xe6546b64;
		h ^= 8;
		return mix(h);
	}

	private static int scramble(int block) {
		block *= C1;
		block = Integer.rotateLeft(block, 15);
		block *= C2;
		return block;
	}

	/** The final avalanche step: every input bit ends up affecting every output bit. */
	static int mix(int h) {
		h ^= h >>> 16;
		h *= 0x85ebca6b;
		h ^= h >>> 13;
		h *= 0xc2b2ae35;
		h ^= h >>> 16;
		return h;
	}

}
