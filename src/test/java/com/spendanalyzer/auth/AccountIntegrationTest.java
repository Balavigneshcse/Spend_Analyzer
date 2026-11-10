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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.spendanalyzer.security.AuthUser;
import com.spendanalyzer.testsupport.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Changing the password and deleting the account, both behind the current password. */
class AccountIntegrationTest extends AuthIntegrationBase {

	private MvcResult changePassword(Cookie session, String current, String next) throws Exception {
		return client.post("/api/account/password", body("currentPassword", current, "newPassword", next), newIp(),
				session);
	}

	private int sessionCount(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM sessions WHERE user_id = ?").param(userId).query(Integer.class).single();
	}

	@Test
	void changingThePasswordKeepsThisSessionAndEndsTheOthers() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie here = client.login(email, PASSWORD, newIp());
		Cookie elsewhere = client.login(email, PASSWORD, newIp());
		assertThat(sessionCount(userId)).isEqualTo(2);

		MvcResult changed = changePassword(here, PASSWORD, OTHER_PASSWORD);
		assertStatus(changed, 204);
		assertThat(changed.getResponse().getContentAsString()).isEmpty();

		assertStatus(client.get("/api/auth/me", here), 200);
		assertError(client.get("/api/auth/me", elsewhere), 401, "unauthorized");
		assertThat(sessionCount(userId)).isEqualTo(1);
		assertError(client.tryLogin(email, PASSWORD, newIp()), 401, "invalid_credentials");
		assertStatus(client.tryLogin(email, OTHER_PASSWORD, newIp()), 200);
	}

	@Test
	void aWrongCurrentPasswordChangesNothing() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		MvcResult wrong = changePassword(session, "Wrong#Guess123", OTHER_PASSWORD);
		assertError(wrong, 400, "validation");
		assertThat(json(wrong).get("fields").get("currentPassword").asString()).isEqualTo("The current password is wrong.");
		assertThat(json(wrong).get("fields").properties()).hasSize(1);

		MvcResult missing = changePassword(session, null, OTHER_PASSWORD);
		assertError(missing, 400, "validation");
		assertThat(json(missing).get("fields").has("currentPassword")).isTrue();

		assertStatus(client.tryLogin(email, PASSWORD, newIp()), 200);
		assertError(client.tryLogin(email, OTHER_PASSWORD, newIp()), 401, "invalid_credentials");
	}

	@Test
	void theNewPasswordMustFollowTheRule() throws Exception {
		String email = "meena.kumari" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
		client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		assertNewPasswordRefused(session, "short");
		assertNewPasswordRefused(session, "Welcome@123");
		assertNewPasswordRefused(session, "Xy1#Meena.Kumari" + email.substring(12, 18));
		assertNewPasswordRefused(session, "Aa#9812345678");
		assertNewPasswordRefused(session, PASSWORD);
		assertNewPasswordRefused(session, null);

		assertStatus(client.tryLogin(email, PASSWORD, newIp()), 200);
	}

	private void assertNewPasswordRefused(Cookie session, String password) throws Exception {
		MvcResult result = changePassword(session, PASSWORD, password);
		assertError(result, 400, "validation");
		assertThat(json(result).get("fields").has("newPassword")).as(String.valueOf(password)).isTrue();
	}

	@Test
	void accountEndpointsNeedALogin() throws Exception {
		mvc.perform(post("/api/account/password").contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("unauthorized"));
		mvc.perform(delete("/api/account").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"a\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("unauthorized"));
	}

	@Test
	void deletingTheAccountNeedsThePassword() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());

		MvcResult wrong = client.delete("/api/account", body("password", "Wrong#Guess123"), newIp(), session);
		assertError(wrong, 400, "validation");
		assertThat(json(wrong).get("fields").get("password").asString()).isEqualTo("The password is wrong.");
		MvcResult missing = client.delete("/api/account", body(), newIp(), session);
		assertError(missing, 400, "validation");

		assertThat(client.userCount(email)).isEqualTo(1);
		assertThat(sessionCount(userId)).isEqualTo(1);
		assertStatus(client.get("/api/auth/me", session), 200);
	}

	@Test
	void deletingTheAccountRemovesTheUserTheirSessionsAndTheirData() throws Exception {
		String email = newEmail();
		UUID userId = client.insertUser(email, PASSWORD);
		Cookie session = client.login(email, PASSWORD, newIp());
		client.login(email, PASSWORD, newIp());
		// some data of another module and an open password reset, to see the cascade work
		jdbc.sql("INSERT INTO accounts (id, user_id, type, balance) VALUES (?, ?, 'BANK', 100)")
			.params(UUID.randomUUID(), userId)
			.update();
		client.requestReset(email, newIp());
		assertThat(sessionCount(userId)).isEqualTo(2);

		MvcResult deleted = client.delete("/api/account", body("password", PASSWORD), newIp(), session);
		assertStatus(deleted, 204);
		assertThat(deleted.getResponse().getHeader("Set-Cookie")).startsWith("sa_session=;").contains("Max-Age=0");

		assertThat(client.userCount(email)).isZero();
		assertThat(sessionCount(userId)).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE user_id = ?").param(userId).query(Integer.class).single())
			.isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM otp_challenges WHERE user_id = ?").param(userId).query(Integer.class).single())
			.isZero();
		assertError(client.get("/api/auth/me", session), 401, "unauthorized");
		assertError(client.tryLogin(email, PASSWORD, newIp()), 401, "invalid_credentials");

		// the address is free again
		client.signUp(email, OTHER_PASSWORD);
		assertThat(client.userCount(email)).isEqualTo(1);
	}

	@Test
	void aSessionOfADeletedUserIsNotLoggedIn() throws Exception {
		// a principal injected by a test (or a request racing with the deletion) for a user that no longer exists
		AuthUser ghost = new AuthUser(UUID.randomUUID(), "ghost@example.com", "Ghost");
		mvc.perform(get("/api/auth/me").with(TestData.as(ghost)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("unauthorized"));
		mvc.perform(post("/api/account/password").with(TestData.as(ghost))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void meWorksForAUserCreatedByTheSharedTestHelper() throws Exception {
		AuthUser user = TestData.createUser(jdbc);
		mvc.perform(get("/api/auth/me").with(TestData.as(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.email").value(user.email()))
			.andExpect(jsonPath("$.user.name").value(user.name()))
			.andExpect(jsonPath("$.user.phoneMasked").value("+91 ••••••3210"));
	}

	@Test
	void wrongPasswordsOnTheAccountPageCountAsLoginFailures() throws Exception {
		String email = newEmail();
		client.insertUser(email, PASSWORD);
		String ip = newIp();
		Cookie session = client.login(email, PASSWORD, ip);

		for (int i = 0; i < 5; i++) {
			assertError(client.post("/api/account/password",
					body("currentPassword", "Wrong#Guess123", "newPassword", OTHER_PASSWORD), ip, session), 400,
					"validation");
		}
		MvcResult blocked = client.post("/api/account/password",
				body("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD), ip, session);
		assertError(blocked, 429, "too_many_requests");
		assertThat(blocked.getResponse().getHeader("Retry-After")).isNotNull();
	}

}
