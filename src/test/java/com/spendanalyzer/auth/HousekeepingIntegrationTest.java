package com.spendanalyzer.auth;

import static com.spendanalyzer.auth.AuthClient.PASSWORD;
import static com.spendanalyzer.auth.AuthClient.newEmail;
import static com.spendanalyzer.auth.AuthClient.newIp;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

/** Old rows are removed; rows the limits still need are kept. */
class HousekeepingIntegrationTest extends AuthIntegrationBase {

	@Autowired
	Clock clock;

	@Autowired
	SessionService sessions;

	private int count(String table, String column, Object value) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")
			.param(value)
			.query(Integer.class)
			.single();
	}

	/** One row in each table for this address, all of the given age. */
	private UUID rowsOfAge(String email, String age) {
		UUID userId = client.insertUser(email, PASSWORD);
		String past = "now() - interval '" + age + "'";
		jdbc.sql("INSERT INTO pending_registrations (id, email, name, phone, password_hash, created_at, expires_at) "
				+ "VALUES (?, ?, 'Old', '+919812345678', 'x', " + past + ", " + past + " + interval '30 minutes')")
			.params(UUID.randomUUID(), email)
			.update();
		jdbc.sql("INSERT INTO otp_challenges (id, flow_id, email, purpose, code_hmac, expires_at, created_at) "
				+ "VALUES (?, ?, ?, 'REGISTER', '\\x00', " + past + " + interval '10 minutes', " + past + ")")
			.params(UUID.randomUUID(), UUID.randomUUID(), email)
			.update();
		// still in use (last seen just now), so only the absolute lifetime decides here
		jdbc.sql("INSERT INTO sessions (id, user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, "
				+ past + ", now(), " + past + " + interval '7 days')")
			.params(UUID.randomUUID(), userId, SecureTokens.sha256(UUID.randomUUID().toString()))
			.update();
		jdbc.sql("INSERT INTO login_attempts (email, ip, success, created_at) VALUES (?, ?, FALSE, " + past + ")")
			.params(email, newIp())
			.update();
		jdbc.sql("INSERT INTO mail_log (recipient, kind, created_at) VALUES (?, 'REGISTER_CODE', " + past + ")")
			.param(email)
			.update();
		return userId;
	}

	@Test
	void oldRowsGoRecentRowsStay() {
		String recent = newEmail();
		String dayOld = newEmail();
		String ancient = newEmail();
		UUID recentUser = rowsOfAge(recent, "20 minutes");
		UUID dayOldUser = rowsOfAge(dayOld, "25 hours");
		UUID ancientUser = rowsOfAge(ancient, "8 days");

		new Housekeeping(jdbc, clock, sessions).run(clock.instant());

		// 20 minutes old: everything is still needed
		assertThat(count("pending_registrations", "email", recent)).isEqualTo(1);
		assertThat(count("otp_challenges", "email", recent)).isEqualTo(1);
		assertThat(count("sessions", "user_id", recentUser)).isEqualTo(1);
		assertThat(count("login_attempts", "email", recent)).isEqualTo(1);
		assertThat(count("mail_log", "recipient", recent)).isEqualTo(1);

		// 25 hours old: the limits no longer look at these; the session (7 days) and the mail log (7 days) stay
		assertThat(count("pending_registrations", "email", dayOld)).isZero();
		assertThat(count("otp_challenges", "email", dayOld)).isZero();
		assertThat(count("login_attempts", "email", dayOld)).isZero();
		assertThat(count("sessions", "user_id", dayOldUser)).isEqualTo(1);
		assertThat(count("mail_log", "recipient", dayOld)).isEqualTo(1);

		// 8 days old: all gone, but never the user
		assertThat(count("pending_registrations", "email", ancient)).isZero();
		assertThat(count("otp_challenges", "email", ancient)).isZero();
		assertThat(count("login_attempts", "email", ancient)).isZero();
		assertThat(count("sessions", "user_id", ancientUser)).isZero();
		assertThat(count("mail_log", "recipient", ancient)).isZero();
		assertThat(client.userCount(ancient)).isEqualTo(1);
	}

	@Test
	void itRunsAtMostOncePerInterval() {
		Housekeeping housekeeping = new Housekeeping(jdbc, clock, sessions);
		String first = newEmail();
		rowsOfAge(first, "8 days");
		housekeeping.runIfDue();
		assertThat(count("mail_log", "recipient", first)).isZero();

		String second = newEmail();
		rowsOfAge(second, "8 days");
		housekeeping.runIfDue();
		assertThat(count("mail_log", "recipient", second)).as("not due again yet").isEqualTo(1);

		housekeeping.run(clock.instant());
		assertThat(count("mail_log", "recipient", second)).isZero();
	}

	@Test
	void idleSessionsGoActiveOnesStay() {
		UUID userId = client.insertUser(newEmail(), PASSWORD);
		UUID active = idleSession(userId, "30 seconds");
		UUID insideGrace = idleSession(userId, "5 minutes 50 seconds");
		UUID idle = idleSession(userId, "6 minutes 10 seconds");
		UUID longIdle = idleSession(userId, "3 hours");

		new Housekeeping(jdbc, clock, sessions).run(clock.instant());

		assertThat(count("sessions", "id", active)).isEqualTo(1);
		assertThat(count("sessions", "id", insideGrace)).as("5 minutes + 60 s grace not yet over").isEqualTo(1);
		assertThat(count("sessions", "id", idle)).isZero();
		assertThat(count("sessions", "id", longIdle)).isZero();
	}

	/** A session with plenty of absolute lifetime left, last seen this long ago. */
	private UUID idleSession(UUID userId, String idleFor) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO sessions (id, user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, "
				+ "now() - interval '1 day', now() - interval '" + idleFor + "', now() + interval '6 days')")
			.params(id, userId, SecureTokens.sha256(UUID.randomUUID().toString()))
			.update();
		return id;
	}

}
