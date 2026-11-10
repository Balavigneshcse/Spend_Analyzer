package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;

/**
 * Every minimum-data and materiality rule in one place. A detector may only speak when its rule
 * here is met; below it the analysis stays silent or says "add more spends".
 * <p>
 * Rupee floors ("at least ₹100") only decide whether something is worth a sentence. The health
 * score uses the scale-free parts of the rules, so it does not change if all amounts are scaled.
 */
final class Guardrails {

	// ---- general
	/** Below this many expenses in the period the summary is one friendly line. */
	static final int SUMMARY_MIN_EXPENSES = 3;

	/** Trends, shares and percentages need at least this many expenses. */
	static final int MIN_EXPENSES = 5;

	/** No percentage is printed on a base smaller than this. */
	static final BigDecimal PERCENT_MIN_BASE = new BigDecimal("200");

	// ---- period over period
	static final double CHANGE_MIN_PERCENT = 15.0;

	static final BigDecimal CHANGE_MIN_RUPEES = new BigDecimal("100");

	/** A previous period counts as tracked when the first expense ever is at most this far into it. */
	static final double TRACKING_SLACK = 0.15;

	/** A period that runs to today must be this many days old before "less than before" is said. */
	static final int TO_DATE_DOWN_MIN_DAYS = 4;

	// ---- small leaks
	static final int LEAK_MIN_COUNT = 5;

	static final BigDecimal LEAK_SMALL_PRICE = new BigDecimal("100");

	static final double LEAK_SMALL_PRICE_SHARE = 0.05;

	static final BigDecimal LEAK_MIN_TOTAL = new BigDecimal("200");

	static final double LEAK_MIN_SHARE = 0.03;

	// ---- unusual spends
	static final int ANOMALY_MIN_REFERENCE = 8;

	static final double ANOMALY_Z = 3.5;

	static final double ANOMALY_MIN_RATIO = 2.0;

	static final BigDecimal ANOMALY_MIN_EXTRA = new BigDecimal("100");

	// ---- runway and forecast
	static final int BURN_WINDOW_DAYS = 30;

	static final int RUNWAY_MIN_EXPENSES = 5;

	static final int RUNWAY_MIN_DAYS = 3;

	static final int RUNWAY_ALERT_DAYS = 7;

	static final int RUNWAY_WARN_DAYS = 14;

	static final int RUNWAY_COMFORT_DAYS = 30;

	static final int RUNWAY_MAX_DAYS = 365;

	static final int FORECAST_MIN_DAY_OF_MONTH = 7;

	static final int FORECAST_MIN_EXPENSES = 10;

	// ---- recurring and weekly pattern
	static final int RECURRING_MIN_OCCURRENCES = 3;

	static final BigDecimal RECURRING_MIN_AMOUNT = new BigDecimal("100");

	static final int WEEK_PATTERN_MIN_WEEKS = 3;

	static final int WEEK_PATTERN_MAX_WEEKS = 8;

	// ---- health score
	static final int HEALTH_MIN_EXPENSES = 5;

	static final int HEALTH_FIRM_DAYS = 14;

	static final int HEALTH_FIRM_EXPENSES = 15;

	private Guardrails() {
	}

	/** May a sentence print a percentage of this base? */
	static boolean percentAllowed(BigDecimal base, int expenses) {
		return expenses >= MIN_EXPENSES && base.compareTo(PERCENT_MIN_BASE) >= 0;
	}

	/** Close to the minimum sample: wording must be hedged ("so far", "early sign"). */
	static boolean nearMinimum(int count, int minimum) {
		return count < 2 * minimum;
	}

}
