package com.spendanalyzer.auth;

import static com.spendanalyzer.auth.AuthClient.OTHER_PASSWORD;
import static com.spendanalyzer.auth.AuthClient.PASSWORD;
import static com.spendanalyzer.auth.AuthClient.assertStatus;
import static com.spendanalyzer.auth.AuthClient.body;
import static com.spendanalyzer.auth.AuthClient.json;
import static com.spendanalyzer.auth.AuthClient.newEmail;
import static com.spendanalyzer.auth.AuthClient.newIp;
import static com.spendanalyzer.auth.AuthClient.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.test.web.servlet.MvcResult;

/**
 * Nobody may find out from the API whether an email address has an account. For every public
 * endpoint the answer for an address with an account is compared with the answer for one without.
 */
class EnumerationIntegrationTest extends AuthIntegrationBase {

	/**
	 * Same status, same headers that matter, same JSON.
	 * @param varyingFields fields that differ between any two calls (random ids, seconds left): they
	 * must be present in both answers, their values are not compared
	 */
	private static void assertSameAnswer(MvcResult known, MvcResult unknown, String... varyingFields) throws Exception {
		assertThat(status(unknown)).isEqualTo(status(known));
		assertThat(unknown.getResponse().getHeader("Retry-After") == null)
			.isEqualTo(known.getResponse().getHeader("Retry-After") == null);
		assertThat(unknown.getResponse().getHeaders("Set-Cookie")).isEqualTo(known.getResponse().getHeaders("Set-Cookie"));
		String knownBody = known.getResponse().getContentAsString();
		String unknownBody = unknown.getResponse().getContentAsString();
		if (knownBody.isEmpty() || unknownBody.isEmpty()) {
			assertThat(unknownBody).isEqualTo(knownBody);
			return;
		}
		JsonNode knownJson = json(known);
		JsonNode unknownJson = json(unknown);
		assertThat(names(unknownJson)).isEqualTo(names(knownJson));
		List<String> varying = List.of(varyingFields);
		for (Map.Entry<String, JsonNode> property : knownJson.properties()) {
			JsonNode other = unknownJson.get(property.getKey());
			if (varying.contains(property.getKey())) {
				assertThat(other.getNodeType()).as(property.getKey()).isEqualTo(property.getValue().getNodeType());
			}
			else {
				assertThat(other).as(property.getKey()).isEqualTo(property.getValue());
			}
		}
	}

	private static void assertIsUuid(MvcResult result, String field) throws Exception {
		assertThat(UUID.fromString(json(result).get(field).asString())).isNotNull();
	}

	private static List<String> names(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.properties().forEach(property -> names.add(property.getKey()));
		return names;
	}

