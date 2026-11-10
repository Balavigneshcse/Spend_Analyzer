package com.spendanalyzer.insight.engine;

import java.util.List;

/**
 * The analysis exactly as GET /api/analysis returns it (see docs/API.md). Money is a string with
 * two decimals, dates are "YYYY-MM-DD", percentages are numbers with one decimal. Blocks without
 * enough data are null; lists are never null.
 */
public record Analysis(Period period, Totals totals, List<CategoryShare> byCategory, List<ModeShare> byMode,
		List<DayTotal> daily, List<TopItem> topItems, NeedsWants needsWants, Runway runway, Forecast forecast,
		Health health, List<Insight> insights, List<Tip> tips, String summary, DataQuality dataQuality,
		String generatedAt) {

	public record Period(String code, String label, String from, String to, int days) {
	}

	public record Totals(String spent, int count, String avgPerDay, DayTotal biggestDay, BiggestSpend biggestSpend,
			String previousSpent, Double changePercent) {
	}

	public record DayTotal(String date, String amount) {
	}

	public record BiggestSpend(String description, String amount, String date, String category,
			String categoryLabel) {
	}

	public record CategoryShare(String category, String label, String need, String amount, int count,
			double percent) {
	}

	public record ModeShare(String mode, String label, String amount, int count, double percent) {
	}

	public record TopItem(String item, int count, String amount, String category, String categoryLabel) {
	}

	public record NeedsWants(String needs, String wants, double wantsPercent) {
	}

	public record Runway(String available, String dailyBurn, int daysLeft, String safePerDay, int daysToMonthEnd) {
	}

	public record Forecast(String monthEndExpected, String monthEndLow, String monthEndHigh) {
	}

	public record Health(int score, String band, boolean provisional, List<Component> components) {
	}

	public record Component(String name, int points, int max, String note) {
	}

	public record Insight(String id, InsightType type, Severity severity, String title, String text) {
	}

	/** @param saving estimated rupees saved per month, or null when the tip is not about saving */
	public record Tip(String text, String saving) {
	}

	public record DataQuality(int needsReviewCount, boolean enoughData, String message) {
	}

}
