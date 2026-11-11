package com.spendanalyzer.auth;

import static com.spendanalyzer.auth.AuthClient.OTHER_PASSWORD;
import static com.spendanalyzer.auth.AuthClient.PASSWORD;
import static com.spendanalyzer.auth.AuthClient.assertError;
import static com.spendanalyzer.auth.AuthClient.assertStatus;
import static com.spendanalyzer.auth.AuthClient.body;
import static com.spendanalyzer.auth.AuthClient.json;
import static com.spendanalyzer.auth.AuthClient.newEmail;
import static com.spendanalyzer.auth.AuthClient.newIp;
import static com.spendanalyzer.auth.AuthClient.wrongCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** How often codes may be requested: per flow, per address, per client, in total. All counted in the database. */
class SendLimitsIntegrationTest extends AuthIntegrationBase {

	@Autowired
	MailLog mailLog;

	private MvcResult requestReset(String email, String ip) throws Exception {
		return client.post("/api/auth/password-reset/request", body("email", email), ip);
	}

	private MvcResult resendReset(String resetId, String ip) throws Exception {
		return client.post("/api/auth/password-reset/resend", body("resetId", resetId), ip);
	}

	private MvcResult resendRegistration(String registrationId, String ip) throws Exception {
		return client.post("/api/auth/register/resend", body("registrationId", registrationId), ip);
	}

	/** Asserts a 429 whose Retry-After header and body agree and lie within the given bounds. */
	private static void assertTooMany(MvcResult result, long minSeconds, long maxSeconds) throws Exception {
		assertError(result, 429, "too_many_requests");
		long header = Long.parseLong(result.getResponse().getHeader("Retry-After"));
		assertThat(header).isBetween(minSeconds, maxSeconds);
		assertThat(json(result).get("retryAfterSeconds").asLong()).isEqualTo(header);
	}

	@Test
	void resendWithinAMinuteIsRefused() throws Exception {
		String email = newEmail();
		String ip = newIp();
		String registrationId = client.register(email, PASSWORD, ip);

		assertTooMany(resendRegistration(registrationId, ip), 50, 60);
		assertThat(client.codes(email)).hasSize(1);

		client.shiftBack(email, Duration.ofSeconds(61));
		MvcResult later = resendRegistration(registrationId, ip);
		assertStatus(later, 200);
		assertThat(json(later).get("expiresInSeconds").asInt()).isEqualTo(600);
		assertThat(json(later).get("resendAfterSeconds").asInt()).isEqualTo(60);
		assertThat(json(later).properties()).hasSize(2);
		assertThat(client.codes(email)).hasSize(2);
	}

	@Test
	void resetResendWithinAMinuteIsRefused() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String ip = newIp();
		String resetId = client.requestReset(email, ip);

