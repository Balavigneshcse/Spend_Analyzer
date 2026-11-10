package com.spendanalyzer.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.spendanalyzer.common.ApiException;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;

/**
 * A password check that could not run (no free hashing slot, a database error) is not a wrong
 * password: the attempt that was written down beforehand is taken back. A password that was
 * compared and found wrong stays counted. No application and no database: the collaborators are
 * small stand-ins.
 */
class UncheckedPasswordTest {

	private static final long ATTEMPT = 41L;

	private static final UserRow USER = new UserRow(UUID.randomUUID(), "student@example.com", "Student",
			"+919812345678", "{bcrypt}stored");

	/** What {@link PasswordHasher} throws when neither of its two slots frees up in time. */
	private static final ApiException BUSY = AuthErrors.tooMany(Duration.ofSeconds(5));

	private final RecordingThrottle throttle = new RecordingThrottle();

	private static final class RecordingThrottle extends LoginThrottle {

		final List<Long> succeeded = new ArrayList<>();

		final List<Long> cancelled = new ArrayList<>();

		RecordingThrottle() {
			super(null, null, null);
		}

		@Override
		long claim(String email, String ip) {
			return ATTEMPT;
		}

		@Override
		void succeeded(long attemptId) {
			succeeded.add(attemptId);
		}

		@Override
		void cancel(long attemptId) {
			cancelled.add(attemptId);
		}

	}

	/** A hasher whose check either fails with the given exception or, without one, says "wrong". */
	private static PasswordHasher hasher(RuntimeException failure) {
		return new PasswordHasher(10, new SecureTokens()) {
			@Override
			public boolean matches(String rawPassword, String storedHash) {
				if (failure != null) {
					throw failure;
				}
				return false;
			}
		};
	}

	private static UserRepository users(RuntimeException failure) {
		return new UserRepository(null) {
			@Override
			public Optional<UserRow> findByEmail(String email) {
				if (failure != null) {
					throw failure;
				}
				return Optional.of(USER);
			}

			@Override
			public Optional<UserRow> findById(UUID id) {
				return Optional.of(USER);
			}
		};
	}

	private LoginService login(UserRepository users, PasswordHasher hasher) {
		return new LoginService(users, hasher, throttle, null, null);
	}

	private AccountService account(PasswordHasher hasher) {
		return new AccountService(null, users(null), null, hasher, throttle, null);
	}

	private static AuthRequests.Login loginForm() {
		return new AuthRequests.Login(USER.email(), "Mango#Tree42x");
	}

	// ------------------------------------------------------------------ login

	@Test
	void aLoginTurnedAwayForLackOfAHashingSlotIsNotAWrongPassword() {
		LoginService service = login(users(null), hasher(BUSY));

		assertThatThrownBy(() -> service.login(loginForm(), "10.0.0.1", null)).isSameAs(BUSY)
			.satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

		assertThat(throttle.cancelled).containsExactly(ATTEMPT);
		assertThat(throttle.succeeded).isEmpty();
	}

	@Test
	void aLoginThatFailedInTheDatabaseIsNotAWrongPassword() {
		IllegalStateException down = new IllegalStateException("the database cannot be reached");
		LoginService service = login(users(down), hasher(null));

		assertThatThrownBy(() -> service.login(loginForm(), "10.0.0.1", null)).isSameAs(down);

		assertThat(throttle.cancelled).containsExactly(ATTEMPT);
	}

	@Test
	void aWrongPasswordAtLoginStaysCounted() {
		LoginService service = login(users(null), hasher(null));

		assertThatThrownBy(() -> service.login(loginForm(), "10.0.0.1", null)).isInstanceOf(ApiException.class)
			.satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("invalid_credentials"));

		assertThat(throttle.cancelled).isEmpty();
		assertThat(throttle.succeeded).isEmpty();
	}

	// ------------------------------------------------------------------ account page

	@Test
	void aPasswordChangeTurnedAwayForLackOfAHashingSlotIsNotAWrongPassword() {
		AccountService service = account(hasher(BUSY));
		AuthRequests.ChangePassword form = new AuthRequests.ChangePassword("Mango#Tree42x", "River!Stone73q");

		assertThatThrownBy(() -> service.changePassword(USER.id(), form, "10.0.0.1", null)).isSameAs(BUSY);

		assertThat(throttle.cancelled).containsExactly(ATTEMPT);
		assertThat(throttle.succeeded).isEmpty();
	}

	@Test
	void anAccountDeletionTurnedAwayForLackOfAHashingSlotIsNotAWrongPassword() {
		AccountService service = account(hasher(BUSY));

		assertThatThrownBy(() -> service.delete(USER.id(), new AuthRequests.DeleteAccount("Mango#Tree42x"), "10.0.0.1"))
			.isSameAs(BUSY);

		assertThat(throttle.cancelled).containsExactly(ATTEMPT);
	}

	@Test
	void aWrongPasswordOnTheAccountPageStaysCounted() {
		AccountService service = account(hasher(null));

		assertThatThrownBy(() -> service.delete(USER.id(), new AuthRequests.DeleteAccount("Wrong#Guess123"), "10.0.0.1"))
			.isInstanceOf(ApiException.class)
			.satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("validation"));

		assertThat(throttle.cancelled).isEmpty();
		assertThat(throttle.succeeded).isEmpty();
	}

}
