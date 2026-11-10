package com.spendanalyzer.auth;

import static com.spendanalyzer.auth.AuthClient.PASSWORD;
import static com.spendanalyzer.auth.AuthClient.assertError;
import static com.spendanalyzer.auth.AuthClient.assertStatus;
import static com.spendanalyzer.auth.AuthClient.json;
import static com.spendanalyzer.auth.AuthClient.newEmail;
import static com.spendanalyzer.auth.AuthClient.newIp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.spendanalyzer.security.SameOriginApiFilter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * What the same-origin filter guards on the public auth endpoints: a path cannot be disguised to get
 * past it, and no request body larger than 16 KB is read.
 */
class RequestGuardIntegrationTest extends AuthIntegrationBase {

	private static final int CAP = SameOriginApiFilter.MAX_BODY_BYTES;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** A registration form whose JSON is exactly this many bytes long (the name is padded). */
	private static String registerJson(String email, int bytes) {
		Map<String, Object> form = AuthClient.registerBody(email, PASSWORD);
		int padding = bytes - JSON.writeValueAsString(form).getBytes(StandardCharsets.UTF_8).length;
		form.put("name", form.get("name") + "x".repeat(padding));
		String json = JSON.writeValueAsString(form);
		assertThat(json.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
		return json;
	}

	private static String loginJson(String email) {
		return JSON.writeValueAsString(AuthClient.body("email", email, "password", "Wrong#Guess123"));
	}

	/**
	 * A POST the way the server hands over "Transfer-Encoding: chunked": a body, but no
	 * Content-Length. (MockMvc's own builder always derives a length from the content.)
	 */
	private static RequestBuilder chunkedPost(String path, String json) {
		return servletContext -> {
			MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "POST", path) {
				@Override
				public int getContentLength() {
					return -1;
				}

				@Override
				public long getContentLengthLong() {
					return -1;
				}
			};
			request.addHeader("Transfer-Encoding", "chunked");
			request.setContentType(MediaType.APPLICATION_JSON_VALUE);
			request.setContent(json.getBytes(StandardCharsets.UTF_8));
			request.setRemoteAddr(newIp());
			return request;
		};
	}

	private static void assertTooLarge(MvcResult result) throws Exception {
		assertError(result, 413, "too_large");
		assertThat(result.getResponse().getContentType()).startsWith("application/json");
		assertThat(json(result).get("message").asString()).isEqualTo("That request is too large.");
	}

	// ------------------------------------------------------------------ disguised paths

	@Test
	void aPercentEncodedPathDoesNotGetPastTheCrossSiteCheck() throws Exception {
		// a java.net.URI, so that MockMvc does not turn the percent sign into %25
		URI disguised = URI.create("/%61pi/auth/logout");

		assertError(mvc.perform(post(disguised).header("Origin", "https://evil.example")).andReturn(), 403, "cross_site");
		assertError(mvc.perform(post(disguised).header("Sec-Fetch-Site", "cross-site")).andReturn(), 403, "cross_site");
		// and it does not log anybody out
		MvcResult refused = mvc.perform(post(disguised).header("Origin", "https://evil.example")).andReturn();
		assertThat(refused.getResponse().getHeaders("Set-Cookie")).isEmpty();
	}

	@Test
	void logoutFromThisSiteStillWorks() throws Exception {
		MvcResult result = mvc.perform(post("/api/auth/logout").header("Sec-Fetch-Site", "same-origin")).andReturn();

		assertStatus(result, 204);
		assertThat(result.getResponse().getHeader("Set-Cookie")).startsWith("sa_session=;");
	}

	// ------------------------------------------------------------------ body size

	@Test
	void aBodyAnnouncedAsLargerThanTheCapIsRefused() throws Exception {
		String email = newEmail();

		assertTooLarge(mvc
			.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(registerJson(email, CAP + 1)))
			.andReturn());
		assertTooLarge(mvc
			.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(registerJson(email, 4 * CAP)))
			.andReturn());

		assertThat(mailbox.mailsTo(email)).isEmpty();
	}

	@Test
	void theCapCannotBeDodgedWithADisguisedPath() throws Exception {
		assertTooLarge(mvc
			.perform(post(URI.create("/%61pi/auth/register")).contentType(MediaType.APPLICATION_JSON)
				.content(registerJson(newEmail(), CAP + 1)))
			.andReturn());
	}

	@Test
	void aBodyOfExactlyTheCapIsStillRead() throws Exception {
		// read and judged by the registration rules (the padded name is too long), not refused for its size
		MvcResult result = mvc
			.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(registerJson(newEmail(), CAP)))
			.andReturn();

		assertError(result, 400, "validation");
		assertThat(json(result).get("fields").has("name")).isTrue();
	}

	@Test
	void aNormalBodyStillWorks() throws Exception {
		String email = newEmail();

		String registrationId = client.register(email, PASSWORD, newIp());

		assertThat(registrationId).isNotBlank();
		assertThat(client.lastCode(email)).hasSize(6);
	}

	@Test
	void anOversizeBodyWithoutALengthIsRefusedWhileItIsRead() throws Exception {
		String email = newEmail();

		assertTooLarge(mvc.perform(chunkedPost("/api/auth/register", registerJson(email, CAP + 1))).andReturn());
		assertTooLarge(mvc.perform(chunkedPost("/api/auth/register", registerJson(email, 4 * CAP))).andReturn());

		assertThat(mailbox.mailsTo(email)).isEmpty();
	}

	@Test
	void aNormalBodyWithoutALengthStillWorks() throws Exception {
		// read through the counting wrapper and answered by the login rules
		assertError(mvc.perform(chunkedPost("/api/auth/login", loginJson(newEmail()))).andReturn(), 401,
				"invalid_credentials");
		// right up to the cap
		MvcResult atTheCap = mvc.perform(chunkedPost("/api/auth/register", registerJson(newEmail(), CAP))).andReturn();
		assertError(atTheCap, 400, "validation");
	}

}
