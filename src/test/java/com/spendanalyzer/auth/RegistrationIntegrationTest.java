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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.spendanalyzer.auth.mail.EmailMessage;
import com.spendanalyzer.common.ApiException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** Registration: input rules, "no account before the code", and what happens when the address is not yours. */
class RegistrationIntegrationTest extends AuthIntegrationBase {

	@Autowired
	Clock clock;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordPolicy passwordPolicy;

	@Autowired
	OtpService otp;

	@Autowired
	SendLimits limits;

	@Autowired
	MailLog mailLog;

	@Autowired
	SessionService sessions;

	@Autowired
	Housekeeping housekeeping;

	private MvcResult register(Map<String, Object> form) throws Exception {
		return client.post("/api/auth/register", form, newIp());
	}

	private MvcResult verify(String registrationId, String code) throws Exception {
		return client.post("/api/auth/register/verify", body("registrationId", registrationId, "code", code), newIp());
	}

	@Test
	void twoPeopleRegisteringOneAddressOnlyTheOneWithTheMailboxWins() throws Exception {
		String email = newEmail();
		String attackerPassword = "Attacker#Pass91";
		String ownerPassword = "Owner!Secret42z";

		// the attacker goes first, then the real owner; each browser gets its own registration id
		String attackerRegistration = client.register(email, attackerPassword, newIp());
		String attackerCode = client.lastCode(email);
		String ownerRegistration = client.register(email, ownerPassword, newIp());
		String ownerCode = client.lastCode(email);
		assertThat(ownerRegistration).isNotEqualTo(attackerRegistration);
		assertThat(client.codes(email)).hasSize(2);
		assertThat(client.userCount(email)).isZero();

		// a code only fits the registration it was mailed for
		if (!attackerCode.equals(ownerCode)) {
			assertError(verify(ownerRegistration, attackerCode), 400, "invalid_code");
		}

		// the owner types the code from her mailbox into her own browser: her password becomes the password
		Cookie session = AuthClient.sessionCookie(verifyOk(ownerRegistration, ownerCode));
		assertStatus(client.get("/api/auth/me", session), 200);
		assertStatus(client.tryLogin(email, ownerPassword, newIp()), 200);
		assertError(client.tryLogin(email, attackerPassword, newIp()), 401, "invalid_credentials");

		// the attacker's registration is dead, even if he somehow learned his code
		assertError(verify(attackerRegistration, attackerCode), 400, "invalid_code");
		assertError(client.tryLogin(email, attackerPassword, newIp()), 401, "invalid_credentials");
		assertThat(client.userCount(email)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM pending_registrations WHERE email = ?")
			.param(email)
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void theSecondOfTwoRegistrationsCannotTakeOverTheFirst() throws Exception {
		// the owner goes first this time; the attacker submits afterwards and must not replace her password
		String email = newEmail();
		String ownerPassword = "Owner!Secret42z";
		String attackerPassword = "Attacker#Pass91";
		String ownerRegistration = client.register(email, ownerPassword, newIp());
		String ownerCode = client.lastCode(email);
		client.register(email, attackerPassword, newIp());

		// the owner's first code still works with the owner's registration (codes are per registration)
		verifyOk(ownerRegistration, ownerCode);
		assertStatus(client.tryLogin(email, ownerPassword, newIp()), 200);
		assertError(client.tryLogin(email, attackerPassword, newIp()), 401, "invalid_credentials");
	}

	private MvcResult verifyOk(String registrationId, String code) throws Exception {
		MvcResult result = verify(registrationId, code);
		assertStatus(result, 200);
		return result;
	}

	@Test
	void emailIsStoredLowerCaseAndTrimmed() throws Exception {
		String email = newEmail();
		String typed = "  " + email.toUpperCase(Locale.ROOT) + " ";

		MvcResult result = register(AuthClient.registerBody(typed, PASSWORD));
		assertStatus(result, 200);
		String registrationId = json(result).get("registrationId").asString();
		MvcResult verified = verifyOk(registrationId, client.lastCode(email));
		assertThat(json(verified).get("user").get("email").asString()).isEqualTo(email);
		assertThat(client.userCount(email)).isEqualTo(1);

		// login is not case sensitive either
		assertStatus(client.tryLogin(typed, PASSWORD, newIp()), 200);
	}

	@Test
	void anAddressInAnotherCaseIsTheSameAccount() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);

		MvcResult result = register(AuthClient.registerBody(email.toUpperCase(Locale.ROOT), OTHER_PASSWORD));
		assertStatus(result, 200);
		String registrationId = json(result).get("registrationId").asString();

		// no code was mailed, the owner got a notice instead, and no second account can come of it
		assertThat(client.codes(email)).isEmpty();
		List<EmailMessage> mails = mailbox.mailsTo(email);
		assertThat(mails).hasSize(1);
		assertThat(mails.get(0).kind()).isEqualTo("ACCOUNT_NOTICE");
		assertThat(mails.get(0).subject()).isEqualTo("You already have a Spend Analyzer account");
		assertError(verify(registrationId, "123456"), 400, "invalid_code");
		assertThat(jdbc.sql("SELECT count(*) FROM users WHERE lower(email) = ?").param(email).query(Integer.class).single())
			.isEqualTo(1);
		assertError(client.tryLogin(email, OTHER_PASSWORD, newIp()), 401, "invalid_credentials");
		assertStatus(client.tryLogin(email, PASSWORD, newIp()), 200);
	}

