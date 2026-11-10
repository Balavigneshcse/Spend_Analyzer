package com.spendanalyzer.ai.model;

/**
 * Multinomial logistic regression ("softmax"): one weight per (feature slot, category). The score
 * of a category is the sum of its weights over the text's features; the scores are turned into
 * probabilities that add up to one. Immutable, so any number of threads may read it.
 */
public final class SoftmaxModel {

	private final float[] weights;

	private final int classes;

	private final int dimension;

	private final double temperature;

	/** @param weights laid out as {@code weights[slot * classes + class]} */
	SoftmaxModel(float[] weights, int classes, int dimension, double temperature) {
		this.weights = weights;
		this.classes = classes;
		this.dimension = dimension;
		this.temperature = temperature;
	}

	public int classes() {
		return classes;
	}

	public double temperature() {
		return temperature;
	}

	/** Memory held by the weight table, in bytes. */
	public long sizeInBytes() {
		return 4L * weights.length;
	}

	/**
	 * The same weights with another temperature. Dividing the scores by a temperature above 1
	 * makes the model less sure of itself; this is how its confidence is calibrated.
	 */
	public SoftmaxModel withTemperature(double newTemperature) {
		return new SoftmaxModel(weights, classes, dimension, newTemperature);
	}

	/** Raw scores, before temperature and softmax. */
	public double[] scores(SparseVector features) {
		double[] scores = new double[classes];
		for (int k = 0; k < features.index.length; k++) {
			int base = features.index[k] * classes;
			float v = features.value[k];
			for (int c = 0; c < classes; c++) {
				scores[c] += weights[base + c] * v;
			}
		}
		return scores;
	}

	/** Probability of every category, adding up to 1. */
	public double[] probabilities(SparseVector features) {
		return softmax(scores(features), temperature);
	}

	/** Numerically safe softmax: subtract the largest score before exponentiating. */
	public static double[] softmax(double[] scores, double temperature) {
		double max = Double.NEGATIVE_INFINITY;
		for (double score : scores) {
			max = Math.max(max, score);
		}
		double[] out = new double[scores.length];
		double sum = 0;
		for (int c = 0; c < scores.length; c++) {
			out[c] = Math.exp((scores[c] - max) / temperature);
			sum += out[c];
		}
		for (int c = 0; c < out.length; c++) {
			out[c] /= sum;
		}
		return out;
	}

	/** A cheap fingerprint of the weights; equal after two trainings means training is repeatable. */
	public long checksum() {
		long h = 1125899906842597L;
		for (float w : weights) {
			h = 31 * h + Float.floatToIntBits(w);
		}
		return h;
	}

}
