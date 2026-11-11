package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.spendanalyzer.auth.mail.ProductionGuard.Settings;
import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The start-up guard: production must mail codes, through the owner's Gmail (Google Apps Script)
 * or through Brevo, and must have real secrets.
 */
class ProductionGuardTest {

	private static final String PEPPER = "0123456789abcdef0123456789abcdef";

	private static final String KEY = "xkeysib-test";

	private static final String FROM = "noreply@example.com";

	private static final String SCRIPT_URL = "https://script.google.com/macros/s/AKfycbTestDeployment_0123456789/exec";

	private static final String SCRIPT_SECRET = "script-secret-0123456789abcdef01";

	/** Production with Brevo; nothing of Google is set. */
	private static Settings brevo(String provider, String apiKey, String from, String pepper, boolean secureCookie) {
		return new Settings(true, false, provider, apiKey, from, "", "", pepper, secureCookie);
	}

	/** Production with the Google Apps Script; nothing of Brevo is set. */
	private static Settings google(String scriptUrl, String secret) {
		return new Settings(true, false, "google", "", "", scriptUrl, secret, PEPPER, true);
	}

	// ------------------------------------------------------------------ complete configurations

	@Test
	void aCompleteGoogleConfigurationPassesWithoutAnythingOfBrevo() {
		assertThat(ProductionGuard.problems(google(SCRIPT_URL, SCRIPT_SECRET))).isEmpty();
		assertThatCode(() -> ProductionGuard.check(google(SCRIPT_URL, SCRIPT_SECRET))).doesNotThrowAnyException();
		assertThat(ProductionGuard.problems(new Settings(true, false, "google", null, null, SCRIPT_URL, SCRIPT_SECRET,
				PEPPER, true)))
			.isEmpty();
		// on Render without the prod profile the same rules apply
		assertThat(ProductionGuard.problems(new Settings(false, true, "google", "", "", SCRIPT_URL, SCRIPT_SECRET, PEPPER,
				true)))
			.isEmpty();
	}

	@Test
	void aCompleteBrevoConfigurationPassesWithoutAnythingOfGoogle() {
		assertThat(ProductionGuard.problems(brevo("brevo", KEY, FROM, PEPPER, true))).isEmpty();
		assertThatCode(() -> ProductionGuard.check(brevo("brevo", KEY, FROM, PEPPER, true))).doesNotThrowAnyException();
		assertThat(ProductionGuard.problems(new Settings(true, false, "brevo", KEY, FROM, null, null, PEPPER, true)))
			.isEmpty();
		// on Render without the prod profile the same rules apply
		assertThat(ProductionGuard.problems(new Settings(false, true, "brevo", KEY, FROM, "", "", PEPPER, true)))
			.isEmpty();
	}

	@Test
	void developmentMayUseTheConsoleSenderAndAShortPepper() {
		assertThat(ProductionGuard
			.problems(new Settings(false, false, "console", "", "dev@localhost", "", "", "short", false))).isEmpty();
		assertThat(ProductionGuard.problems(new Settings(false, false, "capture", null, null, null, null, "short", false)))
			.isEmpty();
		// a half-configured real sender is the sender's own business outside production
		assertThat(ProductionGuard.problems(new Settings(false, false, "google", "", "", "", "", "short", false)))
			.isEmpty();
	}

	@Test
	void thePepperIsRequiredEverywhere() {
		assertThat(ProductionGuard.problems(new Settings(false, false, "console", "", "", "", "", " ", false)))
			.containsExactly("app.security.otp-pepper (OTP_PEPPER) is not set");
		assertThat(ProductionGuard.problems(new Settings(false, false, "console", "", "", "", "", null, false)))
			.hasSize(1);
	}

	// ------------------------------------------------------------------ the provider

