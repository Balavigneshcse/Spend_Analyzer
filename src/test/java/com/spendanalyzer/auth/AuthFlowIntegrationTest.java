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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** The journeys a real user takes: sign up, log out, log in, forget the password. */
class AuthFlowIntegrationTest extends AuthIntegrationBase {

	@Autowired
	SecureTokens tokens;

	@Test
	void registerVerifyLogoutLoginAndResetPassword() throws Exception {
		String email = newEmail();
		String ip = newIp();

		// register: no account yet, a code is mailed
		MvcResult registered = client.post("/api/auth/register", AuthClient.registerBody(email, PASSWORD), ip);
		assertStatus(registered, 200);
		JsonNode registerJson = json(registered);
		assertThat(registerJson.get("expiresInSeconds").asInt()).isEqualTo(600);
		assertThat(registerJson.get("resendAfterSeconds").asInt()).isEqualTo(60);
		assertThat(registered.getResponse().getHeader("Set-Cookie")).isNull();
		assertThat(client.userCount(email)).isZero();
		String registrationId = registerJson.get("registrationId").asString();

		// verify: the account exists and the browser is logged in
		MvcResult verified = client.post("/api/auth/register/verify",
				body("registrationId", registrationId, "code", client.lastCode(email)), ip);
		assertStatus(verified, 200);
		JsonNode user = json(verified).get("user");
		assertThat(user.get("name").asString()).isEqualTo("Bala Student");
		assertThat(user.get("email").asString()).isEqualTo(email);
		assertThat(user.get("phoneMasked").asString()).isEqualTo("+91 ••••••5678");
		assertThat(user.properties()).hasSize(3);
		assertThat(client.userCount(email)).isEqualTo(1);
		Cookie session = AuthClient.sessionCookie(verified);

		// the cookie works on the auth endpoint and on another module's endpoint
		MvcResult me = client.get("/api/auth/me", session);
		assertStatus(me, 200);
		assertThat(json(me).get("user").get("email").asString()).isEqualTo(email);
		assertStatus(client.get("/api/meta", session), 200);

		// logout: the cookie is cleared and the old one is worthless
		MvcResult loggedOut = client.post("/api/auth/logout", null, ip, session);
		assertStatus(loggedOut, 204);
		assertThat(loggedOut.getResponse().getHeader("Set-Cookie")).contains("sa_session=;").contains("Max-Age=0");
		assertError(client.get("/api/auth/me", session), 401, "unauthorized");
		assertError(client.get("/api/meta", session), 401, "unauthorized");

		// login again
		Cookie second = client.login(email, PASSWORD, ip);
		assertThat(second.getValue()).isNotEqualTo(session.getValue());
		assertStatus(client.get("/api/meta", second), 200);

		// forgot password: code by mail, then code + new password in one step
		String resetId = client.requestReset(email, ip);
		String resetCode = client.lastCode(email);
		MvcResult confirmed = client.confirmReset(resetId, resetCode, OTHER_PASSWORD);
		assertStatus(confirmed, 204);
		assertThat(confirmed.getResponse().getHeader("Set-Cookie")).as("reset does not log in").isNull();

		// every session is gone, the old password is dead, the new one works
		assertError(client.get("/api/meta", second), 401, "unauthorized");
		assertError(client.tryLogin(email, PASSWORD, ip), 401, "invalid_credentials");
		Cookie third = client.login(email, OTHER_PASSWORD, ip);
		assertStatus(client.get("/api/auth/me", third), 200);
	}

