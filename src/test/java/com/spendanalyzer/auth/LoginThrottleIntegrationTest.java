package com.spendanalyzer.auth;

import static com.spendanalyzer.auth.AuthClient.OTHER_PASSWORD;
import static com.spendanalyzer.auth.AuthClient.PASSWORD;
import static com.spendanalyzer.auth.AuthClient.assertError;
import static com.spendanalyzer.auth.AuthClient.assertStatus;
import static com.spendanalyzer.auth.AuthClient.body;
import static com.spendanalyzer.auth.AuthClient.json;
import static com.spendanalyzer.auth.AuthClient.newEmail;
import static com.spendanalyzer.auth.AuthClient.newIp;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Password guessing is slowed down, identically for addresses with and without an account. */
class LoginThrottleIntegrationTest extends AuthIntegrationBase {

	private static final String WRONG = "Wrong#Guess123";

	@Autowired
	LoginThrottle throttle;

	private static long assertBlocked(MvcResult result, long maxSeconds) throws Exception {
		assertError(result, 429, "too_many_requests");
		long retryAfter = Long.parseLong(result.getResponse().getHeader("Retry-After"));
		assertThat(retryAfter).isBetween(1L, maxSeconds);
		assertThat(json(result).get("retryAfterSeconds").asLong()).isEqualTo(retryAfter);
		return retryAfter;
	}

	@Test
	void fiveWrongPasswordsFromOneAddressBlockThatPairForFifteenMinutes() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String attacker = newIp();

		for (int i = 0; i < 5; i++) {
			assertError(client.tryLogin(email, WRONG, attacker), 401, "invalid_credentials");
		}
		// blocked now, even with the right password
		long retryAfter = assertBlocked(client.tryLogin(email, PASSWORD, attacker), 900);
		assertThat(retryAfter).isGreaterThan(840);
		assertBlocked(client.tryLogin(email, WRONG, attacker), 900);

		// the owner at another address is not affected
		assertStatus(client.tryLogin(email, PASSWORD, newIp()), 200);

