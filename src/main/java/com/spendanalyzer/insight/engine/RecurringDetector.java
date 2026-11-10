package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.spendanalyzer.model.Category;

/**
 * Finds bills that come back at a steady rhythm: the same item, at least three times, about a
 * week, a fortnight or a month apart, for about the same amount (rent, a recharge, a subscription).
 */
final class RecurringDetector {

	/**
	 * @param amount the typical (median) amount
	 * @param intervalDays the typical gap between two payments
	 * @param cadence "every week", "every two weeks" or "every month"
	 * @param nextDue last payment plus the typical gap
	 */
	record Recurring(String key, Category category, int occurrences, BigDecimal amount, int intervalDays,
			String cadence, LocalDate last, LocalDate nextDue) {

		boolean covers(Expense expense) {
			return expense.itemKey().equals(key);
		}

		double perDay() {
			return amount.doubleValue() / intervalDays;
		}

	}

	private RecurringDetector() {
	}

	static List<Recurring> detect(List<Expense> history, LocalDate today) {
		// item -> date -> amount paid that day
		Map<String, TreeMap<LocalDate, BigDecimal>> byItem = new TreeMap<>();
		Map<String, Category> categories = new TreeMap<>();
		for (Expense row : history) {
			byItem.computeIfAbsent(row.itemKey(), key -> new TreeMap<>()).merge(row.date(), row.amount(), BigDecimal::add);
			categories.put(row.itemKey(), row.category()); // history is sorted: the latest row wins
		}
		List<Recurring> found = new ArrayList<>();
		for (Map.Entry<String, TreeMap<LocalDate, BigDecimal>> entry : byItem.entrySet()) {
			Recurring recurring = examine(entry.getKey(), categories.get(entry.getKey()), entry.getValue(), today);
			if (recurring != null) {
				found.add(recurring);
			}
		}
		found.sort(Comparator.comparing(Recurring::amount).reversed().thenComparing(Recurring::key));
		return found;
	}

	private static Recurring examine(String key, Category category, TreeMap<LocalDate, BigDecimal> payments,
			LocalDate today) {
		if (payments.size() < Guardrails.RECURRING_MIN_OCCURRENCES) {
			return null;
		}
		List<LocalDate> dates = new ArrayList<>(payments.keySet());
		double[] gaps = new double[dates.size() - 1];
		for (int i = 1; i < dates.size(); i++) {
			gaps[i - 1] = ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
		}
		double gap = Stats.median(gaps);
		String cadence = cadence(gap);
		if (cadence == null || Stats.mad(gaps) / gap > 0.2) {
			return null; // no known rhythm, or too irregular
		}
		double[] amounts = Num.doubles(new ArrayList<>(payments.values()));
		double typical = Stats.median(amounts);
		if (typical <= 0 || Stats.mad(amounts) / typical > 0.15) {
			return null; // the amount varies too much to call it one bill
		}
		int interval = (int) Math.round(gap);
		LocalDate last = dates.get(dates.size() - 1);
		LocalDate nextDue = last.plusDays(interval);
		if (ChronoUnit.DAYS.between(nextDue, today) > interval / 2) {
			return null; // long overdue: the pattern has most likely stopped
		}
		return new Recurring(key, category, dates.size(), Num.money(typical), interval, cadence, last, nextDue);
	}

	private static String cadence(double gapDays) {
		if (gapDays >= 6 && gapDays <= 8) {
			return "every week";
		}
		if (gapDays >= 13 && gapDays <= 16) {
			return "every two weeks";
		}
		if (gapDays >= 27 && gapDays <= 33) {
			return "every month";
		}
		return null;
	}

}
