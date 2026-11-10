package com.spendanalyzer.insight.engine;

import java.util.Arrays;

/**
 * Hand-written statistics for the insight engine. Every method is total: an empty input gives 0,
 * never NaN, so callers cannot leak a broken number into the analysis.
 */
public final class Stats {

	/** Scales a MAD so it estimates a standard deviation for bell-shaped data. */
	public static final double MAD_TO_SIGMA = 1.4826;

	/** Scales a mean absolute deviation so it estimates a standard deviation. */
	public static final double MEAN_DEV_TO_SIGMA = 1.2533;

	private Stats() {
	}

	public static double sum(double[] values) {
		double sum = 0;
		for (double v : values) {
			sum += v;
		}
		return sum;
	}

	public static double mean(double[] values) {
		return values.length == 0 ? 0 : sum(values) / values.length;
	}

	/** Middle value; the average of the two middle values for an even count. */
	public static double median(double[] values) {
		if (values.length == 0) {
			return 0;
		}
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int mid = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
	}

	/** Median absolute deviation from the median: a spread measure one outlier cannot move. */
	public static double mad(double[] values) {
		return median(deviations(values));
	}

	/** Mean absolute deviation from the median: the fallback spread when the MAD is zero. */
	public static double meanAbsDev(double[] values) {
		return mean(deviations(values));
	}

	private static double[] deviations(double[] values) {
		double median = median(values);
		double[] deviations = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			deviations[i] = Math.abs(values[i] - median);
		}
		return deviations;
	}

	/**
	 * Exponentially weighted moving average, oldest value first, seeded with the first value.
	 * @param alpha weight of each new value, 0..1 (higher follows recent values faster)
	 */
	public static double ewma(double[] values, double alpha) {
		if (values.length == 0) {
			return 0;
		}
		double level = values[0];
		for (int i = 1; i < values.length; i++) {
			level = alpha * values[i] + (1 - alpha) * level;
		}
		return level;
	}

	/** Copy of the values with everything above {@code cap} pulled down to {@code cap}. */
	public static double[] winsorise(double[] values, double cap) {
		double[] capped = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			capped[i] = Math.min(values[i], cap);
		}
		return capped;
	}

	/**
	 * Robust z-score: how many "robust standard deviations" x lies from the median.
	 * Returns 0 when the MAD is zero (the caller must then use a fallback rule).
	 */
	public static double robustZ(double x, double median, double mad) {
		return mad > 0 ? 0.6745 * (x - median) / mad : 0;
	}

	/** A fitted straight line y = intercept + slope * x. */
	public record Line(double slope, double intercept) {

		public double at(double x) {
			return intercept + slope * x;
		}

	}

	/** Ordinary least squares. With fewer than two points, or no spread in x, the line is flat. */
	public static Line leastSquares(double[] x, double[] y) {
		int n = Math.min(x.length, y.length);
		if (n == 0) {
			return new Line(0, 0);
		}
		double meanX = 0;
		double meanY = 0;
		for (int i = 0; i < n; i++) {
			meanX += x[i];
			meanY += y[i];
		}
		meanX /= n;
		meanY /= n;
		double sxy = 0;
		double sxx = 0;
		for (int i = 0; i < n; i++) {
			sxy += (x[i] - meanX) * (y[i] - meanY);
			sxx += (x[i] - meanX) * (x[i] - meanX);
		}
		if (sxx == 0) {
			return new Line(0, meanY);
		}
		double slope = sxy / sxx;
		return new Line(slope, meanY - slope * meanX);
	}

}
