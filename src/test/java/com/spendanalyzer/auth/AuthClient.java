package com.spendanalyzer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.spendanalyzer.auth.mail.MailDispatcher;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Drives the auth endpoints the way the pages do. Every test uses its own addresses and client IPs
 * (the database is shared by the whole test run and the limits are counted in it).
 */
final class AuthClient {

	/** Two passwords that satisfy the rule. */
	static final String PASSWORD = "Mango#Tree42x";

	static final String OTHER_PASSWORD = "River!Stone73q";

	static final String PHONE = "+919812345678";

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MockMvc mvc;

	private final JdbcClient jdbc;

	private final CapturingEmailSender mailbox;

	private final MailDispatcher dispatcher;

	private final PasswordHasher hasher;

	/** Every response body and Set-Cookie header this client has seen. */
	final List<String> seen = new CopyOnWriteArrayList<>();

	AuthClient(MockMvc mvc, JdbcClient jdbc, CapturingEmailSender mailbox, MailDispatcher dispatcher,
			PasswordHasher hasher) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.mailbox = mailbox;
		this.dispatcher = dispatcher;
		this.hasher = hasher;
		// The mail limits (per client address, per day in total) are counted here. Tests use fresh
		// addresses, so starting each test with an empty log keeps them independent of each other.
		jdbc.sql("DELETE FROM mail_log").update();
	}

	// ------------------------------------------------------------------ identities

	static String newEmail() {
		return "auth" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	/** A client address no other test uses. */
	static String newIp() {
		int n = SEQUENCE.incrementAndGet();
		return "10." + (n / 65536 % 256) + "." + (n / 256 % 256) + "." + (n % 256);
	}

	/** Puts a verified user straight into the database, with a real password hash. */
	UUID insertUser(String email, String password) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO users (id, email, name, phone, password_hash) VALUES (?, ?, ?, ?, ?)")
			.params(id, email, "Test Student", PHONE, hasher.hash(password))
			.update();
		return id;
	}

	// ------------------------------------------------------------------ HTTP

	static Map<String, Object> body(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	MvcResult post(String path, Map<String, Object> body, String ip, Cookie session) throws Exception {
		return send(MockMvcRequestBuilders.post(path), body, ip, session);
	}

	MvcResult post(String path, Map<String, Object> body, String ip) throws Exception {
		return post(path, body, ip, null);
	}

	MvcResult delete(String path, Map<String, Object> body, String ip, Cookie session) throws Exception {
		return send(MockMvcRequestBuilders.delete(path), body, ip, session);
	}

	MvcResult get(String path, Cookie session) throws Exception {
		return send(MockMvcRequestBuilders.get(path), null, newIp(), session);
	}

	private MvcResult send(MockHttpServletRequestBuilder request, Map<String, Object> body, String ip, Cookie session)
			throws Exception {
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
		}
		if (session != null) {
			request.cookie(session);
		}
		request.with(servletRequest -> {
			servletRequest.setRemoteAddr(ip);
			return servletRequest;
		});
		MvcResult result = mvc.perform(request).andReturn();
		seen.add(result.getResponse().getContentAsString());
		seen.addAll(result.getResponse().getHeaders("Set-Cookie"));
		return result;
	}

	static JsonNode json(MvcResult result) throws Exception {
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	/** Asserts the status and the "error" code of an error answer. */
	static void assertError(MvcResult result, int status, String error) throws Exception {
		assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(status);
		assertThat(json(result).path("error").asString()).isEqualTo(error);
	}

	static void assertStatus(MvcResult result, int status) throws Exception {
		assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(status);
	}

	// ------------------------------------------------------------------ flows

	static Map<String, Object> registerBody(String email, String password) {
		return body("name", "Bala Student", "email", email, "phone", "98123 45678", "password", password);
	}

	/** Submits the registration form and returns the registration id. */
	String register(String email, String password, String ip) throws Exception {
		MvcResult result = post("/api/auth/register", registerBody(email, password), ip);
		assertStatus(result, 200);
		return json(result).get("registrationId").asString();
	}

	/** The newest code mailed to the address (waits for the background mail thread). */
	String lastCode(String email) {
		awaitMail();
		return mailbox.lastCode(email).orElseThrow(() -> new AssertionError("no code was mailed to " + email));
	}

	List<String> codes(String email) {
		awaitMail();
		return mailbox.codesTo(email);
	}

	void awaitMail() {
		assertThat(dispatcher.awaitIdle(Duration.ofSeconds(10))).as("mail queue drained").isTrue();
	}

	/** Verifies a registration and returns the session cookie. */
	Cookie verify(String registrationId, String code, String ip) throws Exception {
		MvcResult result = post("/api/auth/register/verify", body("registrationId", registrationId, "code", code), ip);
		assertStatus(result, 200);
		return sessionCookie(result);
	}

	/** Registers and verifies a new user through the API; returns the session cookie. */
	Cookie signUp(String email, String password) throws Exception {
		String ip = newIp();
		String registrationId = register(email, password, ip);
		return verify(registrationId, lastCode(email), ip);
	}

	Cookie login(String email, String password, String ip) throws Exception {
		MvcResult result = post("/api/auth/login", body("email", email, "password", password), ip);
		assertStatus(result, 200);
		return sessionCookie(result);
	}

	MvcResult tryLogin(String email, String password, String ip) throws Exception {
		return post("/api/auth/login", body("email", email, "password", password), ip);
	}

	/** Asks for a password reset and returns the reset id. */
	String requestReset(String email, String ip) throws Exception {
		MvcResult result = post("/api/auth/password-reset/request", body("email", email), ip);
		assertStatus(result, 200);
		return json(result).get("resetId").asString();
	}

	MvcResult confirmReset(String resetId, String code, String newPassword) throws Exception {
		return post("/api/auth/password-reset/confirm", body("resetId", resetId, "code", code, "newPassword", newPassword),
				newIp());
	}

	static Cookie sessionCookie(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie(SessionService.PLAIN_COOKIE);
		assertThat(cookie).as("session cookie").isNotNull();
		return new Cookie(cookie.getName(), cookie.getValue());
	}

	/** A six digit code that is certainly not the given one. */
	static String wrongCode(String right) {
		return String.format("%06d", (Integer.parseInt(right) + 1) % 1_000_000);
	}

	// ------------------------------------------------------------------ database

	/**
	 * Lets time pass for one address: moves everything the limits look at (codes, mail log,
	 * waiting registrations, login attempts) this far into the past.
	 */
	void shiftBack(String email, Duration time) {
		int seconds = (int) time.toSeconds();
		String interval = "(CAST(? AS INTEGER) * interval '1 second')";
		jdbc.sql("UPDATE otp_challenges SET created_at = created_at - " + interval + ", expires_at = expires_at - "
				+ interval + " WHERE email = ?")
			.params(seconds, seconds, email)
			.update();
		jdbc.sql("UPDATE mail_log SET created_at = created_at - " + interval + " WHERE recipient = ?")
			.params(seconds, email)
			.update();
		jdbc.sql("UPDATE pending_registrations SET created_at = created_at - " + interval
				+ ", expires_at = expires_at - " + interval + " WHERE email = ?")
			.params(seconds, seconds, email)
			.update();
		jdbc.sql("UPDATE login_attempts SET created_at = created_at - " + interval + " WHERE email = ?")
			.params(seconds, email)
			.update();
	}

	int attempts(String flowId) {
		return jdbc.sql("SELECT attempts FROM otp_challenges WHERE flow_id = ? ORDER BY created_at DESC LIMIT 1")
			.param(UUID.fromString(flowId))
			.query(Integer.class)
			.single();
	}

	int userCount(String email) {
		return jdbc.sql("SELECT count(*) FROM users WHERE email = ?").param(email).query(Integer.class).single();
	}

	List<String> mailKinds(String email) {
		return jdbc.sql("SELECT kind FROM mail_log WHERE recipient = ? ORDER BY id").param(email).query(String.class).list();
	}

}