	@Test
	void productionRefusesEverySenderThatDoesNotMail() {
		assertThat(ProductionGuard.problems(brevo("console", KEY, FROM, PEPPER, true))).containsExactly(
				"app.mail.provider (MAIL_PROVIDER) must be \"google\" or \"brevo\" in production but is \"console\" "
						+ "(codes must be mailed, never written to the log)");
		for (String provider : new String[] { "capture", "", " ", null, "Google", "GOOGLE", "Brevo", "gmail", "smtp" }) {
			assertThat(ProductionGuard.problems(brevo(provider, KEY, FROM, PEPPER, true))).as("provider: %s", provider)
				.singleElement()
				.asString()
				.startsWith("app.mail.provider (MAIL_PROVIDER) must be \"google\" or \"brevo\"");
		}
		// complete settings of both real senders do not make the console sender acceptable
		assertThat(ProductionGuard
			.problems(new Settings(true, false, "console", KEY, FROM, SCRIPT_URL, SCRIPT_SECRET, PEPPER, true)))
			.hasSize(1);
	}

	@Test
	void theRenderVariableAloneMeansProduction() {
		Settings onRender = new Settings(false, true, "console", "", "dev@localhost", "", "", "short", false);
		assertThat(onRender.production()).isTrue();
		// the provider, the short pepper and the cookie
		assertThat(ProductionGuard.problems(onRender)).hasSize(3);
	}

	// ------------------------------------------------------------------ google

	@Test
	void googleNeedsTheWebAppUrl() {
		for (String missing : new String[] { "", "  ", null }) {
			assertThat(ProductionGuard.problems(google(missing, SCRIPT_SECRET)))
				.containsExactly("app.mail.google.script-url (GOOGLE_SCRIPT_URL) is not set");
		}
	}

	@Test
	void googleNeedsAWebAppUrlOfTheRightShape() {
		assertThat(ProductionGuard
			.problems(google("https://script.google.com/macros/s/AKfycbTestDeployment_0123456789/dev", SCRIPT_SECRET)))
			.containsExactly("app.mail.google.script-url (GOOGLE_SCRIPT_URL) must be the web app URL that ends with "
					+ "/exec, not the test URL that ends with /dev");
		for (String wrong : new String[] { "http://script.google.com/macros/s/AKfycbTestDeployment_0123456789/exec",
				"https://example.com/macros/s/AKfycbTestDeployment_0123456789/exec",
				"https://script.google.com/macros/s/AKfycbTestDeployment_0123456789/exec?x=1",
				"https://script.google.com/home/projects/AKfycbTestDeployment_0123456789/edit", "none" }) {
			assertThat(ProductionGuard.problems(google(wrong, SCRIPT_SECRET))).as(wrong)
				.singleElement()
				.asString()
				.startsWith("app.mail.google.script-url (GOOGLE_SCRIPT_URL) must look like "
						+ "https://script.google.com/macros/s/.../exec")
				// the address lets anybody call the script: it is not repeated
				.doesNotContain("AKfycbTestDeployment");
		}
	}

	@Test
	void googleNeedsASecretOfThirtyTwoCharacters() {
		for (String missing : new String[] { "", "  ", null }) {
			assertThat(ProductionGuard.problems(google(SCRIPT_URL, missing)))
				.containsExactly("app.mail.google.secret (GOOGLE_SCRIPT_SECRET) is not set");
		}
		assertThat(ProductionGuard.problems(google(SCRIPT_URL, "script-secret-0123456789abcdef0")))
			.containsExactly("app.mail.google.secret (GOOGLE_SCRIPT_SECRET) must be at least 32 characters long");
		assertThat(ProductionGuard.problems(google(SCRIPT_URL, "x".repeat(32)))).isEmpty();
	}

	@Test
	void googleWithNothingSetNamesBothVariablesAndNothingOfBrevo() {
		assertThat(ProductionGuard.problems(google("", ""))).containsExactly(
				"app.mail.google.script-url (GOOGLE_SCRIPT_URL) is not set",
				"app.mail.google.secret (GOOGLE_SCRIPT_SECRET) is not set");
		// Brevo's settings do not stand in for Google's
		assertThat(ProductionGuard.problems(new Settings(true, false, "google", KEY, FROM, "", "", PEPPER, true)))
			.hasSize(2);
	}

	// ------------------------------------------------------------------ brevo

