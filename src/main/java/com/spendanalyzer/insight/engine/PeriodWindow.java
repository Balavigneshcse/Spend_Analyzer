package com.spendanalyzer.insight.engine;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * The dates of a period (inclusive) and of the like-for-like period before it.
 * {@code previousFrom}/{@code previousTo} are null when there is nothing to compare with (ALL_TIME).
 */
public record PeriodWindow(PeriodCode code, LocalDate from, LocalDate to, LocalDate previousFrom,
		LocalDate previousTo) {

	/** "Today" in the application's zone. The server's own zone must never decide the date. */
	public static LocalDate today(Clock clock, ZoneId zone) {
		return LocalDate.ofInstant(clock.instant(), zone);
	}

	/**
	 * @param firstExpense the date of the user's first expense; only ALL_TIME uses it (null = none)
	 */
	public static PeriodWindow resolve(PeriodCode code, LocalDate today, LocalDate firstExpense) {
		switch (code) {
			case THIS_WEEK: {
				// Monday to today, against last Monday to the same weekday.
				LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				return new PeriodWindow(code, monday, today, monday.minusWeeks(1), today.minusWeeks(1));
			}
			case THIS_MONTH: {
				// 1st to today, against the 1st to the same day of last month (a shorter month caps it).
				LocalDate first = today.withDayOfMonth(1);
				LocalDate previousFirst = first.minusMonths(1);
				int day = Math.min(today.getDayOfMonth(), previousFirst.lengthOfMonth());
				return new PeriodWindow(code, first, today, previousFirst, previousFirst.withDayOfMonth(day));
			}
			case LAST_MONTH: {
				LocalDate first = today.withDayOfMonth(1).minusMonths(1);
				LocalDate previousFirst = first.minusMonths(1);
				return new PeriodWindow(code, first, first.with(TemporalAdjusters.lastDayOfMonth()), previousFirst,
						previousFirst.with(TemporalAdjusters.lastDayOfMonth()));
			}
			case LAST_30_DAYS:
				return new PeriodWindow(code, today.minusDays(29), today, today.minusDays(59), today.minusDays(30));
			default: {
				LocalDate start = firstExpense != null && !firstExpense.isAfter(today) ? firstExpense : today;
				return new PeriodWindow(code, start, today, null, null);
			}
		}
	}

	public boolean hasPrevious() {
		return previousFrom != null && previousTo != null;
	}

	/** Number of calendar days in the period, both ends included. */
	public int days() {
		return (int) ChronoUnit.DAYS.between(from, to) + 1;
	}

	public int previousDays() {
		return hasPrevious() ? (int) ChronoUnit.DAYS.between(previousFrom, previousTo) + 1 : 0;
	}

	public boolean contains(LocalDate date) {
		return !date.isBefore(from) && !date.isAfter(to);
	}

	public boolean previousContains(LocalDate date) {
		return hasPrevious() && !date.isBefore(previousFrom) && !date.isAfter(previousTo);
	}

}
