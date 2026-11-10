package com.spendanalyzer.auth;

import java.util.UUID;

import com.spendanalyzer.common.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a logged-in user can do to their own account: change the password, delete the account.
 * Both ask for the current password again, so a borrowed, unlocked browser is not enough. Wrong
 * passwords here count towards the same limits as wrong passwords at login.
 */
@Service
public class AccountService {

	private final TransactionTemplate tx;

	private final UserRepository users;

	private final PasswordPolicy passwordPolicy;

	private final PasswordHasher hasher;

	private final LoginThrottle throttle;

	private final SessionService sessions;

	AccountService(TransactionTemplate tx, UserRepository users, PasswordPolicy passwordPolicy, PasswordHasher hasher,
			LoginThrottle throttle, SessionService sessions) {
		this.tx = tx;
		this.users = users;
		this.passwordPolicy = passwordPolicy;
		this.hasher = hasher;
		this.throttle = throttle;
		this.sessions = sessions;
	}

	/** The user of a logged-in request, for {@code GET /api/auth/me}. */
	public UserRow current(UUID userId) {
		return users.findById(userId).orElseThrow(AuthErrors::unauthorized);
	}

	/**
	 * @param currentToken the session of this request; it stays, every other session of the user ends
	 */
	public void changePassword(UUID userId, AuthRequests.ChangePassword form, String ip, String currentToken) {
		UserRow user = current(userId);
		requirePassword(user, form.currentPassword(), "currentPassword", "Enter your current password.",
				"The current password is wrong.", ip);
		passwordPolicy.problem(form.newPassword(), user.email(), user.phone()).ifPresent(problem -> {
			throw ApiException.field("newPassword", problem);
		});
		if (form.newPassword().equals(form.currentPassword())) {
			throw ApiException.field("newPassword", "Choose a password different from the current one.");
		}
		String passwordHash = hasher.hash(form.newPassword());
		tx.executeWithoutResult(status -> {
			users.updatePassword(user.id(), passwordHash);
			sessions.deleteAllExcept(user.id(), currentToken);
		});
	}

	/** Deletes the user and, through ON DELETE CASCADE, everything they ever entered. */
	public void delete(UUID userId, AuthRequests.DeleteAccount form, String ip) {
		UserRow user = current(userId);
		requirePassword(user, form.password(), "password", "Enter your password.", "The password is wrong.", ip);
		users.delete(user.id());
	}

	private void requirePassword(UserRow user, String password, String field, String missing, String wrong, String ip) {
		if (password == null || password.isEmpty()) {
			throw ApiException.field(field, missing);
		}
		long attempt = throttle.claim(user.email(), ip);
		if (!hasher.matches(password, user.passwordHash())) {
			throw ApiException.field(field, wrong);
		}
		throttle.succeeded(attempt);
	}

}
