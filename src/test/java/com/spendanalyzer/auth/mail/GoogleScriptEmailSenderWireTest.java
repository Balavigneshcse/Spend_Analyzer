package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP round trips of the Google Apps Script sender against a local stand-in for Google (the
 * JDK's own little HTTP server). The stand-in behaves like the real thing: the POST to the web app
 * is answered with "302, the answer is over there", and the answer address only accepts GET.
 * <p>
 * Both the JDK HTTP client and server need an NIO selector. On some Windows machines the JDK
 * cannot create one because its internal socket file cannot live in %TEMP%; the test then points
 * the JDK at the build directory, and is skipped if even that does not help.
 */
class GoogleScriptEmailSenderWireTest {

	private static final EmailMessage MAIL = MailTemplates.registerCode("student@example.com", "493817", 10);

	private static final String SECRET = "wire-test-secret-0123456789abcdef0123456789";

	private static final String WEB_APP_PATH = "/macros/s/AKfycbWireTestDeployment/exec";

	private static final String ANSWER_PATH = "/macros/echo";

	private static final String ANSWER_QUERY = "user_content_key=one-time-key&lib=abc";

	/** One request as the server saw it. */
	private record Seen(String method, String path, String query, Headers headers, String body) {
	}

	private HttpServer server;

	private String origin;

	/** What the web app does with the POST: 302 to the answer by default. */
	private final AtomicInteger webAppStatus = new AtomicInteger(302);

	private final AtomicReference<String> webAppLocation = new AtomicReference<>();

	private final AtomicReference<String> webAppBody = new AtomicReference<>("<HTML>Moved Temporarily</HTML>");

	/** What is waiting at the answer address. */
	private final AtomicInteger answerStatus = new AtomicInteger(200);

	private final AtomicReference<String> answerBody = new AtomicReference<>("{\"ok\":true}");

	private final List<Seen> seen = new CopyOnWriteArrayList<>();

	private GoogleScriptEmailSender sender;

	@BeforeAll
	static void selectorsMustWork() {
		Path buildDirectory = Path.of("target").toAbsolutePath();
		if (System.getProperty("jdk.net.unixdomain.tmpdir") == null && Files.isDirectory(buildDirectory)) {
			System.setProperty("jdk.net.unixdomain.tmpdir", buildDirectory.toString());
		}
		boolean selectors;
		try (Selector selector = Selector.open()) {
			selectors = selector.isOpen();
		}
		catch (IOException ex) {
			selectors = false;
		}
		assumeTrue(selectors, "this machine cannot open an NIO selector, so no local HTTP round trip is possible");
	}

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		origin = "http://127.0.0.1:" + server.getAddress().getPort();
		webAppLocation.set(origin + ANSWER_PATH + "?" + ANSWER_QUERY);
		server.createContext(WEB_APP_PATH, exchange -> {
			remember(exchange);
			if (webAppStatus.get() / 100 == 3) {
				exchange.getResponseHeaders().add("Location", webAppLocation.get());
			}
			answer(exchange, webAppStatus.get(), "text/html", webAppBody.get());
		});
		server.createContext(ANSWER_PATH, exchange -> {
			remember(exchange);
			if (!"GET".equals(exchange.getRequestMethod())) {
				// like Google: the stored answer cannot be POSTed to
				exchange.getResponseHeaders().add("Allow", "HEAD, GET");
				answer(exchange, 405, "text/html", "<html>Method Not Allowed</html>");
				return;
			}
			answer(exchange, answerStatus.get(), "application/json; charset=utf-8", answerBody.get());
		});
		server.start();
		sender = new GoogleScriptEmailSender(URI.create(origin + WEB_APP_PATH), SECRET, "Spend Analyzer");
	}

	private void remember(HttpExchange exchange) throws IOException {
		seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
				exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(),
				new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
	}

	private static void answer(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			exchange.getResponseBody().write(bytes);
		}
		exchange.close();
	}

	@AfterEach
	void stopServer() {
		if (sender != null) {
			sender.close();
		}
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void theMailIsPostedOnceAndTheAnswerIsFetchedWithGet() {
		assertThatCode(() -> sender.send(MAIL)).doesNotThrowAnyException();

		assertThat(seen).hasSize(2);
		Seen post = seen.get(0);
		assertThat(post.method()).isEqualTo("POST");
		assertThat(post.path()).isEqualTo(WEB_APP_PATH);
		assertThat(post.query()).isNull();
		assertThat(post.headers().getFirst("content-type")).isEqualTo("application/json");
		assertThat(post.body()).isEqualTo(sender.body(MAIL));
		JsonNode body = JsonMapper.builder().build().readTree(post.body());
		assertThat(body.get("secret").asString()).isEqualTo(SECRET);
		assertThat(body.get("to").asString()).isEqualTo("student@example.com");
		assertThat(body.get("subject").asString()).isEqualTo("493817 is your Spend Analyzer code");
		assertThat(body.get("fromName").asString()).isEqualTo("Spend Analyzer");

		Seen get = seen.get(1);
		assertThat(get.method()).isEqualTo("GET");
		assertThat(get.path()).isEqualTo(ANSWER_PATH);
		assertThat(get.query()).isEqualTo(ANSWER_QUERY);
		assertThat(get.body()).isEmpty();
	}

	@Test
	void theSecretIsInNoAddressAndNoHeader() {
		sender.send(MAIL);

		assertThat(seen).hasSize(2);
		for (Seen request : seen) {
			assertThat(request.path()).doesNotContain(SECRET);
			assertThat(String.valueOf(request.query())).doesNotContain(SECRET);
			request.headers()
				.forEach((name, values) -> assertThat(name + ": " + values).doesNotContain(SECRET)
					.doesNotContain("493817")
					.doesNotContain("student@example.com"));
		}
		// and nothing of the mail travels with the second request
		assertThat(seen.get(1).body()).isEmpty();
	}

	@Test
	void aRefusalOfTheScriptNamesItsErrorWordButNeitherCodeNorRecipientNorSecret() {
		answerBody.set("{\"ok\":false,\"error\":\"unauthorized\"}");

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageContaining("did not send the mail: unauthorized")
			.hasMessageNotContaining("493817")
			.hasMessageNotContaining("student@example.com")
			.hasMessageNotContaining(SECRET)
			.hasMessageNotContaining("AKfycbWireTestDeployment");
		assertThat(seen).extracting(Seen::method).containsExactly("POST", "GET");
	}

	@Test
	void aPageWithStatusTwoHundredIsNotSuccess() {
		// what Google shows when the web app is not open to "Anyone": a sign-in page, status 200
		webAppStatus.set(200);
		webAppBody.set("<!DOCTYPE html><html><head><title>Sign in - Google Accounts</title></head></html>");

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageContaining("HTTP 200")
			.hasMessageContaining("not_json")
			.hasMessageNotContaining("Sign in");
		assertThat(seen).extracting(Seen::method).containsExactly("POST");

		seen.clear();
		webAppStatus.set(302);
		answerBody.set("<html><body>Authorization needed</body></html>");
		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class).hasMessageContaining("not_json");
		assertThat(seen).extracting(Seen::method).containsExactly("POST", "GET");
	}

	@Test
	void aWrongDeploymentIsReportedWithItsStatus() {
		webAppStatus.set(404);
		webAppBody.set("<html>Sorry, unable to open the file at this time.</html>");

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageContaining("HTTP 404")
			.hasMessageContaining("GOOGLE_SCRIPT_URL")
			.hasMessageNotContaining("unable to open");
		assertThat(seen).extracting(Seen::method).containsExactly("POST");
	}

	@Test
	void anErrorAtTheAnswerAddressIsAFailure() {
		answerStatus.set(500);
		answerBody.set("{\"ok\":true}");

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class).hasMessageContaining("HTTP 500");
		assertThat(seen).extracting(Seen::method).containsExactly("POST", "GET");
	}

	@Test
	void aRedirectThatAsksForASecondPostIsNotFollowed() {
		webAppStatus.set(307);

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class).hasMessageContaining("HTTP 307");
		// the body with the secret went to the web app only
		assertThat(seen).extracting(Seen::method).containsExactly("POST");
	}

	@Test
	void aRedirectToAnotherServerIsNotFollowed() {
		// the same server under another name is "another host" for the sender
		webAppLocation.set("http://localhost:" + server.getAddress().getPort() + ANSWER_PATH + "?" + ANSWER_QUERY);

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageContaining("HTTP 302 and pointed to localhost")
			.hasMessageNotContaining("user_content_key");
		assertThat(seen).extracting(Seen::method).containsExactly("POST");
	}

	@Test
	void anUnreachableServerIsAnEmailExceptionWithoutTheMail() {
		server.stop(0);
		server = null;

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageStartingWith("Google Apps Script could not be reached")
			.hasMessageNotContaining("493817")
			.hasMessageNotContaining("student@example.com")
			.hasMessageNotContaining(SECRET)
			.hasNoCause();
		assertThat(seen).isEmpty();
	}

}
