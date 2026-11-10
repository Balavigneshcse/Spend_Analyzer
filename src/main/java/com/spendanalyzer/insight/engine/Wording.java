package com.spendanalyzer.insight.engine;

import java.util.Locale;

/**
 * Phrases for the chosen period and the choice between equivalent wordings. The choice is a hash
 * of the user's seed, the period start and the sentence slot: it differs between periods but is
 * the same on every refresh.
 */
final class Wording {

	private final PeriodCode code;

	private final long seed;

	Wording(PeriodWindow period, long variantSeed) {
		this.code = period.code();
		this.seed = variantSeed * 1_000_003L + period.from().toEpochDay();
	}

	/** "this month", "in the last 30 days" ... reads naturally at the end of a clause. */
	String when() {
		return switch (code) {
			case THIS_WEEK -> "this week";
			case THIS_MONTH -> "this month";
			case LAST_MONTH -> "last month";
			case LAST_30_DAYS -> "in the last 30 days";
			case ALL_TIME -> "overall";
		};
	}

	/** Name of the like-for-like period before this one. */
	String previous() {
		return switch (code) {
			case THIS_WEEK -> "the same days last week";
			case THIS_MONTH -> "the same days last month";
			case LAST_MONTH -> "the month before";
			case LAST_30_DAYS -> "the 30 days before";
			case ALL_TIME -> "before";
		};
	}

	/** The days of the period as a template fragment with a {days} slot: "the {days} days so far this month". */
	String daysSpan() {
		return switch (code) {
			case THIS_WEEK -> "the {days} days so far this week";
			case THIS_MONTH -> "the {days} days so far this month";
			case LAST_MONTH -> "the {days} days of last month";
			case LAST_30_DAYS -> "the last {days} days";
			case ALL_TIME -> "the {days} days since your first entry";
		};
	}

	String pick(String slot, String... variants) {
		long h = seed * 31 + slot.hashCode();
		// xorshift-multiply mix, so neighbouring seeds do not pick neighbouring variants
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		return variants[(int) Long.remainderUnsigned(h, variants.length)];
	}

	static String capitalise(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
	}

	static String plural(long count, String one, String many) {
		return count == 1 ? one : many;
	}

	/** Safe fragment for an insight id: "ice cream" becomes "ice-cream". */
	static String slug(String text) {
		String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
		return slug.isEmpty() ? "item" : slug;
	}

}
