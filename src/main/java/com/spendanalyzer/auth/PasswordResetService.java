package com.spendanalyzer.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.spendanalyzer.auth.mail.EmailMessage;
import com.spendanalyzer.auth.mail.MailDispatcher;
import com.spendanalyzer.common.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Forgot password": a code by mail, then code and new password together in one request (there is
 * no in-between "code accepted" state that could be stolen).
 * <p>
 * Asking for a reset always gets the same answer. For an address without an account a challenge
 * that nothing can solve is stored and no mail is sent, so the later steps (resend, confirm, the
 * limits) behave the same as for a real account.
 */
@Service
public class PasswordResetService {

	/** How long after the first code a reset can still ask for another one. */
	static final Duration FLOW_LIFETIME = Duration.ofMinutes(30);

	private final Clock clock;

	private final TransactionTemplate tx;

	private final UserRepository users;

	private final PasswordPolicy passwordPolicy;

	private final PasswordHasher hasher;

	private final OtpService otp;

	private final MailDispatcher mail;

	private final SessionService sessions;

	private final LoginThrottle throttle;

	private final Housekeeping housekeeping;

	PasswordResetService(Clock clock, TransactionTemplate tx, UserRepository users, PasswordPolicy passwordPolicy,
			PasswordHasher hasher, OtpService otp, MailDispatcher mail, SessionService sessions, LoginThrottle throttle,
			Housekeeping housekeeping) {
		this.clock = clock;
		this.tx = tx;
		this.users = users;
		this.passwordPolicy = passwordPolicy;
		this.hasher = hasher;
		this.otp = otp;
		this.mail = mail;
		this.sessions = sessions;
		this.throttle = throttle;
		this.housekeeping = housekeeping;
	}

	/** Starts a reset and returns its id. The answer is the same for every well-formed address. */
	public UUID request(String emailText, String ip) {
		String email = EmailAddress.normalise(emailText)
			.orElseThrow(() -> ApiException.field("email", "Enter a valid email address."));
		UUID resetId = UUID.randomUUID();
		sendCode(resetId, email, ip);
		housekeeping.runIfDue();
		return resetId;
	}

	/** "Send the code again" for a reset that was started less than half an hour ago. */
	public void resend(String resetIdText, String ip) {
		UUID resetId = Ids.parse(resetIdText).orElseThrow(AuthErrors::invalidCode);
		OtpService.FlowStart flow = otp.flowStart(resetId, OtpPurpose.PASSWORD_RESET)
			.filter(start -> start.startedAt().plus(FLOW_LIFETIME).isAfter(clock.instant()))
			.orElseThrow(AuthErrors::invalidCode);
		sendCode(resetId, flow.email(), ip);
	}

	/** Code and new password in one step. Afterwards every session of the user is gone; no login happens here. */
	public void confirm(String resetIdText, String code, String newPassword) {
		// The parts of the password rule that need no account come first: a typing mistake in the
		// new password should not cost one of the five tries. Nothing here depends on the account.
		passwordPolicy.problem(newPassword).ifPresent(problem -> {
			throw ApiException.field("newPassword", problem);
		});
		UUID resetId = Ids.parse(resetIdText).orElseThrow(AuthErrors::invalidCode);
		OtpService.Challenge challenge = otp.attempt(resetId, OtpPurpose.PASSWORD_RESET, code);

		// From here on the caller has proved to own the mailbox, so the answers may be specific.
		UserRow user = Optional.ofNullable(challenge.userId())
			.flatMap(users::findById)
			.orElseThrow(AuthErrors::invalidCode);
		passwordPolicy.personalProblem(newPassword, user.email(), user.phone()).ifPresent(problem -> {
			throw ApiException.field("newPassword", problem);
		});
		if (hasher.matches(newPassword, user.passwordHash())) {
			throw ApiException.field("newPassword",
					"Choose a password you have not used here before.");
		}
		String passwordHash = hasher.hash(newPassword);

		tx.executeWithoutResult(status -> {
			otp.consume(challenge);
			users.updatePassword(user.id(), passwordHash);
			sessions.deleteAllOf(user.id());
			otp.endAll(user.email(), OtpPurpose.PASSWORD_RESET);
			// the owner is back in control: earlier wrong guesses must not keep them locked out
			throttle.clearFailures(user.email());
		});
	}

	private void sendCode(UUID resetId, String email, String ip) {
		EmailMessage message = tx.execute(status -> {
			Optional<UserRow> user = users.findByEmail(email);
			return otp.issue(resetId, email, OtpPurpose.PASSWORD_RESET, user.map(UserRow::id).orElse(null),
					user.isPresent(), ip);
		});
		mail.send(message);
	}

}
