package com.spendanalyzer.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How often codes may be sent and guessed. Everything is counted in the database (otp_challenges
 * and mail_log), nothing in memory, and nothing depends on whether the address has an account:
 * challenges are written for unknown addresses too.
 * <p>
 * Why these numbers: a code has 1,000,000 values and 5 tries. With 10 codes a day and a freeze
 * after 15 wrong tries, guessing a password-reset code has a chance of about 0.002 % per day.
 */
@Component
class SendLimits {

	private static final Logger log = LoggerFactory.getLogger(SendLimits.class);

	/** Between two codes of the same registration / reset. */
	static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

	static final int SENDS_PER_HOUR = 5;

	static final int SENDS_PER_DAY = 10;

	/** Wrong codes per address and purpose in 24 hours before sending and checking both stop. */
	static final int MAX_FAILED_VERIFICATIONS = 15;

	private static final Duration HOUR = Duration.ofHours(1);

	private static final Duration DAY = Duration.ofHours(24);

	private final JdbcClient jdbc;

	private final MailLog mailLog;

	private final AuthLimits limits;

	SendLimits(JdbcClient jdbc, MailLog mailLog, AuthLimits limits) {
		this.jdbc = jdbc;
		this.mailLog = mailLog;
		this.limits = limits;
	}

	/** One earlier code of the same address and purpose. */
	private record Earlier(Instant createdAt, int failedAttempts) {
	}

	/**
	 * Refuses with 429 when another code may not be sent now.
	 * @param flowId the registration or reset id, or null for a brand-new one
	 */
	void check(UUID flowId, String email, OtpPurpose purpose, String ip, Instant now) {
		if (flowId != null) {
			Optional<Instant> last = jdbc
				.sql("SELECT created_at FROM otp_challenges WHERE flow_id = ? AND purpose = ? ORDER BY created_at DESC LIMIT 1")
				.params(flowId, purpose.name())
				.query((rs, n) -> Db.instant(rs, "created_at"))
				.optional();
			if (last.isPresent() && last.get().plus(RESEND_COOLDOWN).isAfter(now)) {
				throw AuthErrors.tooMany(now, last.get().plus(RESEND_COOLDOWN));
			}
		}

		List<Earlier> earlier = earlier(email, purpose, now);
		Instant frozenUntil = frozenUntil(earlier);
		if (frozenUntil != null) {
			throw AuthErrors.tooMany(now, frozenUntil);
		}
		if (earlier.size() >= SENDS_PER_DAY) {
			throw AuthErrors.tooMany(now, earlier.get(SENDS_PER_DAY - 1).createdAt().plus(DAY));
		}
		Instant hourAgo = now.minus(HOUR);
		long lastHour = earlier.stream().filter(e -> e.createdAt().isAfter(hourAgo)).count();
		if (lastHour >= SENDS_PER_HOUR) {
			throw AuthErrors.tooMany(now, earlier.get(SENDS_PER_HOUR - 1).createdAt().plus(HOUR));
		}

		int perIp = limits.otpRequestsPerIpPerHour();
		List<Instant> fromIp = mailLog.requestsFrom(ip, hourAgo, perIp);
		if (fromIp.size() >= perIp) {
			throw AuthErrors.tooMany(now, fromIp.get(perIp - 1).plus(HOUR));
		}
	}

	/** Refuses with 429 when codes of this address and purpose were guessed wrong too often. */
	void requireNotFrozen(String email, OtpPurpose purpose, Instant now) {
		Instant frozenUntil = frozenUntil(earlier(email, purpose, now));
		if (frozenUntil != null) {
			throw AuthErrors.tooMany(now, frozenUntil);
		}
	}

	/**
	 * The global circuit breaker. When it is open the request is still answered as usual, but no
	 * mail leaves: this protects the mail account's quota and reputation from a flood.
	 */
	boolean mailBudgetLeft(Instant now) {
		int sent = mailLog.mailsSince(now.minus(DAY));
		if (sent >= limits.mailsPerDay()) {
			log.error("Mail circuit breaker is open: {} mails in the last 24 hours (limit {}). Not sending.", sent,
					limits.mailsPerDay());
			return false;
		}
		return true;
	}

	/** The codes of the last 24 hours, newest first. */
	private List<Earlier> earlier(String email, OtpPurpose purpose, Instant now) {
		return jdbc.sql("""
				SELECT created_at, attempts, consumed_at IS NOT NULL AS used FROM otp_challenges
				WHERE email = ? AND purpose = ? AND created_at > ?
				ORDER BY created_at DESC""")
			.params(email, purpose.name(), Db.ts(now.minus(DAY)))
			// a used code spent one of its attempts on the right guess
			.query((rs, n) -> new Earlier(Db.instant(rs, "created_at"),
					rs.getInt("attempts") - (rs.getBoolean("used") ? 1 : 0)))
			.list();
	}

	/** The moment enough wrong guesses have left the 24 hour window again, or null when not frozen. */
	private static Instant frozenUntil(List<Earlier> newestFirst) {
		int failed = newestFirst.stream().mapToInt(Earlier::failedAttempts).sum();
		if (failed < MAX_FAILED_VERIFICATIONS) {
			return null;
		}
		for (int i = newestFirst.size() - 1; i >= 0; i--) {
			Earlier oldest = newestFirst.get(i);
			failed -= oldest.failedAttempts();
			if (failed < MAX_FAILED_VERIFICATIONS) {
				return oldest.createdAt().plus(DAY);
			}
		}
		return null;
	}

}
