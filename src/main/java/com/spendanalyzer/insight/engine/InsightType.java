package com.spendanalyzer.insight.engine;

/** The kinds of insight the engine can report (the list in docs/API.md). */
public enum InsightType {

	SMALL_LEAK,
	UNUSUAL_SPEND,
	PERIOD_CHANGE,
	RUNWAY,
	FORECAST,
	RECURRING,
	WEEK_PATTERN,
	NEEDS_WANTS,
	TOP_CATEGORY,
	REVIEW_NEEDED,
	CREDIT_CARD,
	BORROWED,
	POSITIVE

}
