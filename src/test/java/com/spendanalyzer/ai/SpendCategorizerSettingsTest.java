package com.spendanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import com.spendanalyzer.model.Category;
import org.junit.jupiter.api.Test;

import org.springframework.mock.env.MockEnvironment;

/** The bean's settings, without a Spring context (no user is involved, so no database either). */
class SpendCategorizerSettingsTest {

	private static SpendCategorizer bean(MockEnvironment environment) {
		return new SpendCategorizer(null, Clock.systemUTC(), environment);
	}

	@Test
	void trainsInTheBackgroundByDefaultAndAnswersMeanwhile() {
		long started = System.nanoTime();
		SpendCategorizer categorizer = bean(new MockEnvironment());
		long constructMillis = (System.nanoTime() - started) / 1_000_000;

		// the constructor has not trained anything
		assertThat(categorizer.isModelReady()).isFalse();
		assertThat(constructMillis).isLessThan(3_000);
		assertThat(categorizer.categorize(null, "bus ticket", null, null).category())
			.isEqualTo(Category.TRANSPORT_FUEL);

		categorizer.trainInBackground(); // what ApplicationReadyEvent triggers
		assertThat(categorizer.awaitModel(Duration.ofSeconds(60))).isTrue();
		assertThat(categorizer.isModelReady()).isTrue();
		assertThat(categorizer.stats().modelBytes()).isLessThanOrEqualTo(8L * 1024 * 1024);
	}

	@Test
	void theFirstRequestStartsTrainingIfNothingElseDid() {
		SpendCategorizer categorizer = bean(new MockEnvironment());
		categorizer.categorize(null, "juse", null, null);
		assertThat(categorizer.awaitModel(Duration.ofSeconds(60))).isTrue();
	}

	@Test
	void testsTrainUpFrontSoAnswersNeverDependOnTiming() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("test");
		assertThat(bean(environment).isModelReady()).isTrue();

		assertThat(bean(new MockEnvironment().withProperty("app.ai.training", "startup")).isModelReady()).isTrue();
	}

	@Test
	void trainingCanBeSwitchedOff() {
		SpendCategorizer categorizer = bean(new MockEnvironment().withProperty("app.ai.training", " OFF "));
		categorizer.trainInBackground();
		assertThat(categorizer.categorize(null, "juse", null, null).category()).isEqualTo(Category.SNACKS_BEVERAGES);
		assertThat(categorizer.awaitModel(Duration.ofMillis(200))).isFalse();
		assertThat(categorizer.isModelReady()).isFalse();
	}

	@Test
	void aWrongSettingFailsFastWithAClearMessage() {
		assertThatThrownBy(() -> bean(new MockEnvironment().withProperty("app.ai.training", "sometimes")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.ai.training")
			.hasMessageContaining("sometimes");
	}

}
