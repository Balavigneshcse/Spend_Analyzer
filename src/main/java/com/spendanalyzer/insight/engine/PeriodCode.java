package com.spendanalyzer.insight.engine;

import java.util.Locale;
import java.util.Optional;

/** The periods the dashboard can ask an analysis for. */
public enum PeriodCode {

	THIS_WEEK("This week"),
	THIS_MONTH("This month"),
	LAST_MONTH("Last month"),
	LAST_30_DAYS("Last 30 days"),
	ALL_TIME("All time");

	private final String label;

	PeriodCode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** Reads the query parameter. Empty when the text is not one of the codes. */
	public static Optional<PeriodCode> parse(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String wanted = raw.trim().toUpperCase(Locale.ROOT);
		for (PeriodCode code : values()) {
			if (code.name().equals(wanted)) {
				return Optional.of(code);
			}
		}
		return Optional.empty();
	}

}
