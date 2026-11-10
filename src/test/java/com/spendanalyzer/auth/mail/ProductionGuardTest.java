package com.spendanalyzer.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spendanalyzer.auth.mail.ProductionGuard.Settings;
import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** The start-up guard: production must mail codes through Brevo and must have real secrets. */
class ProductionGuardTest {

	private static final String PEPPER = "0123456789abcdef0123456789abcdef";

	private static final String KEY = "xkeysib-test";

	private static final String FROM = "noreply@example.com";

	private static Settings prod(String provider, String apiKey, String from, String pepper, boolean secureCookie) {
		return new Settings(true, false, provider, apiKey, from, pepper, secureCookie);
	}

	@Test
	void aCompleteProductionConfigurationPasses() {
		assertThat(ProductionGuard.problems(prod("brevo", KEY, FROM, PEPPER, true))).isEmpty();
		assertThatCode(() -> ProductionGuard.check(prod("brevo", KEY, FROM, PEPPER, true))).doesNotThrowAnyException();
		// on Render without the prod profile the same rules apply
		assertThat(ProductionGuard.problems(new Settings(false, true, "brevo", KEY, FROM, PEPPER, true))).isEmpty();
	}

	@Test
	void developmentMayUseTheConsoleSenderAndAShortPepper() {
		assertThat(ProductionGuard.problems(new Settings(false, false, "console", "", "dev@localhost", "short", false)))
			.isEmpty();
		assertThat(ProductionGuard.problems(new Settings(false, false, "capture", null, null, "short", false))).isEmpty();
	}

	@Test
	void thePepperIsRequiredEverywhere() {
		assertThat(ProductionGuard.problems(new Settings(false, false, "console", "", "", " ", false)))
			.containsExactly("app.security.otp-pepper (OTP_PEPPER) is not set");
		assertThat(ProductionGuard.problems(new Settings(false, false, "console", "", "", null, false))).hasSize(1);
	}

	@Test
	void productionRefusesTheConsoleSender() {
		assertThat(ProductionGuard.problems(prod("console", KEY, FROM, PEPPER, true))).singleElement()
			.asString()
			.contains("app.mail.provider must be \"brevo\"");
		assertThat(ProductionGuard.problems(prod("capture", KEY, FROM, PEPPER, true))).hasSize(1);
		assertThat(ProductionGuard.problems(prod("", KEY, FROM, PEPPER, true))).hasSize(1);
		assertThat(ProductionGuard.problems(prod(null, KEY, FROM, PEPPER, true))).hasSize(1);
	}

	@Test
	void theRenderVariableAloneMeansProduction() {
		Settings onRender = new Settings(false, true, "console", "", "dev@localhost", "short", false);
		assertThat(onRender.production()).isTrue();
		assertThat(ProductionGuard.problems(onRender)).hasSize(4);
	}

	@Test
	void productionNeedsTheBrevoKeyAndTheSenderAddress() {
		assertThat(ProductionGuard.problems(prod("brevo", "", FROM, PEPPER, true)))
			.containsExactly("app.mail.brevo.api-key (BREVO_API_KEY) is not set");
		assertThat(ProductionGuard.problems(prod("brevo", null, FROM, PEPPER, true))).hasSize(1);
		assertThat(ProductionGuard.problems(prod("brevo", KEY, "  ", PEPPER, true)))
			.containsExactly("app.mail.from-email (MAIL_FROM_EMAIL) is not set");
		assertThat(ProductionGuard.problems(prod("brevo", KEY, null, PEPPER, true))).hasSize(1);
	}

	@Test
	void productionNeedsAPepperOfThirtyTwoCharacters() {
		assertThat(ProductionGuard.problems(prod("brevo", KEY, FROM, "0123456789abcdef0123456789abcde", true)))
			.containsExactly("app.security.otp-pepper (OTP_PEPPER) must be at least 32 characters long");
		assertThat(ProductionGuard.problems(prod("brevo", KEY, FROM, "", true)))
			.containsExactly("app.security.otp-pepper (OTP_PEPPER) is not set");
	}

	@Test
	void productionNeedsASecureCookie() {
		assertThat(ProductionGuard.problems(prod("brevo", KEY, FROM, PEPPER, false)))
			.containsExactly("app.cookie.secure must be true in production");
	}

	@Test
	void theErrorNamesEveryProblemButNoSecret() {
		Settings settings = prod("console", "secret-api-key", "", "secret-pepper", false);
		assertThatThrownBy(() -> ProductionGuard.check(settings)).isInstanceOf(IllegalStateException.class)
			.hasMessageStartingWith("Spend Analyzer cannot start: ")
			.hasMessageContaining("app.mail.provider")
			.hasMessageContaining("MAIL_FROM_EMAIL")
			.hasMessageContaining("OTP_PEPPER")
			.hasMessageContaining("app.cookie.secure")
			.hasMessageNotContaining("secret-api-key")
			.hasMessageNotContaining("secret-pepper");
		assertThat(settings.toString()).doesNotContain("secret-api-key").doesNotContain("secret-pepper");
	}

	// ------------------------------------------------------------------ the beans themselves

	private static AnnotationConfigApplicationContext contextWith(MockEnvironment environment) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.setEnvironment(environment);
		context.register(ConsoleEmailSender.class, BrevoEmailSender.class);
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
	void theStartupCheckReadsTheEnvironment() {
		MockEnvironment bad = new MockEnvironment().withProperty("app.mail.provider", "console")
			.withProperty("app.security.otp-pepper", PEPPER)
			.withProperty("app.cookie.secure", "true");
		bad.setActiveProfiles("prod");
		assertThatThrownBy(() -> new MailStartupCheck(bad)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.mail.provider must be \"brevo\"")
			.hasMessageContaining("BREVO_API_KEY");

		MockEnvironment good = new MockEnvironment().withProperty("app.mail.provider", "brevo")
			.withProperty("app.mail.brevo.api-key", KEY)
			.withProperty("app.mail.from-email", FROM)
			.withProperty("app.security.otp-pepper", PEPPER)
			.withProperty("app.cookie.secure", "true");
		good.setActiveProfiles("prod");
		assertThatCode(() -> new MailStartupCheck(good)).doesNotThrowAnyException();

		MockEnvironment render = new MockEnvironment().withProperty("app.mail.provider", "console")
			.withProperty("app.security.otp-pepper", PEPPER)
			.withProperty("app.cookie.secure", "false")
			.withProperty("RENDER", "true");
		assertThatThrownBy(() -> new MailStartupCheck(render)).isInstanceOf(IllegalStateException.class);
	}

}
