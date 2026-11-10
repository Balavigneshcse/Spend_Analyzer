package com.spendanalyzer.insight.engine;

/** How loudly an insight should be shown. */
public enum Severity {

	/** Needs action now. */
	ALERT(1.0),
	/** Worth changing. */
	WARN(0.8),
	/** Good to know. */
	INFO(0.5),
	/** Something that is going well. */
	GOOD(0.45);

	private final double weight;

	Severity(double weight) {
		this.weight = weight;
	}

	/** Starting point of an insight's ranking priority. */
	double weight() {
		return weight;
	}

}
