package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import com.spendanalyzer.insight.engine.BurnModel.Burn;

/** How long the money in bank and cash lasts at the current daily burn, assuming no new money comes in. */
final class RunwayCalc {

	/**
	 * @param daysLeft whole days the money lasts, at most {@link Guardrails#RUNWAY_MAX_DAYS}
	 * @param safePerDay what can be spent per day so the money lasts to the end of the month
	 * @param daysToMonthEnd days after today that are left in this month
	 */
	record Result(BigDecimal available, BigDecimal dailyBurn, int daysLeft, BigDecimal safePerDay, int daysToMonthEnd,
			LocalDate monthEnd) {

		boolean capped() {
			return daysLeft >= Guardrails.RUNWAY_MAX_DAYS;
		}

	}

	private RunwayCalc() {
	}

	/** @return null when the burn is unknown */
	static Result of(Burn burn, Balances balances, LocalDate today) {
		if (burn == null) {
			return null;
		}
		BigDecimal dailyBurn = Num.money(burn.perDay());
		if (dailyBurn.signum() <= 0) {
			return null;
		}
		BigDecimal available = balances.available();
		int daysToMonthEnd = today.lengthOfMonth() - today.getDayOfMonth();
		int daysLeft = 0;
		BigDecimal safePerDay = BigDecimal.ZERO.setScale(2);
		if (available.signum() > 0) {
			BigDecimal days = available.divideToIntegralValue(dailyBurn);
			daysLeft = days.compareTo(BigDecimal.valueOf(Guardrails.RUNWAY_MAX_DAYS)) >= 0 ? Guardrails.RUNWAY_MAX_DAYS
					: days.intValue();
			// rounded down: a "safe" figure must never overshoot
			safePerDay = available.divide(BigDecimal.valueOf(Math.max(1, daysToMonthEnd)), 2, RoundingMode.DOWN);
		}
		return new Result(available, dailyBurn, daysLeft, safePerDay, daysToMonthEnd,
				today.withDayOfMonth(today.lengthOfMonth()));
	}

}