	@Test
	void theNoticeToAnExistingOwnerIsSentAtMostOnceADay() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);

		client.register(email, OTHER_PASSWORD, newIp());
		client.register(email, OTHER_PASSWORD, newIp());
		client.awaitMail();
		assertThat(mailbox.mailsTo(email)).hasSize(1);

		client.shiftBack(email, Duration.ofHours(24).plusMinutes(1));
		client.register(email, OTHER_PASSWORD, newIp());
		client.awaitMail();
		assertThat(mailbox.mailsTo(email)).hasSize(2);
		assertThat(mailbox.mailsTo(email)).allMatch(mail -> mail.kind().equals("ACCOUNT_NOTICE"));
	}

	@Test
	void everyAcceptedPhoneFormIsStoredTheSameWay() throws Exception {
		for (String typed : List.of("9876543210", "09876543210", "919876543210", "+91 98765 43210", "+91-98765-43210",
				"(+91) 98765 43210")) {
			String email = newEmail();
			Map<String, Object> form = AuthClient.registerBody(email, PASSWORD);
			form.put("phone", typed);
			assertStatus(register(form), 200);
			String stored = jdbc.sql("SELECT phone FROM pending_registrations WHERE email = ?")
				.param(email)
				.query(String.class)
				.single();
			assertThat(stored).as(typed).isEqualTo("+919876543210");
		}
	}

	@Test
	void thePhoneNumberIsOnlyEverShownMasked() throws Exception {
		String email = newEmail();
		Map<String, Object> form = AuthClient.registerBody(email, PASSWORD);
		form.put("phone", "09876543210");
		MvcResult result = register(form);
		String registrationId = json(result).get("registrationId").asString();
		MvcResult verified = verifyOk(registrationId, client.lastCode(email));

		assertThat(json(verified).get("user").get("phoneMasked").asString())
			.isEqualTo("+91 ••••••3210");
		assertThat(client.seen).noneMatch(text -> text.contains("9876543210") || text.contains("987654"));
	}

	@Test
	void twoAccountsMayShareAPhoneNumber() throws Exception {
		String first = newEmail();
		String second = newEmail();
		client.signUp(first, PASSWORD);
		client.signUp(second, PASSWORD);
		assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email IN (?, ?) AND phone = '+919812345678'")
			.params(first, second)
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	@Test
	void everyWrongFieldIsReportedAtOnce() throws Exception {
		MvcResult result = register(body("name", "A", "email", "not-an-email", "phone", "12345", "password", "short"));
		assertError(result, 400, "validation");
		JsonNode fields = json(result).get("fields");
		assertThat(fields.get("name").asString()).isNotBlank();
		assertThat(fields.get("email").asString()).isNotBlank();
		assertThat(fields.get("phone").asString()).isNotBlank();
		assertThat(fields.get("password").asString()).isEqualTo("Use at least 10 characters.");
		assertThat(json(result).get("message").asString()).isEqualTo(fields.get("name").asString());

		MvcResult empty = register(body());
		assertError(empty, 400, "validation");
		assertThat(json(empty).get("fields").properties()).hasSize(4);
	}

	@Test
	void badInputIsRejectedFieldByField() throws Exception {
		String email = newEmail();
		assertFieldError("name", " ", email);
		assertFieldError("name", "x".repeat(61), email);
		assertFieldError("name", "Bala\u0000Student", email);
		assertFieldError("email", "a@b", email);
		assertFieldError("email", "two@at@example.com", email);
		assertFieldError("email", "line\nbreak@example.com", email);
		assertFieldError("email", "x".repeat(250) + "@example.com", email);
		assertFieldError("email", "\"quoted\"@example.com", email);
		assertFieldError("phone", "5876543210", email);
		assertFieldError("phone", "98765432100", email);
		assertFieldError("phone", "+1 98765 43210", email);
		assertFieldError("phone", "98765abc10", email);
		assertFieldError("phone", "+9198765 4321", email);
		assertThat(jdbc.sql("SELECT count(*) FROM pending_registrations WHERE email = ?")
			.param(email)
			.query(Integer.class)
			.single()).isZero();
	}

	private void assertFieldError(String field, String value, String email) throws Exception {
		Map<String, Object> form = AuthClient.registerBody(email, PASSWORD);
		form.put(field, value);
		MvcResult result = register(form);
		assertError(result, 400, "validation");
		assertThat(json(result).get("fields").properties()).as(field + "=" + value).hasSize(1);
		assertThat(json(result).get("fields").has(field)).as(field + "=" + value).isTrue();
	}

	@Test
	void thePasswordRuleIsEnforcedOnTheServer() throws Exception {
		String email = "priya.sharma" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
		String emailName = email.substring(0, email.indexOf('@'));

		assertPasswordRefused(email, "Short#1a", "Use at least 10 characters.");
		assertPasswordRefused(email, "alllowercase#12", "Add an upper-case letter.");
		assertPasswordRefused(email, "ALLUPPERCASE#12", "Add a lower-case letter.");
		assertPasswordRefused(email, "NoDigitsHere#!", "Add a digit.");
		assertPasswordRefused(email, "NoSymbolHere123", "Add a symbol such as @ or #.");
		assertPasswordRefused(email, "Xy1#" + emailName.toUpperCase(Locale.ROOT), "Do not put your email name in the password.");
		assertPasswordRefused(email, "Aa#9812345678", "Do not put your phone number in the password.");
		assertPasswordRefused(email, "Password@123", "That password is too well known. Choose another one.");
		assertPasswordRefused(email, "welcome@123".toUpperCase(Locale.ROOT).replace("W", "w"),
				"That password is too well known. Choose another one.");
		assertPasswordRefused(email, "Aa1!" + "€".repeat(23),
				"That password is too long for the letters it uses. Make it shorter.");
		assertPasswordRefused(email, "Aa1!" + "x".repeat(61), "Use at most 64 characters.");
		assertThat(mailbox.mailsTo(email)).isEmpty();
	}

	private void assertPasswordRefused(String email, String password, String message) throws Exception {
		MvcResult result = register(AuthClient.registerBody(email, password));
		assertError(result, 400, "validation");
		assertThat(json(result).get("fields").get("password").asString()).isEqualTo(message);
		// the answer never repeats what was typed
		assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
	}

	@Test
	void unknownPropertiesAndNonJsonBodiesAreRejected() throws Exception {
		Map<String, Object> form = AuthClient.registerBody(newEmail(), PASSWORD);
		form.put("admin", true);
		assertError(register(form), 400, "bad_request");

		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("email=a"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.error").value("json_required"));
		mvc.perform(post("/api/auth/register").header("Origin", "https://evil.example")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("cross_site"));
	}

	@Test
	void aRegistrationWaitsThirtyMinutesForItsCode() throws Exception {
		String email = newEmail();
		String ip = newIp();
		String registrationId = client.register(email, PASSWORD, ip);

		jdbc.sql("UPDATE pending_registrations SET expires_at = now() - interval '1 second' WHERE email = ?")
			.param(email)
			.update();
		assertError(client.post("/api/auth/register/resend", body("registrationId", registrationId), ip), 400,
				"invalid_code");
		assertError(verify(registrationId, client.lastCode(email)), 400, "invalid_code");
		assertThat(client.userCount(email)).isZero();

		assertError(client.post("/api/auth/register/resend", body("registrationId", UUID.randomUUID().toString()), ip),
				400, "invalid_code");
		assertError(client.post("/api/auth/register/resend", body("registrationId", "nonsense"), ip), 400,
				"invalid_code");
	}

	@Test
	void registrationCanBeSwitchedOff() {
		// the switch is the property app.registration-enabled; a second service instance avoids a second context
		RegistrationService closed = new RegistrationService(false, jdbc, clock, tx, users, passwordPolicy, hasher, otp,
				limits, mailLog, dispatcher, sessions, housekeeping);
		String email = newEmail();
		AuthRequests.Register form = new AuthRequests.Register("Bala Student", email, "9812345678", PASSWORD);

		assertThat(closed.enabled()).isFalse();
		assertClosed(() -> closed.register(form, newIp()));
		assertClosed(() -> closed.resend(UUID.randomUUID().toString(), newIp()));
		assertClosed(() -> closed.verify(UUID.randomUUID().toString(), "123456"));
		assertThat(jdbc.sql("SELECT count(*) FROM pending_registrations WHERE email = ?")
			.param(email)
			.query(Integer.class)
			.single()).isZero();
	}

	private static void assertClosed(Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(ex.code()).isEqualTo("registration_closed");
		});
	}

}
