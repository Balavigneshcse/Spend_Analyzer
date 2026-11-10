package com.spendanalyzer.insight.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import com.spendanalyzer.insight.engine.AnomalyDetector.Anomaly;
import com.spendanalyzer.insight.engine.Finding.TipDraft;
import com.spendanalyzer.insight.engine.RecurringDetector.Recurring;

/**
 * Detectors about the user's situation today: runway, month-end forecast, recurring bills, the
 * weekend pattern, the credit card due and money owed to friends. They use the recent history, the
 * current balances and the current debts, so they are only shown for periods that run up to today.
 */
final class TodayFindings {

	/** A sentence that lists more friends than this stops being readable; the rest are counted. */
	static final int MAX_FRIENDS_NAMED = 3;

	private TodayFindings() {
	}

	static void addAll(Picture p, List<Finding> out) {
		runway(p, out);
		forecast(p, out);
		recurring(p, out);
		weekPattern(p, out);
		creditCard(p, out);
		borrowed(p, out);
	}

	// ------------------------------------------------------------------ runway

	private static void runway(Picture p, List<Finding> out) {
		RunwayCalc.Result runway = p.runway();
		if (runway == null) {
			return;
		}
		int days = runway.daysLeft();
		Facts facts = Facts.of()
			.about("burn", runway.dailyBurn())
			.rupees("available", runway.available())
			.count("days", days)
			.text("dayWord", Wording.plural(days, "day", "days"));
		String lasts = "At about {burn} a day, your {available} in bank and cash lasts about {days} {dayWord}, "
				+ "assuming no new money comes in.";
		Severity severity;
		String title;
		String text = lasts;
		if (runway.available().signum() <= 0) {
			severity = Severity.ALERT;
			title = "Bank and cash are used up";
			text = "Your bank and cash balances are used up, and you are spending about {burn} a day.";
		}
		else if (days == 0) {
			severity = Severity.ALERT;
			title = "Less than a day of money left";
			text = "Your {available} in bank and cash is less than one day of spending at about {burn} a day.";
		}
		else if (days < Guardrails.RUNWAY_ALERT_DAYS) {
			severity = Severity.ALERT;
			title = "Money is running low";
		}
		else if (days < Guardrails.RUNWAY_WARN_DAYS) {
			severity = Severity.WARN;
			title = "Money is getting tight";
		}
		else if (days < Guardrails.RUNWAY_COMFORT_DAYS) {
			severity = Severity.INFO;
			title = "Money lasts about {days} days";
		}
		else {
			severity = Severity.GOOD;
			title = "Your money has room";
			if (runway.capped()) {
				text = "At about {burn} a day, your {available} in bank and cash lasts more than a year, "
						+ "assuming no new money comes in.";
			}
		}
		double impact = severity == Severity.INFO ? 0.5 : 1;
		out.add(Finding.of("runway", InsightType.RUNWAY, severity, title, text, facts, impact, 1, runwayTip(runway)));
	}

	/** When the money would not reach the end of the month: the daily amount that makes it last. */
	private static TipDraft runwayTip(RunwayCalc.Result runway) {
		BigDecimal safe = runway.safePerDay().setScale(0, RoundingMode.DOWN);
		if (runway.daysToMonthEnd() < 1 || runway.daysLeft() >= runway.daysToMonthEnd() || safe.signum() <= 0) {
			return null;
		}
		Facts facts = Facts.of()
			.rupees("available", runway.available())
			.date("monthEnd", runway.monthEnd())
			.rupees("safe", safe);
		return TipDraft.of("To make {available} last till {monthEnd}, keep daily spend under {safe}.", null, facts);
	}

	// ------------------------------------------------------------------ month-end forecast

	private static void forecast(Picture p, List<Finding> out) {
		ForecastCalc.Result forecast = p.forecast();
		LocalDate today = p.in().today();
		if (forecast == null || forecast.monthEnd().equals(today)) {
			return; // on the last day of the month there is nothing left to forecast
		}
		Facts facts = Facts.of()
			.rupees("expected", forecast.expected())
			.rupees("low", forecast.low())
			.rupees("high", forecast.high())
			.date("monthEnd", forecast.monthEnd());
		// hedge while neither of the two entry rules is met twice over
		boolean early = today.getDayOfMonth() < 2 * Guardrails.FORECAST_MIN_DAY_OF_MONTH
				&& Guardrails.nearMinimum(forecast.expensesThisMonth(), Guardrails.FORECAST_MIN_EXPENSES);
		String text = (early ? "Early estimate: at this pace" : "At this pace")
				+ " you will have spent about {expected} by {monthEnd}";
		text += forecast.low().compareTo(forecast.high()) < 0 ? ", likely between {low} and {high}." : ".";

		Severity severity = Severity.INFO;
		Tally lastMonth = lastMonth(p, today);
		if (lastMonth != null) {
			facts.rupees("lastMonth", lastMonth.total());
			text += " Last month you spent {lastMonth}.";
			if (Num.ratio(forecast.expected(), lastMonth.total()) > 1.15) {
				severity = Severity.WARN;
			}
		}
		out.add(Finding.of("forecast", InsightType.FORECAST, severity, "Month-end forecast", text, facts, 0.6,
				Finding.confidence(forecast.expensesThisMonth(), Guardrails.FORECAST_MIN_EXPENSES), null));
	}

