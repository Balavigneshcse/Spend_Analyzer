package com.spendanalyzer.insight.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The statistics helpers against values worked out by hand. */
class StatsTest {

	private static final double[] NONE = {};

	@Test
	void meanAndSum() {
		assertThat(Stats.sum(new double[] { 2, 4, 9 })).isEqualTo(15.0);
		assertThat(Stats.mean(new double[] { 2, 4, 9 })).isEqualTo(5.0);
	}

	@Test
	void medianOfOddAndEvenCounts() {
		assertThat(Stats.median(new double[] { 9, 1, 4 })).isEqualTo(4.0);
		assertThat(Stats.median(new double[] { 9, 1, 4, 2 })).isEqualTo(3.0); // (2 + 4) / 2
		assertThat(Stats.median(new double[] { 7 })).isEqualTo(7.0);
	}

	@Test
	void medianDoesNotReorderItsInput() {
		double[] values = { 3, 1, 2 };
		Stats.median(values);
		assertThat(values).containsExactly(3, 1, 2);
	}

	@Test
	void madIgnoresOneOutlier() {
		// median 2; distances 1,1,0,0,2,4,7 -> sorted 0,0,1,1,2,4,7 -> middle 1
		assertThat(Stats.mad(new double[] { 1, 1, 2, 2, 4, 6, 9 })).isEqualTo(1.0);
		// the outlier moves the mean a lot, the MAD not at all
		assertThat(Stats.mad(new double[] { 1, 1, 2, 2, 4, 6, 9000 })).isEqualTo(1.0);
	}

	@Test
	void meanAbsoluteDeviationFromTheMedian() {
		// median 100; distances 0,0,0,0,400 -> mean 80
		assertThat(Stats.meanAbsDev(new double[] { 100, 100, 100, 100, 500 })).isEqualTo(80.0);
		// here the MAD is zero, which is why the fallback exists
		assertThat(Stats.mad(new double[] { 100, 100, 100, 100, 500 })).isEqualTo(0.0);
	}

	@Test
	void ewmaFollowsRecentValues() {
		// alpha 0.5: 10 -> 0.5*20 + 0.5*10 = 15 -> 0.5*30 + 0.5*15 = 22.5
		assertThat(Stats.ewma(new double[] { 10, 20, 30 }, 0.5)).isEqualTo(22.5);
		// alpha 0.15: 100 -> 0.15*200 + 0.85*100 = 115 -> 0.15*100 + 0.85*115 = 112.75
		assertThat(Stats.ewma(new double[] { 100, 200, 100 }, 0.15)).isCloseTo(112.75, within(1e-9));
		assertThat(Stats.ewma(new double[] { 42 }, 0.15)).isEqualTo(42.0);
	}

	@Test
	void winsoriseCapsOnlyTheTop() {
		assertThat(Stats.winsorise(new double[] { 0, 100, 3100 }, 300)).containsExactly(0, 100, 300);
	}

	@Test
	void robustZ() {
		// 0.6745 * (10 - 4) / 2 = 2.0235
		assertThat(Stats.robustZ(10, 4, 2)).isCloseTo(2.0235, within(1e-9));
		assertThat(Stats.robustZ(10, 4, 0)).isEqualTo(0.0);
	}

	@Test
	void leastSquaresThroughExactLine() {
		Stats.Line line = Stats.leastSquares(new double[] { 1, 2, 3 }, new double[] { 2, 4, 6 });
		assertThat(line.slope()).isCloseTo(2.0, within(1e-12));
		assertThat(line.intercept()).isCloseTo(0.0, within(1e-12));
		assertThat(line.at(30)).isCloseTo(60.0, within(1e-9));
	}

	@Test
	void leastSquaresThroughScatteredPoints() {
		// x 0,1,2 (mean 1); y 1,3,2 (mean 2); Sxy = (-1)(-1) + 0 + (1)(0) = 1; Sxx = 2
		Stats.Line line = Stats.leastSquares(new double[] { 0, 1, 2 }, new double[] { 1, 3, 2 });
		assertThat(line.slope()).isCloseTo(0.5, within(1e-12));
		assertThat(line.intercept()).isCloseTo(1.5, within(1e-12));
	}

	@Test
	void leastSquaresWithoutSpreadIsFlat() {
		Stats.Line line = Stats.leastSquares(new double[] { 5 }, new double[] { 70 });
		assertThat(line.slope()).isEqualTo(0.0);
		assertThat(line.at(30)).isEqualTo(70.0);
	}

	@Test
	void emptyInputGivesZeroNeverNaN() {
		assertThat(Stats.mean(NONE)).isEqualTo(0.0);
		assertThat(Stats.median(NONE)).isEqualTo(0.0);
		assertThat(Stats.mad(NONE)).isEqualTo(0.0);
		assertThat(Stats.meanAbsDev(NONE)).isEqualTo(0.0);
		assertThat(Stats.ewma(NONE, 0.15)).isEqualTo(0.0);
		assertThat(Stats.leastSquares(NONE, NONE).at(10)).isEqualTo(0.0);
	}

}