	@Test
	void registerAnswersTheSameForATakenAddress() throws Exception {
		String taken = newEmail();
		client.insertUser(taken, PASSWORD);
		String free = newEmail();

		MvcResult forTaken = client.post("/api/auth/register", AuthClient.registerBody(taken, OTHER_PASSWORD), newIp());
		MvcResult forFree = client.post("/api/auth/register", AuthClient.registerBody(free, OTHER_PASSWORD), newIp());
		assertStatus(forFree, 200);
		assertSameAnswer(forTaken, forFree, "registrationId");
		assertIsUuid(forTaken, "registrationId");
		assertIsUuid(forFree, "registrationId");
		assertThat(names(json(forFree))).containsExactly("registrationId", "expiresInSeconds", "resendAfterSeconds");

		// behind the scenes: a parked form and a challenge exist for both
		for (String email : List.of(taken, free)) {
			assertThat(jdbc.sql("SELECT count(*) FROM pending_registrations WHERE email = ?")
				.param(email)
				.query(Integer.class)
				.single()).isEqualTo(1);
			assertThat(jdbc.sql("SELECT count(*) FROM otp_challenges WHERE email = ? AND purpose = 'REGISTER'")
				.param(email)
				.query(Integer.class)
				.single()).isEqualTo(1);
		}
		assertThat(client.codes(free)).hasSize(1);
		assertThat(client.codes(taken)).isEmpty();

		// the follow-up calls cannot tell them apart either
		String takenId = json(forTaken).get("registrationId").asString();
		String freeId = json(forFree).get("registrationId").asString();
		String wrong = AuthClient.wrongCode(client.lastCode(free));
		String ip = newIp();
		MvcResult takenEarly = client.post("/api/auth/register/resend", body("registrationId", takenId), ip);
		MvcResult freeEarly = client.post("/api/auth/register/resend", body("registrationId", freeId), ip);
		assertStatus(freeEarly, 429);
		assertSameAnswer(takenEarly, freeEarly, "retryAfterSeconds");
		for (int i = 0; i < 6; i++) {
			MvcResult takenTry = client.post("/api/auth/register/verify", body("registrationId", takenId, "code", wrong), ip);
			MvcResult freeTry = client.post("/api/auth/register/verify", body("registrationId", freeId, "code", wrong), ip);
			assertStatus(freeTry, 400);
			assertSameAnswer(takenTry, freeTry);
		}
		assertThat(client.attempts(takenId)).isEqualTo(client.attempts(freeId)).isEqualTo(5);
		for (String email : List.of(taken, free)) {
			client.shiftBack(email, Duration.ofSeconds(61));
		}
		MvcResult takenLater = client.post("/api/auth/register/resend", body("registrationId", takenId), ip);
		MvcResult freeLater = client.post("/api/auth/register/resend", body("registrationId", freeId), ip);
		assertStatus(freeLater, 200);
		assertSameAnswer(takenLater, freeLater);
		assertThat(client.userCount(taken)).isEqualTo(1);
		assertThat(client.userCount(free)).isZero();
	}

	@Test
	void loginAnswersTheSameForAnUnknownAddressAndAWrongPassword() throws Exception {
		String known = newEmail();
		client.insertUser(known, PASSWORD);
		String unknown = newEmail();

		MvcResult wrongPassword = client.tryLogin(known, OTHER_PASSWORD, newIp());
		MvcResult noSuchUser = client.tryLogin(unknown, OTHER_PASSWORD, newIp());
		assertStatus(wrongPassword, 401);
		assertSameAnswer(wrongPassword, noSuchUser);
		assertThat(noSuchUser.getResponse().getContentAsString())
			.isEqualTo(wrongPassword.getResponse().getContentAsString());
		assertThat(json(wrongPassword).get("error").asString()).isEqualTo("invalid_credentials");

		// both are written down the same way, so throttling cannot tell them apart either
		for (String email : List.of(known, unknown)) {
			assertThat(jdbc.sql("SELECT count(*) FROM login_attempts WHERE email = ? AND NOT success")
				.param(email)
				.query(Integer.class)
				.single()).isEqualTo(1);
		}
	}

	@Test
	void resetRequestAnswersTheSameForAnUnknownAddress() throws Exception {
		String known = newEmail();
		client.insertUser(known, PASSWORD);
		String unknown = newEmail();
		String ip = newIp();

		MvcResult forKnown = client.post("/api/auth/password-reset/request", body("email", known), ip);
		MvcResult forUnknown = client.post("/api/auth/password-reset/request", body("email", unknown), ip);
		assertStatus(forKnown, 200);
		assertSameAnswer(forKnown, forUnknown, "resetId");
		assertIsUuid(forKnown, "resetId");
		assertIsUuid(forUnknown, "resetId");
		assertThat(names(json(forUnknown))).containsExactly("resetId", "expiresInSeconds", "resendAfterSeconds");

		// a mail only for the real account; a challenge for both (the stranger's belongs to no user)
		assertThat(client.codes(known)).hasSize(1);
		assertThat(mailbox.mailsTo(unknown)).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM otp_challenges WHERE email = ? AND user_id IS NOT NULL")
			.param(known)
			.query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM otp_challenges WHERE email = ? AND user_id IS NULL")
			.param(unknown)
			.query(Integer.class)
			.single()).isEqualTo(1);

