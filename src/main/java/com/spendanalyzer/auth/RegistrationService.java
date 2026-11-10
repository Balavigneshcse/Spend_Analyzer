package com.spendanalyzer.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.spendanalyzer.auth.mail.EmailMessage;
import com.spendanalyzer.auth.mail.MailDispatcher;
import com.spendanalyzer.auth.mail.MailTemplates;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration in two steps: the form, then the emailed code.
 * <p>
 * No users row exists until the code is verified. The form is parked in pending_registrations
 * under a random id that only the submitting browser gets. Verifying creates the user from exactly
 * that row, so the password of the new account is always the one typed in the browser that also
 * typed the code. Somebody who registers with another person's address therefore gains nothing: he
 * never sees the code, and his parked password is thrown away when the real owner verifies.
 * <p>
 * An address that already has an account gets the same answer, a parked row and a challenge like
 * everybody else, but the challenge can never be solved and the owner gets a short notice mail
 * instead of a code.
 */
@Service
public class RegistrationService {

	/** How long a submitted form waits for its code. */
	static final Duration PENDING_LIFETIME = Duration.ofMinutes(30);

	private static final Duration NOTICE_INTERVAL = Duration.ofHours(24);

	private final boolean enabled;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final TransactionTemplate tx;

	private final UserRepository users;

	private final PasswordPolicy passwordPolicy;

	private final PasswordHasher hasher;

	private final OtpService otp;

	private final SendLimits limits;

	private final MailLog mailLog;

	private final MailDispatcher mail;

	private final SessionService sessions;

	private final Housekeeping housekeeping;

	RegistrationService(@Value("${app.registration-enabled:true}") boolean enabled, JdbcClient jdbc, Clock clock,
			TransactionTemplate tx, UserRepository users, PasswordPolicy passwordPolicy, PasswordHasher hasher,
			OtpService otp, SendLimits limits, MailLog mailLog, MailDispatcher mail, SessionService sessions,
			Housekeeping housekeeping) {
		this.enabled = enabled;
		this.jdbc = jdbc;
		this.clock = clock;
		this.tx = tx;
		this.users = users;
		this.passwordPolicy = passwordPolicy;
		this.hasher = hasher;
		this.otp = otp;
		this.limits = limits;
		this.mailLog = mailLog;
		this.mail = mail;
		this.sessions = sessions;
		this.housekeeping = housekeeping;
	}

	/** A finished registration: the new user and the token of the session it starts with. */
	public record Registered(UserRow user, String sessionToken) {
	}

	private record Pending(UUID id, String email, String name, String phone, String passwordHash) {
	}

	public boolean enabled() {
		return enabled;
	}

	/** Step one. Returns the registration id for the browser; the answer never depends on the address. */
	public UUID register(AuthRequests.Register form, String ip) {
		requireEnabled();
		FieldErrors errors = new FieldErrors();
		String name = PersonName.normalise(form.name()).orElse(null);
		if (name == null) {
			errors.add("name", "Enter your name (2 to 60 characters).");
		}
		String email = EmailAddress.normalise(form.email()).orElse(null);
		if (email == null) {
			errors.add("email", "Enter a valid email address.");
		}
		String phone = IndianPhone.normalise(form.phone()).orElse(null);
		if (phone == null) {
			errors.add("phone", "Enter a 10 digit Indian mobile number.");
		}
		passwordPolicy.problem(form.password(), email, phone).ifPresent(problem -> errors.add("password", problem));
		errors.throwIfAny();

		// Cheap refusal first: a blocked client must not make the server hash passwords.
		limits.check(null, email, OtpPurpose.REGISTER, ip, clock.instant());
		// Always hashed, before anybody looks whether the address is taken: same work in both cases.
		String passwordHash = hasher.hash(form.password());

		UUID registrationId = UUID.randomUUID();
		List<EmailMessage> mails = tx.execute(status -> {
			Instant now = clock.instant();
			jdbc.sql("""
					INSERT INTO pending_registrations (id, email, name, phone, password_hash, created_at, expires_at)
					VALUES (?, ?, ?, ?, ?, ?, ?)""")
				.params(registrationId, email, name, phone, passwordHash, Db.ts(now), Db.ts(now.plus(PENDING_LIFETIME)))
				.update();
			return sendCode(registrationId, email, ip);
		});
		mails.forEach(mail::send);
		housekeeping.runIfDue();
		return registrationId;
	}

