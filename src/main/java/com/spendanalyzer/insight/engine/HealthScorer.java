package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.spendanalyzer.insight.engine.AnomalyDetector.Anomaly;
import com.spendanalyzer.insight.engine.Tally.Bucket;
import com.spendanalyzer.insight.engine.Tally.ItemGroup;
import com.spendanalyzer.model.NeedFlag;

/**
 * The 0-100 health score: six transparent parts, each a simple formula on the last 30 days.
 * <pre>
 * Runway       30  days the money lasts / 30
 * Needs/wants  20  (0.60 - share of wants) / 0.30
 * Trend        15  (0.25 - change against the 30 days before) / 0.35
 * Spikes       15  1 - share of unusual spends / 0.25
 * Leaks        10  1 - share of small repeat buys / 0.20
 * Tracking     10  0.6 x (days logged in the last 14 / 10) + 0.4 x (1 - share needing review)
 * </pre>
 * A part without enough data is left out and the rest is rescaled to 100. The score is the sum of
 * the points shown, so the user can check it. Only ratios go in: multiplying every amount by ten
 * gives the same score.
 */
final class HealthScorer {

	private record Part(String name, int weight, double value, String note) {
	}

	private HealthScorer() {
	}

	/** @return null when the history is too thin for any score */
	static Analysis.Health score(LedgerSnapshot in, RunwayCalc.Result runway, AnomalyDetector.Result anomalies) {
		List<Expense> history = in.history();
		if (history.size() < Guardrails.HEALTH_MIN_EXPENSES) {
			return null;
		}
		LocalDate today = in.today();
		LocalDate firstDay = history.get(0).date();
		List<Expense> recentRows = Tally.between(history, today.minusDays(29), today);
		Tally recent = Tally.of(recentRows);

		List<Part> parts = new ArrayList<>();
		addIfKnown(parts, runway(runway));
		addIfKnown(parts, needsWants(recent));
		addIfKnown(parts, trend(history, recent, today, firstDay));
		addIfKnown(parts, spikes(anomalies, recent, today));
		addIfKnown(parts, leaks(recent));
		parts.add(tracking(history, recent, today));

		int[] max = rescale(parts);
		List<Analysis.Component> components = new ArrayList<>();
		int score = 0;
		for (int i = 0; i < parts.size(); i++) {
			Part part = parts.get(i);
			int points = (int) Math.round(Num.clamp01(part.value()) * max[i]);
			score += points;
			components.add(new Analysis.Component(part.name(), points, max[i], part.note()));
		}
		long daysOfHistory = ChronoUnit.DAYS.between(firstDay, today) + 1;
		boolean provisional = daysOfHistory < Guardrails.HEALTH_FIRM_DAYS
				|| history.size() < Guardrails.HEALTH_FIRM_EXPENSES || parts.size() < 3;
		return new Analysis.Health(score, band(score), provisional, components);
	}

	static String band(int score) {
		if (score >= 80) {
			return "Excellent";
		}
		if (score >= 60) {
			return "Good";
		}
		return score >= 40 ? "Fair" : "Needs attention";
	}

	private static void addIfKnown(List<Part> parts, Part part) {
		if (part != null) {
			parts.add(part);
		}
	}

	private static Part runway(RunwayCalc.Result runway) {
		if (runway == null) {
			return null;
		}
		String note;
		if (runway.capped()) {
			note = "Money lasts more than a year at this pace";
		}
		else if (runway.daysLeft() == 0) {
			note = "Less than a day of spending left in bank and cash";
		}
		else {
			note = "Money lasts about " + runway.daysLeft() + Wording.plural(runway.daysLeft(), " day", " days");
		}
		return new Part("Runway", 30, runway.daysLeft() / 30.0, note);
	}

	private static Part needsWants(Tally recent) {
		Bucket needs = recent.flagged(NeedFlag.NEED);
		Bucket wants = recent.flagged(NeedFlag.WANT);
		BigDecimal base = needs.amount().add(wants.amount());
		int count = needs.count() + wants.count();
		if (count < Guardrails.MIN_EXPENSES || base.signum() <= 0) {
			return null;
		}
		double share = Num.ratio(wants.amount(), base);
		String note;
		if (wants.amount().signum() == 0) {
			note = "Nothing spent on wants in the last 30 days";
		}
		else if (Guardrails.percentAllowed(base, count)) {
			// transfers and unknown spends are outside the split, so be exact about the base
			String of = base.compareTo(recent.total()) == 0 ? "your spending" : "your spending on needs and wants";
			note = "Wants are " + Math.round(share * 100) + "% of " + of;
		}
		else {
			int balance = wants.amount().compareTo(needs.amount());
			note = balance > 0 ? "Wants outweigh needs so far"
					: balance < 0 ? "Needs outweigh wants so far" : "Needs and wants are level so far";
		}
		return new Part("Needs/wants", 20, (0.60 - share) / 0.30, note);
	}