	@Test
	void sessionCookieHasTheRightAttributes() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);

		MvcResult result = client.tryLogin(email, PASSWORD, newIp());
		assertStatus(result, 200);
		List<String> headers = result.getResponse().getHeaders("Set-Cookie");
		assertThat(headers).hasSize(1);
		String header = headers.get(0);
		assertThat(header).startsWith("sa_session=");
		List<String> attributes = List.of(header.split("; "));
		assertThat(attributes).contains("HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=604800");
		// the test profile runs on plain http; production adds Secure and the __Host- name (next test)
		assertThat(attributes).doesNotContain("Secure");
		assertThat(header).doesNotContain("Domain");
		// 32 random bytes in base64url
		assertThat(AuthClient.sessionCookie(result).getValue()).matches("[A-Za-z0-9_-]{43}");
	}

	@Test
	void productionCookieIsSecureAndHostBound() {
		SessionService production = new SessionService(jdbc, Clock.systemUTC(), tokens, true, 7);
		String header = production.cookie(tokens.sessionToken()).toString();
		assertThat(header).startsWith("__Host-sa_session=");
		assertThat(List.of(header.split("; "))).contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/",
				"Max-Age=604800");
		assertThat(header).doesNotContain("Domain");
		assertThat(production.clearingCookie().toString()).startsWith("__Host-sa_session=;").contains("Max-Age=0");
	}

	@Test
	void onlyTheHashOfTheSessionTokenIsStored() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		byte[] stored = jdbc.sql("SELECT token_hash FROM sessions WHERE user_id = ?")
			.param(userId)
			.query(byte[].class)
			.single();
		assertThat(stored).isEqualTo(SecureTokens.sha256(session.getValue()));
		assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(session.getValue());
	}

	@Test
	void everyLoginGetsANewTokenAndReplacesTheSessionOfThatBrowser() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		String ip = newIp();

		Cookie first = client.login(email, PASSWORD, ip);
		Cookie otherBrowser = client.login(email, PASSWORD, ip);
		assertThat(otherBrowser.getValue()).isNotEqualTo(first.getValue());
		assertThat(sessionCount(userId)).isEqualTo(2);

		// logging in again from a browser that still has a session swaps it for a new one
		MvcResult again = client.post("/api/auth/login", body("email", email, "password", PASSWORD), ip, first);
		assertStatus(again, 200);
		assertThat(AuthClient.sessionCookie(again).getValue()).isNotEqualTo(first.getValue());
		assertThat(sessionCount(userId)).isEqualTo(2);
		assertError(client.get("/api/auth/me", first), 401, "unauthorized");
		assertStatus(client.get("/api/auth/me", otherBrowser), 200);
	}

	@Test
	void expiredOrMadeUpCookiesAreNotLoggedIn() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		assertError(client.get("/api/auth/me", new Cookie("sa_session", "not-a-token")), 401, "unauthorized");
		assertError(client.get("/api/auth/me", new Cookie("sa_session", tokens.sessionToken())), 401, "unauthorized");
		assertError(client.get("/api/auth/me", null), 401, "unauthorized");
		// the production cookie name means nothing on a plain-http deployment
		assertError(client.get("/api/auth/me", new Cookie("__Host-sa_session", session.getValue())), 401,
				"unauthorized");

		assertStatus(client.get("/api/auth/me", session), 200);
		jdbc.sql("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE user_id = ?").param(userId).update();
		assertError(client.get("/api/auth/me", session), 401, "unauthorized");
	}

	@Test
	void lastSeenIsWrittenAtMostEveryFiveMinutes() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		jdbc.sql("UPDATE sessions SET last_seen_at = now() - interval '2 minutes' WHERE user_id = ?").param(userId).update();
		assertStatus(client.get("/api/auth/me", session), 200);
		assertThat(lastSeenAge(userId)).as("fresh enough: not written").isGreaterThan(Duration.ofSeconds(100));

		jdbc.sql("UPDATE sessions SET last_seen_at = now() - interval '6 minutes' WHERE user_id = ?").param(userId).update();
		assertStatus(client.get("/api/auth/me", session), 200);
		assertThat(lastSeenAge(userId)).as("stale: written").isLessThan(Duration.ofSeconds(60));
	}

	@Test
	void logoutWithoutLoginIsHarmless() throws Exception {
		MvcResult result = client.post("/api/auth/logout", null, newIp());
		assertStatus(result, 204);
		assertThat(result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
	}

	@Test
	void configTellsWhetherRegistrationIsOpen() throws Exception {
		MvcResult result = client.get("/api/auth/config", null);
		assertStatus(result, 200);
		assertThat(json(result).get("registrationEnabled").asBoolean()).isTrue();
	}

	@Test
	void noCodeEverAppearsInAnHttpResponse() throws Exception {
		String email = newEmail();
		String ip = newIp();

		String registrationId = client.register(email, PASSWORD, ip);
		String firstCode = client.lastCode(email);
		client.shiftBack(email, Duration.ofSeconds(61));
		assertStatus(client.post("/api/auth/register/resend", body("registrationId", registrationId), ip), 200);
		String secondCode = client.lastCode(email);
		assertError(client.post("/api/auth/register/verify",
				body("registrationId", registrationId, "code", wrongCode(secondCode)), ip), 400, "invalid_code");
		Cookie session = client.verify(registrationId, secondCode, ip);
		assertStatus(client.get("/api/auth/me", session), 200);

		String resetId = client.requestReset(email, ip);
		client.shiftBack(email, Duration.ofSeconds(61));
		assertStatus(client.post("/api/auth/password-reset/resend", body("resetId", resetId), ip), 200);
		String resetCode = client.lastCode(email);
		assertError(client.confirmReset(resetId, wrongCode(resetCode), OTHER_PASSWORD), 400, "invalid_code");
		assertStatus(client.confirmReset(resetId, resetCode, OTHER_PASSWORD), 204);

		List<String> codes = client.codes(email);
		assertThat(codes).hasSize(4).contains(firstCode, secondCode, resetCode);
		assertThat(client.seen).isNotEmpty();
		for (String code : codes) {
			assertThat(client.seen).noneMatch(text -> text.contains(code));
		}
	}

	private int sessionCount(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM sessions WHERE user_id = ?").param(userId).query(Integer.class).single();
	}

	private Duration lastSeenAge(UUID userId) {
		double seconds = jdbc.sql("SELECT extract(epoch FROM now() - last_seen_at) FROM sessions WHERE user_id = ?")
			.param(userId)
			.query(Double.class)
			.single();
		return Duration.ofMillis((long) (seconds * 1000));
	}

}