		// resend: refused alike within the minute, accepted alike after it
		String knownId = json(forKnown).get("resetId").asString();
		String unknownId = json(forUnknown).get("resetId").asString();
		MvcResult knownEarly = client.post("/api/auth/password-reset/resend", body("resetId", knownId), ip);
		MvcResult unknownEarly = client.post("/api/auth/password-reset/resend", body("resetId", unknownId), ip);
		assertStatus(knownEarly, 429);
		assertSameAnswer(knownEarly, unknownEarly, "retryAfterSeconds");
		for (String email : List.of(known, unknown)) {
			client.shiftBack(email, Duration.ofSeconds(61));
		}
		MvcResult knownLater = client.post("/api/auth/password-reset/resend", body("resetId", knownId), ip);
		MvcResult unknownLater = client.post("/api/auth/password-reset/resend", body("resetId", unknownId), ip);
		assertStatus(knownLater, 200);
		assertSameAnswer(knownLater, unknownLater);
		assertThat(client.codes(known)).hasSize(2);
		assertThat(mailbox.mailsTo(unknown)).isEmpty();
	}

	@Test
	void resetConfirmAnswersTheSameForAnUnknownAddress() throws Exception {
		String known = newEmail();
		client.insertUser(known, PASSWORD);
		String unknown = newEmail();
		String knownId = client.requestReset(known, newIp());
		String unknownId = client.requestReset(unknown, newIp());
		String wrong = AuthClient.wrongCode(client.lastCode(known));

		// a weak password is refused before the code is looked at, for both
		MvcResult knownWeak = client.confirmReset(knownId, wrong, "weak");
		MvcResult unknownWeak = client.confirmReset(unknownId, wrong, "weak");
		assertStatus(knownWeak, 400);
		assertSameAnswer(knownWeak, unknownWeak);
		assertThat(json(knownWeak).get("error").asString()).isEqualTo("validation");
		assertThat(client.attempts(knownId)).isZero();

		// a password containing the account's phone number must not give the account away before the code is right
		MvcResult knownPhone = client.confirmReset(knownId, wrong, "Aa#9812345678");
		MvcResult unknownPhone = client.confirmReset(unknownId, wrong, "Aa#9812345678");
		assertSameAnswer(knownPhone, unknownPhone);
		assertThat(json(knownPhone).get("error").asString()).isEqualTo("invalid_code");

		// wrong codes, including beyond the five tries
		for (int i = 0; i < 6; i++) {
			MvcResult knownTry = client.confirmReset(knownId, wrong, OTHER_PASSWORD);
			MvcResult unknownTry = client.confirmReset(unknownId, wrong, OTHER_PASSWORD);
			assertStatus(knownTry, 400);
			assertSameAnswer(knownTry, unknownTry);
			assertThat(unknownTry.getResponse().getContentAsString())
				.isEqualTo(knownTry.getResponse().getContentAsString());
		}
		assertThat(client.attempts(knownId)).isEqualTo(client.attempts(unknownId)).isEqualTo(5);
	}

	@Test
	void aRightCodeDoesTellTheOwnerAboutTheirOwnPhoneNumber() throws Exception {
		// once the mailbox is proved, the specific message is fine and the code is not used up by it
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String resetId = client.requestReset(email, newIp());
		String code = client.lastCode(email);

		MvcResult withPhone = client.confirmReset(resetId, code, "Aa#9812345678");
		AuthClient.assertError(withPhone, 400, "validation");
		assertThat(json(withPhone).get("fields").get("newPassword").asString())
			.isEqualTo("Do not put your phone number in the password.");
		MvcResult samePassword = client.confirmReset(resetId, code, PASSWORD);
		AuthClient.assertError(samePassword, 400, "validation");
		assertThat(json(samePassword).get("fields").has("newPassword")).isTrue();

		assertStatus(client.confirmReset(resetId, code, OTHER_PASSWORD), 204);
		assertStatus(client.tryLogin(email, OTHER_PASSWORD, newIp()), 200);
	}

}