		assertTooMany(resendReset(resetId, ip), 50, 60);
		client.shiftBack(email, Duration.ofSeconds(61));
		assertStatus(resendReset(resetId, ip), 200);
		assertThat(client.codes(email)).hasSize(2);
	}

	@Test
	void theSixthCodeWithinAnHourIsRefused() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String ip = newIp();
		String resetId = client.requestReset(email, ip);
		for (int i = 0; i < 4; i++) {
			client.shiftBack(email, Duration.ofSeconds(61));
			assertStatus(resendReset(resetId, ip), 200);
		}
		assertThat(client.codes(email)).hasSize(5);

		// the minute has passed, the hour has not
		client.shiftBack(email, Duration.ofSeconds(61));
		assertTooMany(resendReset(resetId, ip), 61, 3600);
		// a new reset for the same address is refused as well, from any client
		assertTooMany(requestReset(email, newIp()), 61, 3600);
		assertThat(client.codes(email)).hasSize(5);

		client.shiftBack(email, Duration.ofMinutes(56));
		assertStatus(requestReset(email, newIp()), 200);
	}

	@Test
	void theSixthRegistrationOfAnAddressWithinAnHourIsRefused() throws Exception {
		String email = newEmail();
		for (int i = 0; i < 5; i++) {
			client.register(email, PASSWORD, newIp());
		}
		MvcResult sixth = client.post("/api/auth/register", AuthClient.registerBody(email, PASSWORD), newIp());
		assertTooMany(sixth, 3500, 3600);
		assertThat(client.codes(email)).hasSize(5);
	}

	@Test
	void theEleventhCodeWithinADayIsRefused() throws Exception {
		// an address without an account: the limit must hold for those just the same
		String email = newEmail();
		for (int i = 0; i < 10; i++) {
			assertStatus(requestReset(email, newIp()), 200);
			// 13 minutes apart: never five within an hour
			client.shiftBack(email, Duration.ofMinutes(13));
		}
		assertTooMany(requestReset(email, newIp()), 3600, 86400);

		client.shiftBack(email, Duration.ofHours(22));
		assertStatus(requestReset(email, newIp()), 200);
	}

	@Test
	void oneClientAddressGetsTenCodeRequestsAnHour() throws Exception {
		String ip = newIp();
		String known = newEmail();
		client.insertUser(known, PASSWORD);
		// accounts and strangers mixed: both count the same
		assertStatus(requestReset(known, ip), 200);
		for (int i = 0; i < 8; i++) {
			assertStatus(requestReset(newEmail(), ip), 200);
		}
		client.register(newEmail(), PASSWORD, ip);

		assertTooMany(requestReset(newEmail(), ip), 3500, 3600);
		String blockedKnown = newEmail();
		client.insertUser(blockedKnown, PASSWORD);
		assertTooMany(requestReset(blockedKnown, ip), 3500, 3600);
		MvcResult register = client.post("/api/auth/register", AuthClient.registerBody(newEmail(), PASSWORD), ip);
		assertTooMany(register, 3500, 3600);

		// another client is not affected
		assertStatus(requestReset(blockedKnown, newIp()), 200);
	}

	@Test
	void fifteenWrongCodesFreezeSendingAndCheckingForThatAddressAndPurpose() throws Exception {
		String email = newEmail();
		String ip = newIp();
		String registrationId = client.register(email, PASSWORD, ip);
		for (int round = 0; round < 3; round++) {
			if (round > 0) {
				client.shiftBack(email, Duration.ofSeconds(61));
				assertStatus(resendRegistration(registrationId, ip), 200);
			}
			String code = client.lastCode(email);
			for (int i = 0; i < 5; i++) {
				assertError(client.post("/api/auth/register/verify",
						body("registrationId", registrationId, "code", wrongCode(code)), ip), 400, "invalid_code");
			}
		}

		// frozen: no further code, neither in this registration nor in a new one
		client.shiftBack(email, Duration.ofSeconds(61));
		assertTooMany(resendRegistration(registrationId, ip), 3600, 86400);
		MvcResult again = client.post("/api/auth/register", AuthClient.registerBody(email, PASSWORD), newIp());
		assertTooMany(again, 3600, 86400);
		// and no further guessing
		MvcResult guess = client.post("/api/auth/register/verify",
				body("registrationId", registrationId, "code", client.lastCode(email)), ip);
		assertTooMany(guess, 3600, 86400);
		assertThat(client.codes(email)).hasSize(3);

		// the freeze is per purpose: a password reset for the same address is still answered
		assertStatus(requestReset(email, newIp()), 200);

		// a day later it is over
		client.shiftBack(email, Duration.ofHours(24));
		client.register(email, PASSWORD, newIp());
		assertThat(client.codes(email)).hasSize(4);
	}

	@Test
	void aSolvedCodeDoesNotCountAsAWrongGuess() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String password = PASSWORD;
		// three resets, each: four wrong codes, then the right one = 12 wrong guesses and 3 right ones
		for (int round = 0; round < 3; round++) {
			String resetId = client.requestReset(email, newIp());
			String code = client.lastCode(email);
			for (int i = 0; i < 4; i++) {
				assertError(client.confirmReset(resetId, wrongCode(code), OTHER_PASSWORD), 400, "invalid_code");
			}
			password = password.equals(PASSWORD) ? OTHER_PASSWORD : PASSWORD;
			assertStatus(client.confirmReset(resetId, code, password), 204);
		}
		// 15 tries were made but only 12 failed: not frozen
		assertStatus(requestReset(email, newIp()), 200);
	}

	@Test
	void whenTheDailyMailBudgetIsSpentRequestsAreStillAnsweredButNothingIsSent() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String filler = "filler-" + newEmail();
		try {
			for (int i = 0; i < 200; i++) {
				jdbc.sql("INSERT INTO mail_log (recipient, kind, created_at) VALUES (?, 'REGISTER_CODE', now())")
					.param(filler)
					.update();
			}
			MvcResult reset = requestReset(email, newIp());
			assertStatus(reset, 200);
			assertThat(json(reset).properties()).hasSize(3);
			String newcomer = newEmail();
			client.register(newcomer, PASSWORD, newIp());

			client.awaitMail();
			assertThat(mailbox.mailsTo(email)).isEmpty();
			assertThat(mailbox.mailsTo(newcomer)).isEmpty();
			assertThat(client.mailKinds(email)).containsExactly("SUPPRESSED");
			assertThat(client.mailKinds(newcomer)).containsExactly("SUPPRESSED");
		}
		finally {
			jdbc.sql("DELETE FROM mail_log WHERE recipient = ?").param(filler).update();
		}

		// with budget again, mail flows
		client.shiftBack(email, Duration.ofSeconds(61));
		client.requestReset(email, newIp());
		assertThat(client.codes(email)).hasSize(1);
	}

	@Test
	void theGmailSenderStopsAtEightyMailsADayUnlessABudgetIsConfigured() {
		// the same mail log, seen with the limits of each mail provider
		SendLimits google = new SendLimits(jdbc, mailLog, new AuthLimits(10, null), "google");
		SendLimits brevo = new SendLimits(jdbc, mailLog, new AuthLimits(10, null), "brevo");
		SendLimits configured = new SendLimits(jdbc, mailLog, new AuthLimits(10, 120), "google");
		String filler = "filler-" + newEmail();
		try {
			sentToday(filler, 79);
			assertThat(google.mailBudgetLeft(Instant.now())).isTrue();

			// a suppressed request sent nothing and does not use up the budget
			jdbc.sql("INSERT INTO mail_log (recipient, kind, created_at) VALUES (?, 'SUPPRESSED', now())")
				.param(filler)
				.update();
			assertThat(google.mailBudgetLeft(Instant.now())).isTrue();

			sentToday(filler, 1);
			assertThat(google.mailBudgetLeft(Instant.now())).as("80 mails: below Gmail's 100 a day").isFalse();
			assertThat(brevo.mailBudgetLeft(Instant.now())).isTrue();
			assertThat(configured.mailBudgetLeft(Instant.now())).isTrue();

			sentToday(filler, 40);
			assertThat(configured.mailBudgetLeft(Instant.now())).isFalse();
			assertThat(brevo.mailBudgetLeft(Instant.now())).isTrue();

			// a day later the budget is back
			assertThat(google.mailBudgetLeft(Instant.now().plus(Duration.ofHours(25)))).isTrue();
		}
		finally {
			jdbc.sql("DELETE FROM mail_log WHERE recipient = ?").param(filler).update();
		}
	}

	private void sentToday(String recipient, int mails) {
		for (int i = 0; i < mails; i++) {
			jdbc.sql("INSERT INTO mail_log (recipient, kind, created_at) VALUES (?, 'REGISTER_CODE', now())")
				.param(recipient)
				.update();
		}
	}

	@Test
	void mailsAreLoggedByKind() throws Exception {
		String stranger = newEmail();
		String owner = newEmail();
		client.insertUser(owner, PASSWORD);

		client.register(stranger, PASSWORD, newIp());
		client.requestReset(stranger, newIp());
		client.requestReset(owner, newIp());
		client.register(owner, PASSWORD, newIp());

		// a stranger's reset and an owner's registration send no code
		assertThat(client.mailKinds(stranger)).containsExactly("REGISTER_CODE", "SUPPRESSED");
		assertThat(client.mailKinds(owner)).containsExactly("RESET_CODE", "SUPPRESSED", "ACCOUNT_NOTICE");
	}

}
