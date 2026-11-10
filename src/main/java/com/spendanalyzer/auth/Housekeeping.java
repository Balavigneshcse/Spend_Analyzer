package com.spendanalyzer.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes rows nobody needs any more. It runs as a side effect of logins and code requests, at
 * most every ten minutes: the free host puts the application to sleep when idle, so a timer alone
 * would rarely fire.
 * <p>
 * Challenges and login attempts are kept for 24 hours because the limits count over 24 hours.
 */
@Component
class Housekeeping {

	private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

	private static final Duration INTERVAL = Duration.ofMinutes(10);

	private static final Duration LIMIT_WINDOW = Duration.ofHours(24);

	private static final Duration MAIL_LOG_KEPT = Duration.ofDays(7);

	private final JdbcClient jdbc;

	private final Clock clock;

	private final AtomicReference<Instant> lastRun = new AtomicReference<>(Instant.MIN);

	Housekeeping(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Cleans up if the last clean-up is long enough ago. Never fails the request it rides on. */
	void runIfDue() {
		Instant now = clock.instant();
		Instant last = lastRun.get();
		if (last.plus(INTERVAL).isAfter(now) || !lastRun.compareAndSet(last, now)) {
			return;
		}
		try {
			run(now);
		}
		catch (RuntimeException ex) {
			log.warn("Clean-up of old auth rows failed: {}", ex.getClass().getSimpleName());
		}
	}

	void run(Instant now) {
		jdbc.sql("DELETE FROM pending_registrations WHERE expires_at < ?").param(Db.ts(now)).update();
		jdbc.sql("DELETE FROM otp_challenges WHERE created_at < ?").param(Db.ts(now.minus(LIMIT_WINDOW))).update();
		jdbc.sql("DELETE FROM sessions WHERE expires_at < ?").param(Db.ts(now)).update();
		jdbc.sql("DELETE FROM login_attempts WHERE created_at < ?").param(Db.ts(now.minus(LIMIT_WINDOW))).update();
		jdbc.sql("DELETE FROM mail_log WHERE created_at < ?").param(Db.ts(now.minus(MAIL_LOG_KEPT))).update();
	}

}
