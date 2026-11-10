package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;

/**
 * What a detector found: the finished insight text, the facts it was filled from, how important
 * it is, and optionally a tip that follows from it.
 *
 * @param priority 0..1, higher is shown first: severity x rupee impact x confidence in the sample
 */
record Finding(String id, InsightType type, Severity severity, String title, String text, Facts facts,
		double priority, TipDraft tip) {

	/** A tip derived from a finding. {@code saving} is rupees per month, or null. */
	record TipDraft(String text, BigDecimal saving, Facts facts) {

		static TipDraft of(String template, BigDecimal saving, Facts facts) {
			return new TipDraft(facts.fill(template), saving, facts);
		}

	}

	/**
	 * @param impact 0..1, how much money this is about compared with the period's spending
	 * @param confidence 0..1, how far the sample is above the detector's minimum
	 */
	static Finding of(String id, InsightType type, Severity severity, String titleTemplate, String textTemplate,
			Facts facts, double impact, double confidence, TipDraft tip) {
		double priority = severity.weight() * Num.clamp(impact, 0.3, 1) * Num.clamp(confidence, 0.5, 1);
		return new Finding(id, type, severity, facts.fill(titleTemplate), facts.fill(textTemplate), facts, priority,
				tip);
	}

	/** Confidence from sample size: full at twice the detector's minimum. */
	static double confidence(int sample, int minimum) {
		return sample / (2.0 * minimum);
	}

	/** Impact of a rupee amount: full when it is a tenth of the reference spending or more. */
	static double impact(BigDecimal rupees, BigDecimal reference) {
		if (reference.signum() <= 0) {
			return 1;
		}
		return Num.ratio(rupees.abs(), reference) / 0.10;
	}

	boolean positive() {
		return severity == Severity.GOOD;
	}

}
