package com.spendanalyzer.auth;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.spendanalyzer.auth.mail.EmailMessage;
import com.spendanalyzer.auth.mail.MailTemplates;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Emailed six digit codes: issuing them and checking them.
 * <p>
 * A code is valid for 10 minutes, for 5 tries, for one use, for one purpose and for the one
 * registration / reset ("flow") it was issued for. The 5 tries are enforced by a single SQL
 * statement, so twenty guesses sent at the same moment still get five tries, not twenty.
 */
@Service
public class OtpService {

	public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);

	public static final int MAX_ATTEMPTS = 5;

	private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

	private final JdbcClient jdbc;

	private final Clock clock;

	private final SecureTokens tokens;

	private final OtpHmac hmac;

	private final SendLimits limits;

	private final MailLog mailLog;

	OtpService(JdbcClient jdbc, Clock clock, SecureTokens tokens, OtpHmac hmac, SendLimits limits, MailLog mailLog) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.tokens = tokens;
		this.hmac = hmac;
		this.limits = limits;
		this.mailLog = mailLog;
	}

	/** A code that was guessed right and may now be used up with {@link #consume}. */
	public record Challenge(UUID id, String email, UUID userId) {
	}

	private record Attempt(UUID id, String email, UUID userId, byte[] codeHmac) {
	}

	/**
	 * Creates the next code of a flow and returns the mail to send once the transaction has
	 * committed, or null when no mail must go out. Refuses with 429 when the limits say so.
	 * <p>
	 * With {@code usable = false} the stored value is random bytes: the challenge exists, counts
	 * towards every limit and takes guesses like a real one, but no code can ever match it and
	 * nothing is mailed. That is how unknown addresses (password reset) and addresses that already
	 * have an account (registration) are made to look exactly like the normal case.
	 * @param userId the account a password reset is for, else null
	 */
	EmailMessage issue(UUID flowId, String email, OtpPurpose purpose, UUID userId, boolean usable, String ip) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("issue() must run inside a transaction");
		}
		// One at a time per address and purpose, so that parallel requests cannot slip past the counts.
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
			.param(purpose.name() + "|" + email)
			.query((rs, n) -> 0)
			.list();
		Instant now = clock.instant();
		limits.check(flowId, email, purpose, ip, now);

		// A new code ends the previous one of this flow.
		jdbc.sql("""
				UPDATE otp_challenges SET expires_at = ?
				WHERE flow_id = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > ?""")
			.params(Db.ts(now), flowId, purpose.name(), Db.ts(now))
			.update();

		UUID id = UUID.randomUUID();
		String code = tokens.otpCode();
		byte[] stored = usable ? hmac.of(id, purpose, email, code) : tokens.bytes(OtpHmac.LENGTH);
		jdbc.sql("""
				INSERT INTO otp_challenges (id, flow_id, email, purpose, user_id, code_hmac, attempts, expires_at, created_at)
				VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)""")
			.params(id, flowId, email, purpose.name(), userId, stored, Db.ts(now.plus(CODE_LIFETIME)), Db.ts(now))
			.update();

		boolean send = usable && limits.mailBudgetLeft(now);
		mailLog.record(email, send ? purpose.mailKind() : MailLog.SUPPRESSED, ip, now);
		if (!send) {
			return null;
		}
		long minutes = CODE_LIFETIME.toMinutes();
		return purpose == OtpPurpose.REGISTER ? MailTemplates.registerCode(email, code, minutes)
				: MailTemplates.resetCode(email, code, minutes);
	}

	/** The address a flow belongs to and when its first code was issued; empty for an unknown flow. */
	Optional<FlowStart> flowStart(UUID flowId, OtpPurpose purpose) {
		return jdbc
			.sql("SELECT email, created_at FROM otp_challenges WHERE flow_id = ? AND purpose = ? ORDER BY created_at LIMIT 1")
			.params(flowId, purpose.name())
			.query((rs, n) -> new FlowStart(rs.getString("email"), Db.instant(rs, "created_at")))
			.optional();
	}

	record FlowStart(String email, Instant startedAt) {
	}

	/**
	 * Spends one try on a code. Returns the challenge when the code is right; otherwise throws the
	 * one generic "invalid_code" error (wrong, expired, used, too many tries, unknown flow: the
	 * caller cannot tell which), or 429 when the address is frozen after too many wrong codes.
	 * <p>
	 * Must NOT run inside a transaction: a wrong guess has to stay counted even though the request fails.
	 */
	public Challenge attempt(UUID flowId, OtpPurpose purpose, String code) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("attempt() must not run inside a transaction");
		}
		if (flowId == null || code == null || !CODE.matcher(code).matches()) {
			throw AuthErrors.invalidCode();
		}
		Instant now = clock.instant();
		String email = jdbc
			.sql("SELECT email FROM otp_challenges WHERE flow_id = ? AND purpose = ? ORDER BY created_at DESC LIMIT 1")
			.params(flowId, purpose.name())
			.query(String.class)
			.optional()
			.orElseThrow(AuthErrors::invalidCode);
		limits.requireNotFrozen(email, purpose, now);

		// The guard and the increment are one statement. Parallel requests queue on the row lock and
		// each re-checks "attempts < 5" against the committed value, so the cap cannot be outrun.
		Attempt attempt = jdbc.sql("""
				UPDATE otp_challenges SET attempts = attempts + 1
				WHERE flow_id = ? AND purpose = ? AND attempts < ? AND consumed_at IS NULL AND expires_at > ?
				RETURNING id, email, user_id, code_hmac""")
			.params(flowId, purpose.name(), MAX_ATTEMPTS, Db.ts(now))
			.query((rs, n) -> new Attempt(rs.getObject("id", UUID.class), rs.getString("email"),
					rs.getObject("user_id", UUID.class), rs.getBytes("code_hmac")))
			.optional()
			.orElseThrow(AuthErrors::invalidCode);

		byte[] expected = hmac.of(attempt.id(), purpose, attempt.email(), code);
		if (!MessageDigest.isEqual(expected, attempt.codeHmac())) {
			throw AuthErrors.invalidCode();
		}
		return new Challenge(attempt.id(), attempt.email(), attempt.userId());
	}

	/**
	 * Uses the code up. Call it inside the transaction that creates the user or sets the password:
	 * if two requests carry the same right code, only one gets past this line.
	 */
	public void consume(Challenge challenge) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("consume() must run inside a transaction");
		}
		int updated = jdbc.sql("UPDATE otp_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL")
			.params(Db.ts(clock.instant()), challenge.id())
			.update();
		if (updated != 1) {
			throw AuthErrors.invalidCode();
		}
	}

	/** Ends every code of this purpose still open for an address (e.g. once its account exists). */
	void endAll(String email, OtpPurpose purpose) {
		Instant now = clock.instant();
		jdbc.sql("""
				UPDATE otp_challenges SET expires_at = ?
				WHERE email = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > ?""")
			.params(Db.ts(now), email, purpose.name(), Db.ts(now))
			.update();
	}

}
