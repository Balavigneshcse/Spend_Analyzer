package com.spendanalyzer.auth;

import static com.spendanalyzer.auth.AuthClient.PASSWORD;
import static com.spendanalyzer.auth.AuthClient.assertError;
import static com.spendanalyzer.auth.AuthClient.assertStatus;
import static com.spendanalyzer.auth.AuthClient.newEmail;
import static com.spendanalyzer.auth.AuthClient.newIp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;

/** "Log out after a few minutes without activity": the server side of it. */
class SessionIdleIntegrationTest extends AuthIntegrationBase {

	private static final Instant START = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	@Autowired
	SecureTokens tokens;

	/** A clock the test moves by hand. */
	private static final class HandClock extends Clock {

		private Instant now = START;

		void set(Instant instant) {
			now = instant;
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

	private final HandClock clock = new HandClock();

	private SessionService service(int idleMinutes) {
		return new SessionService(jdbc, clock, tokens, false, 7, idleMinutes);
	}

	private static MockHttpServletRequest requestWith(String token) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
		request.setCookies(new Cookie(SessionService.PLAIN_COOKIE, token));
		return request;
	}

	private int sessionRows(String token) {
		return jdbc.sql("SELECT count(*) FROM sessions WHERE token_hash = ?")
			.param(SecureTokens.sha256(token))
			.query(Integer.class)
			.single();
	}

	private Instant lastSeen(String token) {
		return jdbc.sql("SELECT last_seen_at FROM sessions WHERE token_hash = ?")
			.param(SecureTokens.sha256(token))
			.query((rs, n) -> Db.instant(rs, "last_seen_at"))
			.single();
	}

	/** A session made at START. */
	private String newSession(SessionService sessions) {
		clock.set(START);
		return sessions.create(client.insertUser(newEmail(), PASSWORD));
	}

	@Test
	void theGraceIsSixtySecondsOnTopOfTheLimit() {
		assertThat(SessionService.IDLE_GRACE).isEqualTo(Duration.ofSeconds(60));
		assertThat(SessionService.LAST_SEEN_STEP).isEqualTo(Duration.ofSeconds(30));
		assertThat(service(5).idleCutoff()).isEqualTo(Duration.ofMinutes(6));
		assertThat(service(1).idleCutoff()).isEqualTo(Duration.ofMinutes(2));
	}

	@Test
	void justInsideTheIdleLimitPlusGraceTheSessionWorks() {
		SessionService sessions = service(5);
		String token = newSession(sessions);

		clock.set(START.plus(Duration.ofMinutes(6)).minusSeconds(1));
		assertThat(sessions.authenticate(requestWith(token))).isPresent();
		assertThat(lastSeen(token)).as("the request counts as activity").isEqualTo(clock.instant());
	}

	@Test
	void atOrJustOutsideTheIdleLimitPlusGraceTheSessionIsDeleted() {
		SessionService sessions = service(5);
		String exactly = newSession(sessions);
		String later = newSession(sessions);

		clock.set(START.plus(Duration.ofMinutes(6)));
		assertThat(sessions.authenticate(requestWith(exactly))).isEmpty();
		assertThat(sessionRows(exactly)).isZero();

		clock.set(START.plus(Duration.ofMinutes(6)).plusSeconds(1));
		assertThat(sessions.authenticate(requestWith(later))).isEmpty();
		assertThat(sessionRows(later)).as("an idle session is removed, not only refused").isZero();
		// and it stays refused: there is nothing left to bring back
		clock.set(START);
		assertThat(sessions.authenticate(requestWith(later))).isEmpty();
	}

	@Test
	void theLimitFollowsTheSetting() {
		SessionService sessions = service(1);
		String inside = newSession(sessions);
		String outside = newSession(sessions);

		clock.set(START.plusSeconds(119));
		assertThat(sessions.authenticate(requestWith(inside))).isPresent();
		clock.set(START.plusSeconds(121));
		assertThat(sessions.authenticate(requestWith(outside))).isEmpty();
	}

	@Test
	void requestsKeepTheSessionAliveForAsLongAsTheyCome() {
		SessionService sessions = service(5);
		String token = newSession(sessions);

		// a request every 4 minutes for an hour: never idle
		for (int minute = 4; minute <= 60; minute += 4) {
			clock.set(START.plus(Duration.ofMinutes(minute)));
			assertThat(sessions.authenticate(requestWith(token))).as("minute " + minute).isPresent();
		}
	}

	@Test
	void lastSeenIsRefreshedAtMostEveryThirtySeconds() {
		SessionService sessions = service(5);
		String token = newSession(sessions);

		clock.set(START.plusSeconds(30));
		assertThat(sessions.authenticate(requestWith(token))).isPresent();
		assertThat(lastSeen(token)).as("30 s old: not written").isEqualTo(START);

		clock.set(START.plusSeconds(31));
		assertThat(sessions.authenticate(requestWith(token))).isPresent();
		assertThat(lastSeen(token)).as("31 s old: written").isEqualTo(START.plusSeconds(31));
	}

