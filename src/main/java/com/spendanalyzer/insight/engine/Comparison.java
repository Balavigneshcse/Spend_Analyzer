package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The chosen period against the like-for-like period before it, with the rules for what may be
 * said about the difference.
 *
 * @param previousSpent total of the previous period; null when there is no previous period or
 * nothing was recorded in it
 * @param changePercent change in percent, one decimal; null when the previous total is too small
 * a base (under ₹200) or the user had not started tracking when the previous period began (the
 * comparison would be with a half-empty notebook)
 * @param speakable true when a sentence may state the change: {@code changePercent} is known and
 * both periods have at least five expenses
 */
record Comparison(BigDecimal previousSpent, Double changePercent, boolean tracked, boolean speakable) {

	static Comparison of(LedgerSnapshot in, Tally now, Tally before) {
		PeriodWindow period = in.period();
		if (!period.hasPrevious() || before.isEmpty()) {
			return new Comparison(null, null, false, false);
		}
		boolean tracked = tracked(period, in.trackingSince());
		Double change = null;
		if (tracked && before.total().compareTo(Guardrails.PERCENT_MIN_BASE) >= 0) {
			change = Num.changePercent(now.total(), before.total());
		}
		boolean speakable = change != null && now.count() >= Guardrails.MIN_EXPENSES
				&& before.count() >= Guardrails.MIN_EXPENSES;
		return new Comparison(before.total(), change, tracked, speakable);
	}

	/** Did tracking start by the beginning of the previous period (give or take a little)? */
	private static boolean tracked(PeriodWindow period, LocalDate trackingSince) {
		if (trackingSince == null) {
			return false;
		}
		long slack = (long) (period.previousDays() * Guardrails.TRACKING_SLACK);
		return !trackingSince.isAfter(period.previousFrom().plusDays(slack));
	}

}
