package com.spendanalyzer.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slows down password guessing, counted in the login_attempts table (so a restart does not reset it):
 * <ul>
 * <li>5 wrong passwords for one email from one address within 15 minutes: that pair waits 15 minutes;</li>
 * <li>20 wrong passwords for one email within an hour, from anywhere: that email waits an hour.
 * Password reset still works, so the owner is never locked out for good.</li>
 * </ul>
 * The counts are by the email that was typed, whether or not it has an account.
 * <p>
 * A guess is written down as a failure BEFORE the password is checked and corrected afterwards
 * when it was right (or removed when the check could not run). Requests arriving together are
 * therefore all counted before any of them gets its answer and cannot outrun the limit.
 */
@Component
class LoginThrottle {

	static final int PAIR_LIMIT = 5;

	static final Duration PAIR_WINDOW = Duration.ofMinutes(15);

	static final int EMAIL_LIMIT = 20;

	static final Duration EMAIL_WINDOW = Duration.ofHours(1);

	private final JdbcClient jdbc;

	private final Clock clock;

	private final TransactionTemplate tx;

	LoginThrottle(JdbcClient jdbc, Clock clock, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.tx = tx;
	}

	/**
	 * Takes one guess for this email from this address, or refuses with 429.
	 * @return the id of the attempt, for {@link #succeeded(long)} or {@link #cancel(long)}
	 */
	long claim(String email, String ip) {
		return tx.execute(status -> {
			jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
				.param("LOGIN|" + email)
				.query((rs, n) -> 0)
				.list();
			Instant now = clock.instant();
			Instant until = blockedUntil(email, ip, now);
			if (until != null) {
				throw AuthErrors.tooMany(now, until);
			}
			return jdbc
				.sql("INSERT INTO login_attempts (email, ip, success, created_at) VALUES (?, ?, FALSE, ?) RETURNING id")
				.params(email, ip, Db.ts(now))
				.query(Long.class)
				.single();
		});
	}

	/** The password was right: the attempt no longer counts as a failure. */
	void succeeded(long attemptId) {
		jdbc.sql("UPDATE login_attempts SET success = TRUE WHERE id = ?").param(attemptId).update();
	}

	/**
	 * The password was never compared (no free hashing slot, a database error): the attempt was not
	 * a guess and must not count as a wrong one. A guess that was not checked reveals nothing, so
	 * forgetting it gives nobody a free try.
	 */
	void cancel(long attemptId) {
		jdbc.sql("DELETE FROM login_attempts WHERE id = ?").param(attemptId).update();
	}

	/** Forgets the failures of an email, after its owner proved themselves with a password reset. */
	void clearFailures(String email) {
		jdbc.sql("DELETE FROM login_attempts WHERE email = ? AND NOT success").param(email).update();
	}

	private Instant blockedUntil(String email, String ip, Instant now) {
		// Failures of this pair since its last successful login.
		List<Instant> pair = jdbc.sql("""
				SELECT created_at FROM login_attempts
				WHERE email = ? AND ip = ? AND NOT success AND created_at > ?
				  AND created_at > COALESCE((SELECT max(created_at) FROM login_attempts
				                             WHERE email = ? AND ip = ? AND success), '-infinity')
				ORDER BY created_at DESC LIMIT ?""")
			.params(email, ip, Db.ts(now.minus(PAIR_WINDOW.multipliedBy(2))), email, ip, PAIR_LIMIT)
			.query((rs, n) -> Db.instant(rs, "created_at"))
			.list();
		Instant pairBlock = blockEnd(pair, PAIR_LIMIT, PAIR_WINDOW, now);

		List<Instant> all = jdbc.sql("""
				SELECT created_at FROM login_attempts
				WHERE email = ? AND NOT success AND created_at > ?
				ORDER BY created_at DESC LIMIT ?""")
			.params(email, Db.ts(now.minus(EMAIL_WINDOW.multipliedBy(2))), EMAIL_LIMIT)
			.query((rs, n) -> Db.instant(rs, "created_at"))
			.list();
		Instant emailBlock = blockEnd(all, EMAIL_LIMIT, EMAIL_WINDOW, now);

		if (pairBlock == null) {
			return emailBlock;
		}
		return emailBlock != null && emailBlock.isAfter(pairBlock) ? emailBlock : pairBlock;
	}

	/**
	 * @param newestFirst the newest {@code limit} failures
	 * @return when the block ends: one window after the failure that completed {@code limit}
	 * failures within one window; null when there is no block now
	 */
	private static Instant blockEnd(List<Instant> newestFirst, int limit, Duration window, Instant now) {
		if (newestFirst.size() < limit) {
			return null;
		}
		Instant newest = newestFirst.get(0);
		Instant oldest = newestFirst.get(limit - 1);
		if (Duration.between(oldest, newest).compareTo(window) > 0) {
			return null;
		}
		Instant end = newest.plus(window);
		return end.isAfter(now) ? end : null;
	}

}
