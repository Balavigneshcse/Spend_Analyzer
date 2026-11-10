package com.spendanalyzer.insight.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class PeriodWindowTest {

	private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

	private static LocalDate date(String text) {
		return LocalDate.parse(text);
	}

	private static PeriodWindow resolve(PeriodCode code, String today) {
		return PeriodWindow.resolve(code, date(today), null);
	}

	@Test
	void thisWeekRunsFromMondayToToday() {
		// 10 Nov 2026 is a Tuesday
		PeriodWindow week = resolve(PeriodCode.THIS_WEEK, "2026-11-10");
		assertThat(week.from()).isEqualTo(date("2026-11-09"));
		assertThat(week.to()).isEqualTo(date("2026-11-10"));
		assertThat(week.days()).isEqualTo(2);
		assertThat(week.previousFrom()).isEqualTo(date("2026-11-02"));
		assertThat(week.previousTo()).isEqualTo(date("2026-11-03"));
	}

	@Test
	void thisWeekOnAMondayIsOneDay() {
		PeriodWindow week = resolve(PeriodCode.THIS_WEEK, "2026-11-09");
		assertThat(week.from()).isEqualTo(week.to());
		assertThat(week.days()).isEqualTo(1);
		assertThat(week.previousFrom()).isEqualTo(date("2026-11-02"));
		assertThat(week.previousTo()).isEqualTo(date("2026-11-02"));
	}

	@Test
	void thisMonthComparesWithTheSameDaysOfLastMonth() {
		PeriodWindow month = resolve(PeriodCode.THIS_MONTH, "2026-11-10");
		assertThat(month.from()).isEqualTo(date("2026-11-01"));
		assertThat(month.to()).isEqualTo(date("2026-11-10"));
		assertThat(month.days()).isEqualTo(10);
		assertThat(month.previousFrom()).isEqualTo(date("2026-10-01"));
		assertThat(month.previousTo()).isEqualTo(date("2026-10-10"));
	}

	@Test
	void thisMonthOnThe31stCapsAtTheEndOfAShorterMonth() {
		PeriodWindow month = resolve(PeriodCode.THIS_MONTH, "2026-03-31");
		assertThat(month.previousFrom()).isEqualTo(date("2026-02-01"));
		assertThat(month.previousTo()).isEqualTo(date("2026-02-28"));
	}

	@Test
	void lastMonthIsTheWholePreviousMonth() {
		PeriodWindow month = resolve(PeriodCode.LAST_MONTH, "2026-11-10");
		assertThat(month.from()).isEqualTo(date("2026-10-01"));
		assertThat(month.to()).isEqualTo(date("2026-10-31"));
		assertThat(month.days()).isEqualTo(31);
		assertThat(month.previousFrom()).isEqualTo(date("2026-09-01"));
		assertThat(month.previousTo()).isEqualTo(date("2026-09-30"));
	}

	@Test
	void lastMonthInJanuaryGoesBackAYear() {
		PeriodWindow month = resolve(PeriodCode.LAST_MONTH, "2027-01-05");
		assertThat(month.from()).isEqualTo(date("2026-12-01"));
		assertThat(month.to()).isEqualTo(date("2026-12-31"));
		assertThat(month.previousFrom()).isEqualTo(date("2026-11-01"));
	}

	@Test
	void last30DaysIncludesToday() {
		PeriodWindow window = resolve(PeriodCode.LAST_30_DAYS, "2026-11-10");
		assertThat(window.from()).isEqualTo(date("2026-10-12"));
		assertThat(window.to()).isEqualTo(date("2026-11-10"));
		assertThat(window.days()).isEqualTo(30);
		assertThat(window.previousFrom()).isEqualTo(date("2026-09-12"));
		assertThat(window.previousTo()).isEqualTo(date("2026-10-11"));
		assertThat(window.previousDays()).isEqualTo(30);
	}

	@Test
	void allTimeStartsAtTheFirstExpenseAndHasNothingToCompareWith() {
		PeriodWindow all = PeriodWindow.resolve(PeriodCode.ALL_TIME, date("2026-11-10"), date("2026-08-03"));
		assertThat(all.from()).isEqualTo(date("2026-08-03"));
		assertThat(all.to()).isEqualTo(date("2026-11-10"));
		assertThat(all.days()).isEqualTo(100);
		assertThat(all.hasPrevious()).isFalse();
	}

	@Test
	void allTimeWithoutExpensesIsJustToday() {
		PeriodWindow all = resolve(PeriodCode.ALL_TIME, "2026-11-10");
		assertThat(all.from()).isEqualTo(date("2026-11-10"));
		assertThat(all.days()).isEqualTo(1);
	}

	@Test
	void todayComesFromIndiaNotFromTheServerZone() {
		TimeZone original = TimeZone.getDefault();
		try {
			// A server running in UTC, half past midnight in India on 1 December.
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			Clock clock = Clock.fixed(Instant.parse("2026-11-30T19:00:00Z"), ZoneOffset.UTC);
			assertThat(LocalDate.now(clock)).isEqualTo(date("2026-11-30")); // what the server zone would say

			LocalDate today = PeriodWindow.today(clock, INDIA);
			assertThat(today).isEqualTo(date("2026-12-01"));
			PeriodWindow month = PeriodWindow.resolve(PeriodCode.THIS_MONTH, today, null);
			assertThat(month.from()).isEqualTo(date("2026-12-01"));
			assertThat(month.to()).isEqualTo(date("2026-12-01"));
			assertThat(month.previousFrom()).isEqualTo(date("2026-11-01"));
			PeriodWindow lastMonth = PeriodWindow.resolve(PeriodCode.LAST_MONTH, today, null);
			assertThat(lastMonth.from()).isEqualTo(date("2026-11-01"));
			assertThat(lastMonth.to()).isEqualTo(date("2026-11-30"));
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	void parsesPeriodCodes() {
		assertThat(PeriodCode.parse("THIS_WEEK")).contains(PeriodCode.THIS_WEEK);
		assertThat(PeriodCode.parse(" last_30_days ")).contains(PeriodCode.LAST_30_DAYS);
		assertThat(PeriodCode.parse("YESTERDAY")).isEmpty();
		assertThat(PeriodCode.parse(null)).isEmpty();
	}

}