	private static Part trend(List<Expense> history, Tally recent, LocalDate today, LocalDate firstDay) {
		Tally before = Tally.of(Tally.between(history, today.minusDays(59), today.minusDays(30)));
		// The 30 days before must have been tracked (almost) from their start, or the change is fake.
		boolean tracked = !firstDay.isAfter(today.minusDays(59).plusDays((long) (30 * Guardrails.TRACKING_SLACK)));
		if (!tracked || recent.count() < Guardrails.MIN_EXPENSES || before.count() < Guardrails.MIN_EXPENSES
				|| before.total().signum() <= 0) {
			return null;
		}
		double change = Num.ratio(recent.total().subtract(before.total()), before.total());
		String note;
		long percent = Math.round(Math.abs(change) * 100);
		if (percent < 5) {
			note = "Spending is about the same as the 30 days before";
		}
		else if (Guardrails.percentAllowed(before.total(), before.count())) {
			note = "Spending is " + percent + "% " + (change > 0 ? "higher" : "lower") + " than the 30 days before";
		}
		else {
			note = "Spending is " + (change > 0 ? "higher" : "lower") + " than the 30 days before";
		}
		return new Part("Trend", 15, (0.25 - change) / 0.35, note);
	}

	private static Part spikes(AnomalyDetector.Result anomalies, Tally recent, LocalDate today) {
		if (!anomalies.testable() || recent.total().signum() <= 0) {
			return null;
		}
		BigDecimal unusual = BigDecimal.ZERO;
		int count = 0;
		for (Anomaly anomaly : anomalies.anomalies()) {
			if (!anomaly.expense().date().isBefore(today.minusDays(29))) {
				unusual = unusual.add(anomaly.expense().amount());
				count++;
			}
		}
		double share = Num.ratio(unusual, recent.total());
		String note = count == 0 ? "No unusually large spends in the last 30 days"
				: count + Wording.plural(count, " unusually large spend", " unusually large spends")
						+ " in the last 30 days";
		return new Part("Spikes", 15, 1 - share / 0.25, note);
	}

	private static Part leaks(Tally recent) {
		if (recent.count() < Guardrails.MIN_EXPENSES || recent.total().signum() <= 0) {
			return null;
		}
		List<ItemGroup> leaks = LeakDetector.detect(recent.rows(), false);
		BigDecimal leaked = BigDecimal.ZERO;
		for (ItemGroup leak : leaks) {
			leaked = leaked.add(leak.total());
		}
		double share = Num.ratio(leaked, recent.total());
		String note;
		if (leaks.isEmpty()) {
			note = "No repeat buys of wants adding up";
		}
		else if (Guardrails.percentAllowed(recent.total(), recent.count())) {
			note = "Repeat buys of wants are " + Math.round(share * 100) + "% of your spending";
		}
		else {
			note = "Some repeat buys of wants are adding up";
		}
		return new Part("Leaks", 10, 1 - share / 0.20, note);
	}

	private static Part tracking(List<Expense> history, Tally recent, LocalDate today) {
		Set<LocalDate> activeDays = new HashSet<>();
		for (Expense row : Tally.between(history, today.minusDays(13), today)) {
			activeDays.add(row.date());
		}
		int toReview = recent.needsReview().count();
		double reviewShare = recent.count() == 0 ? 0 : (double) toReview / recent.count();
		double value = 0.6 * Math.min(1, activeDays.size() / 10.0) + 0.4 * (1 - reviewShare);
		String note = "Spends logged on " + activeDays.size() + " of the last 14 days";
		if (toReview > 0) {
			note += ", " + toReview + Wording.plural(toReview, " needs", " need") + " a category check";
		}
		return new Part("Tracking", 10, value, note);
	}

	/** Shares 100 points between the parts in proportion to their weights (largest remainder). */
	private static int[] rescale(List<Part> parts) {
		int totalWeight = 0;
		for (Part part : parts) {
			totalWeight += part.weight();
		}
		int[] max = new int[parts.size()];
		double[] remainder = new double[parts.size()];
		int given = 0;
		for (int i = 0; i < max.length; i++) {
			double exact = parts.get(i).weight() * 100.0 / totalWeight;
			max[i] = (int) Math.floor(exact);
			remainder[i] = exact - max[i];
			given += max[i];
		}
		while (given < 100) {
			int best = 0;
			for (int i = 1; i < max.length; i++) {
				if (remainder[i] > remainder[best]) {
					best = i;
				}
			}
			max[best]++;
			remainder[best] = -1;
			given++;
		}
		return max;
	}

}
