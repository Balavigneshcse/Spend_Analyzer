package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import com.spendanalyzer.insight.engine.BurnModel.Burn;
import com.spendanalyzer.insight.engine.RecurringDetector.Recurring;

/**
 * Expected total spend of the current month:
 * spent so far + remaining days x everyday level + recurring bills still due this month.
 * The range is an 80% band from the day-to-day spread (never narrower than a tenth of what is
 * still to come), and it is widened when a straight line through the month's cumulative everyday
 * spend disagrees with the estimate.
 */
final class ForecastCalc {

	/** 80% of a bell curve lies within this many standard deviations of the middle. */
	private static final double Z_80 = 1.28;

	/** All amounts are whole rupees. */
	record Result(BigDecimal expected, BigDecimal low, BigDecimal high, BigDecimal spentSoFar, int expensesThisMonth,
			LocalDate monthEnd) {
	}

	private ForecastCalc() {
	}

	/** @return null early in the month with few expenses, or when the burn is unknown */
	static Result of(List<Expense> history, LocalDate today, Burn burn) {
		if (burn == null) {
			return null;
		}
		LocalDate monthStart = today.withDayOfMonth(1);
		LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
		List<Expense> month = Tally.between(history, monthStart, today);
		if (today.getDayOfMonth() < Guardrails.FORECAST_MIN_DAY_OF_MONTH
				&& month.size() < Guardrails.FORECAST_MIN_EXPENSES) {
			return null;
		}
		double spent = Num.sum(month).doubleValue();
		int remaining = today.lengthOfMonth() - today.getDayOfMonth();
		double toCome = remaining * burn.everyday() + billsDue(burn.bills(), today, monthEnd);
		double expected = spent + toCome;

		double band = Math.max(Z_80 * burn.spread() * Math.sqrt(remaining), 0.10 * toCome);
		// Cross-check on the everyday spending only: bills and one-off spikes are steps, not a slope.
		double[] everydaySoFar = BurnModel.everydaySeries(month, monthStart, today, burn.bills());
		double modelled = Stats.sum(everydaySoFar) + remaining * burn.everyday();
		double line = straightLine(everydaySoFar, today.lengthOfMonth());
		if (remaining > 0 && Math.abs(line - modelled) > 0.25 * modelled) {
			band = Math.max(band, Math.abs(line - modelled)); // two methods disagree: be less sure
		}
		BigDecimal spentSoFar = BigDecimal.valueOf(spent).setScale(0, RoundingMode.HALF_UP);
		BigDecimal low = whole(Math.max(spent, expected - band));
		return new Result(whole(expected).max(spentSoFar), low.max(spentSoFar), whole(expected + band).max(spentSoFar),
				spentSoFar, month.size(), monthEnd);
	}

	/** Recurring bills expected after today and up to the end of the month. */
	private static double billsDue(List<Recurring> bills, LocalDate today, LocalDate monthEnd) {
		double due = 0;
		for (Recurring bill : bills) {
			LocalDate next = bill.nextDue();
			if (!next.isAfter(today)) {
				next = today.plusDays(1); // slightly overdue: assume it is paid in the days to come
			}
			while (!next.isAfter(monthEnd)) {
				due += bill.amount().doubleValue();
				next = next.plusDays(bill.intervalDays());
			}
		}
		return due;
	}

	/** Month-end value of a least-squares line through the cumulative everyday spend of the month so far. */
	private static double straightLine(double[] daily, int daysInMonth) {
		double[] x = new double[daily.length];
		double[] cumulative = new double[daily.length];
		for (int i = 0; i < daily.length; i++) {
			x[i] = i + 1;
			cumulative[i] = daily[i] + (i > 0 ? cumulative[i - 1] : 0);
		}
		return Stats.leastSquares(x, cumulative).at(daysInMonth);
	}

	private static BigDecimal whole(double value) {
		return Num.money(value).setScale(0, RoundingMode.HALF_UP);
	}

}
