package com.spendanalyzer.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.spendanalyzer.auth.SessionService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The administrator's numbers. Counts only: every figure is an aggregate computed by PostgreSQL, so
 * no row of any user (email, name, phone, money) is ever read into the application here.
 * <p>
 * Days are calendar days in the app's zone (India). "Last 7 days" is today and the 6 days before
 * it; "last 30 days" is today and the 29 days before it, the same days as the chart.
 */
@Service
public class AdminStatsService {

	static final int CHART_DAYS = 30;

	static final int WEEK_DAYS = 7;

	public record Users(long total, long newToday, long newLast7Days, long newLast30Days) {
	}

	public record Activity(long onlineNow, long activeLast7Days, long activeLast30Days) {
	}

	public record Day(String date, long count) {
	}

	public record Stats(Users users, Activity activity, long waitingForCode, List<Day> signupsLast30Days,
			String generatedAt) {
	}

	private record Counts(long total, long online, long active7, long active30, long waiting) {
	}

	private record DayCount(LocalDate day, long count) {
	}

	private final JdbcClient jdbc;

	private final Clock clock;

	private final ZoneId zone;

	private final SessionService sessions;

	public AdminStatsService(JdbcClient jdbc, Clock clock, @Qualifier("appZone") ZoneId zone,
			SessionService sessions) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.zone = zone;
		this.sessions = sessions;
	}

	public Stats stats() {
		return stats(clock.instant());
	}

	Stats stats(Instant now) {
		LocalDate today = LocalDate.ofInstant(now, zone);
		LocalDate firstDay = today.minusDays(CHART_DAYS - 1);
		Instant monthStart = firstDay.atStartOfDay(zone).toInstant();
		Instant weekStart = today.minusDays(WEEK_DAYS - 1).atStartOfDay(zone).toInstant();
		// "online" = a session the server still accepts (SessionService.authenticate uses the same cutoff)
		Instant onlineSince = now.minus(sessions.idleCutoff());

		// "Active" = added an entry. A session row is deleted minutes after its last use (logout, idle
		// logout, Housekeeping) and no login history is kept, so sessions cannot say who logged in last week.
		// Everything bounded by "now" as well, so a row written a moment later cannot make the numbers disagree.
		Counts counts = jdbc.sql("""
				SELECT
				  (SELECT count(*) FROM users) AS total,
				  (SELECT count(DISTINCT user_id) FROM sessions
				     WHERE last_seen_at > :onlineSince AND last_seen_at <= :now AND expires_at > :now) AS online,
				  a.active7,
				  a.active30,
				  (SELECT count(DISTINCT p.email) FROM pending_registrations p
				     WHERE p.expires_at > :now AND p.created_at <= :now
				       AND NOT EXISTS (SELECT 1 FROM users u WHERE u.email = p.email)) AS waiting
				FROM (SELECT count(DISTINCT user_id) FILTER (WHERE created_at >= :weekStart) AS active7,
				             count(DISTINCT user_id) AS active30
				      FROM entries WHERE created_at >= :monthStart AND created_at <= :now) a""")
			.param("onlineSince", ts(onlineSince))
			.param("now", ts(now))
			.param("weekStart", ts(weekStart))
			.param("monthStart", ts(monthStart))
			.query((rs, n) -> new Counts(rs.getLong("total"), rs.getLong("online"), rs.getLong("active7"),
					rs.getLong("active30"), rs.getLong("waiting")))
			.single();

		// sign-ups per calendar day in the app's zone; days with none are filled in below
		Map<LocalDate, Long> perDay = new HashMap<>();
		jdbc.sql("""
				SELECT CAST(created_at AT TIME ZONE CAST(:zone AS text) AS date) AS day, count(*) AS n
				FROM users
				WHERE created_at >= :monthStart AND created_at <= :now
				GROUP BY 1""")
			.param("zone", zone.getId())
			.param("monthStart", ts(monthStart))
			.param("now", ts(now))
			.query((rs, n) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getLong("n")))
			.list()
			.forEach(row -> perDay.put(row.day(), row.count()));

		List<Day> days = new ArrayList<>(CHART_DAYS);
		long newToday = 0;
		long newWeek = 0;
		long newMonth = 0;
		for (int i = 0; i < CHART_DAYS; i++) {
			LocalDate day = firstDay.plusDays(i);
			long count = perDay.getOrDefault(day, 0L);
			days.add(new Day(day.toString(), count));
			newMonth += count;
			if (i >= CHART_DAYS - WEEK_DAYS) {
				newWeek += count;
			}
			if (day.equals(today)) {
				newToday = count;
			}
		}

		return new Stats(new Users(counts.total(), newToday, newWeek, newMonth),
				new Activity(counts.online(), counts.active7(), counts.active30()), counts.waiting(), List.copyOf(days),
				now.toString());
	}

	private static OffsetDateTime ts(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
