package com.spendanalyzer.ai.model;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Trains a {@link SoftmaxModel} by stochastic gradient descent with AdaGrad step sizes.
 *
 * <p>For every example the model predicts, the error (predicted probability minus the truth) is
 * pushed back into the weights of the features that were present. AdaGrad gives each weight its
 * own step size that shrinks the more that weight has already been moved, so rare features (an
 * unusual letter group) learn fast and common ones settle down. A small L2 pull towards zero keeps
 * the weights from growing without bound. Single-threaded and fully repeatable: same examples and
 * seed, same weights.
 */
public final class SoftmaxTrainer {

	/** One training text with its right answer (index of the category). */
	public record Example(SparseVector features, int label) {
	}

	public record Options(int epochs, double learningRate, double l2, long seed) {

		public static final Options DEFAULT = new Options(4, 0.5, 1e-5, 42L);

	}

	/** @param lossPerEpoch average negative log-likelihood of each pass; it should fall */
	public record Outcome(SoftmaxModel model, double[] lossPerEpoch) {
	}

	private SoftmaxTrainer() {
	}

	public static Outcome train(List<Example> examples, int classes, Options options) {
		int dimension = FeatureExtractor.DIMENSION;
		float[] weights = new float[dimension * classes];
		float[] squaredGradients = new float[dimension * classes];
		int[] order = new int[examples.size()];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		Random random = new Random(options.seed());
		double[] lossPerEpoch = new double[options.epochs()];
		double[] scores = new double[classes];
		float learningRate = (float) options.learningRate();
		float l2 = (float) options.l2();

		for (int epoch = 0; epoch < options.epochs(); epoch++) {
			shuffle(order, random);
			double loss = 0;
			for (int position : order) {
				Example example = examples.get(position);
				int[] index = example.features().index;
				float[] value = example.features().value;

				Arrays.fill(scores, 0);
				for (int k = 0; k < index.length; k++) {
					int base = index[k] * classes;
					float v = value[k];
					for (int c = 0; c < classes; c++) {
						scores[c] += weights[base + c] * v;
					}
				}
				double[] probabilities = SoftmaxModel.softmax(scores, 1.0);
				loss -= Math.log(Math.max(probabilities[example.label()], 1e-12));

				for (int k = 0; k < index.length; k++) {
					int base = index[k] * classes;
					float v = value[k];
					for (int c = 0; c < classes; c++) {
						float error = (float) probabilities[c] - (c == example.label() ? 1f : 0f);
						float gradient = error * v + l2 * weights[base + c];
						float seen = squaredGradients[base + c] + gradient * gradient;
						squaredGradients[base + c] = seen;
						weights[base + c] -= learningRate * gradient / ((float) Math.sqrt(seen) + 1e-6f);
					}
				}
			}
			lossPerEpoch[epoch] = examples.isEmpty() ? 0 : loss / examples.size();
		}
		return new Outcome(new SoftmaxModel(weights, classes, dimension, 1.0), lossPerEpoch);
	}

	/** Fisher-Yates shuffle. */
	private static void shuffle(int[] order, Random random) {
		for (int i = order.length - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			int swap = order[i];
			order[i] = order[j];
			order[j] = swap;
		}
	}

}
