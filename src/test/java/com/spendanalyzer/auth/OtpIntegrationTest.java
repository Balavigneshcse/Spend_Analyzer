package com.spendanalyzer.auth;

import static com.spendanalyzer.auth.AuthClient.OTHER_PASSWORD;
import static com.spendanalyzer.auth.AuthClient.PASSWORD;
import static com.spendanalyzer.auth.AuthClient.assertError;
import static com.spendanalyzer.auth.AuthClient.assertStatus;
import static com.spendanalyzer.auth.AuthClient.body;
import static com.spendanalyzer.auth.AuthClient.newEmail;
import static com.spendanalyzer.auth.AuthClient.newIp;
import static com.spendanalyzer.auth.AuthClient.wrongCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** The emailed codes: tries, expiry, single use, purpose, and that the cap holds under parallel guessing. */
class OtpIntegrationTest extends AuthIntegrationBase {

	@Autowired
	OtpHmac hmac;

	private MvcResult verify(String registrationId, String code) throws Exception {
		return client.post("/api/auth/register/verify", body("registrationId", registrationId, "code", code), newIp());
	}

	@Test
	void afterFiveWrongCodesTheRightCodeFailsToo() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);

		for (int i = 0; i < 5; i++) {
			assertError(verify(registrationId, wrongCode(code)), 400, "invalid_code");
		}
		assertThat(client.attempts(registrationId)).isEqualTo(5);
		assertError(verify(registrationId, code), 400, "invalid_code");
		assertThat(client.attempts(registrationId)).isEqualTo(5);
		assertThat(client.userCount(email)).isZero();
	}

	@Test
	void afterFiveWrongCodesTheRightResetCodeFailsToo() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String resetId = client.requestReset(email, newIp());
		String code = client.lastCode(email);

		for (int i = 0; i < 5; i++) {
			assertError(client.confirmReset(resetId, wrongCode(code), OTHER_PASSWORD), 400, "invalid_code");
		}
		assertError(client.confirmReset(resetId, code, OTHER_PASSWORD), 400, "invalid_code");
		assertStatus(client.tryLogin(email, PASSWORD, newIp()), 200);
	}

	@Test
	void theFifthTryStillCounts() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);

		for (int i = 0; i < 4; i++) {
			assertError(verify(registrationId, wrongCode(code)), 400, "invalid_code");
		}
		assertStatus(verify(registrationId, code), 200);
	}

	@Test
	void twentyParallelWrongGuessesGetExactlyFiveTries() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);
		String wrong = wrongCode(code);

		int guesses = 20;
		ExecutorService pool = Executors.newFixedThreadPool(guesses);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> answers = new ArrayList<>();
			for (int i = 0; i < guesses; i++) {
				Callable<Integer> guess = () -> {
					start.await();
					return AuthClient.status(verify(registrationId, wrong));
				};
				answers.add(pool.submit(guess));
			}
			start.countDown();
			for (Future<Integer> answer : answers) {
				assertThat(answer.get()).isEqualTo(400);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(client.attempts(registrationId)).isEqualTo(5);
		// the five tries are used up: even the right code is refused now
		assertError(verify(registrationId, code), 400, "invalid_code");
		assertThat(client.userCount(email)).isZero();
	}

	@Test
	void parallelRightCodesCreateOneUser() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);

		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> answers = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Callable<Integer> request = () -> {
					start.await();
					return AuthClient.status(verify(registrationId, code));
				};
				answers.add(pool.submit(request));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> answer : answers) {
				statuses.add(answer.get());
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 400, 400, 400);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(client.userCount(email)).isEqualTo(1);
	}

	@Test
	void aRegistrationCodeIsUselessForPasswordReset() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);

		assertError(client.confirmReset(registrationId, code, OTHER_PASSWORD), 400, "invalid_code");
		// the reset endpoint did not even touch the registration's challenge
		assertThat(client.attempts(registrationId)).isZero();
		assertStatus(verify(registrationId, code), 200);
	}

	@Test
	void aResetCodeIsUselessForRegistration() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String resetId = client.requestReset(email, newIp());
		String code = client.lastCode(email);

		assertError(verify(resetId, code), 400, "invalid_code");
		assertThat(client.attempts(resetId)).isZero();
		assertStatus(client.confirmReset(resetId, code, OTHER_PASSWORD), 204);
	}

	@Test
	void theStoredValueIsBoundToPurposeChallengeAndAddress() {
		UUID id = UUID.randomUUID();
		byte[] register = hmac.of(id, OtpPurpose.REGISTER, "a@example.com", "123456");
		assertThat(register).hasSize(32);
		assertThat(register).isEqualTo(hmac.of(id, OtpPurpose.REGISTER, "a@example.com", "123456"));
		assertThat(register).isNotEqualTo(hmac.of(id, OtpPurpose.PASSWORD_RESET, "a@example.com", "123456"));
		assertThat(register).isNotEqualTo(hmac.of(UUID.randomUUID(), OtpPurpose.REGISTER, "a@example.com", "123456"));
		assertThat(register).isNotEqualTo(hmac.of(id, OtpPurpose.REGISTER, "b@example.com", "123456"));
		assertThat(register).isNotEqualTo(hmac.of(id, OtpPurpose.REGISTER, "a@example.com", "123457"));
	}

	@Test
	void theCodeItselfIsNotInTheDatabase() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);

		byte[] stored = jdbc.sql("SELECT code_hmac FROM otp_challenges WHERE flow_id = ?")
			.param(UUID.fromString(registrationId))
			.query(byte[].class)
			.single();
		assertThat(stored).hasSize(32);
		assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(code);
		assertThat(stored).isNotEqualTo(SecureTokens.sha256(code));
	}

	@Test
	void anExpiredCodeIsRefused() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);

		client.shiftBack(email, Duration.ofMinutes(10).plusSeconds(1));
		assertError(verify(registrationId, code), 400, "invalid_code");
		assertThat(client.userCount(email)).isZero();
	}

	@Test
	void anExpiredResetCodeIsRefused() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String resetId = client.requestReset(email, newIp());
		String code = client.lastCode(email);

		client.shiftBack(email, Duration.ofMinutes(10).plusSeconds(1));
		assertError(client.confirmReset(resetId, code, OTHER_PASSWORD), 400, "invalid_code");
		assertStatus(client.tryLogin(email, PASSWORD, newIp()), 200);
	}

	@Test
	void aCodeWorksOnlyOnce() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);
		assertStatus(verify(registrationId, code), 200);
		assertError(verify(registrationId, code), 400, "invalid_code");
		assertThat(client.userCount(email)).isEqualTo(1);

		String resetId = client.requestReset(email, newIp());
		String resetCode = client.lastCode(email);
		assertStatus(client.confirmReset(resetId, resetCode, OTHER_PASSWORD), 204);
		// a second use must not change the password again
		assertError(client.confirmReset(resetId, resetCode, "Third#Secret91z"), 400, "invalid_code");
		assertStatus(client.tryLogin(email, OTHER_PASSWORD, newIp()), 200);
	}

	@Test
	void aNewCodeEndsThePreviousOne() throws Exception {
		String email = newEmail();
		String ip = newIp();
		String registrationId = client.register(email, PASSWORD, ip);
		String first = client.lastCode(email);

		client.shiftBack(email, Duration.ofSeconds(61));
		assertStatus(client.post("/api/auth/register/resend", body("registrationId", registrationId), ip), 200);
		String second = client.lastCode(email);
		assertThat(client.codes(email)).hasSize(2);

		if (!first.equals(second)) {
			assertError(verify(registrationId, first), 400, "invalid_code");
		}
		assertStatus(verify(registrationId, second), 200);
	}

	@Test
	void onlySixDigitsAreACodeAndAnythingElseCostsNoTry() throws Exception {
		String email = newEmail();
		String registrationId = client.register(email, PASSWORD, newIp());
		String code = client.lastCode(email);

		for (String notACode : new String[] { "", "12345", "1234567", "12a456", " " + code, code + " ", "１２３４５６",
				"-12345", "1e5000" }) {
			assertError(verify(registrationId, notACode), 400, "invalid_code");
		}
		assertError(verify(registrationId, null), 400, "invalid_code");
		assertError(verify("not-a-uuid", code), 400, "invalid_code");
		assertError(verify(UUID.randomUUID().toString(), code), 400, "invalid_code");
		assertError(verify(null, code), 400, "invalid_code");
		assertThat(client.attempts(registrationId)).isZero();
		assertStatus(verify(registrationId, code), 200);
	}

}
