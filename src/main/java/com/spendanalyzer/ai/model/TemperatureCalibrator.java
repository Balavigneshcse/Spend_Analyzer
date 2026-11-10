package com.spendanalyzer.ai.model;

import java.util.List;

/**
 * Makes the model's confidence honest. A model trained on its own seed data is far too sure of
 * itself; dividing its scores by a "temperature" fixes that without changing which category wins.
 * The temperature is the one that makes held-out, hand-labelled phrases most likely (lowest
 * negative log-likelihood), found by a simple grid search.
 */
public final class TemperatureCalibrator {

	static final double LOWEST = 0.5;

	static final double HIGHEST = 4.0;

	static final double STEP = 0.1;

	private TemperatureCalibrator() {
	}

	/** @return the best temperature, or 1.0 when there is nothing to calibrate on */
	public static double fit(SoftmaxModel model, List<SoftmaxTrainer.Example> heldOut) {
		if (heldOut.isEmpty()) {
			return 1.0;
		}
		double[][] scores = new double[heldOut.size()][];
		for (int i = 0; i < scores.length; i++) {
			scores[i] = model.scores(heldOut.get(i).features());
		}
		double best = 1.0;
		double bestLoss = Double.MAX_VALUE;
		for (double t = LOWEST; t <= HIGHEST + 1e-9; t += STEP) {
			double loss = 0;
			for (int i = 0; i < scores.length; i++) {
				double p = SoftmaxModel.softmax(scores[i], t)[heldOut.get(i).label()];
				loss -= Math.log(Math.max(p, 1e-12));
			}
			if (loss < bestLoss - 1e-9) {
				bestLoss = loss;
				best = t;
			}
		}
		return Math.round(best * 10) / 10.0;
	}

}