	@Test
	void theAbsoluteLifetimeStillEndsAnActiveSession() {
		SessionService sessions = service(5);
		String token = newSession(sessions);

		// active all the time: refresh last_seen_at right up to the end
		Instant end = START.plus(Duration.ofDays(7));
		jdbc.sql("UPDATE sessions SET last_seen_at = ? WHERE token_hash = ?")
			.params(Db.ts(end.minusSeconds(10)), SecureTokens.sha256(token))
			.update();
		clock.set(end.minusSeconds(1));
		assertThat(sessions.authenticate(requestWith(token))).isPresent();
		clock.set(end);
		assertThat(sessions.authenticate(requestWith(token))).isEmpty();
	}

	@Test
	void theSettingMustBeBetweenOneAndOneHundredTwentyMinutes() {
		assertThat(service(1).idleMinutes()).isEqualTo(1);
		assertThat(service(120).idleMinutes()).isEqualTo(120);
		for (int bad : new int[] { 0, -5, 121, 10_000 }) {
			assertThatThrownBy(() -> service(bad)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.session.idle-minutes");
		}
	}

	// ------------------------------------------------------------------ over HTTP

	@Test
	void anIdleSessionIsAnsweredWithTheUsual401() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());
		assertStatus(client.get("/api/balances", session), 200);

		jdbc.sql("UPDATE sessions SET last_seen_at = now() - interval '6 minutes 5 seconds' WHERE user_id = ?")
			.param(userId)
			.update();
		assertError(client.get("/api/balances", session), 401, "unauthorized");
		assertError(client.get("/api/auth/me", session), 401, "unauthorized");
		assertThat(countSessions(userId)).isZero();
	}

	@Test
	void justInsideTheGraceTheCookieStillWorksOverHttp() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		jdbc.sql("UPDATE sessions SET last_seen_at = now() - interval '5 minutes 50 seconds' WHERE user_id = ?")
			.param(userId)
			.update();
		assertStatus(client.get("/api/auth/me", session), 200);
	}

	@Test
	void pingKeepsTheSessionAlive() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		jdbc.sql("UPDATE sessions SET last_seen_at = now() - interval '5 minutes' WHERE user_id = ?").param(userId).update();
		MvcResult ping = client.post("/api/auth/ping", null, newIp(), session);
		assertStatus(ping, 204);
		assertThat(ping.getResponse().getContentAsString()).isEmpty();
		assertThat(ping.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(ping.getResponse().getHeader("Set-Cookie")).as("the cookie is left as it is").isNull();
		double age = jdbc.sql("SELECT extract(epoch FROM now() - last_seen_at) FROM sessions WHERE user_id = ?")
			.param(userId)
			.query(Double.class)
			.single();
		assertThat(age).as("last_seen_at refreshed by the ping").isLessThan(10);

		// so a little later the session is still there
		jdbc.sql("UPDATE sessions SET last_seen_at = last_seen_at - interval '3 minutes' WHERE user_id = ?")
			.param(userId)
			.update();
		assertStatus(client.get("/api/auth/me", session), 200);
	}

	@Test
	void pingAcceptsAnEmptyJsonBodyToo() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());
		assertStatus(client.post("/api/auth/ping", AuthClient.body(), newIp(), session), 204);
	}

	@Test
	void pingWithoutLoginIs401() throws Exception {
		assertError(client.post("/api/auth/ping", null, newIp()), 401, "unauthorized");
		assertError(client.post("/api/auth/ping", null, newIp(), new Cookie(SessionService.PLAIN_COOKIE,
				tokens.sessionToken())), 401, "unauthorized");
	}

	@Test
	void pingAfterTheIdleLimitIs401AndDoesNotBringTheSessionBack() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		jdbc.sql("UPDATE sessions SET last_seen_at = now() - interval '7 minutes' WHERE user_id = ?").param(userId).update();
		assertError(client.post("/api/auth/ping", null, newIp(), session), 401, "unauthorized");
		assertError(client.get("/api/auth/me", session), 401, "unauthorized");
	}

	@Test
	void configTellsThePageTheIdleLimit() throws Exception {
		MvcResult result = client.get("/api/auth/config", null);
		assertStatus(result, 200);
		assertThat(AuthClient.json(result).get("idleMinutes").asInt()).isEqualTo(5);
		assertThat(AuthClient.json(result).properties()).hasSize(2);
	}

	private int countSessions(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM sessions WHERE user_id = ?").param(userId).query(Integer.class).single();
	}

}
