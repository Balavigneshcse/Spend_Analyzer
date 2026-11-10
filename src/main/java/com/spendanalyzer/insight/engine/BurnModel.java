package com.spendanalyzer.insight.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.spendanalyzer.insight.engine.RecurringDetector.Recurring;

/**
 * How fast money leaves, per day, judged from the last 30 days (or since the first entry, when
 * that is more recent). Days without spending count as zero.
 * <p>
 * One big purchase must not dominate, so day totals are winsorised: anything above a robust upper
 * fence is pulled down to the fence. Known recurring bills are taken out of the day totals and
 * added back spread evenly over their interval (rent of ₹3,000 a month is ₹100 a day, on every
 * day, not a spike on the 5th). The everyday level is the larger of the plain mean and an EWMA,
 * so a recent rise in spending shows up at once while a quiet week does not hide the usual pace.
 */
final class BurnModel {

	static final double EWMA_ALPHA = 0.15;

	/**
	 * @param everyday rupees per day without recurring bills
	 * @param perDay everyday plus recurring bills spread per day: the daily burn
	 * @param spread robust standard deviation of the everyday day totals
	 * @param bills the recurring bills that were taken out of the everyday series
	 */
	record Burn(double everyday, double perDay, double spread, int windowDays, List<Recurring> bills) {
	}

	private BurnModel() {
	}

	/** @return null when there are too few recent expenses to judge a pace */
	static Burn estimate(List<Expense> history, LocalDate today, List<Recurring> recurring) {
		if (history.isEmpty()) {
			return null;
		}
		LocalDate start = today.minusDays(Guardrails.BURN_WINDOW_DAYS - 1L);
		LocalDate first = history.get(0).date();
		if (first.isAfter(start)) {
			start = first; // do not count the days before tracking began as zero-spend days
		}
		List<Expense> window = Tally.between(history, start, today);
		Set<LocalDate> spendDays = new HashSet<>();
		for (Expense row : window) {
			spendDays.add(row.date());
		}
		if (window.size() < Guardrails.RUNWAY_MIN_EXPENSES || spendDays.size() < Guardrails.RUNWAY_MIN_DAYS) {
			return null;
		}
		double[] capped = everydaySeries(window, start, today, recurring);
		double everyday = Math.max(Stats.mean(capped), Stats.ewma(capped, EWMA_ALPHA));
		double bills = 0;
		for (Recurring bill : recurring) {
			bills += bill.perDay();
		}
		double perDay = everyday + bills;
		if (!(perDay > 0) || !Double.isFinite(perDay)) {
			return null;
		}
		double spread = Stats.MAD_TO_SIGMA * Stats.mad(capped);
		if (spread == 0) {
			spread = Stats.MEAN_DEV_TO_SIGMA * Stats.meanAbsDev(capped);
		}
		return new Burn(everyday, perDay, spread, capped.length, recurring);
	}

	/**
	 * Day totals from {@code start} to {@code end} (zero-filled), without recurring bills and with
	 * extreme days pulled down to the fence.
	 */
	static double[] everydaySeries(List<Expense> rows, LocalDate start, LocalDate end, List<Recurring> recurring) {
		double[] daily = dayTotals(rows, start, end, recurring);
		return Stats.winsorise(daily, fence(daily));
	}

	/** Plain day totals from {@code start} to {@code end} (zero-filled), without recurring bills. */
	static double[] dayTotals(List<Expense> rows, LocalDate start, LocalDate end, List<Recurring> recurring) {
		int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
		double[] daily = new double[days];
		for (Expense row : rows) {
			if (row.date().isBefore(start) || row.date().isAfter(end) || isBill(row, recurring)) {
				continue;
			}
			daily[(int) ChronoUnit.DAYS.between(start, row.date())] += row.amount().doubleValue();
		}
		return daily;
	}

	/** Upper fence from the days that had spending: max(3 x median, median + 3.5 robust sigmas). */
	static double fence(double[] daily) {
		List<Double> spent = new ArrayList<>();
		for (double day : daily) {
			if (day > 0) {
				spent.add(day);
			}
		}
		if (spent.isEmpty()) {
			return 0;
		}
		double[] values = spent.stream().mapToDouble(Double::doubleValue).toArray();
		double median = Stats.median(values);
		return Math.max(3 * median, median + 3.5 * Stats.MAD_TO_SIGMA * Stats.mad(values));
	}

	static boolean isBill(Expense row, List<Recurring> recurring) {
		for (Recurring bill : recurring) {
			if (bill.covers(row)) {
				return true;
			}
		}
		return false;
	}

}
