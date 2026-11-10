package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.spendanalyzer.common.Money;
import com.spendanalyzer.insight.engine.Tally.Bucket;
import com.spendanalyzer.insight.engine.Tally.ItemGroup;
import com.spendanalyzer.model.Category;
import com.spendanalyzer.model.NeedFlag;
import com.spendanalyzer.model.PaymentMode;

/** The plain sums of the analysis: totals, shares, the day series and the top items. */
final class Breakdown {

	static final int TOP_ITEMS = 8;

	/** ALL_TIME can span years; the chart only gets the most recent days. */
	static final int MAX_DAILY_ROWS = 90;

	private Breakdown() {
	}

	static Analysis.Period period(PeriodWindow window) {
		return new Analysis.Period(window.code().name(), window.code().label(), window.from().toString(),
				window.to().toString(), window.days());
	}

	static Analysis.Totals totals(Tally now, PeriodWindow window, Comparison comparison) {
		BigDecimal perDay = now.total().divide(BigDecimal.valueOf(window.days()), 2, RoundingMode.HALF_UP);
		return new Analysis.Totals(Money.format(now.total()), now.count(), Money.format(perDay), biggestDay(now),
				biggestSpend(now), Money.format(comparison.previousSpent()), comparison.changePercent());
	}

	private static Analysis.DayTotal biggestDay(Tally now) {
		Map.Entry<LocalDate, BigDecimal> best = null;
		for (Map.Entry<LocalDate, BigDecimal> day : now.byDay().entrySet()) {
			if (best == null || day.getValue().compareTo(best.getValue()) > 0) {
				best = day; // days come oldest first, so the earliest day wins a tie
			}
		}
		return best == null ? null : new Analysis.DayTotal(best.getKey().toString(), Money.format(best.getValue()));
	}

	private static Analysis.BiggestSpend biggestSpend(Tally now) {
		Expense best = null;
		for (Expense row : now.rows()) {
			if (best == null || row.amount().compareTo(best.amount()) > 0) {
				best = row; // rows are in canonical order, so ties are settled the same way every time
			}
		}
		if (best == null) {
			return null;
		}
		return new Analysis.BiggestSpend(best.label(), Money.format(best.amount()), best.date().toString(),
				best.category().name(), best.category().label());
	}

	static List<Analysis.CategoryShare> byCategory(Tally now) {
		List<Analysis.CategoryShare> out = new ArrayList<>();
		for (Map.Entry<Category, Bucket> entry : now.categoriesBySize()) {
			Category category = entry.getKey();
			Bucket bucket = entry.getValue();
			out.add(new Analysis.CategoryShare(category.name(), category.label(), category.needFlag().name(),
					Money.format(bucket.amount()), bucket.count(), Num.percentOf(bucket.amount(), now.total())));
		}
		return out;
	}

	static List<Analysis.ModeShare> byMode(Tally now) {
		List<Map.Entry<PaymentMode, Bucket>> entries = new ArrayList<>(now.byMode().entrySet());
		entries.sort(Comparator.comparing((Map.Entry<PaymentMode, Bucket> e) -> e.getValue().amount())
			.reversed()
			.thenComparing(Map.Entry::getKey));
		List<Analysis.ModeShare> out = new ArrayList<>();
		for (Map.Entry<PaymentMode, Bucket> entry : entries) {
			PaymentMode mode = entry.getKey();
			Bucket bucket = entry.getValue();
			out.add(new Analysis.ModeShare(mode.name(), mode.label(), Money.format(bucket.amount()), bucket.count(),
					Num.percentOf(bucket.amount(), now.total())));
		}
		return out;
	}

	/** One row per day of the period, zero days included, oldest first. */
	static List<Analysis.DayTotal> daily(Tally now, PeriodWindow window) {
		LocalDate start = window.from();
		LocalDate earliest = window.to().minusDays(MAX_DAILY_ROWS - 1L);
		if (start.isBefore(earliest)) {
			start = earliest;
		}
		List<Analysis.DayTotal> out = new ArrayList<>();
		for (LocalDate day = start; !day.isAfter(window.to()); day = day.plusDays(1)) {
			out.add(new Analysis.DayTotal(day.toString(), Money.format(now.byDay().getOrDefault(day, Money.ZERO))));
		}
		return out;
	}

	static List<Analysis.TopItem> topItems(Tally now) {
		List<Analysis.TopItem> out = new ArrayList<>();
		for (ItemGroup item : now.items()) {
			if (out.size() == TOP_ITEMS) {
				break;
			}
			out.add(new Analysis.TopItem(item.key(), item.count(), Money.format(item.total()), item.category().name(),
					item.category().label()));
		}
		return out;
	}

	/** Null when nothing was spent on needs or wants (transfers and unknown spends are left out). */
	static Analysis.NeedsWants needsWants(Tally now) {
		BigDecimal needs = now.flagged(NeedFlag.NEED).amount();
		BigDecimal wants = now.flagged(NeedFlag.WANT).amount();
		BigDecimal both = needs.add(wants);
		if (both.signum() <= 0) {
			return null;
		}
		return new Analysis.NeedsWants(Money.format(needs), Money.format(wants), Num.percentOf(wants, both));
	}

	static Analysis.Runway runway(RunwayCalc.Result runway) {
		if (runway == null) {
			return null;
		}
		return new Analysis.Runway(Money.format(runway.available()), Money.format(runway.dailyBurn()),
				runway.daysLeft(), Money.format(runway.safePerDay()), runway.daysToMonthEnd());
	}

	static Analysis.Forecast forecast(ForecastCalc.Result forecast) {
		if (forecast == null) {
			return null;
		}
		return new Analysis.Forecast(Money.format(forecast.expected()), Money.format(forecast.low()),
				Money.format(forecast.high()));
	}

}