	/** "Send the code again" for a registration that is still waiting. */
	public void resend(String registrationIdText, String ip) {
		requireEnabled();
		UUID registrationId = Ids.parse(registrationIdText).orElseThrow(AuthErrors::invalidCode);
		String email = jdbc.sql("SELECT email FROM pending_registrations WHERE id = ? AND expires_at > ?")
			.params(registrationId, Db.ts(clock.instant()))
			.query(String.class)
			.optional()
			.orElseThrow(AuthErrors::invalidCode);
		List<EmailMessage> mails = tx.execute(status -> sendCode(registrationId, email, ip));
		mails.forEach(mail::send);
	}

	/** Step two: the code. Creates the user and logs them in. */
	public Registered verify(String registrationIdText, String code) {
		requireEnabled();
		UUID registrationId = Ids.parse(registrationIdText).orElseThrow(AuthErrors::invalidCode);
		OtpService.Challenge challenge = otp.attempt(registrationId, OtpPurpose.REGISTER, code);
		return tx.execute(status -> {
			otp.consume(challenge);
			Instant now = clock.instant();
			Pending pending = jdbc.sql("""
					SELECT id, email, name, phone, password_hash FROM pending_registrations
					WHERE id = ? AND expires_at > ? FOR UPDATE""")
				.params(registrationId, Db.ts(now))
				.query((rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name"),
						rs.getString("phone"), rs.getString("password_hash")))
				.optional()
				.orElseThrow(AuthErrors::invalidCode);

			UserRow user = new UserRow(UUID.randomUUID(), pending.email(), pending.name(), pending.phone(),
					pending.passwordHash());
			// Somebody else finished registering this address a moment ago: the same generic answer.
			if (!users.insert(user.id(), user.email(), user.name(), user.phone(), user.passwordHash(), now)) {
				throw AuthErrors.invalidCode();
			}
			// Every other parked form for this address (possibly a stranger's) and its codes are now void.
			jdbc.sql("DELETE FROM pending_registrations WHERE email = ?").param(user.email()).update();
			otp.endAll(user.email(), OtpPurpose.REGISTER);
			return new Registered(user, sessions.create(user.id()));
		});
	}

	/** Issues the next code of a registration. Runs inside a transaction; returns the mails to send after it. */
	private List<EmailMessage> sendCode(UUID registrationId, String email, String ip) {
		boolean taken = users.existsByEmail(email);
		List<EmailMessage> mails = new ArrayList<>();
		EmailMessage code = otp.issue(registrationId, email, OtpPurpose.REGISTER, null, !taken, ip);
		if (code != null) {
			mails.add(code);
		}
		if (taken) {
			accountNotice(email).ifPresent(mails::add);
		}
		return mails;
	}

	/** Tells the owner of an existing account that somebody tried to register it: at most once a day. */
	private Optional<EmailMessage> accountNotice(String email) {
		Instant now = clock.instant();
		if (mailLog.sentSince(email, MailTemplates.KIND_ACCOUNT_NOTICE, now.minus(NOTICE_INTERVAL))
				|| !limits.mailBudgetLeft(now)) {
			return Optional.empty();
		}
		// no client address: the notice must not count against the per-IP limit a second time
		mailLog.record(email, MailTemplates.KIND_ACCOUNT_NOTICE, null, now);
		return Optional.of(MailTemplates.accountExists(email));
	}

	private void requireEnabled() {
		if (!enabled) {
			throw AuthErrors.registrationClosed();
		}
	}

}
