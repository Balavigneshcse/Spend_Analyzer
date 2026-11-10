package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real HTTP round trip of the Brevo sender against a local stand-in for api.brevo.com (the
 * JDK's own little HTTP server).
 * <p>
 * Both the JDK HTTP client and server need an NIO selector. On some Windows machines the JDK
 * cannot create one because its internal socket file cannot live in %TEMP%; the test then points
 * the JDK at the build directory, and is skipped if even that does not help.
 */
class BrevoEmailSenderWireTest {

	private static final EmailMessage MAIL = MailTemplates.registerCode("student@example.com", "493817", 10);

	private HttpServer server;

	private final AtomicInteger answerStatus = new AtomicInteger(201);

	private final AtomicReference<String> answerBody = new AtomicReference<>("{\"messageId\":\"<abc@smtp-relay>\"}");

	private final AtomicReference<String> seenMethod = new AtomicReference<>();

	private final AtomicReference<Headers> seenHeaders = new AtomicReference<>();

	private final AtomicReference<String> seenBody = new AtomicReference<>();

	private BrevoEmailSender sender;

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
		server.createContext("/v3/smtp/email", exchange -> {
			seenMethod.set(exchange.getRequestMethod());
			seenHeaders.set(exchange.getRequestHeaders());
			seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] answer = answerBody.get().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(answerStatus.get(), answer.length);
			exchange.getResponseBody().write(answer);
			exchange.close();
		});
		server.start();
		sender = new BrevoEmailSender("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email",
				"xkeysib-test-key", "noreply@example.com", "Spend Analyzer");
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
	void theMailArrivesAtTheServerAsSent() {
		assertThatCode(() -> sender.send(MAIL)).doesNotThrowAnyException();

		assertThat(seenMethod.get()).isEqualTo("POST");
		assertThat(seenHeaders.get().getFirst("api-key")).isEqualTo("xkeysib-test-key");
		assertThat(seenHeaders.get().getFirst("accept")).isEqualTo("application/json");
		assertThat(seenHeaders.get().getFirst("content-type")).isEqualTo("application/json");
		assertThat(seenBody.get()).isEqualTo(sender.body(MAIL));
		JsonNode body = JsonMapper.builder().build().readTree(seenBody.get());
		assertThat(body.get("to").get(0).get("email").asString()).isEqualTo("student@example.com");
		assertThat(body.get("subject").asString()).isEqualTo("493817 is your Spend Analyzer code");
	}

	@Test
	void aRefusalNamesStatusAndAnswerButNeitherCodeNorRecipientNorKey() {
		answerStatus.set(401);
		answerBody.set("{\"message\":\"Key not found\",\"code\":\"unauthorized\"}");

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageContaining("HTTP 401")
			.hasMessageContaining("Key not found")
			.hasMessageNotContaining("493817")
			.hasMessageNotContaining("student@example.com")
			.hasMessageNotContaining("xkeysib-test-key");
	}

	@Test
	void anUnreachableServerIsAnEmailExceptionWithoutTheMail() {
		server.stop(0);
		server = null;

		assertThatThrownBy(() -> sender.send(MAIL)).isInstanceOf(EmailException.class)
			.hasMessageStartingWith("Brevo could not be reached")
			.hasMessageNotContaining("493817")
			.hasMessageNotContaining("student@example.com")
			.hasNoCause();
	}

}
