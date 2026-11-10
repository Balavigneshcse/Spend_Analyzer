package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;

/** Small number helpers. Money stays BigDecimal; doubles are only used for statistics and shares. */
final class Num {

	static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	static final BigDecimal TEN = BigDecimal.TEN;

	private Num() {
	}

	static BigDecimal sum(Collection<Expense> rows) {
		BigDecimal total = BigDecimal.ZERO;
		for (Expense row : rows) {
			total = total.add(row.amount());
		}
		return total;
	}

	/** {@code part} as a percentage of {@code whole}, one decimal. 0 when there is nothing to divide by. */
	static double percentOf(BigDecimal part, BigDecimal whole) {
		if (whole.signum() <= 0) {
			return 0;
		}
		return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP).doubleValue();
	}

	/** Change from {@code before} to {@code now} in percent, one decimal. Callers check that before is positive. */
	static double changePercent(BigDecimal now, BigDecimal before) {
		return percentOf(now.subtract(before), before);
	}

	/** a / b as a plain ratio for scoring; 0 when b is not positive. */
	static double ratio(BigDecimal a, BigDecimal b) {
		return b.signum() <= 0 ? 0 : a.doubleValue() / b.doubleValue();
	}

	/** A statistic turned back into money: two decimals, and never NaN or infinite. */
	static BigDecimal money(double value) {
		if (!Double.isFinite(value)) {
			return BigDecimal.ZERO.setScale(2);
		}
		return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
	}

	static BigDecimal wholeRupees(BigDecimal amount) {
		return amount.setScale(0, RoundingMode.HALF_UP);
	}

	/** Rounded to the nearest ten rupees: used for "about" figures such as estimated savings. */
	static BigDecimal nearestTen(BigDecimal amount) {
		return amount.divide(TEN, 0, RoundingMode.HALF_UP).multiply(TEN);
	}

	static double clamp01(double value) {
		if (!(value > 0)) {
			return 0; // also catches NaN
		}
		return Math.min(1, value);
	}

	static double clamp(double value, double low, double high) {
		if (Double.isNaN(value)) {
			return low;
		}
		return Math.max(low, Math.min(high, value));
	}

	static BigDecimal median(List<BigDecimal> amounts) {
		return money(Stats.median(doubles(amounts)));
	}

	static double[] doubles(List<BigDecimal> amounts) {
		double[] values = new double[amounts.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = amounts.get(i).doubleValue();
		}
		return values;
	}

}