	@Test
	void brevoNeedsTheKeyAndTheSenderAddress() {
		assertThat(ProductionGuard.problems(brevo("brevo", "", FROM, PEPPER, true)))
			.containsExactly("app.mail.brevo.api-key (BREVO_API_KEY) is not set");
		assertThat(ProductionGuard.problems(brevo("brevo", null, FROM, PEPPER, true))).hasSize(1);
		assertThat(ProductionGuard.problems(brevo("brevo", KEY, "  ", PEPPER, true)))
			.containsExactly("app.mail.from-email (MAIL_FROM_EMAIL) is not set");
		assertThat(ProductionGuard.problems(brevo("brevo", KEY, null, PEPPER, true))).hasSize(1);
		// Google's settings do not stand in for Brevo's
		assertThat(ProductionGuard
			.problems(new Settings(true, false, "brevo", "", "", SCRIPT_URL, SCRIPT_SECRET, PEPPER, true)))
			.containsExactly("app.mail.brevo.api-key (BREVO_API_KEY) is not set",
					"app.mail.from-email (MAIL_FROM_EMAIL) is not set");
	}

	// ------------------------------------------------------------------ pepper and cookie

	@Test
	void productionNeedsAPepperOfThirtyTwoCharacters() {
		assertThat(ProductionGuard.problems(brevo("brevo", KEY, FROM, "0123456789abcdef0123456789abcde", true)))
			.containsExactly("app.security.otp-pepper (OTP_PEPPER) must be at least 32 characters long");
		assertThat(ProductionGuard.problems(brevo("brevo", KEY, FROM, "", true)))
			.containsExactly("app.security.otp-pepper (OTP_PEPPER) is not set");
		assertThat(ProductionGuard
			.problems(new Settings(true, false, "google", "", "", SCRIPT_URL, SCRIPT_SECRET, "short", true)))
			.containsExactly("app.security.otp-pepper (OTP_PEPPER) must be at least 32 characters long");
		assertThat(ProductionGuard.problems(new Settings(true, false, "google", "", "", SCRIPT_URL, SCRIPT_SECRET, "", true)))
			.containsExactly("app.security.otp-pepper (OTP_PEPPER) is not set");
	}

	@Test
	void productionNeedsASecureCookie() {
		assertThat(ProductionGuard.problems(brevo("brevo", KEY, FROM, PEPPER, false)))
			.containsExactly("app.cookie.secure must be true in production");
		assertThat(ProductionGuard
			.problems(new Settings(true, false, "google", "", "", SCRIPT_URL, SCRIPT_SECRET, PEPPER, false)))
			.containsExactly("app.cookie.secure must be true in production");
	}

	@Test
	void theErrorNamesEveryProblemButNoSecret() {
		Settings console = new Settings(true, false, "console", "secret-api-key", "", SCRIPT_URL, "secret-of-the-script",
				"secret-pepper", false);
		assertThatThrownBy(() -> ProductionGuard.check(console)).isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("Spend Analyzer cannot start: ")
			.hasMessageContaining("MAIL_PROVIDER")
			.hasMessageContaining("OTP_PEPPER")
			.hasMessageContaining("app.cookie.secure")
			.hasMessageNotContaining("secret-api-key")
			.hasMessageNotContaining("secret-of-the-script")
			.hasMessageNotContaining("secret-pepper");

		Settings google = new Settings(true, false, "google", "secret-api-key", "",
				"https://script.google.com/macros/s/AKfycbTestDeployment_0123456789/dev", "secret-of-the-script",
				"secret-pepper", false);
		assertThatThrownBy(() -> ProductionGuard.check(google)).isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("Spend Analyzer cannot start: ")
			.hasMessageContaining("GOOGLE_SCRIPT_URL")
			.hasMessageContaining("GOOGLE_SCRIPT_SECRET")
			.hasMessageContaining("OTP_PEPPER")
			.hasMessageContaining("app.cookie.secure")
			.hasMessageNotContaining("BREVO")
			.hasMessageNotContaining("secret-api-key")
			.hasMessageNotContaining("secret-of-the-script")
			.hasMessageNotContaining("AKfycbTestDeployment")
			.hasMessageNotContaining("secret-pepper");

		Settings brevo = new Settings(true, false, "brevo", "", "", SCRIPT_URL, "secret-of-the-script", "secret-pepper",
				true);
		assertThatThrownBy(() -> ProductionGuard.check(brevo)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("BREVO_API_KEY")
			.hasMessageContaining("MAIL_FROM_EMAIL")
			.hasMessageNotContaining("GOOGLE");

		for (Settings settings : new Settings[] { console, google, brevo }) {
			assertThat(settings.toString()).doesNotContain("secret-api-key")
				.doesNotContain("secret-of-the-script")
				.doesNotContain("secret-pepper")
				.doesNotContain("AKfycbTestDeployment");
		}
	}

	// ------------------------------------------------------------------ the beans themselves

	private static AnnotationConfigApplicationContext contextWith(MockEnvironment environment) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.setEnvironment(environment);
		context.register(ConsoleEmailSender.class, BrevoEmailSender.class, GoogleScriptEmailSender.class);
		context.refresh();
		return context;
	}

