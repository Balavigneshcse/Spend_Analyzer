package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.spendanalyzer.insight.engine.RecurringDetector.Recurring;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.NeedFlag;

/**
 * Finds spends that are far above what the user normally pays.
 * <p>
 * Main rule, per category: each spend is compared with the OTHER spends of its category (so it
 * cannot hide itself), on the log of the amount (spending is skewed: ₹20 and ₹200 are both normal
 * for food). It is unusual when the robust z-score {@code 0.6745 * (x - median) / MAD} is above
 * 3.5 and it is at least twice the usual amount. When the MAD is zero (most amounts identical)
 * the mean absolute deviation is used, and when that is zero too the rule is simply "three times
 * the usual amount".
 * <p>
 * Fallback for a "want" category with too few spends to judge (one pair of shoes in three months):
 * the spend is compared with all other spends, and must be at least five times the typical one.
 * Bills and other needs are not judged this way: rent is large by nature, not unusual.
 * Known recurring bills are never called unusual.
 */
final class AnomalyDetector {

	/** A lone big purchase must be this many times the typical spend overall. */
	private static final double OVERALL_MIN_RATIO = 5.0;

	/** The overall comparison needs at least this many other spends. */
	private static final int OVERALL_MIN_REFERENCE = 15;

	/**
	 * @param typical the median of the spends it was compared with
	 * @param withinCategory true when compared with its own category, false when compared with
	 * all spends (the fallback)
	 */
	record Anomaly(Expense expense, BigDecimal typical, double ratio, boolean withinCategory) {
	}

	/** @param testable false when there were too few spends to judge anything */
	record Result(List<Anomaly> anomalies, boolean testable) {
	}

	private AnomalyDetector() {
	}

	static Result detect(List<Expense> history, List<Recurring> recurring) {
		Map<Category, List<Expense>> byCategory = new EnumMap<>(Category.class);
		for (Expense row : history) {
			byCategory.computeIfAbsent(row.category(), key -> new ArrayList<>()).add(row);
		}
		List<Anomaly> anomalies = new ArrayList<>();
		boolean testable = false;
		for (List<Expense> rows : byCategory.values()) {
			if (rows.size() > Guardrails.ANOMALY_MIN_REFERENCE) {
				testable = true;
				judge(rows, rows, Guardrails.ANOMALY_MIN_RATIO, true, recurring, anomalies);
			}
			else if (rows.get(0).category().needFlag() == NeedFlag.WANT && history.size() > OVERALL_MIN_REFERENCE) {
				testable = true;
				judge(rows, history, OVERALL_MIN_RATIO, false, recurring, anomalies);
			}
		}
		anomalies.sort(Comparator.comparing((Anomaly a) -> a.expense().amount())
			.reversed()
			.thenComparing(Anomaly::expense, Expense.CANONICAL));
		return new Result(anomalies, testable);
	}

	/** Compares each candidate with all rows of the reference except itself. */
	private static void judge(List<Expense> candidates, List<Expense> reference, double minRatio, boolean withinCategory,
			List<Recurring> recurring, List<Anomaly> out) {
		double[] amounts = new double[reference.size()];
		for (int i = 0; i < amounts.length; i++) {
			amounts[i] = reference.get(i).amount().doubleValue();
		}
		double overallMedian = Stats.median(amounts);
		for (Expense row : candidates) {
			double amount = row.amount().doubleValue();
			if (amount <= overallMedian || isRecurring(row, recurring)) {
				continue;
			}
			double[] others = without(amounts, reference.indexOf(row));
			double typical = Stats.median(others);
			if (typical > 0 && amount >= minRatio * typical && farOut(amount, others)) {
				out.add(new Anomaly(row, Num.money(typical), amount / typical, withinCategory));
			}
		}
	}

	private static boolean farOut(double amount, double[] others) {
		double[] logs = new double[others.length];
		for (int i = 0; i < others.length; i++) {
			logs[i] = Math.log(others[i]);
		}
		double x = Math.log(amount);
		double median = Stats.median(logs);
		double mad = Stats.mad(logs);
		if (mad > 0) {
			return Stats.robustZ(x, median, mad) > Guardrails.ANOMALY_Z;
		}
		double meanDev = Stats.meanAbsDev(logs);
		if (meanDev > 0) {
			return (x - median) / (Stats.MEAN_DEV_TO_SIGMA * meanDev) > Guardrails.ANOMALY_Z;
		}
		return amount >= 3 * Stats.median(others);
	}

	private static double[] without(double[] values, int index) {
		double[] out = new double[values.length - 1];
		System.arraycopy(values, 0, out, 0, index);
		System.arraycopy(values, index + 1, out, index, values.length - index - 1);
		return out;
	}

	private static boolean isRecurring(Expense row, List<Recurring> recurring) {
		for (Recurring bill : recurring) {
			if (bill.covers(row)) {
				return true;
			}
		}
		return false;
	}

}