	/** Last calendar month, when it was tracked from (about) its start and is big enough to compare with. */
	private static Tally lastMonth(Picture p, LocalDate today) {
		LocalDate first = today.withDayOfMonth(1).minusMonths(1);
		LocalDate since = p.in().trackingSince();
		long slack = (long) (first.lengthOfMonth() * Guardrails.TRACKING_SLACK);
		if (since == null || since.isAfter(first.plusDays(slack))) {
			return null;
		}
		Tally month = Tally.of(Tally.between(p.in().history(), first, first.with(TemporalAdjusters.lastDayOfMonth())));
		return Guardrails.percentAllowed(month.total(), month.count()) ? month : null;
	}

	// ------------------------------------------------------------------ recurring bills

	private static void recurring(Picture p, List<Finding> out) {
		LocalDate today = p.in().today();
		for (Recurring bill : p.recurring()) { // largest first
			if (bill.amount().compareTo(Guardrails.RECURRING_MIN_AMOUNT) < 0) {
				continue;
			}
			Facts facts = Facts.of()
				.text("item", bill.key())
				.text("Item", Wording.capitalise(bill.key()))
				.rupees("amount", bill.amount())
				.count("count", bill.occurrences())
				.text("cadence", bill.cadence())
				.date("next", bill.nextDue());
			boolean upcoming = !bill.nextDue().isBefore(today);
			String text = "{Item} comes back {cadence}: {amount} each time, {count} times in the last four months.";
			if (upcoming) {
				text += " The next one may fall around {next}.";
			}
			TipDraft tip = null;
			// a weekly habit needs no reminder; a fortnightly or monthly bill that is close does
			if (upcoming && bill.intervalDays() >= 13 && ChronoUnit.DAYS.between(today, bill.nextDue()) <= 10) {
				tip = TipDraft.of("Keep {amount} aside for {item} around {next}.", null, facts);
			}
			out.add(Finding.of("recurring-" + Wording.slug(bill.key()), InsightType.RECURRING, Severity.INFO,
					"{Item} repeats {cadence}", text, facts, Finding.impact(bill.amount(), p.now().total()),
					Finding.confidence(bill.occurrences(), Guardrails.RECURRING_MIN_OCCURRENCES), tip));
		}
	}

	// ------------------------------------------------------------------ weekend against weekdays

	private static void weekPattern(Picture p, List<Finding> out) {
		List<Expense> history = p.in().history();
		if (history.isEmpty()) {
			return;
		}
		LocalDate thisMonday = p.in().today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		LocalDate firstDay = history.get(0).date();
		// Full Monday-to-Sunday weeks before this week, as long as tracking had started.
		int weeks = 0;
		while (weeks < Guardrails.WEEK_PATTERN_MAX_WEEKS && !thisMonday.minusWeeks(weeks + 1L).isBefore(firstDay)) {
			weeks++;
		}
		if (weeks < Guardrails.WEEK_PATTERN_MIN_WEEKS) {
			return;
		}
		LocalDate start = thisMonday.minusWeeks(weeks);
		LocalDate end = thisMonday.minusDays(1);
		// Real day totals of everyday spending: a bill or a one-off purchase is not a weekend habit.
		List<Expense> everyday = new ArrayList<>();
		boolean leftOut = false;
		for (Expense row : Tally.between(history, start, end)) {
			if (BurnModel.isBill(row, p.recurring()) || isUnusual(row, p)) {
				leftOut = true;
			}
			else {
				everyday.add(row);
			}
		}
		double[] daily = BurnModel.dayTotals(everyday, start, end, List.of());
		double weekendSum = 0;
		double weekdaySum = 0;
		int weekendHeavier = 0;
		int weekdayHeavier = 0;
		for (int week = 0; week < weeks; week++) {
			double weekdays = 0;
			for (int day = 0; day < 5; day++) {
				weekdays += daily[week * 7 + day];
			}
			double weekend = daily[week * 7 + 5] + daily[week * 7 + 6];
			weekdaySum += weekdays;
			weekendSum += weekend;
			if (weekend / 2 > weekdays / 5) {
				weekendHeavier++;
			}
			else if (weekend / 2 < weekdays / 5) {
				weekdayHeavier++;
			}
		}
		double weekendDay = weekendSum / (2 * weeks);
		double weekday = weekdaySum / (5 * weeks);
		if (!(weekendDay > 0) || !(weekday > 0)) {
			return;
		}
		double ratio = weekendDay / weekday;
		int mostWeeks = (int) Math.ceil(0.75 * weeks);
		boolean weekendCostsMore = ratio >= 1.4 && weekendHeavier >= mostWeeks;
		boolean weekdaysCostMore = ratio <= 0.7 && weekdayHeavier >= mostWeeks;
		if (!weekendCostsMore && !weekdaysCostMore) {
			return;
		}
		Facts facts = Facts.of()
			.count("weeks", weeks)
			.about("weekend", Num.money(weekendDay))
			.about("weekday", Num.money(weekday));
		String text = (leftOut ? "Leaving out bills and one-off spends, over" : "Over")
				+ " the last {weeks} full weeks you spent about {weekend} a day on weekends and {weekday} a day on weekdays.";
		TipDraft tip = null;
		BigDecimal monthly = Num.nearestTen(Num.money(Math.abs(weekendDay - weekday) * 8));
		if (weekendCostsMore && monthly.signum() > 0) {
			Facts tipFacts = Facts.of().rupees("saving", monthly);
			tip = TipDraft.of("Matching your weekday pace on weekends would save about {saving} a month.", monthly,
					tipFacts);
		}
		out.add(Finding.of("week-pattern", InsightType.WEEK_PATTERN, Severity.INFO,
				weekendCostsMore ? "Weekends cost more" : "Weekdays cost more", text, facts,
				Finding.impact(monthly, p.now().total()), Finding.confidence(weeks, Guardrails.WEEK_PATTERN_MIN_WEEKS),
				tip));
	}