	@Test
	void theConsoleSenderExistsInDevelopment() {
		MockEnvironment environment = new MockEnvironment().withProperty("app.mail.provider", "console");
		environment.setActiveProfiles("dev");
		try (AnnotationConfigApplicationContext context = contextWith(environment)) {
			assertThat(context.getBeansOfType(EmailSender.class).values()).singleElement()
				.isInstanceOf(ConsoleEmailSender.class);
		}
	}

	@Test
	void theConsoleSenderDoesNotExistWithTheProdProfileEvenWhenAskedFor() {
		MockEnvironment environment = new MockEnvironment().withProperty("app.mail.provider", "console");
		environment.setActiveProfiles("prod");
		try (AnnotationConfigApplicationContext context = contextWith(environment)) {
			assertThat(context.getBeansOfType(EmailSender.class)).isEmpty();
		}
	}

	@Test
	void theConsoleSenderDoesNotExistOnRenderEvenWhenAskedFor() {
		MockEnvironment environment = new MockEnvironment().withProperty("app.mail.provider", "console")
			.withProperty("RENDER", "true");
		environment.setActiveProfiles("dev");
		try (AnnotationConfigApplicationContext context = contextWith(environment)) {
			assertThat(context.getBeansOfType(EmailSender.class)).isEmpty();
		}
	}

	@Test
	void productionGetsTheBrevoSender() {
		MockEnvironment environment = new MockEnvironment().withProperty("app.mail.provider", "brevo")
			.withProperty("app.mail.brevo.api-key", KEY)
			.withProperty("app.mail.from-email", FROM);
		environment.setActiveProfiles("prod");
		try (AnnotationConfigApplicationContext context = contextWith(environment)) {
			assertThat(context.getBeansOfType(EmailSender.class).values()).singleElement()
				.isInstanceOf(BrevoEmailSender.class);
		}
	}

	@Test
	void productionGetsTheGoogleSender() {
		MockEnvironment environment = new MockEnvironment().withProperty("app.mail.provider", "google")
			.withProperty("app.mail.google.script-url", SCRIPT_URL)
			.withProperty("app.mail.google.secret", SCRIPT_SECRET);
		environment.setActiveProfiles("prod");
		try (AnnotationConfigApplicationContext context = contextWith(environment)) {
			assertThat(context.getBeansOfType(EmailSender.class).values()).singleElement()
				.isInstanceOf(GoogleScriptEmailSender.class);
		}
	}

	@Test
	void theGoogleSenderItselfRefusesAHalfConfigurationEverywhere() {
		// outside production the guard does not look: the sender must not come up half-working
		MockEnvironment environment = new MockEnvironment().withProperty("app.mail.provider", "google")
			.withProperty("app.mail.google.script-url", SCRIPT_URL);
		environment.setActiveProfiles("dev");
		assertThatThrownBy(() -> contextWith(environment)).hasRootCauseInstanceOf(IllegalStateException.class)
			.rootCause()
			.hasMessageContaining("GOOGLE_SCRIPT_SECRET");
	}

