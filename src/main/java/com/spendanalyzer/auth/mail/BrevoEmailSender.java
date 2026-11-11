package com.spendanalyzer.auth.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production mail, the alternative to {@link GoogleScriptEmailSender}: Brevo's HTTPS API. (The free
 * host blocks the SMTP ports, so SMTP is not an option.)
 * A failure is reported with the HTTP status and Brevo's answer only: never with the request body,
 * which contains the code.
 * <p>
 * The timeouts are generous: mail goes out on a background thread that nobody waits for, and the
 * first TLS handshake of a cold JVM on a tenth of a CPU takes several seconds. A connection that
 * could not be made at all is tried once more.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "brevo")
public class BrevoEmailSender implements EmailSender {

	public static final String DEFAULT_URL = "https://api.brevo.com/v3/smtp/email";

	/** Covers the TLS handshake as well. */
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private static final int MAX_LOGGED_BODY = 500;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final URI url;

	private final String apiKey;

	private final String fromEmail;

	private final String fromName;

	/** Created with the first mail: an idle instance then has no HTTP client threads at all. */
	private HttpClient client;

	public BrevoEmailSender(@Value("${app.mail.brevo.url:" + DEFAULT_URL + "}") String url,
			@Value("${app.mail.brevo.api-key:}") String apiKey, @Value("${app.mail.from-email:}") String fromEmail,
			@Value("${app.mail.from-name:Spend Analyzer}") String fromName) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("app.mail.provider=brevo needs app.mail.brevo.api-key (BREVO_API_KEY)");
		}
		if (fromEmail == null || fromEmail.isBlank()) {
			throw new IllegalStateException("app.mail.provider=brevo needs app.mail.from-email (MAIL_FROM_EMAIL)");
		}
		this.url = URI.create(url.strip());
		this.apiKey = apiKey.strip();
		this.fromEmail = fromEmail.strip();
		this.fromName = fromName == null || fromName.isBlank() ? "Spend Analyzer" : fromName.strip();
	}

	@Override
	public void send(EmailMessage message) {
		HttpResponse<String> response;
		try {
			try {
				response = exchange(request(message));
			}
			catch (HttpConnectTimeoutException | ConnectException ex) {
				// No connection was made, so nothing was transmitted: one more try cannot send the
				// mail twice. Any other failure may have delivered it and is not retried.
				response = exchange(request(message));
			}
		}
		catch (IOException | UncheckedIOException ex) {
			// only the exception type: nothing of the request may end up in the log
			throw new EmailException("Brevo could not be reached: " + ex.getClass().getSimpleName());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new EmailException("Interrupted while sending through Brevo");
		}
		requireAccepted(response.statusCode(), response.body());
	}

	/** One HTTP round trip. On its own so that a test can stand in for the network. */
	HttpResponse<String> exchange(HttpRequest request) throws IOException, InterruptedException {
		return client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/** Brevo's "send a transactional email" call for this mail. */
	HttpRequest request(EmailMessage message) {
		return HttpRequest.newBuilder(url)
			.timeout(REQUEST_TIMEOUT)
			.header("api-key", apiKey)
			.header("accept", "application/json")
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body(message), StandardCharsets.UTF_8))
			.build();
	}

	/** The JSON body. Built by Jackson, so every text is escaped properly. */
	String body(EmailMessage message) {
		Map<String, Object> sender = new LinkedHashMap<>();
		sender.put("name", fromName);
		sender.put("email", fromEmail);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sender", sender);
		body.put("to", List.of(Map.of("email", message.to())));
		body.put("subject", message.subject());
		body.put("textContent", message.text());
		body.put("htmlContent", message.html());
		return JSON.writeValueAsString(body);
	}

	/** Success is any 2xx. Anything else is reported with the status and the (shortened) answer. */
	static void requireAccepted(int status, String responseBody) {
		if (status / 100 != 2) {
			throw new EmailException("Brevo answered HTTP " + status + ": " + shorten(responseBody));
		}
	}

	private synchronized HttpClient client() {
		if (client == null) {
			client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		}
		return client;
	}

	@PreDestroy
	synchronized void close() {
		if (client != null) {
			client.shutdownNow();
			client = null;
		}
	}

	private static String shorten(String body) {
		if (body == null) {
			return "";
		}
		String oneLine = body.replaceAll("\\s+", " ").strip();
		return oneLine.length() > MAX_LOGGED_BODY ? oneLine.substring(0, MAX_LOGGED_BODY) + "..." : oneLine;
	}

}
