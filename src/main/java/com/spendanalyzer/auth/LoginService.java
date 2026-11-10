package com.spendanalyzer.auth;

import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Email and password login. Unknown email and wrong password get the same answer after the same
 * amount of work (a BCrypt check always runs), so neither the text nor the timing of the answer
 * says whether an address has an account.
 */
@Service
public class LoginService {

	private final UserRepository users;

	private final PasswordHasher hasher;

	private final LoginThrottle throttle;

	private final SessionService sessions;

	private final Housekeeping housekeeping;

	LoginService(UserRepository users, PasswordHasher hasher, LoginThrottle throttle, SessionService sessions,
			Housekeeping housekeeping) {
		this.users = users;
		this.hasher = hasher;
		this.throttle = throttle;
		this.sessions = sessions;
		this.housekeeping = housekeeping;
	}

	/** A successful login: the user and the token of the new session. */
	public record LoggedIn(UserRow user, String sessionToken) {
	}

	/**
	 * @param previousToken the session token the browser sent along, if any; that session is ended
	 * so that logging in again never leaves old sessions behind
	 */
	public LoggedIn login(AuthRequests.Login form, String ip, String previousToken) {
		FieldErrors errors = new FieldErrors();
		String email = EmailAddress.normalise(form.email()).orElse(null);
		if (email == null) {
			errors.add("email", "Enter a valid email address.");
		}
		if (form.password() == null || form.password().isEmpty()) {
			errors.add("password", "Enter your password.");
		}
		errors.throwIfAny();

		long attempt = throttle.claim(email, ip);
		Optional<UserRow> user = users.findByEmail(email);
		boolean matches = hasher.matches(form.password(), user.map(UserRow::passwordHash).orElse(null));
		if (!matches) {
			throw AuthErrors.invalidCredentials();
		}
		throttle.succeeded(attempt);

		UserRow found = user.get();
		if (hasher.needsRehash(found.passwordHash())) {
			// the configured BCrypt cost was raised since this password was stored
			users.updatePassword(found.id(), hasher.hash(form.password()));
		}
		sessions.delete(previousToken);
		String token = sessions.create(found.id());
		housekeeping.runIfDue();
		return new LoggedIn(found, token);
	}

}
