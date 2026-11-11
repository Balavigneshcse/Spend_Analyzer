package com.spendanalyzer.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.spendanalyzer.auth.SecureTokens;
import com.spendanalyzer.auth.SessionService;
import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.IntegrationTest;
import com.spendanalyzer.testsupport.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The administrator: the admin flag of GET /api/auth/me and GET /api/admin/stats. The exact
 * numbers are checked on a fixture far in the future (2099), where no row of any other test can
 * fall into the windows; the HTTP checks use the real clock and compare before and after.
 */
@IntegrationTest
class AdminStatsIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 10:00 in India on 15 March 2099. */
	private static final Instant NOW = Instant.parse("2099-03-15T04:30:00Z");

	private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	@Autowired
	@Qualifier("appZone")
	ZoneId appZone;

	@Autowired
	SecureTokens tokens;

	@Autowired
	AdminStatsService service;

	private final List<UUID> users = new ArrayList<>();

	private final List<UUID> pending = new ArrayList<>();

	/** Every email, name and phone the fixture wrote, to make sure none of them is ever sent. */
	private final List<String> personal = new ArrayList<>();

	@AfterEach
	void removeFixture() {
		// sessions and entries go with their user (ON DELETE CASCADE)
		for (UUID id : users) {
			jdbc.sql("DELETE FROM users WHERE id = ?").param(id).update();
		}
		for (UUID id : pending) {
			jdbc.sql("DELETE FROM pending_registrations WHERE id = ?").param(id).update();
		}
	}

	// ------------------------------------------------------------------ fixture, straight into the tables

	private static OffsetDateTime ts(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static Instant india(String localDateTime) {
		return java.time.LocalDateTime.parse(localDateTime).atZone(INDIA).toInstant();
	}

	private static String uniqueEmail(String who) {
		return who + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private static String randomPhone() {
		return "+919" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
	}

	private UUID user(String who, Instant createdAt) {
		UUID id = UUID.randomUUID();
		String email = uniqueEmail(who);
		String name = "Fixture " + who + " " + id.toString().substring(0, 6);
		String phone = randomPhone();
		jdbc.sql("INSERT INTO users (id, email, name, phone, password_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)")
			.params(id, email, name, phone, "{noop}not-a-real-hash", ts(createdAt))
			.update();
		users.add(id);
		personal.add(email);
		personal.add(name);
		personal.add(phone);
		personal.add(phone.substring(phone.length() - 4));
		personal.add(id.toString());
		return id;
	}

	private String emailOf(UUID userId) {
		return jdbc.sql("SELECT email FROM users WHERE id = ?").param(userId).query(String.class).single();
	}

	private void session(UUID userId, Instant lastSeenAt, Instant expiresAt) {
		byte[] hash = new byte[32];
		ThreadLocalRandom.current().nextBytes(hash);
		jdbc.sql("""
				INSERT INTO sessions (id, user_id, token_hash, created_at, last_seen_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?)""")
			.params(UUID.randomUUID(), userId, hash, ts(lastSeenAt.minus(Duration.ofHours(1))), ts(lastSeenAt),
					ts(expiresAt))
			.update();
	}

	private void entry(UUID userId, Instant createdAt) {
		jdbc.sql("""
				INSERT INTO entries (id, user_id, client_id, type, amount, account, entry_date, created_at, updated_at)
				VALUES (?, ?, ?, 'INCOME', 100.00, 'BANK', ?, ?, ?)""")
			.params(UUID.randomUUID(), userId, UUID.randomUUID(), LocalDate.ofInstant(createdAt, INDIA), ts(createdAt),
					ts(createdAt))
			.update();
	}

	private void pendingRegistration(String email, Instant createdAt, Instant expiresAt) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO pending_registrations (id, email, name, phone, password_hash, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)""")
			.params(id, email, "Pending Person " + id.toString().substring(0, 6), randomPhone(), "{noop}x",
					ts(createdAt), ts(expiresAt))
			.update();
		pending.add(id);
		personal.add(email);
	}

	private long allUsers() {
		return jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
	}

	/** The administrator of IntegrationTest.ADMIN_EMAILS, created once per test run. */
	private AuthUser admin() {
		jdbc.sql("""
				INSERT INTO users (id, email, name, phone, password_hash) VALUES (?, ?, 'Stats Admin', '+919800000001', 'x')
				ON CONFLICT (email) DO NOTHING""")
			.params(UUID.randomUUID(), IntegrationTest.ADMIN_EMAIL)
			.update();
		UUID id = jdbc.sql("SELECT id FROM users WHERE email = ?")
			.param(IntegrationTest.ADMIN_EMAIL)
			.query(UUID.class)
			.single();
		return new AuthUser(id, IntegrationTest.ADMIN_EMAIL, "Stats Admin");
	}

	private MvcResult call(String path, AuthUser as) throws Exception {
		var request = get(path);
		if (as != null) {
			request.with(TestData.as(as));
		}
		return mvc.perform(request).andReturn();
	}

	private static String text(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private static JsonNode json(MvcResult result) throws Exception {
		return JSON.readTree(text(result));
	}

	// ------------------------------------------------------------------ exact numbers on a known fixture

	/**
	 * The fixture of the two exact tests. Days are in India; NOW is 10:00 on 15 March 2099, so the
	 * last 7 days start on 9 March and the last 30 days on 14 February.
	 * <p>
	 * Only rows that can exist in production: a session row is deleted at most about 16 minutes after
	 * its last use (idle limit + grace, then Housekeeping every 10 minutes), so every session here was
	 * seen minutes ago. "Active" comes from entries only.
	 */
	private void fixture() {
		Instant later = NOW.plus(Duration.ofDays(7)); // a session that has not expired

		UUID today = user("today", india("2099-03-15T00:05:00"));
		// 23:50 in India (18:20 UTC) belongs to the 14th: yesterday, not today
		UUID lateEvening = user("late", india("2099-03-14T23:50:00"));
		UUID weekStart = user("weekstart", india("2099-03-09T00:00:00"));
		UUID beforeWeek = user("beforeweek", india("2099-03-08T23:59:00"));
		UUID monthStart = user("monthstart", india("2099-02-14T00:00:00"));
		UUID beforeMonth = user("beforemonth", india("2099-02-13T23:59:00"));
		UUID old = user("old", india("2098-06-01T12:00:00"));

		// online (idle limit 5 minutes, so the server accepts a session seen less than 6 minutes ago)
		session(today, NOW.minus(Duration.ofMinutes(2)), later);
		entry(today, NOW.minus(Duration.ofHours(1))); // active this week; online as well, counted once each
		// seen 5:59 ago: still accepted by the server (the grace), so online; only a session, so not active
		session(lateEvening, NOW.minus(Duration.ofSeconds(359)), later);
		entry(lateEvening, NOW.plusSeconds(1)); // written a moment after the count: not counted
		// seen 8 minutes ago (Housekeeping has not run yet): online only with a limit of 10 minutes
		session(weekStart, NOW.minus(Duration.ofMinutes(8)), later);
		entry(weekStart, india("2099-03-09T00:00:00")); // the first moment of the 7 days: active this week
		// seen a minute ago but the session has reached its absolute end: not online
		session(beforeWeek, NOW.minus(Duration.ofMinutes(1)), NOW.minusSeconds(1));
		entry(beforeWeek, india("2099-03-08T23:59:00")); // just before the 7 days: active in 30 days only
		// active in the last 30 days only (two entries, counted once)
		entry(monthStart, india("2099-02-14T00:00:00"));
		entry(monthStart, india("2099-03-01T12:00:00"));
		// just before the 30 days: not active
		entry(beforeMonth, india("2099-02-13T23:59:00"));
		entry(beforeMonth, india("2099-02-10T09:00:00"));
		// an old user who added an entry this week
		entry(old, india("2099-03-10T08:00:00"));

		// waiting for a code: two people (one of them asked twice); an expired one and an existing user do not count
		String waiting = uniqueEmail("waiting");
		pendingRegistration(waiting, NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofMinutes(5)));
		pendingRegistration(waiting, NOW.minus(Duration.ofMinutes(2)), NOW.plus(Duration.ofMinutes(8)));
		pendingRegistration(uniqueEmail("waiting2"), NOW.minus(Duration.ofMinutes(1)), NOW.plus(Duration.ofMinutes(9)));
		pendingRegistration(uniqueEmail("expired"), NOW.minus(Duration.ofMinutes(11)), NOW.minusSeconds(1));
		pendingRegistration(emailOf(today), NOW.minus(Duration.ofMinutes(1)), NOW.plus(Duration.ofMinutes(9)));
	}

	@Test
	void theCountsFollowTheDefinitionsOnAKnownFixture() {
		assertThat(appZone).isEqualTo(INDIA);
		long before = allUsers();
		fixture();

		AdminStatsService.Stats stats = service.stats(NOW);

		assertThat(stats.users()).isEqualTo(new AdminStatsService.Users(before + 7, 1, 3, 5));
		// online: today, lateEvening. Active in 7 days: today, weekStart, old. In 30 days: + beforeWeek, monthStart.
		// lateEvening used the app but added nothing: online, not active.
		assertThat(stats.activity()).isEqualTo(new AdminStatsService.Activity(2, 3, 5));
		assertThat(stats.waitingForCode()).isEqualTo(2);
		assertThat(stats.generatedAt()).isEqualTo(NOW.toString());

		List<AdminStatsService.Day> days = stats.signupsLast30Days();
		assertThat(days).hasSize(30);
		assertThat(days.getFirst().date()).isEqualTo("2099-02-14");
		assertThat(days.getLast().date()).isEqualTo("2099-03-15");
		for (int i = 1; i < days.size(); i++) {
			assertThat(LocalDate.parse(days.get(i).date())).isEqualTo(LocalDate.parse(days.get(i - 1).date()).plusDays(1));
		}
		assertThat(days).filteredOn(d -> d.count() > 0)
			.containsExactly(new AdminStatsService.Day("2099-02-14", 1), new AdminStatsService.Day("2099-03-08", 1),
					new AdminStatsService.Day("2099-03-09", 1), new AdminStatsService.Day("2099-03-14", 1),
					new AdminStatsService.Day("2099-03-15", 1));
		assertThat(days.stream().mapToLong(AdminStatsService.Day::count).sum()).isEqualTo(stats.users().newLast30Days());
	}

	@Test
	void onlineNowFollowsTheIdleSetting() {
		fixture();
		AdminStatsService tenMinutes = new AdminStatsService(jdbc, clock, appZone,
				new SessionService(jdbc, clock, tokens, false, 7, 10));
		AdminStatsService oneMinute = new AdminStatsService(jdbc, clock, appZone,
				new SessionService(jdbc, clock, tokens, false, 7, 1));

		// "online" uses the cutoff the server itself uses to accept a session: the idle limit plus the grace
		assertThat(service.stats(NOW).activity().onlineNow()).isEqualTo(2);
		assertThat(tenMinutes.stats(NOW).activity().onlineNow()).isEqualTo(3);
		// limit 1 minute = cutoff 2 minutes: "today" was seen exactly 2 minutes ago, which the server no longer accepts
		assertThat(oneMinute.stats(NOW).activity().onlineNow()).isZero();
		// one second earlier, the same session was seen 1:59 before, which the server still accepts
		assertThat(oneMinute.stats(NOW.minusSeconds(1)).activity().onlineNow()).isEqualTo(1);
		// the idle setting changes "online" only
		assertThat(tenMinutes.stats(NOW).activity().activeLast7Days()).isEqualTo(3);
		assertThat(oneMinute.stats(NOW).activity().activeLast30Days()).isEqualTo(5);
	}

	@Test
	void aSessionTheServerStillAcceptsIsOnline() {
		UUID u = user("edge", india("2099-03-01T10:00:00"));
		SessionService sessions = new SessionService(jdbc, clock, tokens, false, 7, 5);
		AdminStatsService fiveMinutes = new AdminStatsService(jdbc, clock, appZone, sessions);
		long before = fiveMinutes.stats(NOW).activity().onlineNow();
		// a user still using the app: last_seen_at is written only every 30 s and the page pings at most
		// once a minute, so it can be more than the idle limit old while the server still accepts it
		session(u, NOW.minus(sessions.idleCutoff()).plusSeconds(1), NOW.plus(Duration.ofDays(1)));
		assertThat(fiveMinutes.stats(NOW).activity().onlineNow()).isEqualTo(before + 1);
		// but it is not "active": it added nothing
		assertThat(fiveMinutes.stats(NOW).activity().activeLast7Days()).isZero();
	}

	// ------------------------------------------------------------------ GET /api/admin/stats over HTTP

	@Test
	void withoutLoginTheAnswerIs401() throws Exception {
		MvcResult result = call("/api/admin/stats", null);
		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(json(result).get("error").asString()).isEqualTo("unauthorized");
	}

	@Test
	void aNormalUserGetsTheSame404AsAnUnknownPath() throws Exception {
		AuthUser normal = TestData.createUser(jdbc);
		users.add(normal.id());

		MvcResult stats = call("/api/admin/stats", normal);
		MvcResult unknown = call("/api/admin/no-such-thing", normal);

		assertThat(stats.getResponse().getStatus()).isEqualTo(404);
		assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
		assertThat(text(stats)).isEqualTo(text(unknown));
		assertThat(json(stats).get("error").asString()).isEqualTo("not_found");
		assertThat(text(stats)).doesNotContain("users", "total", "admin");
	}

	@Test
	void anAdministratorGetsCountsOnly() throws Exception {
		AuthUser admin = admin();
		Instant now = clock.instant();

		JsonNode before = json(call("/api/admin/stats", admin));
		UUID a = user("httpa", now.minusSeconds(2));
		UUID b = user("httpb", now.minusSeconds(1));
		user("httpc", now.minusSeconds(1));
		session(a, now.minusSeconds(5), now.plus(Duration.ofDays(1)));
		entry(b, now.minusSeconds(1));
		pendingRegistration(uniqueEmail("httpwait"), now.minusSeconds(30), now.plus(Duration.ofMinutes(9)));

		MvcResult result = call("/api/admin/stats", admin);
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
		JsonNode after = json(result);

		assertThat(after.propertyNames()).containsExactlyInAnyOrder("users", "activity", "waitingForCode",
				"signupsLast30Days", "generatedAt");
		assertThat(after.get("users").propertyNames()).containsExactlyInAnyOrder("total", "newToday", "newLast7Days",
				"newLast30Days");
		assertThat(after.get("activity").propertyNames()).containsExactlyInAnyOrder("onlineNow", "activeLast7Days",
				"activeLast30Days");
		assertThat(after.get("users").get("total").asLong()).isEqualTo(allUsers())
			.isEqualTo(before.get("users").get("total").asLong() + 3);
		assertThat(after.get("users").get("newLast30Days").asLong())
			.isEqualTo(before.get("users").get("newLast30Days").asLong() + 3);
		assertThat(after.get("waitingForCode").asLong()).isEqualTo(before.get("waitingForCode").asLong() + 1);
		assertThat(after.get("activity").get("activeLast7Days").asLong())
			.isGreaterThanOrEqualTo(before.get("activity").get("activeLast7Days").asLong() + 1);
		assertThat(after.get("activity").get("onlineNow").asLong())
			.isGreaterThanOrEqualTo(before.get("activity").get("onlineNow").asLong() + 1);

		JsonNode days = after.get("signupsLast30Days");
		assertThat(days.size()).isEqualTo(30);
		assertThat(days.get(29).get("date").asString()).isEqualTo(LocalDate.ofInstant(clock.instant(), INDIA).toString());
		for (JsonNode day : days) {
			assertThat(day.propertyNames()).containsExactlyInAnyOrder("date", "count");
		}

		// counts only: nothing that names or identifies a person, the administrator included
		String body = text(result);
		assertThat(body).doesNotContain("@", "+91", "phone", "name", "email", "balance", "amount");
		assertThat(body).doesNotContain(IntegrationTest.ADMIN_EMAIL, admin.id().toString(), "Stats Admin");
		for (String value : personal) {
			assertThat(body).doesNotContain(value);
		}
	}

	// ------------------------------------------------------------------ the admin flag of GET /api/auth/me

	@Test
	void meSaysAdminTrueOnlyForAListedEmail() throws Exception {
		JsonNode adminMe = json(call("/api/auth/me", admin()));
		assertThat(adminMe.get("user").get("admin").asBoolean()).isTrue();

		AuthUser normal = TestData.createUser(jdbc);
		users.add(normal.id());
		JsonNode normalMe = json(call("/api/auth/me", normal));
		assertThat(normalMe.get("user").get("admin").isBoolean()).isTrue();
		assertThat(normalMe.get("user").get("admin").asBoolean()).isFalse();
		assertThat(normalMe.get("user").propertyNames()).containsExactlyInAnyOrder("name", "email", "phoneMasked",
				"admin");
	}

	@Test
	void theListIsReadWithItsOddCaseAndSpaces() {
		// IntegrationTest.ADMIN_EMAILS is "  Stats.Admin@Example.COM , ,second.admin@example.com  "
		assertThat(IntegrationTest.ADMIN_EMAILS).contains("Stats.Admin@Example.COM", " , ,");
		AdminAccess access = new AdminAccess(IntegrationTest.ADMIN_EMAILS);
		assertThat(access.count()).isEqualTo(2);
		assertThat(access.isAdmin(IntegrationTest.ADMIN_EMAIL)).isTrue();
		assertThat(access.isAdmin("second.admin@example.com")).isTrue();
	}

}