	@Test
	void theStartupCheckReadsTheEnvironment() {
		MockEnvironment bad = new MockEnvironment().withProperty("app.mail.provider", "console")
			.withProperty("app.security.otp-pepper", PEPPER)
			.withProperty("app.cookie.secure", "true");
		bad.setActiveProfiles("prod");
		assertThatThrownBy(() -> new MailStartupCheck(bad)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.mail.provider (MAIL_PROVIDER) must be \"google\" or \"brevo\"");

		MockEnvironment brevo = new MockEnvironment().withProperty("app.mail.provider", "brevo")
			.withProperty("app.mail.brevo.api-key", KEY)
			.withProperty("app.mail.from-email", FROM)
			.withProperty("app.security.otp-pepper", PEPPER)
			.withProperty("app.cookie.secure", "true");
		brevo.setActiveProfiles("prod");
		assertThatCode(() -> new MailStartupCheck(brevo)).doesNotThrowAnyException();

		MockEnvironment google = new MockEnvironment().withProperty("app.mail.provider", "google")
			.withProperty("app.mail.google.script-url", SCRIPT_URL)
			.withProperty("app.mail.google.secret", SCRIPT_SECRET)
			.withProperty("app.security.otp-pepper", PEPPER)
			.withProperty("app.cookie.secure", "true");
		google.setActiveProfiles("prod");
		assertThatCode(() -> new MailStartupCheck(google)).doesNotThrowAnyException();

		MockEnvironment halfGoogle = new MockEnvironment().withProperty("app.mail.provider", "google")
			.withProperty("app.mail.google.script-url", SCRIPT_URL)
			.withProperty("app.security.otp-pepper", PEPPER)
			.withProperty("app.cookie.secure", "true");
		halfGoogle.setActiveProfiles("prod");
		assertThatThrownBy(() -> new MailStartupCheck(halfGoogle)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("GOOGLE_SCRIPT_SECRET")
			.hasMessageNotContaining("GOOGLE_SCRIPT_URL");

		MockEnvironment render = new MockEnvironment().withProperty("app.mail.provider", "console")
			.withProperty("app.security.otp-pepper", PEPPER)
			.withProperty("app.cookie.secure", "false")
			.withProperty("RENDER", "true");
		assertThatThrownBy(() -> new MailStartupCheck(render)).isInstanceOf(IllegalStateException.class);
	}

	// ------------------------------------------------------------------ application-prod.properties

	/** The production properties file, with these environment variables and no others. */
	private static MockEnvironment production(String... variables) throws IOException {
		MockEnvironment environment = new MockEnvironment();
		for (int i = 0; i < variables.length; i += 2) {
			environment.setProperty(variables[i], variables[i + 1]);
		}
		environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application-prod.properties"));
		environment.setActiveProfiles("prod");
		return environment;
	}

	@Test
	void productionMailsThroughGoogleUnlessToldOtherwise() throws IOException {
		MockEnvironment environment = production("OTP_PEPPER", PEPPER, "GOOGLE_SCRIPT_URL", SCRIPT_URL,
				"GOOGLE_SCRIPT_SECRET", SCRIPT_SECRET);

		assertThat(environment.getProperty("app.mail.provider")).isEqualTo("google");
		assertThat(environment.getProperty("app.mail.google.script-url")).isEqualTo(SCRIPT_URL);
		assertThat(environment.getProperty("app.mail.google.secret")).isEqualTo(SCRIPT_SECRET);
		assertThat(environment.getProperty("app.mail.from-name")).isEqualTo("Spend Analyzer");
		// Brevo's variables are not there, and that is no error
		assertThat(environment.getProperty("app.mail.brevo.api-key")).isEmpty();
		assertThat(environment.getProperty("app.mail.from-email")).isEmpty();
		assertThatCode(() -> new MailStartupCheck(environment)).doesNotThrowAnyException();
		try (AnnotationConfigApplicationContext context = contextWith(environment)) {
			assertThat(context.getBeansOfType(EmailSender.class).values()).singleElement()
				.isInstanceOf(GoogleScriptEmailSender.class);
		}
	}

	@Test
	void productionCanStillMailThroughBrevoWithoutAnythingOfGoogle() throws IOException {
		MockEnvironment environment = production("OTP_PEPPER", PEPPER, "MAIL_PROVIDER", "brevo", "BREVO_API_KEY", KEY,
				"MAIL_FROM_EMAIL", FROM, "MAIL_FROM_NAME", "My Spends");

		assertThat(environment.getProperty("app.mail.provider")).isEqualTo("brevo");
		assertThat(environment.getProperty("app.mail.brevo.api-key")).isEqualTo(KEY);
		assertThat(environment.getProperty("app.mail.from-email")).isEqualTo(FROM);
		assertThat(environment.getProperty("app.mail.from-name")).isEqualTo("My Spends");
		assertThat(environment.getProperty("app.mail.google.script-url")).isEmpty();
		assertThat(environment.getProperty("app.mail.google.secret")).isEmpty();
		assertThatCode(() -> new MailStartupCheck(environment)).doesNotThrowAnyException();
		try (AnnotationConfigApplicationContext context = contextWith(environment)) {
			assertThat(context.getBeansOfType(EmailSender.class).values()).singleElement()
				.isInstanceOf(BrevoEmailSender.class);
		}
	}

	@Test
	void theLocalSmokeTestOfTheProductionImageStillStarts() throws IOException {
		// compose.yaml runs the production image with dummy mail settings: they must pass the guard
		String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
		MockEnvironment environment = production("OTP_PEPPER", composeValue(compose, "OTP_PEPPER"), "MAIL_PROVIDER",
				composeValue(compose, "MAIL_PROVIDER"), "GOOGLE_SCRIPT_URL", composeValue(compose, "GOOGLE_SCRIPT_URL"),
				"GOOGLE_SCRIPT_SECRET", composeValue(compose, "GOOGLE_SCRIPT_SECRET"));

		assertThat(environment.getProperty("app.mail.provider")).isEqualTo("google");
		assertThatCode(() -> new MailStartupCheck(environment)).doesNotThrowAnyException();
		try (AnnotationConfigApplicationContext context = contextWith(environment)) {
			assertThat(context.getBeansOfType(EmailSender.class).values()).singleElement()
				.isInstanceOf(GoogleScriptEmailSender.class);
		}
	}

	private static String composeValue(String compose, String variable) {
		Matcher matcher = Pattern.compile("(?m)^\\s+" + variable + ":\\s*(\\S+)\\s*$").matcher(compose);
		assertThat(matcher.find()).as("%s in compose.yaml", variable).isTrue();
		return matcher.group(1);
	}

	@Test
	void productionWithoutTheVariablesOfItsSenderDoesNotStart() throws IOException {
		// a fresh Render service before the owner has typed in the web app URL
		MockEnvironment noUrl = production("OTP_PEPPER", PEPPER, "GOOGLE_SCRIPT_SECRET", SCRIPT_SECRET);
		assertThatThrownBy(() -> new MailStartupCheck(noUrl)).isInstanceOf(IllegalStateException.class)
			.hasMessage("Spend Analyzer cannot start: app.mail.google.script-url (GOOGLE_SCRIPT_URL) is not set.");

		MockEnvironment nothing = production("OTP_PEPPER", PEPPER);
		assertThatThrownBy(() -> new MailStartupCheck(nothing)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("GOOGLE_SCRIPT_URL")
			.hasMessageContaining("GOOGLE_SCRIPT_SECRET")
			.hasMessageNotContaining("BREVO");

		MockEnvironment brevoWithoutKey = production("OTP_PEPPER", PEPPER, "MAIL_PROVIDER", "brevo", "MAIL_FROM_EMAIL",
				FROM);
		assertThatThrownBy(() -> new MailStartupCheck(brevoWithoutKey)).isInstanceOf(IllegalStateException.class)
			.hasMessage("Spend Analyzer cannot start: app.mail.brevo.api-key (BREVO_API_KEY) is not set.");

		MockEnvironment console = production("OTP_PEPPER", PEPPER, "MAIL_PROVIDER", "console", "GOOGLE_SCRIPT_URL",
				SCRIPT_URL, "GOOGLE_SCRIPT_SECRET", SCRIPT_SECRET);
		assertThatThrownBy(() -> new MailStartupCheck(console)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MAIL_PROVIDER");
	}

}