		// after the fifteen minutes the pair may try again
		client.shiftBack(email, Duration.ofMinutes(15).plusSeconds(1));
		assertStatus(client.tryLogin(email, PASSWORD, attacker), 200);
	}

	@Test
	void anUnknownAddressIsThrottledTheSameWay() throws Exception {
		String email = newEmail();
		String ip = newIp();
		for (int i = 0; i < 5; i++) {
			assertError(client.tryLogin(email, WRONG, ip), 401, "invalid_credentials");
		}
		assertBlocked(client.tryLogin(email, WRONG, ip), 900);
	}

	@Test
	void aSuccessfulLoginStartsTheCountAgain() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String ip = newIp();

		for (int i = 0; i < 4; i++) {
			assertError(client.tryLogin(email, WRONG, ip), 401, "invalid_credentials");
		}
		assertStatus(client.tryLogin(email, PASSWORD, ip), 200);
		for (int i = 0; i < 4; i++) {
			assertError(client.tryLogin(email, WRONG, ip), 401, "invalid_credentials");
		}
		assertStatus(client.tryLogin(email, PASSWORD, ip), 200);
	}

	@Test
	void twentyWrongPasswordsFromAnywhereBlockTheEmailForAnHourButResetStillWorks() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);

		// a spread-out attack: every guess from another address, so the per-address rule never fires
		for (int i = 0; i < 20; i++) {
			assertError(client.tryLogin(email, WRONG, newIp()), 401, "invalid_credentials");
		}
		long retryAfter = assertBlocked(client.tryLogin(email, PASSWORD, newIp()), 3600);
		assertThat(retryAfter).isGreaterThan(3500);

		// the owner is not locked out: password reset works and lifts the block
		String resetId = client.requestReset(email, newIp());
		assertStatus(client.confirmReset(resetId, client.lastCode(email), OTHER_PASSWORD), 204);
		assertStatus(client.tryLogin(email, OTHER_PASSWORD, newIp()), 200);
	}

	@Test
	void theEmailBlockEndsAfterAnHour() throws Exception {
		String email = newEmail();
		for (int i = 0; i < 20; i++) {
			assertError(client.tryLogin(email, WRONG, newIp()), 401, "invalid_credentials");
		}
		assertBlocked(client.tryLogin(email, WRONG, newIp()), 3600);

		client.shiftBack(email, Duration.ofMinutes(60).plusSeconds(1));
		assertError(client.tryLogin(email, WRONG, newIp()), 401, "invalid_credentials");
	}

	@Test
	void parallelGuessesCannotOutrunTheLimit() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String ip = newIp();

		int guesses = 12;
		ExecutorService pool = Executors.newFixedThreadPool(guesses);
		List<Integer> statuses = new ArrayList<>();
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> answers = new ArrayList<>();
			for (int i = 0; i < guesses; i++) {
				Callable<Integer> guess = () -> {
					start.await();
					return AuthClient.status(client.tryLogin(email, WRONG, ip));
				};
				answers.add(pool.submit(guess));
			}
			start.countDown();
			for (Future<Integer> answer : answers) {
				statuses.add(answer.get());
			}
		}
		finally {
			pool.shutdownNow();
		}
		// exactly five passwords were checked, the rest were turned away
		assertThat(statuses.stream().filter(status -> status == 401)).hasSize(5);
		assertThat(statuses.stream().filter(status -> status == 429)).hasSize(guesses - 5);
	}

	@Test
	void aCancelledAttemptIsNotCountedAsAWrongPassword() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String ip = newIp();

		// more than either limit: attempts whose password could not be checked at all
		for (int i = 0; i < LoginThrottle.EMAIL_LIMIT + 1; i++) {
			long attempt = throttle.claim(email, ip);
			assertThat(failures(email)).isEqualTo(1);
			throttle.cancel(attempt);
			assertThat(failures(email)).isZero();
		}
		assertStatus(client.tryLogin(email, PASSWORD, ip), 200);

		// cancelling takes back that one attempt only: real wrong passwords stay counted
		for (int i = 0; i < 4; i++) {
			assertError(client.tryLogin(email, WRONG, ip), 401, "invalid_credentials");
		}
		throttle.cancel(throttle.claim(email, ip));
		assertThat(failures(email)).isEqualTo(4);
		assertError(client.tryLogin(email, WRONG, ip), 401, "invalid_credentials");
		assertBlocked(client.tryLogin(email, PASSWORD, ip), 900);
	}

	private int failures(String email) {
		return jdbc.sql("SELECT count(*) FROM login_attempts WHERE email = ? AND NOT success")
			.param(email)
			.query(Integer.class)
			.single();
	}

	@Test
	void blankInputIsAValidationErrorNotAGuess() throws Exception {
		String email = newEmail();
		String ip = newIp();
		assertError(client.post("/api/auth/login", body("email", email, "password", ""), ip), 400, "validation");
		assertError(client.post("/api/auth/login", body("email", "nope", "password", WRONG), ip), 400, "validation");
		assertError(client.post("/api/auth/login", body(), ip), 400, "validation");
		assertThat(jdbc.sql("SELECT count(*) FROM login_attempts WHERE email = ?").param(email).query(Integer.class).single())
			.isZero();
	}

	@Test
	void anOverlongPasswordIsJustAWrongPassword() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		// longer than BCrypt's 72 bytes: must be a clean 401, not a server error
		assertError(client.tryLogin(email, PASSWORD + "x".repeat(200), newIp()), 401, "invalid_credentials");
	}

	@Test
	void aUserWhosePasswordHashIsNotBcryptCannotLogIn() throws Exception {
		// TestData.createUser stores "{noop}not-a-real-hash": that must never match anything
		com.spendanalyzer.security.AuthUser user = com.spendanalyzer.testsupport.TestData.createUser(jdbc);
		assertError(client.tryLogin(user.email(), "not-a-real-hash", newIp()), 401, "invalid_credentials");
	}

}