	private static boolean isUnusual(Expense row, Picture p) {
		for (Anomaly anomaly : p.anomalies().anomalies()) {
			if (anomaly.expense().equals(row)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ credit card

	private static void creditCard(Picture p, List<Finding> out) {
		BigDecimal due = p.in().balances().creditCardDue();
		BigDecimal available = p.in().balances().available();
		if (due.signum() <= 0) {
			return;
		}
		Facts facts = Facts.of().rupees("due", due).rupees("available", available);
		Severity severity;
		String text;
		if (available.signum() <= 0) {
			severity = Severity.ALERT;
			text = "You owe {due} on your credit card and have no money in bank or cash to pay it.";
		}
		else if (due.compareTo(available) > 0) {
			severity = Severity.ALERT;
			text = "Your credit card due of {due} is more than the {available} you have in bank and cash.";
		}
		else if (due.multiply(BigDecimal.valueOf(2)).compareTo(available) > 0) {
			severity = Severity.WARN;
			text = "Your credit card due of {due} is more than half of the {available} you have in bank and cash.";
		}
		else {
			severity = Severity.INFO;
			text = "You owe {due} on your credit card, against {available} in bank and cash.";
		}
		TipDraft tip = severity == Severity.INFO ? null
				: TipDraft.of("Set aside {due} for the credit card bill before other spending.", null, facts);
		out.add(Finding.of("credit-card", InsightType.CREDIT_CARD, severity, "Credit card due", text, facts,
				severity == Severity.INFO ? 0.4 : 1, 1, tip));
	}

	// ------------------------------------------------------------------ money owed to friends

	/**
	 * The total, the friends owed the most, and what would be left after paying everyone back.
	 * A friend's name is the user's own text: it only ever enters the sentence as the value of a
	 * slot (never as part of the template), so whatever it contains stays plain text.
	 */
	private static void borrowed(Picture p, List<Finding> out) {
		List<Debt> debts = p.in().debts(); // only friends still owed money, the largest debt first
		if (debts.isEmpty()) {
			return;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (Debt debt : debts) {
			total = total.add(debt.owed());
		}
		BigDecimal available = p.in().balances().available();
		Facts facts = Facts.of().rupees("total", total).rupees("available", available);

		StringBuilder text = new StringBuilder();
		boolean oneFriend = debts.size() == 1;
		if (oneFriend) {
			facts.text("name0", debts.get(0).name());
			text.append("You owe {name0} {total}.");
		}
		else {
			text.append("You owe friends {total} in total: ");
			int named = Math.min(debts.size(), MAX_FRIENDS_NAMED);
			for (int i = 0; i < named; i++) {
				facts.text("name" + i, debts.get(i).name()).rupees("owed" + i, debts.get(i).owed());
				text.append(i == 0 ? "" : ", ").append("{name").append(i).append("} {owed").append(i).append('}');
			}
			if (debts.size() > named) {
				facts.count("more", debts.size() - named);
				text.append(" and {more} more");
			}
			text.append('.');
		}

		Severity severity;
		if (available.signum() <= 0) {
			severity = Severity.WARN;
			text.append(" You have no money in bank or cash to pay it back.");
		}
		else if (total.compareTo(available) > 0) {
			severity = Severity.WARN;
			text.append(" That is more than the {available} you have in bank and cash.");
		}
		else {
			severity = Severity.INFO;
			facts.rupees("left", available.subtract(total));
			text.append(oneFriend ? " After paying it back" : " After paying everyone back")
				.append(" you would have {left} in bank and cash.");
		}
		out.add(Finding.of("borrowed", InsightType.BORROWED, severity, "Money owed to friends", text.toString(), facts,
				severity == Severity.INFO ? 0.4 : 1, 1, null));
	}

}
