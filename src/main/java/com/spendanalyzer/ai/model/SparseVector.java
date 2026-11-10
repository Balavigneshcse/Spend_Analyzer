package com.spendanalyzer.ai.model;

import java.util.Arrays;

/**
 * The features of one text: a short list of (slot, value) pairs out of the 65,536 possible slots.
 * A slot may appear twice; every user of the vector simply adds the values up.
 */
public final class SparseVector {

	final int[] index;

	final float[] value;

	SparseVector(int[] index, float[] value) {
		this.index = index;
		this.value = value;
	}

	public int size() {
		return index.length;
	}

	public boolean isEmpty() {
		return index.length == 0;
	}

	/** Grows as features are added; one builder per text. */
	static final class Builder {

		private int[] index = new int[64];

		private float[] value = new float[64];

		private int size;

		/** Start of the feature family being added, so it can be scaled as a whole. */
		private int groupStart;

		void add(int slot, float v) {
			if (size == index.length) {
				index = Arrays.copyOf(index, size * 2);
				value = Arrays.copyOf(value, size * 2);
			}
			index[size] = slot;
			value[size] = v;
			size++;
		}

		/**
		 * Closes the current family: scales it to unit length times {@code weight}, so a long word
		 * with many letter groups does not shout louder than a short one.
		 */
		void closeGroup(float weight) {
			double sumOfSquares = 0;
			for (int i = groupStart; i < size; i++) {
				sumOfSquares += value[i] * value[i];
			}
			if (sumOfSquares > 0) {
				float scale = (float) (weight / Math.sqrt(sumOfSquares));
				for (int i = groupStart; i < size; i++) {
					value[i] *= scale;
				}
			}
			groupStart = size;
		}

		SparseVector build() {
			return new SparseVector(Arrays.copyOf(index, size), Arrays.copyOf(value, size));
		}

	}

}
